package io.github.HanJason233.chimera.data.model

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
