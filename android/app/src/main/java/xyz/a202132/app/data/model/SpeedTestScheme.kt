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
    val bandwidthEnabled: Boolean = false,
    val tcpingPercent: Int = 100,
    val urlTestPercent: Int = 50,
    val bandwidthPercent: Int = 50,
    val tcpingTimeoutMs: Long = 1_000L,
    val urlTestTimeoutMs: Long = 1_000L,
    val urlTestUrl: String = UrlTestTargets.DEFAULT_URL,
    val urlTestBackupUrl: String = "",
    val bandwidthTestDurationMs: Long = 3_000L,
    val bandwidthTestUrl: String = BandwidthTestTargets.DEFAULT_URL,
    val tcpingConcurrency: Int = 16,
    val urlTestConcurrency: Int = 10,
    val bandwidthTestConcurrency: Int = 1
) {
    companion object {
        /** 当前版本唯一的内置测速方案；旧的标准/全面方案不再展示。 */
        const val DEFAULT_ID = "builtin_speed_test_default"

        fun default() = SpeedTestScheme(
            id = DEFAULT_ID,
            name = "默认测速",
            builtIn = true,
            scope = SpeedTestScope.CURRENT_GROUP,
            tcpingEnabled = true,
            urlTestEnabled = true,
            bandwidthEnabled = false,
            tcpingPercent = 100,
            urlTestPercent = 50,
            tcpingTimeoutMs = 1_000L,
            urlTestTimeoutMs = 1_000L,
            urlTestUrl = UrlTestTargets.DEFAULT_URL,
            tcpingConcurrency = 16,
            urlTestConcurrency = 10
        )

        fun create(name: String, template: SpeedTestScheme = default()): SpeedTestScheme =
            template.copy(
                id = UUID.randomUUID().toString(),
                name = name.trim().ifBlank { "我的方案" },
                builtIn = false
            )
    }
}
