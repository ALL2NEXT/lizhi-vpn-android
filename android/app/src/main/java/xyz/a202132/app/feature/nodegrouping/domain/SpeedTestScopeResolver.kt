package xyz.a202132.app.feature.nodegrouping.domain

import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeSource
import xyz.a202132.app.feature.nodegrouping.model.NodeGroupingMode
import xyz.a202132.app.util.CountryCodeResolver

/** 根据当前分组方式解析测速方案的候选范围。 */
internal object SpeedTestScopeResolver {
    fun selectCandidates(
        currentNodes: List<Node>,
        allSubscriptionNodes: List<Node>,
        groupingMode: NodeGroupingMode,
        testAllNodes: Boolean,
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

        val scopedNodes = if (testAllNodes) {
            allSubscriptionNodes.filter { node -> node.source == NodeSource.SUBSCRIPTION }
        } else {
            currentNodes
        }
        return scopedNodes
    }
}
