package xyz.a202132.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeSource
import xyz.a202132.app.data.model.NodeType

class PublicNodePoolOptimizationTest {
    @Test
    fun usesConfiguredOptimizedConcurrencyForSubscriptionGroupOverThreshold() {
        val result = PublicNodePoolOptimization.resolveConcurrency(
            nodes = subscriptionNodes(101),
            enabled = true,
            nodeThreshold = 100,
            publicPoolTcpingConcurrency = 300,
            publicPoolUrlTestConcurrency = 40,
            defaultTcpingConcurrency = 16,
            defaultUrlTestConcurrency = 10
        )

        assertTrue(result.isPublicNodePool)
        assertEquals(300, result.tcping)
        assertEquals(40, result.urlTest)
    }

    @Test
    fun oneHundredNodesIsNotAPublicNodePool() {
        val result = PublicNodePoolOptimization.resolveConcurrency(
            nodes = subscriptionNodes(100),
            enabled = true,
            nodeThreshold = 100,
            publicPoolTcpingConcurrency = 256,
            publicPoolUrlTestConcurrency = 32,
            defaultTcpingConcurrency = 16,
            defaultUrlTestConcurrency = 10
        )

        assertFalse(result.isPublicNodePool)
        assertEquals(16, result.tcping)
        assertEquals(10, result.urlTest)
    }

    @Test
    fun disabledOptimizationKeepsConfiguredConcurrency() {
        val result = PublicNodePoolOptimization.resolveConcurrency(
            nodes = subscriptionNodes(101),
            enabled = false,
            nodeThreshold = 100,
            publicPoolTcpingConcurrency = 256,
            publicPoolUrlTestConcurrency = 32,
            defaultTcpingConcurrency = 24,
            defaultUrlTestConcurrency = 12
        )

        assertFalse(result.isPublicNodePool)
        assertEquals(24, result.tcping)
        assertEquals(12, result.urlTest)
    }

    @Test
    fun favoritesAreNotTreatedAsASubscriptionGroup() {
        val nodes = subscriptionNodes(101).map { node ->
            node.copy(source = NodeSource.FAVORITE)
        }

        val result = PublicNodePoolOptimization.resolveConcurrency(
            nodes = nodes,
            enabled = true,
            nodeThreshold = 100,
            publicPoolTcpingConcurrency = 256,
            publicPoolUrlTestConcurrency = 32,
            defaultTcpingConcurrency = 16,
            defaultUrlTestConcurrency = 10
        )

        assertFalse(result.isPublicNodePool)
    }

    private fun subscriptionNodes(count: Int): List<Node> = List(count) { index ->
        Node(
            id = "node-$index",
            name = "Node $index",
            type = NodeType.VLESS,
            server = "192.0.2.${index % 255}",
            port = 443,
            rawLink = "vless://node-$index",
            subscriptionSourceId = "public-pool",
            subscriptionSourceName = "Public Pool"
        )
    }
}
