package xyz.a202132.app.feature.nodegrouping.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeSource
import xyz.a202132.app.data.model.NodeType
import xyz.a202132.app.data.model.SpeedTestScope
import xyz.a202132.app.feature.nodegrouping.model.NodeGroupingMode

class SpeedTestScopeResolverTest {
    @Test
    fun `默认模式只使用当前节点范围`() {
        val currentNodes = listOf(node("current", "group-a", "JP"))
        val allNodes = currentNodes + node("other", "group-b", "US")

        val candidates = SpeedTestScopeResolver.selectCandidates(
            currentNodes = currentNodes,
            allSubscriptionNodes = allNodes,
            groupingMode = NodeGroupingMode.SUBSCRIPTION,
            scope = SpeedTestScope.CURRENT_GROUP,
            specifiedGroupIds = emptySet(),
            selectedCountryCode = null
        )

        assertEquals(listOf("current"), candidates.map(Node::id))
    }

    @Test
    fun `全量测试使用所有订阅组`() {
        val allNodes = listOf(
            node("jp-a", "group-a", "JP"),
            node("us-a", "group-a", "US"),
            node("jp-b", "group-b", "JP"),
            node("favorite", null, "JP", NodeSource.FAVORITE)
        )

        val candidates = SpeedTestScopeResolver.selectCandidates(
            currentNodes = listOf(allNodes.first()),
            allSubscriptionNodes = allNodes,
            groupingMode = NodeGroupingMode.SUBSCRIPTION,
            scope = SpeedTestScope.ALL_GROUPS,
            specifiedGroupIds = emptySet(),
            selectedCountryCode = null
        )

        assertEquals(listOf("jp-a", "us-a", "jp-b"), candidates.map(Node::id))
    }

    @Test
    fun `选择地区后跨订阅组且只测试该地区`() {
        val allNodes = listOf(
            node("jp-a", "group-a", "JP"),
            node("us-a", "group-a", "US"),
            node("jp-b", "group-b", "JP"),
            node("favorite", null, "JP", NodeSource.FAVORITE)
        )

        val candidates = SpeedTestScopeResolver.selectCandidates(
            currentNodes = listOf(allNodes[1]),
            allSubscriptionNodes = allNodes,
            groupingMode = NodeGroupingMode.COUNTRY,
            scope = SpeedTestScope.CURRENT_GROUP,
            specifiedGroupIds = emptySet(),
            selectedCountryCode = "jp"
        )

        assertEquals(listOf("jp-a", "jp-b"), candidates.map(Node::id))
    }

    @Test
    fun `订阅模式忽略残留地区并使用当前订阅组`() {
        val currentNodes = listOf(node("us-current", "group-a", "US"))
        val allNodes = currentNodes + node("jp-other", "group-b", "JP")

        val candidates = SpeedTestScopeResolver.selectCandidates(
            currentNodes = currentNodes,
            allSubscriptionNodes = allNodes,
            groupingMode = NodeGroupingMode.SUBSCRIPTION,
            scope = SpeedTestScope.CURRENT_GROUP,
            specifiedGroupIds = emptySet(),
            selectedCountryCode = "JP"
        )

        assertEquals(listOf("us-current"), candidates.map(Node::id))
    }

    @Test
    fun `指定模式支持选择多个订阅组`() {
        val allNodes = listOf(
            node("jp-a", "group-a", "JP"),
            node("us-a", "group-b", "US"),
            node("jp-b", "group-c", "JP")
        )

        val candidates = SpeedTestScopeResolver.selectCandidates(
            currentNodes = listOf(allNodes.first()),
            allSubscriptionNodes = allNodes,
            groupingMode = NodeGroupingMode.SUBSCRIPTION,
            scope = SpeedTestScope.SPECIFIED_GROUPS,
            specifiedGroupIds = setOf("group-a", "group-c"),
            selectedCountryCode = null
        )

        assertEquals(listOf("jp-a", "jp-b"), candidates.map(Node::id))
    }

    private fun node(
        id: String,
        sourceId: String?,
        country: String,
        source: NodeSource = NodeSource.SUBSCRIPTION
    ) = Node(
        id = id,
        name = id,
        type = NodeType.VLESS,
        server = "example.com",
        port = 443,
        rawLink = "vless://$id@example.com:443",
        country = country,
        subscriptionSourceId = sourceId,
        subscriptionSourceName = sourceId,
        source = source
    )
}
