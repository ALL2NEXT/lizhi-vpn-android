package io.github.HanJason233.chimera.network.subscription
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.HanJason233.chimera.data.model.SubscriptionList
import io.github.HanJason233.chimera.data.model.SubscriptionSource

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

        val parsedSources = sourceArray.mapNotNull { parseSource(it) }
        require(sourceArray.size() == 0 || parsedSources.isNotEmpty()) { "订阅列表中没有有效线路" }
        val sources = parsedSources
            .mapIndexed { index, source ->
                source.copy(
                    // 后台筛选后列表顺序会变化，订阅身份不能再取列表下标。
                    id = source.id.ifBlank { source.url },
                    name = source.name.ifBlank { "线路${index + 1}" }
                )
            }
            .filter { it.enabled }

        val subscriptionVersions = rootObject
            ?.get("subscriptionVersions")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.entrySet()
            ?.mapNotNull { (subscriptionId, value) ->
                value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.let { runCatching { it.asLong }.getOrNull() }
                    ?.takeIf { it >= 0L }
                    ?.let { subscriptionId to it }
            }
            ?.toMap()
            ?: emptyMap()

        SubscriptionList(
            sources = sources,
            subscriptionVersions = subscriptionVersions
        )
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
        val id = readString(source, "id")?.trim().orEmpty()
        val allowedStatuses = readStringList(source, "allowedStatuses")
        val allowedMembershipTypes = readStringList(source, "allowedMembershipTypes")
        val minAppVersion = readString(source, "minAppVersion")?.trim().orEmpty()
        val maxAppVersion = readString(source, "maxAppVersion")?.trim().orEmpty()
        return SubscriptionSource(
            id = id,
            name = name,
            url = url,
            enabled = enabled,
            allowedStatuses = allowedStatuses,
            allowedMembershipTypes = allowedMembershipTypes,
            minAppVersion = minAppVersion,
            maxAppVersion = maxAppVersion
        )
    }

    private fun readStringList(objectValue: JsonObject, key: String): List<String>? {
        val element = objectValue.get(key)?.takeIf { it.isJsonArray } ?: return null
        return element.asJsonArray.mapNotNull { item ->
            item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }
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
