package io.github.HanJason233.chimera.network

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.util.CountryCodeResolver
import java.net.InetAddress
import java.net.Inet6Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Adds a country code to nodes that do not expose one in their name. */
object NodeLocationResolver {
    private const val GEO_LOOKUP_HOST = "ipwho.is"
    private const val MAX_CONCURRENCY = 8

    private val client = NetworkClient.withUserAgent(OkHttpClient.Builder())
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private val countryCache = ConcurrentHashMap<String, String>()

    suspend fun enrichNodes(nodes: List<Node>): List<Node> {
        return nodes.chunked(MAX_CONCURRENCY).flatMap { batch ->
            coroutineScope {
                batch.map { node ->
                    async(Dispatchers.IO) { enrichNode(node) }
                }.awaitAll()
            }
        }
    }

    private suspend fun enrichNode(node: Node): Node {
        val knownCountry = CountryCodeResolver.normalizeCountryCode(node.country)
            ?: CountryCodeResolver.fromNodeName(node.name)
            ?: CountryCodeResolver.fromNodeName(node.countryName)
        val resolvedCountry = knownCountry ?: resolveServerCountry(node.server)
        if (resolvedCountry == null) return node

        val resolvedName = CountryCodeResolver.displayName(resolvedCountry)
        return node.copy(
            country = resolvedCountry,
            countryName = resolvedName ?: node.countryName
        )
    }

    private suspend fun resolveServerCountry(server: String): String? {
        val ip = resolvePublicIp(server) ?: return null
        countryCache[ip]?.let { return it }

        return runCatching {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host(GEO_LOOKUP_HOST)
                .addPathSegment(ip)
                .build()
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val json = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
                if (json.get("success")?.asBoolean != true) return@runCatching null
                CountryCodeResolver.normalizeCountryCode(
                    json.get("country_code")?.asString
                )
            }
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
        ) {
            return false
        }

        if (address is Inet6Address) {
            val firstByte = address.address.firstOrNull()?.toInt()?.and(0xFF) ?: return false
            if ((firstByte and 0xFE) == 0xFC) return false
        }
        return true
    }
}
