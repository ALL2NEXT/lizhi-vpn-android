package io.github.HanJason233.chimera.data.model

/** 检测当前出站节点是否可用，并在故障时选择转移节点。 */
data class FailoverSettings(
    val detection: FailoverDetectionSettings = FailoverDetectionSettings(),
    val transfer: FailoverTransferSettings = FailoverTransferSettings()
)

data class FailoverDetectionSettings(
    val enabled: Boolean = false,
    val firstProbe: FailoverProbeStepSettings = FailoverProbeStepSettings(
        probeUrl = DEFAULT_FIRST_PROBE_URL,
        intervalSeconds = 30,
        timeoutMs = DEFAULT_PROBE_TIMEOUT_MS
    ),
    val secondProbe: FailoverProbeStepSettings = FailoverProbeStepSettings(
        probeUrl = DEFAULT_SECOND_PROBE_URL,
        intervalSeconds = 5,
        timeoutMs = DEFAULT_PROBE_TIMEOUT_MS
    ),
    val thirdProbe: FailoverProbeStepSettings = FailoverProbeStepSettings(
        probeUrl = DEFAULT_THIRD_PROBE_URL,
        intervalSeconds = 5,
        timeoutMs = DEFAULT_PROBE_TIMEOUT_MS
    )
) {
    val probeSteps: List<FailoverProbeStepSettings>
        get() = listOf(firstProbe, secondProbe, thirdProbe)

    fun probeStep(index: Int): FailoverProbeStepSettings = probeSteps[index.coerceIn(0, PROBE_COUNT - 1)]

    companion object {
        const val DEFAULT_FIRST_PROBE_URL = "https://www.gstatic.com/generate_204"
        const val DEFAULT_SECOND_PROBE_URL = "https://www.google.com/generate_204"
        const val DEFAULT_THIRD_PROBE_URL = "https://cp.cloudflare.com/generate_204"
        const val DEFAULT_PROBE_TIMEOUT_MS = 1_500L
        const val PROBE_COUNT = 3
        const val CONSECUTIVE_FAILURE_THRESHOLD = PROBE_COUNT
        const val MIN_PROBE_INTERVAL_SECONDS = 1
        const val MAX_PROBE_INTERVAL_SECONDS = 3_600
        const val MIN_PROBE_TIMEOUT_MS = 250L
        const val MAX_PROBE_TIMEOUT_MS = 30_000L
    }
}

/** 单次 204 探测配置；间隔表示本次探测前的等待时间。 */
data class FailoverProbeStepSettings(
    val probeUrl: String,
    val intervalSeconds: Int,
    val timeoutMs: Long
)

/** 故障确认后选择备用线路的策略。 */
sealed interface FailoverTransferStrategy {
    data class SpecifiedNode(val nodeId: String) : FailoverTransferStrategy
    data class SpecifiedGroup(val groupId: String) : FailoverTransferStrategy
    data object SecondBestTestResult : FailoverTransferStrategy
}

data class FailoverTransferSettings(
    val strategy: FailoverTransferStrategy? = null
)

enum class FailoverRuntimeState {
    DISABLED,
    MONITORING,
    DETECTING,
    WAITING_FOR_NETWORK,
    SWITCHING,
    USING_BACKUP,
    NO_BACKUP,
    ERROR
}

data class FailoverRuntimeStatus(
    val state: FailoverRuntimeState = FailoverRuntimeState.DISABLED,
    val message: String = "故障转移未运行",
    val activeNodeId: String? = null
)
