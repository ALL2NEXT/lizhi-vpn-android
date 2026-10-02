package io.github.HanJason233.chimera.feature.nodegrouping.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeType
import io.github.HanJason233.chimera.data.model.SpeedTestScope
import io.github.HanJason233.chimera.feature.nodegrouping.model.NodeGroupingMode

class SpeedTestScopeResolverTest {
    @Test
    fun `默认模式只使用当前节点范围`() {
        val currentNodes = listOf(node("current", "group-a", "JP"))
        val allNodes = currentNodes + node("other", "group-b", "US")

        val candidates = SpeedTestScopeResolver.selectCandidates(
            currentNodes = currentNodes,
            allNodes = allNodes,
            specifiedGroupNodes = emptyMap(),
            groupingMode = NodeGroupingMode.WAREHOUSE,
            scope = SpeedTestScope.CURRENT_GROUP,
            specifiedGroupIds = emptySet(),
            selectedCountryCode = null
        )

        assertEquals(listOf("current"), candidates.map(Node::id))
    }

    @Test
    fun `全量测试使用所有节点组`() {
        val allNodes = listOf(
            node("jp-a", "group-a", "JP"),
            node("us-a", "group-a", "US"),
            node("jp-b", "group-b", "JP")
        )

        val candidates = SpeedTestScopeResolver.selectCandidates(
            currentNodes = listOf(allNodes.first()),
            allNodes = allNodes,
            specifiedGroupNodes = emptyMap(),
            groupingMode = NodeGroupingMode.WAREHOUSE,
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
            node("jp-b", "group-b", "JP")
        )

        val candidates = SpeedTestScopeResolver.selectCandidates(
            currentNodes = listOf(allNodes[1]),
            allNodes = allNodes,
            specifiedGroupNodes = emptyMap(),
            groupingMode = NodeGroupingMode.REGION,
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
            allNodes = allNodes,
            specifiedGroupNodes = emptyMap(),
            groupingMode = NodeGroupingMode.WAREHOUSE,
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
            allNodes = allNodes,
            specifiedGroupNodes = mapOf(
                "subscription:group-a" to listOf(allNodes[0]),
                "subscription:group-c" to listOf(allNodes[2])
            ),
            groupingMode = NodeGroupingMode.WAREHOUSE,
            scope = SpeedTestScope.SPECIFIED_GROUPS,
            specifiedGroupIds = setOf("subscription:group-a", "subscription:group-c"),
            selectedCountryCode = null
        )

        assertEquals(listOf("jp-a", "jp-b"), candidates.map(Node::id))
    }

    private fun node(
        id: String,
        sourceId: String?,
        country: String
    ) = Node(
        id = id,
        name = id,
        type = NodeType.VLESS,
        server = "example.com",
        port = 443,
        rawLink = "vless://$id@example.com:443",
        country = country,
        subscriptionSourceId = sourceId,
        subscriptionSourceName = sourceId
    )
}
