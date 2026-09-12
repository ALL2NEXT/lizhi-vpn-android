package xyz.a202132.app.data.model

import java.util.UUID

/** 测速方案的候选节点作用域。顺序与编辑页面保持一致。 */
enum class SpeedTestScope {
    CURRENT_GROUP,
    SPECIFIED_GROUPS,
    ALL_GROUPS
}

/** 测速方案编辑页可选择的节点组，ID 使用 NodeGroupKey 的稳定值。 */
data class SpeedTestGroupOption(
    val id: String,
    val name: String
)

/**
 * 可复用的节点选择方案。测速阶段按 TCPing、204 URL、带宽测速的顺序执行，
 * 每个阶段都可以单独开关，并独立配置测速作用域和参数。
 */
data class SpeedTestScheme(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
    val scope: SpeedTestScope = SpeedTestScope.CURRENT_GROUP,
    val specifiedGroupIds: Set<String> = emptySet(),
    val tcpingEnabled: Boolean = true,
    val urlTestEnabled: Boolean = true,
    val bandwidthEnabled: Boolean = true,
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
            name = "默认方案1",
            builtIn = true,
            scope = SpeedTestScope.CURRENT_GROUP,
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
            name = "默认方案2",
            builtIn = true,
            scope = SpeedTestScope.ALL_GROUPS,
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
