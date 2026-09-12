package xyz.a202132.windows.model

import java.util.UUID

/** A reusable recipe for selecting the fastest available node. */
data class SpeedTestScheme(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
    val testAllNodes: Boolean = false,
    val tcpingPercent: Int = 100,
    val urlTestPercent: Int = 50,
    val bandwidthPercent: Int = 50,
    val tcpingTimeoutMs: Int = 1_000,
    val urlTestTimeoutMs: Int = 1_000,
    val urlTestUrl: String = UrlTestTargets.DEFAULT_URL,
    val bandwidthTestDurationMs: Int = 3_000,
    val bandwidthTestUrl: String = BandwidthTestTargets.DEFAULT_URL,
    val tcpingConcurrency: Int = 16,
    val urlTestConcurrency: Int = 10,
    val bandwidthTestConcurrency: Int = 1
) {
    companion object {
        const val STANDARD_ID = "builtin_speed_test_standard"
        const val DEEP_ID = "builtin_speed_test_deep"

        fun standard() = SpeedTestScheme(
            id = STANDARD_ID,
            name = "标准测速",
            builtIn = true,
            testAllNodes = false,
            tcpingPercent = 100,
            urlTestPercent = 50,
            bandwidthPercent = 50,
            tcpingTimeoutMs = 1_000,
            urlTestTimeoutMs = 1_000,
            urlTestUrl = UrlTestTargets.DEFAULT_URL,
            bandwidthTestDurationMs = 3_000,
            bandwidthTestUrl = BandwidthTestTargets.DEFAULT_URL,
            tcpingConcurrency = 16,
            urlTestConcurrency = 10,
            bandwidthTestConcurrency = 1
        )

        fun deep() = standard().copy(
            id = DEEP_ID,
            name = "全面测速",
            builtIn = true,
            testAllNodes = true
        )

        fun create(name: String, template: SpeedTestScheme = standard()) = template.copy(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "我的方案" },
            builtIn = false
        )
    }
}
