package xyz.a202132.app.feature.nodegrouping.domain

import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeSource
import xyz.a202132.app.feature.nodegrouping.model.NodeGroupingMode
import xyz.a202132.app.util.CountryCodeResolver

/** 根据当前分组方式解析智能连接候选范围。 */
internal object SmartConnectScopeResolver {
    fun selectCandidates(
        currentNodes: List<Node>,
        allSubscriptionNodes: List<Node>,
        groupingMode: NodeGroupingMode,
        fullTestEnabled: Boolean,
        selectedCountryCode: String?
    ): List<Node> {
        if (groupingMode == NodeGroupingMode.COUNTRY) {
            val normalizedCountryCode =
                CountryCodeResolver.normalizeCountryCode(selectedCountryCode)
            if (normalizedCountryCode != null) {
                return allSubscriptionNodes.filter { node ->
                    node.source == NodeSource.SUBSCRIPTION &&
                        node.getCountryCode() == normalizedCountryCode
                }
            }
        }

        val scopedNodes = if (fullTestEnabled) {
            allSubscriptionNodes.filter { node -> node.source == NodeSource.SUBSCRIPTION }
        } else {
            currentNodes
        }
        return scopedNodes
    }
}
