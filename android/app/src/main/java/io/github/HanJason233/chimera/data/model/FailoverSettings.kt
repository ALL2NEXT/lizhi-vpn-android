package io.github.HanJason233.chimera.data.model

import java.net.IDN
import java.net.InetAddress

/** Settings for detecting an unreachable active outbound and selecting a replacement. */
data class FailoverSettings(
    val detection: FailoverDetectionSettings = FailoverDetectionSettings(),
    val transfer: FailoverTransferSettings = FailoverTransferSettings()
)

data class FailoverDetectionSettings(
    val enabled: Boolean = false,
    val method: FailoverDetectionMethod = FailoverDetectionMethod.HTTPS_PROBE,
    val probeUrl: String = DEFAULT_PROBE_URL,
    val pingHost: String = DEFAULT_PING_HOST,
    val pingPort: Int = DEFAULT_PING_PORT,
    val intervalSeconds: Int = DEFAULT_INTERVAL_SECONDS,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val consecutiveFailures: Int = DEFAULT_CONSECUTIVE_FAILURES
) {
    companion object {
        const val DEFAULT_PROBE_URL = "https://www.gstatic.com/generate_204"
        const val DEFAULT_PING_HOST = "www.google.com"
        const val DEFAULT_PING_PORT = 443
        const val DEFAULT_INTERVAL_SECONDS = 1
        const val DEFAULT_TIMEOUT_MS = 1_000L
        const val DEFAULT_CONSECUTIVE_FAILURES = 2
    }
}

enum class FailoverDetectionMethod {
    HTTPS_PROBE,
    PROXY_TCP_PING
}

/** 规范化代理 Ping 目标；域名保留为域名，由 SOCKS5 代理端执行 DNS 解析。 */
object FailoverPingTarget {
    fun normalizeHost(value: String?): String? {
        val input = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val host = if (input.startsWith('[') && input.endsWith(']')) {
            input.substring(1, input.length - 1)
        } else {
            input
        }
        if (host.isEmpty() || host.any(Char::isWhitespace) || host.any { it in "/\\?#@" }) return null

        if (':' in host) {
            if ('%' in host) return null
            val address = runCatching { InetAddress.getByName(host).address }.getOrNull()
            return host.lowercase().takeIf { address?.size == 16 }
        }

        if (host.all { it.isDigit() || it == '.' }) {
            val octets = host.split('.')
            if (octets.size != 4 || octets.any { part ->
                    part.isEmpty() || part.length > 3 || part.toIntOrNull()?.let { it in 0..255 } != true
                }
            ) return null
            return octets.joinToString(".") { it.toInt().toString() }
        }

        val ascii = runCatching { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES) }
            .getOrNull()
            ?.lowercase()
            ?: return null
        val labels = ascii.removeSuffix(".").split('.')
        if (ascii.length > 253 || labels.any { it.isEmpty() || it.length > 63 }) return null
        return ascii
    }

    fun isValidPort(port: Int): Boolean = port in 1..65_535
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
