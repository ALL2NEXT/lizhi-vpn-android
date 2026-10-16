package io.github.HanJason233.chimera.network.subscription

import io.github.HanJason233.chimera.network.subscription.importer.LocalSubscriptionContent
import io.github.HanJason233.chimera.network.NetworkClient
import io.github.HanJason233.chimera.network.subscription.importer.SubscriptionConverter

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeType
import android.net.Uri
import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.data.model.PersonalSourceUserAgentMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * 订阅解析器：所有本地配置和远程响应先统一转换为 URI，再构造节点。
 */
class SubscriptionParser {
    
    private val gson = Gson()
    private val tag = "SubscriptionParser"

    suspend fun fetchAndParse(
        url: String,
        timeoutMs: Long = 25000L
    ): Result<List<Node>> = fetchAndParseWithMetadata(url, timeoutMs)
        .map(ParsedSubscription::nodes)

    internal suspend fun fetchAndParseWithMetadata(
        url: String,
        timeoutMs: Long = 25000L,
        userAgentMode: PersonalSourceUserAgentMode? = null,
        customUserAgent: String? = null
    ): Result<ParsedSubscription> {
        val userAgents = when (userAgentMode) {
            null -> listOf(AppConfig.HTTP_USER_AGENT)
            PersonalSourceUserAgentMode.AUTO -> AUTOMATIC_USER_AGENTS
            PersonalSourceUserAgentMode.CUSTOM -> listOfNotNull(
                customUserAgent?.trim()?.takeIf(String::isNotBlank)
            )
        }
        if (userAgents.isEmpty()) {
            return Result.failure(IllegalArgumentException("固定 User-Agent 不能为空"))
        }

        var lastFailure: Throwable? = null
        for (userAgent in userAgents) {
            try {
                val response = withTimeout(timeoutMs.coerceIn(5000L, 60000L)) {
                    NetworkClient.apiService.getSubscription(url, userAgent)
                }
                if (!response.isSuccessful) {
                    throw IllegalStateException("订阅请求返回 HTTP ${response.code()}")
                }
                val raw = withContext(Dispatchers.IO) {
                    response.body()?.use { it.string() }
                        ?: throw IllegalStateException("订阅接口返回空内容")
                }
                val nodes = withContext(Dispatchers.Default) {
                    parseSubscription(raw)
                }
                if (nodes.isEmpty()) {
                    throw IllegalStateException("订阅内容未解析到可用节点")
                }
                val userInfo = SubscriptionUserInfo.parse(
                    response.headers()[SUBSCRIPTION_USERINFO_HEADER]
                )
                val name = parseSubscriptionName(
                    response.headers()["Profile-Title"],
                    response.headers()["Subscription-Name"],
                    response.headers()["Content-Disposition"]
                )
                return Result.success(ParsedSubscription(nodes, userInfo, name))
            } catch (error: CancellationException) {
                if (error !is TimeoutCancellationException || !currentCoroutineContext().isActive) {
                    throw error
                }
                lastFailure = error
            } catch (error: Exception) {
                lastFailure = error
            }

            if (userAgentMode == null || userAgentMode == PersonalSourceUserAgentMode.CUSTOM) {
                break
            }
        }

        val error = lastFailure ?: IllegalStateException("订阅请求失败")
        Log.e(tag, "Failed to fetch subscription with configured User-Agent options", error)
        return Result.failure(error)
    }

    /**
     * 解析订阅内容
     */
    fun parseSubscription(content: String): List<Node> {
        Log.d(tag, "Original content length: ${content.length}")
        return parseStandardUris(SubscriptionConverter.toStandardUris(content))
    }

    /** 智能识别后的本地内容直接传递明文片段；远程响应仍从字符串入口归一化。 */
    internal fun parseSubscription(content: LocalSubscriptionContent): List<Node> =
        parseStandardUris(SubscriptionConverter.toStandardUris(content))

    private fun parseStandardUris(uris: List<String>): List<Node> {
        Log.d(tag, "Converted ${uris.size} standard subscription URIs")
        return uris.mapNotNull(::parseNodeLink)
            .filter { it.server.isNotBlank() && it.port in 1..65535 }
    }
    
    /**
     * 解析单个节点链接
     */
    fun parseNodeLink(link: String): Node? {
        return try {
            // 支持从 Markdown 等文本中复制的转义 URI，例如 vless\://...\@host。
            val normalizedLink = link.trim()
                .replace("\\://", "://")
                .replace("\\@", "@")
            val lowerLink = normalizedLink.lowercase()
            when {
                lowerLink.startsWith("vless://") -> parseVlessLink(normalizedLink)
                lowerLink.startsWith("vmess://") -> parseVmessLink(normalizedLink)
                lowerLink.startsWith("trojan://") -> parseTrojanLink(normalizedLink)
                lowerLink.startsWith("hysteria2://") || lowerLink.startsWith("hy2://") -> parseHysteria2Link(normalizedLink)
                lowerLink.startsWith("anytls://") -> parseAnyTlsLink(normalizedLink)
                lowerLink.startsWith("tuic://") -> parseTuicLink(normalizedLink)
                lowerLink.startsWith("naive://") || lowerLink.startsWith("naive+https://") -> parseNaiveLink(normalizedLink)
                lowerLink.startsWith("wireguard://") -> parseWireGuardLink(normalizedLink)
                lowerLink.startsWith("masque://") -> parseMasqueLink(normalizedLink)
                lowerLink.startsWith("ss://") -> parseShadowsocksLink(normalizedLink)
                lowerLink.startsWith("socks://") || lowerLink.startsWith("socks5://") || lowerLink.startsWith("socks4://") || lowerLink.startsWith("socks4a://") -> parseSocksLink(normalizedLink)
                lowerLink.startsWith("http://") || lowerLink.startsWith("https://") -> parseHttpLink(normalizedLink)
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
        // 参考 NekoBox 的 VLESS 导入方式：借用 OkHttp URL 解析器处理 authority、IPv6 和转义字符。
        val url = link.replaceFirst(Regex("(?i)^vless://"), "https://").toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid VLESS URI")
        require(url.username.isNotBlank() && url.host.isNotBlank()) { "VLESS URI is missing credentials or host" }
        val name = url.fragment?.takeIf { it.isNotBlank() } ?: "VLESS Node"
        val server = url.host
        val port = url.port
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.VLESS,
            server = server,
            port = port,
            rawLink = link
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
            rawLink = link
        )
    }
    
    /**
     * 解析 Trojan 链接
     * 格式: trojan://password@host:port?params#name
     */
    private fun parseTrojanLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = nodeName(uri, "Trojan Node")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 443 else uri.port
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.TROJAN,
            server = server,
            port = port,
            rawLink = link
        )
    }
    
    /**
     * 解析 Hysteria2 链接
     * 格式: hysteria2://password@host:port?params#name
     */
    private fun parseHysteria2Link(link: String): Node {
        val normalizedLink = link.replace("hy2://", "hysteria2://")
        val uri = Uri.parse(normalizedLink)
        val name = nodeName(uri, "Hysteria2 Node")
        val endpoint = parseHysteria2Endpoint(normalizedLink)
        val server = endpoint.first ?: (uri.host ?: "")
        val port = endpoint.second ?: if (uri.port == -1) 443 else uri.port
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.HYSTERIA2,
            server = server,
            port = port,
            rawLink = link
        )
    }

    /**
     * 解析 AnyTLS 链接
     * 格式: anytls://password@host:port?params#name
     */
    private fun parseAnyTlsLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = nodeName(uri, "AnyTLS Node")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 443 else uri.port

        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.ANYTLS,
            server = server,
            port = port,
            rawLink = link
        )
    }

    /**
     * 解析 TUIC 链接
     * 格式: tuic://uuid:password@host:port?params#name
     */
    private fun parseTuicLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = nodeName(uri, "TUIC Node")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 443 else uri.port

        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.TUIC,
            server = server,
            port = port,
            rawLink = link
        )
    }

    /**
     * 解析 Naive 链接
     * 格式: naive+https://username:password@host:port?params#name
     */
    private fun parseNaiveLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = nodeName(uri, "Naive Node")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 443 else uri.port

        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.NAIVE,
            server = server,
            port = port,
            rawLink = link
        )
    }

    /**
     * 解析 WireGuard 链接
     * 格式: wireguard://private_key@host:port?params#name
     */
    private fun parseWireGuardLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = nodeName(uri, "WireGuard Node")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 51820 else uri.port

        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.WIREGUARD,
            server = server,
            port = port,
            rawLink = link
        )
    }

    private fun parseMasqueLink(link: String): Node {
        val url = link.replaceFirst(Regex("(?i)^masque://"), "https://").toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid MASQUE URI")
        require(url.host.isNotBlank()) { "MASQUE URI is missing its server" }
        val name = url.fragment?.takeIf(String::isNotBlank) ?: "MASQUE Node"
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.MASQUE,
            server = url.host,
            port = url.port,
            rawLink = link
        )
    }
    
    /**
     * 解析 Shadowsocks 链接
     * 格式: ss://base64(method:password)@host:port#name
     * 或: ss://base64(method:password@host:port)#name
     */
    private fun parseShadowsocksLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = nodeName(uri, "Shadowsocks Node")
        
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
            server = server.orEmpty(),
            port = if (port == -1) 443 else port,
            rawLink = link
        )
    }

    /**
     * 解析 Socks 链接
     * 格式: socks://user:pass@host:port#name
     * 也支持: socks5://... 和 socks4://...
     */
    private fun parseSocksLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = nodeName(uri, "Socks Node")
        val server = uri.host ?: ""
        val port = if (uri.port == -1) 1080 else uri.port
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.SOCKS,
            server = server,
            port = port,
            rawLink = link
        )
    }
    
    /**
     * 解析 HTTP/HTTPS 代理链接
     * 格式: http://user:pass@host:port#name
     * 或: https://user:pass@host:port#name
     */
    private fun parseHttpLink(link: String): Node {
        val uri = Uri.parse(link)
        val name = nodeName(uri, "HTTP Proxy")
        val server = uri.host ?: ""
        val defaultPort = if (link.startsWith("https://")) 443 else 80
        val port = if (uri.port == -1) defaultPort else uri.port
        
        return Node(
            id = generateId(link),
            name = name,
            type = NodeType.HTTP,
            server = server,
            port = port,
            rawLink = link
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

    private fun parseSubscriptionName(
        profileTitle: String?,
        subscriptionName: String?,
        contentDisposition: String?
    ): String? {
        decodeSubscriptionTitle(profileTitle)
            ?.let { return it }
        decodeSubscriptionTitle(subscriptionName)
            ?.let { return it }

        val extendedFilename = CONTENT_DISPOSITION_EXTENDED_FILENAME.find(contentDisposition.orEmpty())
        val filename = extendedFilename?.groupValues?.getOrNull(1)
            ?: CONTENT_DISPOSITION_FILENAME.find(contentDisposition.orEmpty())?.groupValues?.getOrNull(1)
            ?: return null
        val unquotedFilename = filename.trim().removeSurrounding("\"")
        val decodedFilename = if (extendedFilename != null) {
            runCatching { URLDecoder.decode(unquotedFilename.replace("+", "%2B"), "UTF-8") }
                .getOrNull()
                ?: return null
        } else {
            unquotedFilename
        }
        return decodedFilename.substringAfterLast('/').substringAfterLast('\\')
            .replace(FILENAME_EXTENSION, "")
            .trim()
            .takeIf(String::isNotEmpty)
    }

    private fun decodeSubscriptionTitle(value: String?): String? {
        val title = value?.trim()?.removeSurrounding("\"")?.takeIf(String::isNotEmpty)
            ?: return null
        if (!title.startsWith("base64:", ignoreCase = true)) return title

        val decoded = runCatching {
            String(Base64.decode(title.substringAfter(':').trim(), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()?.trim()
        return decoded?.takeIf(String::isNotEmpty)
    }

    private companion object {
        // 自动模式按列表顺序逐个尝试，成功解析后立即停止。
        val AUTOMATIC_USER_AGENTS = listOf(
            "NekoBox/Android/1.4.2 (Prefer ClashMeta Format)",
            "sing-box/1.15.0",
            "Singbox/1.14",
            "clash-meta",
            "v2rayN/7.8.2",
            "Throne/1.0.0",
            "sing-box/1.14.0"
        )
        private const val SUBSCRIPTION_USERINFO_HEADER = "Subscription-Userinfo"
        val CONTENT_DISPOSITION_EXTENDED_FILENAME =
            Regex("""(?i)(?:^|;)\s*filename\*\s*=\s*UTF-8''([^;]+)""")
        val CONTENT_DISPOSITION_FILENAME =
            Regex("""(?i)(?:^|;)\s*filename\s*=\s*"?([^";]+)"?""")
        val FILENAME_EXTENSION = Regex("""\.(?:yaml|yml|txt|conf)$""", RegexOption.IGNORE_CASE)
    }

    private fun nodeName(uri: Uri, fallback: String): String =
        uri.fragment?.trim()?.takeIf(String::isNotEmpty) ?: fallback
}
