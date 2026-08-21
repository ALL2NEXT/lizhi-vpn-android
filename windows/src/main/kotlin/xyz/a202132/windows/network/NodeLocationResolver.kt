package xyz.a202132.windows.network

import com.google.gson.JsonParser
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import xyz.a202132.windows.model.CountryCodeResolver
import xyz.a202132.windows.model.DesktopNode

/** Mirrors Android's asynchronous node-location enrichment for Windows cards. */
internal object NodeLocationResolver {
    private const val GEO_LOOKUP_HOST = "ipwho.is"
    private const val MAX_CONCURRENCY = 8

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val countryCache = ConcurrentHashMap<String, String>()

    suspend fun enrichNodes(nodes: List<DesktopNode>): List<DesktopNode> =
        nodes.chunked(MAX_CONCURRENCY).flatMap { batch ->
            coroutineScope {
                batch.map { node ->
                    async(Dispatchers.IO) { enrichNode(node) }
                }.awaitAll()
            }
        }

    private suspend fun enrichNode(node: DesktopNode): DesktopNode {
        val knownCountry = CountryCodeResolver.normalizeCountryCode(node.countryCode)
            ?: CountryCodeResolver.fromNodeName(node.name)
        val resolvedCountry = knownCountry ?: resolveServerCountry(node.server)
        return if (resolvedCountry == null) node else node.copy(countryCode = resolvedCountry)
    }

    private suspend fun resolveServerCountry(server: String): String? {
        val ip = resolvePublicIp(server) ?: return null
        countryCache[ip]?.let { return it }
        return runCatching {
            val request = HttpRequest.newBuilder(
                URI("https://$GEO_LOOKUP_HOST/$ip")
            ).timeout(Duration.ofSeconds(5)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            val json = JsonParser.parseString(response.body()).asJsonObject
            if (json.get("success")?.asBoolean != true) return@runCatching null
            CountryCodeResolver.normalizeCountryCode(json.get("country_code")?.asString)
        }.getOrNull()?.also { countryCache[ip] = it }
    }

    private suspend fun resolvePublicIp(server: String): String? = withContext(Dispatchers.IO) {
        val host = server.trim().removePrefix("[").removeSuffix("]")
        if (host.isBlank()) return@withContext null
        runCatching {
            InetAddress.getAllByName(host)
                .asSequence()
                .filter(::isPublicAddress)
                .mapNotNull { it.hostAddress?.substringBefore('%') }
                .firstOrNull()
        }.getOrNull()
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return false

        if (address is Inet6Address) {
            val firstByte = address.address.firstOrNull()?.toInt()?.and(0xFF) ?: return false
            if ((firstByte and 0xFE) == 0xFC) return false
        }
        return true
    }
}
