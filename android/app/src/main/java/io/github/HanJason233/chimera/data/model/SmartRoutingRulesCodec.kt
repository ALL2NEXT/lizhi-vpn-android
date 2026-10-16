package io.github.HanJason233.chimera.data.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class SmartRoutingPreset(val assetName: String, val title: String) {
    CHINA_WHITELIST("china-whitelist.json", "中国白名单"),
    CHINA_BLACKLIST("china-blacklist.json", "中国黑名单"),
    GLOBAL("global.json", "全局代理"),
    IRAN_WHITELIST("iran-whitelist.json", "伊朗白名单"),
    RUSSIA_WHITELIST("russia-whitelist.json", "俄罗斯白名单")
}

/** v2rayNG 路由规则 JSON 编解码，保证剪贴板和二维码数据可以互通。 */
object SmartRoutingRulesCodec {
    fun decode(json: String): List<SmartRoutingRule> {
        val array = runCatching { JSONArray(json) }
            .getOrElse { throw IllegalArgumentException("路由规则 JSON 格式错误") }
        val rules = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(decodeRule(item))
            }
        }
        if (rules.isEmpty()) throw IllegalArgumentException("没有找到有效的路由规则")
        return rules
    }

    fun encode(rules: List<SmartRoutingRule>): String = JSONArray().apply {
        rules.forEach { rule ->
            val domains = rule.domains.ifEmpty {
                if (rule.type == SmartRoutingRuleType.CUSTOM_DOMAIN) rule.values else emptyList()
            }
            val ips = rule.ips.ifEmpty {
                if (rule.type == SmartRoutingRuleType.CUSTOM_IP) rule.values else emptyList()
            }
            put(JSONObject().apply {
                put("id", rule.id)
                put("remarks", rule.name)
                put("outboundTag", rule.outboundTag)
                if (domains.isNotEmpty()) put("domain", JSONArray().apply { domains.forEach(::put) })
                if (ips.isNotEmpty()) put("ip", JSONArray().apply { ips.forEach(::put) })
                if (rule.processes.isNotEmpty()) put("process", JSONArray().apply { rule.processes.forEach(::put) })
                if (rule.ports.isNotBlank()) put("port", rule.ports)
                if (rule.network.isNotBlank()) put("network", rule.network)
                if (rule.protocols.isNotEmpty()) put("protocol", JSONArray().apply { rule.protocols.forEach(::put) })
                put("enabled", rule.enabled)
                put("locked", rule.locked)
            })
        }
    }.toString()

    private fun decodeRule(item: JSONObject): SmartRoutingRule {
        fun readList(arrayKey: String, stringKey: String = arrayKey): List<String> {
            // v2rayNG 使用单数键保存数组，并兼容历史上被再次序列化的数组字符串。
            val array = item.optJSONArray(arrayKey)
                ?: if (stringKey != arrayKey) item.optJSONArray(stringKey) else null
            if (array != null) {
                return buildList {
                    for (index in 0 until array.length()) {
                        addAll(decodeRoutingRuleValues(array.optString(index)))
                    }
                }.distinct()
            }
            return decodeRoutingRuleValues(item.optString(stringKey)
                .takeUnless { it == "null" }
                .orEmpty())
        }

        val rawType = item.optString("type")
        val type = runCatching { SmartRoutingRuleType.valueOf(rawType) }
            .getOrDefault(SmartRoutingRuleType.CUSTOM_RULE)
        val rawAction = item.optString("action")
        val outboundTag = item.optString("outboundTag")
            .takeUnless { it.isBlank() || it == "null" }
            ?: runCatching { SmartRoutingRuleAction.valueOf(rawAction).configTag }
                .getOrDefault(SmartRoutingRuleAction.PROXY.configTag)
        val action = runCatching { SmartRoutingRuleAction.valueOf(rawAction) }
            .getOrElse { SmartRoutingRuleAction.fromOutboundTag(outboundTag) }
        val domains = readList("domains", "domain")
        val ips = readList("ips", "ip")
        val legacyValues = readList("values")
        val resolvedDomains = domains.ifEmpty {
            if (type == SmartRoutingRuleType.CUSTOM_DOMAIN) legacyValues else emptyList()
        }
        val resolvedIps = ips.ifEmpty {
            if (type == SmartRoutingRuleType.CUSTOM_IP) legacyValues else emptyList()
        }
        val name = item.optString("name")
            .takeUnless { it.isBlank() || it == "null" }
            ?: item.optString("remarks").takeUnless { it.isBlank() || it == "null" }
            ?: "导入规则"
        val id = item.optString("id")
            .takeUnless { it.isBlank() || it == "null" }
            ?: UUID.randomUUID().toString()

        return SmartRoutingRule(
            id = id,
            name = name,
            type = type,
            action = action,
            outboundTag = outboundTag,
            values = legacyValues.ifEmpty { resolvedDomains.ifEmpty { resolvedIps } },
            enabled = item.optBoolean("enabled", true),
            locked = item.optBoolean("locked", false),
            domains = resolvedDomains,
            ips = resolvedIps,
            ports = item.optString("ports")
                .takeUnless { it == "null" || it.isBlank() }
                ?: item.optString("port").takeUnless { it == "null" }.orEmpty(),
            network = item.optString("network").takeUnless { it == "null" }.orEmpty(),
            protocols = readList("protocols", "protocol"),
            processes = readList("processes", "process")
        )
    }
}
