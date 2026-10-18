package io.github.HanJason233.chimera.data.model

/** Settings for detecting an unreachable active outbound and selecting a replacement. */
data class FailoverSettings(
    val detection: FailoverDetectionSettings = FailoverDetectionSettings(),
    val transfer: FailoverTransferSettings = FailoverTransferSettings()
)

data class FailoverDetectionSettings(
    val enabled: Boolean = false,
    val method: FailoverDetectionMethod = FailoverDetectionMethod.HTTPS_PROBE,
    val probeUrl: String = DEFAULT_PROBE_URL,
    val intervalSeconds: Int = DEFAULT_INTERVAL_SECONDS,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val consecutiveFailures: Int = DEFAULT_CONSECUTIVE_FAILURES
) {
    companion object {
        const val DEFAULT_PROBE_URL = "https://www.gstatic.com/generate_204"
        const val DEFAULT_INTERVAL_SECONDS = 1
        const val DEFAULT_TIMEOUT_MS = 1_000L
        const val DEFAULT_CONSECUTIVE_FAILURES = 2
    }
}

enum class FailoverDetectionMethod {
    HTTPS_PROBE
}

/** Ordered node ids; the first usable node is tried first after the active node fails. */
data class FailoverTransferSettings(
    val nodeIds: List<String> = emptyList()
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
