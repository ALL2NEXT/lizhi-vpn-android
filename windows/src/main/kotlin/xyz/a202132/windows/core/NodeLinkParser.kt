package xyz.a202132.windows.core

import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import xyz.a202132.windows.model.DesktopNode
import xyz.a202132.windows.model.NodeType

class NodeLinkParser {
    fun parseSubscription(content: String): List<DesktopNode> {
        val decoded = decodeSubscription(content.trim())
        return decoded.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull(::parseNodeLink)
            .distinctBy(DesktopNode::id)
            .toList()
    }

    fun parseNodeLink(link: String): DesktopNode? = runCatching {
        val trimmed = link.trim()
        when (trimmed.substringBefore("://").lowercase()) {
            "vmess" -> parseVmess(trimmed)
            "vless" -> parseUriNode(trimmed, NodeType.VLESS, 443)
            "trojan" -> parseUriNode(trimmed, NodeType.TROJAN, 443)
            "hysteria2", "hy2" -> parseUriNode(
                trimmed.replaceFirst(Regex("(?i)^hy2://"), "hysteria2://"),
                NodeType.HYSTERIA2,
                443,
                originalLink = trimmed
            )
            "anytls" -> parseUriNode(trimmed, NodeType.ANYTLS, 443)
            "tuic" -> parseUriNode(trimmed, NodeType.TUIC, 443)
            "naive", "naive+https" -> parseUriNode(
                trimmed.replaceFirst(Regex("(?i)^naive[+]https://"), "naive://"),
                NodeType.NAIVE,
                443,
                originalLink = trimmed
            )
            "wireguard" -> parseUriNode(trimmed, NodeType.WIREGUARD, 51820)
            "ss" -> parseShadowsocks(trimmed)
            "socks", "socks4", "socks4a", "socks5" ->
                parseUriNode(trimmed, NodeType.SOCKS, 1080)
            "http" -> parseUriNode(trimmed, NodeType.HTTP, 80)
            "https" -> parseUriNode(trimmed, NodeType.HTTP, 443)
            else -> null
        }
    }.getOrNull()

    private fun parseVmess(link: String): DesktopNode {
        val encoded = link.substringAfter("://").substringBefore('#')
        val json = JsonParser.parseString(decodeBase64(encoded)).asJsonObject
        val server = json.get("add")?.asString.orEmpty()
        val port = json.get("port")?.asString?.toIntOrNull() ?: 443
        require(server.isNotBlank())
        return node(
            link = link,
            name = json.get("ps")?.asString.orEmpty().ifBlank { "VMess $server" },
            type = NodeType.VMESS,
            server = server,
            port = port
        )
    }

    private fun parseShadowsocks(link: String): DesktopNode {
        val withoutFragment = link.substringBefore('#')
        val fragment = link.substringAfter('#', "").urlDecode()
        val payload = withoutFragment.substringAfter("ss://")
        val authority = if ('@' in payload) {
            payload.substringAfterLast('@').substringBefore('?')
        } else {
            decodeBase64(payload.substringBefore('?')).substringAfter('@')
        }
        val (server, port) = parseServerPort(authority, 8388)
        return node(
            link = link,
            name = fragment.ifBlank { "Shadowsocks $server" },
            type = NodeType.SHADOWSOCKS,
            server = server,
            port = port
        )
    }

    private fun parseUriNode(
        normalizedLink: String,
        type: NodeType,
        defaultPort: Int,
        originalLink: String = normalizedLink
    ): DesktopNode {
        val uri = URI(normalizedLink.replace(" ", "%20"))
        val authority = uri.rawAuthority.orEmpty().substringAfterLast('@')
        val (server, parsedPort) = parseServerPort(authority, defaultPort)
        require(server.isNotBlank())
        val name = uri.rawFragment?.urlDecode().orEmpty()
            .ifBlank { "${type.protocol} $server" }
        return node(originalLink, name, type, server, parsedPort)
    }

    private fun node(
        link: String,
        name: String,
        type: NodeType,
        server: String,
        port: Int
    ) = DesktopNode(
        id = sha256(link).take(24),
        name = name,
        type = type,
        server = server.removeSurrounding("[", "]"),
        port = port,
        rawLink = link
    )

    private fun parseServerPort(authority: String, defaultPort: Int): Pair<String, Int> {
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            require(end > 0)
            val host = authority.substring(1, end)
            val port = authority.substring(end + 1).removePrefix(":")
                .substringBefore(',')
                .substringBefore('-')
                .toIntOrNull() ?: defaultPort
            return host to port
        }
        val host = authority.substringBeforeLast(':', authority)
        val port = authority.substringAfterLast(':', "")
            .substringBefore(',')
            .substringBefore('-')
            .toIntOrNull() ?: defaultPort
        return host to port
    }

    private fun decodeSubscription(content: String): String {
        if (content.contains("://")) return content
        return runCatching { decodeBase64(content) }.getOrDefault(content)
    }

    private fun decodeBase64(value: String): String {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
    }

    private fun String.urlDecode(): String =
        URLDecoder.decode(this, StandardCharsets.UTF_8)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
