package io.github.HanJason233.chimera.data.model

enum class ManualGroupTestStage {
    TCPING,
    URL_TEST,
    BANDWIDTH,
    LINK_TYPE,
    IP_QUALITY
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
 * Legacy representation used only while importing settings from older app versions.
 */
data class LegacySpeedTestSettings(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
    val tcpingEnabled: Boolean = true,
    val urlTestEnabled: Boolean = true,
    val bandwidthEnabled: Boolean = false,
    /** 第四步保留的链路类型；测试在 VPN 创建前通过临时代理批量执行。 */
    val linkTypeTestEnabled: Boolean = false,
    val linkTypeAcceptDirect: Boolean = true,
    val linkTypeAcceptRelay: Boolean = true,
    val linkTypeAcceptCdnRelay: Boolean = true,
    /** 第五步按所选服务全部通过来保留线路。 */
    val unlockTestsEnabled: Boolean = false,
    /** 第五步要检测的流媒体与 AI 服务。 */
    val unlockTestIds: Set<String> = emptySet(),
    val linkTypeConcurrencyMode: SpeedTestSelectionMode = SpeedTestSelectionMode.COUNT,
    val linkTypeConcurrency: Int = 3,
    val unlockTestConcurrencyMode: SpeedTestSelectionMode = SpeedTestSelectionMode.COUNT,
    val unlockTestConcurrency: Int = 3,
    val unlockTestRequestConcurrency: Int = 4,
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
    val tcpingConcurrencyMode: SpeedTestSelectionMode = SpeedTestSelectionMode.PERCENT,
    val tcpingConcurrency: Int = 30,
    val urlTestConcurrencyMode: SpeedTestSelectionMode = SpeedTestSelectionMode.PERCENT,
    val urlTestConcurrency: Int = 30,
    val bandwidthTestConcurrencyMode: SpeedTestSelectionMode = SpeedTestSelectionMode.PERCENT,
    val bandwidthTestConcurrency: Int = 30
) {
    companion object {
        /** Identifier used by the former built-in speed-test settings record. */
        const val DEFAULT_ID = "builtin_speed_test_default"

        fun default() = LegacySpeedTestSettings(
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
            tcpingConcurrencyMode = SpeedTestSelectionMode.PERCENT,
            tcpingConcurrency = 30,
            urlTestConcurrencyMode = SpeedTestSelectionMode.PERCENT,
            urlTestConcurrency = 30,
            bandwidthTestConcurrencyMode = SpeedTestSelectionMode.PERCENT,
            bandwidthTestConcurrency = 30
        )

    }
}
