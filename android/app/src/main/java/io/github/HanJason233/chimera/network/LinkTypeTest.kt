package io.github.HanJason233.chimera.network

import com.google.gson.JsonParser
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.data.model.LinkTypeTestTargets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.InetAddress
import kotlin.coroutines.coroutineContext

internal enum class LinkTypeTestState(val title: String) {
    IDLE("未检测"),
    TESTING("检测中"),
    DIRECT("直连"),
    RELAY("普通中转"),
    CDN_RELAY("CDN 中转"),
    NOT_CONNECTED("未连接"),
    ERROR("检测失败")
}

internal data class LinkTypeTestResult(
    val state: LinkTypeTestState = LinkTypeTestState.IDLE,
    val serverAddress: String = "",
    val entryIps: List<String> = emptyList(),
    val exitIp: String = "",
    /** Only entry IPs are looked up; the IP-check service's CDN is irrelevant. */
    val entryCdnProviders: Map<String, String> = emptyMap(),
    val message: String = ""
)

/** Parses literals without ever resolving a hostname supplied by an HTTP response. */
internal object LinkIpAddress {
    fun parse(value: String): InetAddress? {
        val host = value.trim().removePrefix("[").removeSuffix("]")
        if (host.isEmpty() || '%' in host) return null
        if (':' in host) {
            if (!host.all { it in "0123456789abcdefABCDEF:." }) return null
            return runCatching { InetAddress.getByName(host) }.getOrNull()
        }
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
        return InetAddress.getByAddress(octets.map(Int::toByte).toByteArray())
    }

    fun normalize(value: String): String? = parse(value)?.hostAddress
}

internal object LinkTypeClassifier {
    fun classify(
        serverAddress: String,
        serverIps: List<String>,
        exitIp: String,
        cdnProvider: (String) -> String? = CdnIpCatalog::providerFor
    ): LinkTypeTestResult {
        val entries = serverIps.mapNotNull(LinkIpAddress::normalize).distinct()
        val exit = LinkIpAddress.normalize(exitIp)
        if (entries.isEmpty() || exit == null) {
            return LinkTypeTestResult(
                state = LinkTypeTestState.ERROR,
                serverAddress = serverAddress,
                entryIps = entries,
                exitIp = exit.orEmpty(),
                message = if (entries.isEmpty()) "无法解析节点服务器地址" else "无法获取有效的落地点 IP"
            )
        }
        if (exit in entries) {
            return LinkTypeTestResult(LinkTypeTestState.DIRECT, serverAddress, entries, exit)
        }
        val providers = entries.mapNotNull { ip -> cdnProvider(ip)?.let { ip to it } }.toMap()
        return LinkTypeTestResult(
            state = if (providers.isEmpty()) LinkTypeTestState.RELAY else LinkTypeTestState.CDN_RELAY,
            serverAddress = serverAddress,
            entryIps = entries,
            exitIp = exit,
            entryCdnProviders = providers
        )
    }
}

/** Published CDN ranges, bundled locally so detection never depends on an ASN lookup. */
internal object CdnIpCatalog {
    private data class Range(val provider: String, val address: ByteArray, val prefix: Int) {
        fun contains(ip: ByteArray): Boolean {
            if (ip.size != address.size) return false
            val wholeBytes = prefix / 8
            if ((0 until wholeBytes).any { ip[it] != address[it] }) return false
            val bits = prefix % 8
            if (bits == 0) return true
            val mask = (0xFF shl (8 - bits)) and 0xFF
            return (ip[wholeBytes].toInt() and mask) == (address[wholeBytes].toInt() and mask)
        }
    }

    private val ranges: List<Range> by lazy {
        val stream = checkNotNull(CdnIpCatalog::class.java.getResourceAsStream("/cdn-ip-ranges.json"))
        stream.bufferedReader().use { reader ->
            JsonParser.parseReader(reader).asJsonObject.getAsJsonObject("providers")
                .entrySet().flatMap { (provider, cidrs) ->
                    cidrs.asJsonArray.map { cidr ->
                        val parts = cidr.asString.split('/')
                        val address = checkNotNull(LinkIpAddress.parse(parts[0])).address
                        val prefix = parts[1].toInt()
                        check(prefix in 0..address.size * 8)
                        Range(provider, address, prefix)
                    }
                }.sortedByDescending { it.prefix }
        }
    }

    fun providerFor(ip: String): String? {
        val address = LinkIpAddress.parse(ip)?.address ?: return null
        return ranges.firstOrNull { it.contains(address) }?.provider
    }
}

/** Every exit-IP request uses the active session's SOCKS endpoint, including fallbacks. */
internal class LinkTypeTestRunner(
    private val endpoint: LocalSocksEndpoint,
    private val resolveServer: (String) -> List<String>,
    private val fetch: (String) -> Socks5HttpProbe.HttpResponse = { url ->
        Socks5HttpProbe.fetch(
            endpoint = endpoint,
            targetUrl = url,
            timeoutMs = 8_000L,
            followRedirects = false,
            maxBodyBytes = 4_096
        )
    }
) {
    suspend fun run(serverAddress: String): LinkTypeTestResult = withContext(Dispatchers.IO) {
        val entries = try {
            resolveServer(serverAddress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        if (entries.isEmpty()) {
            return@withContext LinkTypeClassifier.classify(serverAddress, entries, "")
        }
        // Prefer the same address family as the node entrance. A v6 entrance and
        // a v4 exit must still compare as different IPs, according to the scheme rule.
        val preferIpv6 = entries.all { LinkIpAddress.parse(it)?.address?.size == 16 }
        val targets = if (preferIpv6) {
            listOf(LinkTypeTestTargets.hosts[2], LinkTypeTestTargets.hosts[1], LinkTypeTestTargets.hosts[0])
        } else {
            LinkTypeTestTargets.hosts.take(2)
        }.map { "https://$it" }
        for (url in targets) {
            coroutineContext.ensureActive()
            val ip = try {
                val response = fetch(url)
                if (response.statusCode == 200) LinkIpAddress.normalize(response.body) else null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (ip != null) return@withContext LinkTypeClassifier.classify(serverAddress, entries, ip)
        }
        LinkTypeClassifier.classify(serverAddress, entries, "")
    }
}
