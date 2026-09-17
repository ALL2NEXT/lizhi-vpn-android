package xyz.a202132.app.feature.nodegrouping.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType

class NodeTestResultOrderingTest {
    @Test
    fun `TCPing 和 URL 测试按各自延迟升序且未测节点在后`() {
        val nodes = listOf(
            node("unmeasured", tcping = -1, urlTest = -1),
            node("slow", tcping = 500, urlTest = 300),
            node("fast", tcping = 100, urlTest = 80)
        )

        assertEquals(
            listOf("fast", "slow", "unmeasured"),
            NodeTestResultOrdering.sort(nodes, NodeTestResultSortMode.TCPING_LATENCY).map(Node::id)
        )
        assertEquals(
            listOf("fast", "slow", "unmeasured"),
            NodeTestResultOrdering.sort(nodes, NodeTestResultSortMode.URL_TEST_LATENCY).map(Node::id)
        )
    }

    @Test
    fun `带宽测试按下载速度降序且未测节点在后`() {
        val nodes = listOf(
            node("unmeasured", bandwidth = 0f),
            node("slow", bandwidth = 12.5f),
            node("fast", bandwidth = 80f)
        )

        assertEquals(
            listOf("fast", "slow", "unmeasured"),
            NodeTestResultOrdering.sort(nodes, NodeTestResultSortMode.DOWNLOAD_BANDWIDTH)
                .map(Node::id)
        )
    }

    private fun node(
        id: String,
        tcping: Int = -1,
        urlTest: Int = -1,
        bandwidth: Float = 0f
    ) = Node(
        id = id,
        name = id,
        type = NodeType.VLESS,
        server = "example.com",
        port = 443,
        rawLink = "vless://$id@example.com:443",
        tcpingLatency = tcping,
        urlTestLatency = urlTest,
        downloadMbps = bandwidth
    )
}
