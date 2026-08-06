package xyz.a202132.app.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import xyz.a202132.app.data.model.SubscriptionList
import xyz.a202132.app.data.model.SubscriptionSource
import java.security.MessageDigest

/**
 * Parses the subscription contents returned by the unified Worker backend.
 *
 * Supported object format:
 * {
 *   "update": 1,
 *   "subscriptions": [
 *     {"id": "line1", "name": "线路1", "content": "vless://..."}
 *   ]
 * }
 *
 * `sources`, `list` and `data` are accepted as aliases for `subscriptions`.
 */
class SubscriptionListParser {

    fun parse(content: String): Result<SubscriptionList> = runCatching {
        val root = JsonParser.parseString(content.trim())
        val rootObject = root.takeIf { it.isJsonObject }?.asJsonObject
        val sourceArray = when {
            root.isJsonArray -> root.asJsonArray
            rootObject != null -> findSourceArray(rootObject)
            else -> null
        } ?: throw IllegalArgumentException("订阅列表缺少 subscriptions 数组")

        val autoUpdate = rootObject?.let { readUpdateFlag(it) } ?: true
        val sources = sourceArray.mapIndexedNotNull { index, element ->
            parseSource(element, index)
        }.filter { it.enabled }

        require(sources.isNotEmpty()) { "订阅列表中没有有效线路" }
        SubscriptionList(autoUpdate = autoUpdate, sources = sources)
    }

    private fun findSourceArray(root: JsonObject): JsonArray? {
        return listOf("subscriptions", "sources", "list", "data")
            .asSequence()
            .mapNotNull { key -> root.get(key)?.takeIf { it.isJsonArray }?.asJsonArray }
            .firstOrNull()
    }

    private fun readUpdateFlag(root: JsonObject): Boolean {
        val element = root.get("update")
            ?: root.get("autoUpdate")
            ?: root.get("auto_update")
            ?: return false
        return when {
            element.isJsonPrimitive && element.asJsonPrimitive.isBoolean -> element.asBoolean
            element.isJsonPrimitive -> when (element.asString.trim().lowercase()) {
                "1", "true", "yes", "on" -> true
                else -> false
            }
            else -> false
        }
    }

    private fun parseSource(element: JsonElement, index: Int): SubscriptionSource? {
        if (!element.isJsonObject) return null
        val source = element.asJsonObject
        val content = readString(source, "content", "body")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val name = readString(source, "name", "title", "label")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "线路${index + 1}"
        val id = readString(source, "id", "key")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: md5(content)
        val enabled = source.get("enabled")?.let { readBoolean(it) } ?: true
        return SubscriptionSource(id = id, name = name, content = content, enabled = enabled)
    }

    private fun readString(objectValue: JsonObject, vararg keys: String): String? {
        return keys.asSequence()
            .mapNotNull { key -> objectValue.get(key)?.takeIf { it.isJsonPrimitive }?.asString }
            .firstOrNull()
    }

    private fun readBoolean(element: JsonElement): Boolean {
        if (!element.isJsonPrimitive) return true
        val primitive = element.asJsonPrimitive
        if (primitive.isBoolean) return primitive.asBoolean
        return primitive.asString.trim().lowercase() !in setOf("0", "false", "off", "no")
    }

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
