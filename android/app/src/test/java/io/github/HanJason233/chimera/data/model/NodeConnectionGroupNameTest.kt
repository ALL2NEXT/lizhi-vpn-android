package io.github.HanJason233.chimera.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeConnectionGroupNameTest {
    @Test
    fun subscriptionNodeUsesSourceName() {
        val node = testNode(
            subscriptionSourceId = "42",
            subscriptionSourceName = " 聚合订阅 "
        )

        assertEquals("聚合订阅", node.getConnectionGroupName())
    }

    @Test
    fun sourceIdIsUsedWhenSourceNameIsBlank() {
        val node = testNode(
            subscriptionSourceId = "42",
            subscriptionSourceName = "  "
        )

        assertEquals("42", node.getConnectionGroupName())
    }

    private fun testNode(
        subscriptionSourceId: String? = null,
        subscriptionSourceName: String? = null
    ) = Node(
        id = "node-1",
        name = "测试节点",
        type = NodeType.VLESS,
        server = "192.0.2.1",
        port = 443,
        rawLink = "vless://node-1",
        subscriptionSourceId = subscriptionSourceId,
        subscriptionSourceName = subscriptionSourceName
    )
}
