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
        allNodes: List<Node>,
        specifiedGroupNodes: Map<String, List<Node>>,
        groupingMode: NodeGroupingMode,
        scope: SpeedTestScope,
        specifiedGroupIds: Set<String>,
        selectedCountryCode: String?
    ): List<Node> {
        if (scope == SpeedTestScope.CURRENT_GROUP && groupingMode == NodeGroupingMode.COUNTRY) {
            val normalizedCountryCode =
                CountryCodeResolver.normalizeCountryCode(selectedCountryCode)
            if (normalizedCountryCode != null) {
                return allNodes.filter { node ->
                    node.source == NodeSource.SUBSCRIPTION &&
                        node.getCountryCode() == normalizedCountryCode
                }
            }
        }

        return when (scope) {
            SpeedTestScope.CURRENT_GROUP -> currentNodes
            SpeedTestScope.SPECIFIED_GROUPS -> specifiedGroupIds
                .flatMap { groupId -> specifiedGroupNodes[groupId].orEmpty() }
                .distinctBy(Node::id)
            SpeedTestScope.ALL_GROUPS -> allNodes.distinctBy(Node::id)
        }
    }
}
