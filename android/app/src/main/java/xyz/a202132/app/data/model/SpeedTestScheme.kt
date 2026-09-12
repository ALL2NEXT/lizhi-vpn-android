package xyz.a202132.app.data.model

import java.util.UUID

/**
 * A reusable node selection recipe.  A recipe always runs the same three
 * probes (TCPing, 204 URL and bandwidth) but controls their scope and
 * sampling independently.
 */
data class SpeedTestScheme(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
    val testAllNodes: Boolean = false,
    val tcpingPercent: Int = 100,
    val urlTestPercent: Int = 50,
    val bandwidthPercent: Int = 50,
    val tcpingTimeoutMs: Long = 1_000L,
    val urlTestTimeoutMs: Long = 1_000L,
    val urlTestUrl: String = UrlTestTargets.DEFAULT_URL,
    val bandwidthTestDurationMs: Long = 3_000L,
    val bandwidthTestUrl: String = BandwidthTestTargets.DEFAULT_URL,
    val tcpingConcurrency: Int = 16,
    val urlTestConcurrency: Int = 10,
    val bandwidthTestConcurrency: Int = 1
) {
    companion object {
        const val STANDARD_ID = "builtin_speed_test_standard"
        const val DEEP_ID = "builtin_speed_test_deep"

        fun standard(
            tcpingPercent: Int = 100,
            urlTestPercent: Int = 50,
            bandwidthPercent: Int = 50,
            tcpingTimeoutMs: Long = 1_000L,
            urlTestTimeoutMs: Long = 1_000L,
            urlTestUrl: String = UrlTestTargets.DEFAULT_URL,
            bandwidthTestDurationMs: Long = 3_000L,
            bandwidthTestUrl: String = BandwidthTestTargets.DEFAULT_URL,
            tcpingConcurrency: Int = 16,
            urlTestConcurrency: Int = 10,
            bandwidthTestConcurrency: Int = 1
        ) = SpeedTestScheme(
            id = STANDARD_ID,
            name = "标准测速",
            builtIn = true,
            testAllNodes = false,
            tcpingPercent = tcpingPercent,
            urlTestPercent = urlTestPercent,
            bandwidthPercent = bandwidthPercent,
            tcpingTimeoutMs = tcpingTimeoutMs,
            urlTestTimeoutMs = urlTestTimeoutMs,
            urlTestUrl = urlTestUrl,
            bandwidthTestDurationMs = bandwidthTestDurationMs,
            bandwidthTestUrl = bandwidthTestUrl,
            tcpingConcurrency = tcpingConcurrency,
            urlTestConcurrency = urlTestConcurrency,
            bandwidthTestConcurrency = bandwidthTestConcurrency
        )

        fun deep(
            tcpingPercent: Int = 100,
            urlTestPercent: Int = 50,
            bandwidthPercent: Int = 50,
            tcpingTimeoutMs: Long = 1_000L,
            urlTestTimeoutMs: Long = 1_000L,
            urlTestUrl: String = UrlTestTargets.DEFAULT_URL,
            bandwidthTestDurationMs: Long = 3_000L,
            bandwidthTestUrl: String = BandwidthTestTargets.DEFAULT_URL,
            tcpingConcurrency: Int = 256,
            urlTestConcurrency: Int = 32,
            bandwidthTestConcurrency: Int = 1
        ) = SpeedTestScheme(
            id = DEEP_ID,
            name = "全面测速",
            builtIn = true,
            testAllNodes = true,
            tcpingPercent = tcpingPercent,
            urlTestPercent = urlTestPercent,
            bandwidthPercent = bandwidthPercent,
            tcpingTimeoutMs = tcpingTimeoutMs,
            urlTestTimeoutMs = urlTestTimeoutMs,
            urlTestUrl = urlTestUrl,
            bandwidthTestDurationMs = bandwidthTestDurationMs,
            bandwidthTestUrl = bandwidthTestUrl,
            tcpingConcurrency = tcpingConcurrency,
            urlTestConcurrency = urlTestConcurrency,
            bandwidthTestConcurrency = bandwidthTestConcurrency
        )

        fun create(name: String, template: SpeedTestScheme = standard()): SpeedTestScheme =
            template.copy(
                id = UUID.randomUUID().toString(),
                name = name.trim().ifBlank { "我的方案" },
                builtIn = false
            )
    }
}
