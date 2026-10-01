package io.github.HanJason233.chimera.network

/** URL 测速复测与聚合策略，保持为纯逻辑以便独立验证。 */
internal object UrlTestSamplePolicy {
    fun selectFinalistIds(
        firstPass: List<LatencyTester.LatencyResult>,
        limit: Int
    ): List<String> = firstPass.asSequence()
        .filter { it.isAvailable && it.latency > 0 }
        .sortedBy(LatencyTester.LatencyResult::latency)
        .take(limit.coerceAtLeast(0))
        .map(LatencyTester.LatencyResult::nodeId)
        .toList()

    fun stabilize(
        firstPass: List<LatencyTester.LatencyResult>,
        rechecks: Map<String, List<LatencyTester.LatencyResult>>
    ): List<LatencyTester.LatencyResult> = firstPass.map { initial ->
        if (!initial.isAvailable || initial.latency <= 0) return@map initial
        val successful = buildList {
            add(initial.latency)
            rechecks[initial.nodeId].orEmpty()
                .filter { it.nodeId == initial.nodeId && it.isAvailable && it.latency > 0 }
                .forEach { add(it.latency) }
        }.sorted()
        initial.copy(latency = median(successful))
    }

    private fun median(sorted: List<Int>): Int {
        require(sorted.isNotEmpty())
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else ((sorted[middle - 1].toLong() + sorted[middle]) / 2L).toInt()
    }
}
