package io.github.HanJason233.chimera.data.model

import java.util.UUID

enum class ManualGroupTestStage {
    TCPING,
    URL_TEST,
    BANDWIDTH
}

/** How many nodes a speed-test stage should take from its upstream result. */
enum class SpeedTestSelectionMode {
    PERCENT,
    COUNT
}

fun formatSpeedTestSelection(mode: SpeedTestSelectionMode, value: Int): String = when (mode) {
    SpeedTestSelectionMode.PERCENT -> "$value%"
    SpeedTestSelectionMode.COUNT -> "$value 个"
}

/**
 * 可复用的节点选择方案。测速阶段按 TCPing、204 URL、带宽测速的顺序执行，
 * 前三项为链路质量测试；第四项为链路类型测试，第五项为 IP 质量测试。
 */
data class SpeedTestScheme(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
    val tcpingEnabled: Boolean = true,
    val urlTestEnabled: Boolean = true,
    val bandwidthEnabled: Boolean = false,
    /** 第四步只检测连接后的当前节点，无额外参数。 */
    val linkTypeTestEnabled: Boolean = false,
    /** 第五步解锁检测的总开关。 */
    val unlockTestsEnabled: Boolean = false,
    /** 第五步要在 VPN 连接后执行的流媒体与 AI 解锁检测项目。 */
    val unlockTestIds: Set<String> = emptySet(),
    val tcpingSelectionMode: SpeedTestSelectionMode = SpeedTestSelectionMode.PERCENT,
    val tcpingSelectionValue: Int = 100,
    val urlTestSelectionMode: SpeedTestSelectionMode = SpeedTestSelectionMode.PERCENT,
    val urlTestSelectionValue: Int = 50,
    val bandwidthSelectionMode: SpeedTestSelectionMode = SpeedTestSelectionMode.PERCENT,
    val bandwidthSelectionValue: Int = 50,
    /** TCPing 本项结束后，是否删除本轮超时节点。 */
    val tcpingAutoDeleteTimeoutNodes: Boolean = false,
    /** URL Test 本项结束后，是否删除本轮超时节点。 */
    val urlTestAutoDeleteTimeoutNodes: Boolean = false,
    /** 带宽测试后是否删除低于阈值的节点。 */
    val bandwidthAutoDeleteLowSpeedNodes: Boolean = false,
    val bandwidthAutoDeleteThresholdMbps: Int = 10,
    val tcpingTimeoutMs: Long = 1_000L,
    val urlTestWarmupTimeoutMs: Long = 4_000L,
    val urlTestMeasurementTimeoutMs: Long = 1_000L,
    val urlTestUrl: String = UrlTestTargets.DEFAULT_URL,
    val urlTestBackupUrl: String = UrlTestTargets.DEFAULT_BACKUP_URL,
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
            tcpingEnabled = true,
            urlTestEnabled = true,
            bandwidthEnabled = false,
            linkTypeTestEnabled = true,
            // 默认将 TCPing 延迟最优的 10 个节点交给下一项测试。
            tcpingSelectionMode = SpeedTestSelectionMode.COUNT,
            tcpingSelectionValue = 10,
            urlTestSelectionMode = SpeedTestSelectionMode.COUNT,
            urlTestSelectionValue = 10,
            tcpingTimeoutMs = 1_000L,
            urlTestWarmupTimeoutMs = 4_000L,
            urlTestMeasurementTimeoutMs = 1_000L,
            urlTestUrl = UrlTestTargets.DEFAULT_URL,
            urlTestBackupUrl = UrlTestTargets.DEFAULT_BACKUP_URL,
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
