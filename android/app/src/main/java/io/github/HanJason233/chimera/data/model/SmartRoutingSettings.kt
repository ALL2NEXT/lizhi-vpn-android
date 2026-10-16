package io.github.HanJason233.chimera.data.model

import org.json.JSONArray
import java.util.UUID

enum class SmartRoutingRuleType {
    DOMESTIC_DOMAIN,
    DOMESTIC_IP,
    CUSTOM_DOMAIN,
    CUSTOM_IP,
    CUSTOM_RULE
}

enum class SmartRoutingDomainStrategy(val xrayValue: String, val title: String) {
    AS_IS("AsIs", "AsIs"),
    IP_IF_NON_MATCH("IPIfNonMatch", "IPIfNonMatch"),
    IP_ON_DEMAND("IPOnDemand", "IPOnDemand")
}

enum class SmartRoutingRuleAction(val configTag: String) {
    DIRECT("direct"),
    PROXY("proxy"),
    BLOCK("block");

    companion object {
        fun fromOutboundTag(tag: String): SmartRoutingRuleAction = when (tag) {
            "direct" -> DIRECT
            "block" -> BLOCK
            else -> PROXY
        }
    }
}

/** 清理导入规则项两端误带的引号、转义符和不成对数组括号。 */
internal fun normalizeRoutingRuleValue(value: String): String {
    var normalized = value.trim()
    repeat(4) {
        val previous = normalized
        normalized = normalized.trim('"', '\'', '\\').trim()
        val openingBrackets = normalized.count { it == '[' }
        val closingBrackets = normalized.count { it == ']' }
        normalized = when {
            openingBrackets > closingBrackets && normalized.startsWith("[") ->
                normalized.removePrefix("[")
            closingBrackets > openingBrackets && normalized.endsWith("]") ->
                normalized.removeSuffix("]")
            else -> normalized
        }.trim().trim('"', '\'', '\\').trim()
        if (normalized == previous) return normalized
    }
    return normalized
}

/** 解析标准数组及旧版把 JSON 数组保存成字符串的路由条件。 */
internal fun decodeRoutingRuleValues(value: String): List<String> {
    val source = value.trim()
    if (source.isBlank()) return emptyList()
    val jsonArray = runCatching { JSONArray(source) }.getOrNull()
    if (jsonArray != null) {
        return buildList {
            for (index in 0 until jsonArray.length()) {
                addAll(decodeRoutingRuleValues(jsonArray.optString(index)))
            }
        }.distinct()
    }

    val unquoted = normalizeRoutingRuleValue(source)
    if (unquoted != source && unquoted.startsWith("[") && unquoted.endsWith("]")) {
        return decodeRoutingRuleValues(unquoted)
    }
    val listContent = if (source.startsWith("[") && source.endsWith("]")) {
        source.substring(1, source.lastIndex)
    } else {
        source
    }
    val parts = listContent.split(',', '\n')
    val wholeListQuoted = parts.size > 1 &&
        source.length >= 2 && source.first() == source.last() &&
        source.first() in setOf('"', '\'') &&
        !(parts.first().trim().let { it.firstOrNull() == source.first() && it.lastOrNull() == source.first() } &&
            parts.last().trim().let { it.firstOrNull() == source.last() && it.lastOrNull() == source.last() })
    if (wholeListQuoted) return decodeRoutingRuleValues(unquoted)
    return parts.map(::normalizeRoutingRuleValue)
        .filter(String::isNotBlank)
        .distinct()
}

data class SmartRoutingRule(
    val id: String,
    val name: String,
    val type: SmartRoutingRuleType,
    val action: SmartRoutingRuleAction,
    val values: List<String> = emptyList(),
    val enabled: Boolean = true,
    val locked: Boolean = false,
    val domains: List<String> = if (type == SmartRoutingRuleType.CUSTOM_DOMAIN) values else emptyList(),
    val ips: List<String> = if (type == SmartRoutingRuleType.CUSTOM_IP) values else emptyList(),
    val ports: String = "",
    val network: String = "",
    val protocols: List<String> = emptyList(),
    val processes: List<String> = emptyList(),
    /** 保留 v2rayNG 的原始出口标签，未知标签会由核心按源项目规则回退到 proxy。 */
    val outboundTag: String = action.configTag
)

/** 当前运行核心支持的出口标签；原始标签仍由规则编解码器保留。 */
val SmartRoutingRule.effectiveOutboundTag: String
    get() = when (outboundTag) {
        "direct", "proxy", "block" -> outboundTag
        else -> SmartRoutingRuleAction.PROXY.configTag
    }

data class SmartRoutingSettings(
    val rules: List<SmartRoutingRule> = emptyList(),
    val domainStrategy: SmartRoutingDomainStrategy = SmartRoutingDomainStrategy.AS_IS
) {
    companion object {
        fun custom(
            name: String,
            type: SmartRoutingRuleType,
            action: SmartRoutingRuleAction,
            values: List<String>
        ): SmartRoutingRule = SmartRoutingRule(
            id = UUID.randomUUID().toString(),
            name = name,
            type = type,
            action = action,
            values = values
        )
    }
}
