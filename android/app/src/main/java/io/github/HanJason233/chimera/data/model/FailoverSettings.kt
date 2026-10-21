package io.github.HanJason233.chimera.data.model

/** 检测当前出站节点是否可用，并在故障时选择转移节点。 */
data class FailoverSettings(
    val detection: FailoverDetectionSettings = FailoverDetectionSettings(),
    val transfer: FailoverTransferSettings = FailoverTransferSettings()
)

data class FailoverDetectionSettings(
    val enabled: Boolean = false,
    /** 通过当前节点访问的 HTTPS 测活地址。 */
    val probeUrl: String = DEFAULT_PROBE_URL
) {
    companion object {
        const val DEFAULT_PROBE_URL = "https://www.gstatic.com/generate_204"
        const val PROBE_INTERVAL_SECONDS = 2
        const val PROBE_TIMEOUT_MS = 1_500L
        const val CONSECUTIVE_FAILURE_THRESHOLD = 2
    }
}

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
