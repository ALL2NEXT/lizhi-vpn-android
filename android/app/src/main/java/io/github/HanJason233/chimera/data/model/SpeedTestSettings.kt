package io.github.HanJason233.chimera.data.model

/** 一个测速入口对应一份固定配置，主页图标与手动测速互不影响。 */
data class SpeedTestSettings(
    val stageOrder: List<SpeedTestStage> = SpeedTestStage.entries,
    val tcpingEnabled: Boolean = true,
    val urlTestEnabled: Boolean = true,
    val bandwidthEnabled: Boolean = false,
    val linkTypeTestEnabled: Boolean = false,
    val unlockTestsEnabled: Boolean = false,
    val unlockTestIds: Set<String> = emptySet(),
    val tcpingSelectionMode: SpeedTestSelectionMode = SpeedTestSelectionMode.PERCENT,
    val tcpingSelectionValue: Int = 100,
    val urlTestSelectionMode: SpeedTestSelectionMode = SpeedTestSelectionMode.PERCENT,
    val urlTestSelectionValue: Int = 50,
    val bandwidthSelectionMode: SpeedTestSelectionMode = SpeedTestSelectionMode.PERCENT,
    val bandwidthSelectionValue: Int = 50,
    val tcpingAutoDeleteTimeoutNodes: Boolean = false,
    val urlTestAutoDeleteTimeoutNodes: Boolean = false,
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
        fun default() = SpeedTestSettings(
            tcpingEnabled = true,
            urlTestEnabled = true,
            bandwidthEnabled = false,
            linkTypeTestEnabled = true,
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

/** 保持已有拖动顺序，并把数据缺失的测试项补到列表末尾。 */
fun normalizeSpeedTestStageOrder(order: List<SpeedTestStage>): List<SpeedTestStage> {
    val uniqueOrder = order.distinct()
    return uniqueOrder + SpeedTestStage.entries.filterNot(uniqueOrder::contains)
}
