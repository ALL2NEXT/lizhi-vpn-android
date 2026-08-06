package xyz.a202132.app.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType
import android.net.Uri
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.coroutines.withTimeout

/**
 * 订阅解析器 - 解析Base64编码的节点列表
 */
class SubscriptionParser {
    
    private val gson = Gson()
    private val tag = "SubscriptionParser"

    suspend fun fetchAndParse(
        url: String,
        encrypted: Boolean,
        timeoutMs: Long = 25000L
    ): Result<List<Node>> {
        return try {
            val response = withTimeout(timeoutMs.coerceIn(5000L, 60000L)) {
                NetworkClient.apiService.getSubscription(url)
            }
            Result.success(parseSubscription(response, encrypted))
        } catch (e: Exception) {
            Log.e(tag, "Failed to fetch subscription: $url", e)
            Result.failure(e)
        }
    }
    
    /**
     * 解析订阅内容
     */
    fun parseSubscription(content: String, encrypted: Boolean = false): List<Node> {
        Log.d(tag, "Original content length: ${content.length}")

        val finalContent = if (encrypted) {
            decodeEncryptedContent(content)
        } else {
            decodeUnencryptedContent(content)
        }
        
        val lines = finalContent.split("\n", "\r\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            
        Log.d(tag, "Parsed ${lines.size} lines")
        
        return lines.mapNotNull { parseNodeLink(it) }
    }

    private fun decodeEncryptedContent(content: String): String {
        var decrypted = xyz.a202132.app.util.CryptoUtils.decryptNodes(content.trim())
        if (decrypted.startsWith("Error")) {
            throw IllegalArgumentException("AES subscription decryption failed")
        }
        if (!decrypted.contains("://")) {
            decrypted = decodeBase64Compat(decrypted.trim())
        }
        require(decrypted.contains("://")) { "Decrypted subscription contains no node links" }
        return decrypted
    }

    private fun decodeUnencryptedContent(content: String): String {
        val raw = content.trim()
        if (raw.contains("://")) return raw

        return runCatching { decodeBase64Compat(raw) }
            .getOrDefault(raw)
    }
    
    /**
     * 解析单个节点链接
     */
    fun parseNodeLink(link: String): Node? {
        return try {
            val lowerLink = link.lowercase()
            when {
                lowerLink.startsWith("vless://") -> parseVlessLink(link)
                lowerLink.startsWith("vmess://") -> parseVmessLink(link)
                lowerLink.startsWith("trojan://") -> parseTrojanLink(link)
                lowerLink.startsWith("hysteria2://") || lowerLink.startsWith("hy2://") -> parseHysteria2Link(link)
                lowerLink.startsWith("anytls://") -> parseAnyTlsLink(link)
                lowerLink.startsWith("tuic://") -> parseTuicLink(link)
                lowerLink.startsWith("naive://") || lowerLink.startsWith("naive+https://") -> parseNaiveLink(link)
                lowerLink.startsWith("wireguard://") -> parseWireGuardLink(link)
                lowerLink.startsWith("ss://") -> parseShadowsocksLink(link)
                lowerLink.startsWith("socks://") || lowerLink.startsWith("socks5://") || lowerLink.startsWith("socks4://") || lowerLink.startsWith("socks4a://") -> parseSocksLink(link)
                lowerLink.startsWith("http://") || lowerLink.startsWith("https://") -> parseHttpLink(link)
                else -> null
            }
        } catch (e: Exception) {
            val scheme = link.substringBefore("://", "unknown")
            Log.e(tag, "Failed to parse link, scheme=$scheme, length=${link.length}", e)
            null
        }
    }
    
    /**
     * 解析 VLESS 链接
     * 格式: vless://uuid@host:port?params#name
     */
    private fun parseVlessLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "VLESS Node", "UTF-8")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 80 else uri.port
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.VLESS,
            server = server,
            port = port,
            rawLink = xyz.a202132.app.util.CryptoUtils.encryptForStorage(link)
        )
    }
    
    /**
     * 解析 VMess 链接
     * 格式: vmess://base64-encoded-json
     */
    private fun parseVmessLink(link: String): Node {
        val base64Content = link.removePrefix("vmess://")
        val jsonStr = decodeBase64Compat(base64Content)
        val json = gson.fromJson(jsonStr, JsonObject::class.java)
        
        val name = json.get("ps")?.asString ?: "VMess Node"
        val server = json.get("add")?.asString ?: ""
        val port = json.get("port")?.asInt ?: json.get("port")?.asString?.toIntOrNull() ?: 443
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.VMESS,
            server = server,
            port = port,
            rawLink = xyz.a202132.app.util.CryptoUtils.encryptForStorage(link)
        )
    }
    
    /**
     * 解析 Trojan 链接
     * 格式: trojan://password@host:port?params#name
     */
    private fun parseTrojanLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "Trojan Node", "UTF-8")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 443 else uri.port
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.TROJAN,
            server = server,
            port = port,
            rawLink = xyz.a202132.app.util.CryptoUtils.encryptForStorage(link)
        )
    }
    
    /**
     * 解析 Hysteria2 链接
     * 格式: hysteria2://password@host:port?params#name
     */
    private fun parseHysteria2Link(link: String): Node {
        val normalizedLink = link.replace("hy2://", "hysteria2://")
        val uri = Uri.parse(normalizedLink)
        val name = URLDecoder.decode(uri.fragment ?: "Hysteria2 Node", "UTF-8")
        val endpoint = parseHysteria2Endpoint(normalizedLink)
        val server = endpoint.first ?: (uri.host ?: "")
        val port = endpoint.second ?: if (uri.port == -1) 443 else uri.port
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.HYSTERIA2,
            server = server,
            port = port,
            rawLink = xyz.a202132.app.util.CryptoUtils.encryptForStorage(link)
        )
    }

    /**
     * 解析 AnyTLS 链接
     * 格式: anytls://password@host:port?params#name
     */
    private fun parseAnyTlsLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "AnyTLS Node", "UTF-8")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 443 else uri.port

        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.ANYTLS,
            server = server,
            port = port,
            rawLink = xyz.a202132.app.util.CryptoUtils.encryptForStorage(link)
        )
    }

    /**
     * 解析 TUIC 链接
     * 格式: tuic://uuid:password@host:port?params#name
     */
    private fun parseTuicLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "TUIC Node", "UTF-8")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 443 else uri.port

        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.TUIC,
            server = server,
            port = port,
            rawLink = xyz.a202132.app.util.CryptoUtils.encryptForStorage(link)
        )
    }

    /**
     * 解析 Naive 链接
     * 格式: naive+https://username:password@host:port?params#name
     */
    private fun parseNaiveLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "Naive Node", "UTF-8")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 443 else uri.port

        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.NAIVE,
            server = server,
            port = port,
            rawLink = xyz.a202132.app.util.CryptoUtils.encryptForStorage(link)
        )
    }

    /**
     * 解析 WireGuard 链接
     * 格式: wireguard://private_key@host:port?params#name
     */
    private fun parseWireGuardLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "WireGuard Node", "UTF-8")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 51820 else uri.port

        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.WIREGUARD,
            server = server,
            port = port,
            rawLink = xyz.a202132.app.util.CryptoUtils.encryptForStorage(link)
        )
    }
    
    /**
     * 解析 Shadowsocks 链接
     * 格式: ss://base64(method:password)@host:port#name
     * 或: ss://base64(method:password@host:port)#name
     */
    private fun parseShadowsocksLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "Shadowsocks Node", "UTF-8")
        
        var server = uri.host
        var port = uri.port
        
        // 如果host为空，说明整个部分都被base64编码了
        if (server == null) {
            val base64Part = link.removePrefix("ss://").substringBefore("#")
            val decoded = decodeBase64Compat(base64Part)
            // 格式: method:password@host:port
            val hostPort = decoded.substringAfter("@")
            server = hostPort.substringBefore(":")
            port = hostPort.substringAfter(":").toIntOrNull() ?: 443
        }
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.SHADOWSOCKS,
            server = server ?: "",
            port = if (port == -1) 443 else port,
            rawLink = xyz.a202132.app.util.CryptoUtils.encryptForStorage(link)
        )
    }

    /**
     * 解析 Socks 链接
     * 格式: socks://user:pass@host:port#name
     * 也支持: socks5://... 和 socks4://...
     */
    private fun parseSocksLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "Socks Node", "UTF-8")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 1080 else uri.port
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.SOCKS,
            server = server,
            port = port,
            rawLink = xyz.a202132.app.util.CryptoUtils.encryptForStorage(link)
        )
    }
    
    /**
     * 解析 HTTP/HTTPS 代理链接
     * 格式: http://user:pass@host:port#name
     * 或: https://user:pass@host:port#name
     */
    private fun parseHttpLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = URLDecoder.decode(uri.fragment ?: "HTTP Proxy", "UTF-8")
        val server = uri.host ?: ""
        val defaultPort = if (link.startsWith("https://")) 443 else 80
        val port = if (uri.port == -1) defaultPort else uri.port
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.HTTP,
            server = server,
            port = port,
            rawLink = xyz.a202132.app.util.CryptoUtils.encryptForStorage(link)
        )
    }
    
    /**
     * 生成节点ID (MD5 hash)
     */
    private fun generateId(link: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(link.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun decodeBase64Compat(rawValue: String): String {
        val normalized = rawValue.trim()
            .replace('-', '+')
            .replace('_', '/')
        val padding = (4 - normalized.length % 4) % 4
        val padded = normalized + "=".repeat(padding)
        return String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun parseHysteria2Endpoint(link: String): Pair<String?, Int?> {
        val authority = link.substringAfter("@", "").substringBefore("?").substringBefore("#").trim()
        if (authority.isEmpty()) return null to null

        return if (authority.startsWith("[")) {
            val endBracket = authority.indexOf(']')
            if (endBracket <= 0) {
                null to null
            } else {
                val host = authority.substring(1, endBracket)
                val portSpec = authority.substring(endBracket + 1).removePrefix(":")
                host to parseFirstPort(portSpec)
            }
        } else {
            val lastColon = authority.lastIndexOf(':')
            if (lastColon <= 0) {
                authority to null
            } else {
                val host = authority.substring(0, lastColon)
                val portSpec = authority.substring(lastColon + 1)
                host to parseFirstPort(portSpec)
            }
        }
    }

    private fun parseFirstPort(portSpec: String): Int? {
        return portSpec
            .substringBefore(",")
            .substringBefore("-")
            .trim()
            .toIntOrNull()
    }
}
