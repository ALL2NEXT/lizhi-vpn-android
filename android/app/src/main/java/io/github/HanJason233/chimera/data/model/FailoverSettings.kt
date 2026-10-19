package io.github.HanJason233.chimera.data.model

import java.net.IDN
import java.net.InetAddress
import java.util.UUID

/** 检测当前出站节点是否可用，并在故障时选择转移节点。 */
data class FailoverSettings(
    val detection: FailoverDetectionSettings = FailoverDetectionSettings(),
    val transfer: FailoverTransferSettings = FailoverTransferSettings()
)

data class FailoverDetectionSettings(
    val enabled: Boolean = false,
    /** 按顺序执行的测试项；每项每轮只测一次，列表长度决定连续失败次数。 */
    val probeSteps: List<FailoverProbeStep> = DEFAULT_PROBE_STEPS
) {
    companion object {
        const val DEFAULT_PROBE_URL = "https://www.gstatic.com/generate_204"
        const val DEFAULT_PING_HOST = "www.google.com"
        const val DEFAULT_PING_PORT = 443
        const val DEFAULT_INTERVAL_SECONDS = 1
        const val DEFAULT_TIMEOUT_MS = 1_000L

        /** 默认保留两次 HTTPS 测活，延续旧版连续失败两次才转移的行为。 */
        val DEFAULT_PROBE_STEPS = listOf(
            FailoverProbeStep(id = "default-https-1"),
            FailoverProbeStep(id = "default-https-2")
        )
    }
}

/** 一项代理连通性检测，可选择 HTTPS 测活或 TCP 连接测试。 */
data class FailoverProbeStep(
    val id: String = UUID.randomUUID().toString(),
    val method: FailoverDetectionMethod = FailoverDetectionMethod.HTTPS_PROBE,
    val probeUrl: String = FailoverDetectionSettings.DEFAULT_PROBE_URL,
    val pingHost: String = FailoverDetectionSettings.DEFAULT_PING_HOST,
    val pingPort: Int = FailoverDetectionSettings.DEFAULT_PING_PORT,
    /** 本项完成后到下一项或下一轮的等待时间。 */
    val intervalSeconds: Int = FailoverDetectionSettings.DEFAULT_INTERVAL_SECONDS,
    /** 本项单次连接或请求的最长等待时间。 */
    val timeoutMs: Long = FailoverDetectionSettings.DEFAULT_TIMEOUT_MS
)

enum class FailoverDetectionMethod(val title: String) {
    HTTPS_PROBE("HTTPS 测活"),
    PROXY_TCP_PING("代理 TCP Ping")
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

/** 故障时使用单个备用节点，或从指定节点组中随机挑选节点。 */
sealed interface FailoverTransferTarget {
    data class Node(val nodeId: String) : FailoverTransferTarget
    data class Group(val groupId: String) : FailoverTransferTarget
}

data class FailoverTransferSettings(
    val target: FailoverTransferTarget? = null
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
