package xyz.a202132.app.network
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import xyz.a202132.app.data.model.SubscriptionList
import xyz.a202132.app.data.model.SubscriptionSource

/**
 * Parses the subscription contents returned by the unified Worker backend.
 *
 * Supported object format:
 * {
 *   "subscriptions": [
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

        val sources = sourceArray.mapNotNull { parseSource(it) }
            .mapIndexed { index, source ->
                source.copy(
                    id = (index + 1).toString(),
                    name = source.name.ifBlank { "线路${index + 1}" }
                )
            }
            .filter { it.enabled }

        require(sources.isNotEmpty()) { "订阅列表中没有有效线路" }
        SubscriptionList(sources = sources)
    }

    private fun findSourceArray(root: JsonObject): JsonArray? {
        return listOf("subscriptions", "sources", "list", "data")
            .asSequence()
            .mapNotNull { key -> root.get(key)?.takeIf { it.isJsonArray }?.asJsonArray }
            .firstOrNull()
    }

    private fun parseSource(element: JsonElement): SubscriptionSource? {
        if (!element.isJsonObject) return null
        val source = element.asJsonObject
        val url = readString(source, "url", "subscribeUrl", "subscriptionUrl")
            ?.trim()
            ?.takeIf { it.matches(Regex("https?://.+", RegexOption.IGNORE_CASE)) }
            ?: return null
        val name = readString(source, "name", "title", "label")
            ?.trim()
            ?: ""
        val enabled = source.get("enabled")?.let { readBoolean(it) } ?: true
        val encrypted = source.get("encrypted")?.let { readBoolean(it) }
            ?: source.get("isEncrypted")?.let { readBoolean(it) }
            ?: false
        val refreshOnAppOpen = source.get("refreshOnAppOpen")?.let { readBoolean(it) }
            ?: source.get("openAppImmediateUpdate")?.let { readBoolean(it) }
            ?: source.get("updateOnAppOpen")?.let { readBoolean(it) }
            ?: true
        return SubscriptionSource(
            id = "",
            name = name,
            url = url,
            enabled = enabled,
            encrypted = encrypted,
            refreshOnAppOpen = refreshOnAppOpen
        )
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


}
