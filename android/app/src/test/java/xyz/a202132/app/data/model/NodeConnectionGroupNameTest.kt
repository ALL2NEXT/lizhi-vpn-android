package xyz.a202132.app.data.model

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

    @Test
    fun importedFavoriteUsesFavoriteLabel() {
        val node = testNode(source = NodeSource.FAVORITE)

        assertEquals("收藏节点", node.getConnectionGroupName())
    }

    private fun testNode(
        subscriptionSourceId: String? = null,
        subscriptionSourceName: String? = null,
        source: NodeSource = NodeSource.SUBSCRIPTION
    ) = Node(
        id = "node-1",
        name = "测试节点",
        type = NodeType.VLESS,
        server = "192.0.2.1",
        port = 443,
        rawLink = "vless://node-1",
        subscriptionSourceId = subscriptionSourceId,
        subscriptionSourceName = subscriptionSourceName,
        source = source
    )
}
