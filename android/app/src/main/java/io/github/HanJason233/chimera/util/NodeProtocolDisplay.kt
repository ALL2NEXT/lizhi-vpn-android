package io.github.HanJason233.chimera.util

import com.google.gson.JsonParser
import java.net.URLDecoder
import java.util.Base64
import io.github.HanJason233.chimera.data.model.NodeDisplaySettings
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeType
import io.github.HanJason233.chimera.data.model.ProtocolCoreSettings
import io.github.HanJason233.chimera.data.model.ProxyCore

/** 生成“协议+安全层+传输方式+核心”节点标签，存在的层级始终完整展示。 */
internal fun Node.protocolDisplayLabel(
    settings: NodeDisplaySettings = NodeDisplaySettings(),
    coreSettings: ProtocolCoreSettings = ProtocolCoreSettings()
): String {
    val rawLink = getRawLinkPlain()
    val protocol = when (type) {
        NodeType.VLESS -> "vless"
        NodeType.VMESS -> "vmess"
        NodeType.TROJAN -> "trojan"
        NodeType.HYSTERIA2 -> "hy2"
        NodeType.ANYTLS -> "anytls"
        NodeType.TUIC -> "tuic"
        NodeType.NAIVE -> "naive"
        NodeType.WIREGUARD -> "wg"
        NodeType.MASQUE -> "masque"
        NodeType.SHADOWSOCKS -> "ss"
        NodeType.SOCKS -> socksProtocolLabel(rawLink)
        NodeType.HTTP -> "http"
        NodeType.UNKNOWN -> "unknown"
    }

    // 安全层和传输层分别解析，避免 Reality/TLS 覆盖 TCP/QUIC 信息。
    val layers = when (type) {
        NodeType.VLESS -> DisplayLayers(
            security = uriSecurity(rawLink) ?: "none",
            transport = uriTransport(rawLink) ?: "tcp"
        )
        NodeType.TROJAN -> DisplayLayers(
            security = uriSecurity(rawLink) ?: "tls",
            transport = uriTransport(rawLink) ?: "tcp"
        )
        NodeType.VMESS -> vmessLayers(rawLink)
        NodeType.HYSTERIA2 -> DisplayLayers(security = "tls", transport = "quic")
        NodeType.ANYTLS -> DisplayLayers(security = "tls", transport = "tcp")
        NodeType.TUIC -> DisplayLayers(security = "tls", transport = "quic")
        NodeType.NAIVE -> DisplayLayers(
            security = "tls",
            transport = if (queryFlag(rawLink, "quic")) "quic" else "h2"
        )
        NodeType.WIREGUARD -> DisplayLayers(transport = "udp")
        NodeType.MASQUE -> DisplayLayers(security = "tls", transport = "quic")
        NodeType.SHADOWSOCKS -> shadowsocksLayers(rawLink)
        NodeType.SOCKS -> DisplayLayers(security = "none", transport = "tcp")
        NodeType.HTTP -> DisplayLayers(
            security = if (rawLink.startsWith("https://", true)) "tls" else "none",
            transport = "tcp"
        )
        NodeType.UNKNOWN -> DisplayLayers()
    }

    return listOfNotNull(
        protocol.takeIf { settings.showProtocol },
        layers.security?.takeIf { settings.showSecurity },
        layers.transport?.takeIf { settings.showTransport },
        coreSettings.coreFor(type).displayLabel().takeIf { settings.showCore }
    ).joinToString("+")
}

private fun ProxyCore.displayLabel(): String = when (this) {
    ProxyCore.SING_BOX -> "sing-box"
    ProxyCore.SING_BOX_LX -> "sing-box-lx"
    ProxyCore.XRAY -> "xray"
}

private data class DisplayLayers(
    val security: String? = null,
    val transport: String? = null
)

private fun uriSecurity(rawLink: String): String? = normalizeSecurity(
    queryParameter(rawLink, "security")
)

private fun uriTransport(rawLink: String): String? = normalizeTransport(
    queryParameter(rawLink, "type", "network")
)

private fun vmessLayers(rawLink: String): DisplayLayers = runCatching {
    val payload = rawLink.substringAfter("vmess://").substringBefore('#').substringBefore('?')
    val json = JsonParser.parseString(decodeDisplayBase64(payload)).asJsonObject
    DisplayLayers(
        security = normalizeSecurity(parseJsonString(json.get("tls"))) ?: "none",
        transport = normalizeTransport(
            parseJsonString(json.get("net")) ?: parseJsonString(json.get("network"))
        ) ?: "tcp"
    )
}.getOrDefault(DisplayLayers(security = "none", transport = "tcp"))

private fun decodeDisplayBase64(rawValue: String): String {
    val normalized = rawValue.trim().replace('-', '+').replace('_', '/')
    val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
    return String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
}

private fun shadowsocksLayers(rawLink: String): DisplayLayers {
    val plugin = queryParameter(rawLink, "plugin")?.lowercase()
        ?: return DisplayLayers(transport = "tcp")
    return when {
        plugin.startsWith("v2ray-plugin") -> {
            val mode = plugin.split(';')
                .firstOrNull { it.startsWith("mode=") }
                ?.substringAfter('=')
            DisplayLayers(transport = normalizeTransport(mode ?: "ws"))
        }
        plugin.startsWith("shadow-tls") -> DisplayLayers(security = "tls", transport = "tcp")
        plugin.startsWith("obfs-local") || plugin.startsWith("simple-obfs") -> {
            val obfs = plugin.split(';')
                .firstOrNull { it.startsWith("obfs=") }
                ?.substringAfter('=')
                ?.takeIf { it == "http" || it == "tls" }
            DisplayLayers(transport = obfs ?: "plugin")
        }
        else -> DisplayLayers(transport = "plugin")
    }
}

private fun socksProtocolLabel(rawLink: String): String = when {
    rawLink.startsWith("socks4a://", true) -> "socks4a"
    rawLink.startsWith("socks4://", true) -> "socks4"
    else -> "socks5"
}

private fun normalizeTransport(rawValue: String?): String? = when (rawValue?.trim()?.lowercase()) {
    null, "" -> null
    "none", "raw", "tcp" -> "tcp"
    "websocket" -> "ws"
    "gun" -> "grpc"
    "http", "h2" -> "h2"
    "http-upgrade", "httpupgrade" -> "httpupgrade"
    "splithttp", "split-http", "xhttp" -> "xhttp"
    "mkcp" -> "kcp"
    else -> rawValue.trim().lowercase()
}

private fun normalizeSecurity(rawValue: String?): String? = when (rawValue?.trim()?.lowercase()) {
    "reality" -> "reality"
    "tls", "xtls" -> "tls"
    else -> null
}

private fun queryParameter(rawLink: String, vararg keys: String): String? {
    val query = rawLink.substringAfter('?', "").substringBefore('#')
    if (query.isBlank()) return null
    return query.split('&')
        .asSequence()
        .map { part ->
            decodeQueryPart(part.substringBefore('=')) to
                decodeQueryPart(part.substringAfter('=', ""))
        }
        .firstOrNull { (key, value) ->
            keys.any { it.equals(key, true) } && value.isNotBlank()
        }
        ?.second
}

private fun decodeQueryPart(value: String): String = runCatching {
    URLDecoder.decode(value, Charsets.UTF_8.name())
}.getOrDefault(value)

private fun queryFlag(rawLink: String, vararg keys: String): Boolean =
    queryParameter(rawLink, *keys)?.trim()?.lowercase() in setOf("1", "true", "yes", "on")
