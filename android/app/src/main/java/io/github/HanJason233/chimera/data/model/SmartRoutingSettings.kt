package io.github.HanJason233.chimera.data.model

import java.util.UUID

enum class SmartRoutingRuleType {
    DOMESTIC_DOMAIN,
    DOMESTIC_IP,
    CUSTOM_DOMAIN,
    CUSTOM_IP
}

enum class SmartRoutingRuleAction(val configTag: String) {
    DIRECT("direct"),
    PROXY("proxy"),
    BLOCK("block")
}

data class SmartRoutingRule(
    val id: String,
    val name: String,
    val type: SmartRoutingRuleType,
    val action: SmartRoutingRuleAction,
    val values: List<String> = emptyList(),
    val enabled: Boolean = true,
    val locked: Boolean = false
)

data class SmartRoutingSettings(
    val rules: List<SmartRoutingRule> = defaultRules()
) {
    companion object {
        const val DOMESTIC_DOMAIN_RULE_ID = "builtin_smart_domestic_domain"
        const val DOMESTIC_IP_RULE_ID = "builtin_smart_domestic_ip"

        fun defaultRules(): List<SmartRoutingRule> = listOf(
            SmartRoutingRule(
                id = DOMESTIC_DOMAIN_RULE_ID,
                name = "国内域名（内置）",
                type = SmartRoutingRuleType.DOMESTIC_DOMAIN,
                action = SmartRoutingRuleAction.DIRECT,
                enabled = true,
                locked = true
            ),
            SmartRoutingRule(
                id = DOMESTIC_IP_RULE_ID,
                name = "国内 IP",
                type = SmartRoutingRuleType.DOMESTIC_IP,
                action = SmartRoutingRuleAction.DIRECT,
                enabled = true,
                locked = true
            )
        )

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
