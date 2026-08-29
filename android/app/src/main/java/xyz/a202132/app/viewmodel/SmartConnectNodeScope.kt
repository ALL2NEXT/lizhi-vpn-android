package xyz.a202132.app.viewmodel

import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeSource
import xyz.a202132.app.util.filterNodesByRegionWhitelist

/** 解析智能连接候选范围；全量测试只包含普通订阅节点，不重复加入收藏副本。 */
internal object SmartConnectNodeScope {
    fun selectCandidates(
        currentNodes: List<Node>,
        allSubscriptionNodes: List<Node>,
        fullTestEnabled: Boolean,
        selectedRegionCodes: Set<String>
    ): List<Node> {
        val scopedNodes = if (fullTestEnabled) {
            allSubscriptionNodes.filter { node -> node.source == NodeSource.SUBSCRIPTION }
        } else {
            currentNodes
        }
        return filterNodesByRegionWhitelist(scopedNodes, selectedRegionCodes)
    }
}
