package xyz.a202132.app.feature.nodegrouping.domain

import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeSource
import xyz.a202132.app.data.model.SpeedTestScope
import xyz.a202132.app.feature.nodegrouping.model.NodeGroupingMode
import xyz.a202132.app.util.CountryCodeResolver

/** 根据当前分组方式解析测速方案的候选范围。 */
internal object SpeedTestScopeResolver {
    fun selectCandidates(
        currentNodes: List<Node>,
        allSubscriptionNodes: List<Node>,
        groupingMode: NodeGroupingMode,
        scope: SpeedTestScope,
        specifiedGroupIds: Set<String>,
        selectedCountryCode: String?
    ): List<Node> {
        if (scope == SpeedTestScope.CURRENT_GROUP && groupingMode == NodeGroupingMode.COUNTRY) {
            val normalizedCountryCode =
                CountryCodeResolver.normalizeCountryCode(selectedCountryCode)
            if (normalizedCountryCode != null) {
                return allSubscriptionNodes.filter { node ->
                    node.source == NodeSource.SUBSCRIPTION &&
                        node.getCountryCode() == normalizedCountryCode
                }
            }
        }

        return when (scope) {
            SpeedTestScope.CURRENT_GROUP -> currentNodes
            SpeedTestScope.SPECIFIED_GROUPS -> allSubscriptionNodes.filter { node ->
                node.source == NodeSource.SUBSCRIPTION &&
                    node.subscriptionSourceId in specifiedGroupIds
            }
            SpeedTestScope.ALL_GROUPS -> allSubscriptionNodes.filter { node ->
                node.source == NodeSource.SUBSCRIPTION
            }
        }
    }
}
