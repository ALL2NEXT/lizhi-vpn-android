package io.github.HanJason233.chimera.feature.nodegrouping.domain

import io.github.HanJason233.chimera.data.model.Node

/** 线路信息在最近一次测试后的排序依据。 */
enum class NodeTestResultSortMode {
    TCPING_LATENCY,
    URL_TEST_LATENCY,
    DOWNLOAD_BANDWIDTH
}

/** 根据最后完成的测试阶段排列线路信息。 */
internal object NodeTestResultOrdering {
    fun sort(nodes: List<Node>, mode: NodeTestResultSortMode): List<Node> =
        when (mode) {
            NodeTestResultSortMode.TCPING_LATENCY -> nodes.sortedWith(
                compareBy<Node> { it.tcpingLatency <= 0 }
                    .thenBy { it.tcpingLatency.takeIf { latency -> latency > 0 } ?: Int.MAX_VALUE }
                    .thenBy(Node::sortOrder)
            )

            NodeTestResultSortMode.URL_TEST_LATENCY -> nodes.sortedWith(
                compareBy<Node> { it.urlTestLatency <= 0 }
                    .thenBy { it.urlTestLatency.takeIf { latency -> latency > 0 } ?: Int.MAX_VALUE }
                    .thenBy(Node::sortOrder)
            )

            NodeTestResultSortMode.DOWNLOAD_BANDWIDTH -> nodes.sortedWith(
                compareBy<Node> { it.downloadMbps <= 0f }
                    .thenByDescending(Node::downloadMbps)
                    .thenBy(Node::sortOrder)
            )
        }
}
