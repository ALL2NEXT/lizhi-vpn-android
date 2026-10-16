package io.github.HanJason233.chimera.network.location

import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.RegionLookupMode
import io.github.HanJason233.chimera.network.NetworkClient
import io.github.HanJason233.chimera.util.CountryCodeResolver
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 按设置的查询方式补充节点缺失的国家代码。 */
object NodeLocationResolver {
    private const val GEO_LOOKUP_HOST = "ipwho.is"
    private const val MAX_CONCURRENCY = 8

    private val client = NetworkClient.withUserAgent(OkHttpClient.Builder())
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private val remoteCountryCache = ConcurrentHashMap<String, String>()

    suspend fun enrichNodes(
        nodes: List<Node>,
        mode: RegionLookupMode,
        geoIpDatabase: File
    ): List<Node> {
        return nodes.chunked(MAX_CONCURRENCY).flatMap { batch ->
            coroutineScope {
                batch.map { node ->
                    async(Dispatchers.IO) { enrichNode(node, mode, geoIpDatabase) }
                }.awaitAll()
            }
        }
    }

    private suspend fun enrichNode(
        node: Node,
        mode: RegionLookupMode,
        geoIpDatabase: File
    ): Node {
        val knownCountry = CountryCodeResolver.normalizeCountryCode(node.country)
            ?: CountryCodeResolver.fromNodeName(node.name)
            ?: CountryCodeResolver.fromNodeName(node.countryName)
        val resolvedCountry = knownCountry
            ?: resolveServerCountry(node.server, mode, geoIpDatabase)
        if (resolvedCountry == null) return node

        val resolvedName = CountryCodeResolver.displayName(resolvedCountry)
        return node.copy(
            country = resolvedCountry,
            countryName = resolvedName ?: node.countryName
        )
    }

    private suspend fun resolveServerCountry(
        server: String,
        mode: RegionLookupMode,
        geoIpDatabase: File
    ): String? {
        val addresses = resolvePublicAddresses(server)
        if (addresses.isEmpty()) return null

        return when (mode) {
            RegionLookupMode.LOCAL -> addresses.firstNotNullOfOrNull {
                XrayGeoIpDatabase.lookup(geoIpDatabase, it)
            }
            RegionLookupMode.REMOTE -> resolveRemoteCountry(addresses.first())
            RegionLookupMode.HYBRID -> addresses.firstNotNullOfOrNull {
                XrayGeoIpDatabase.lookup(geoIpDatabase, it)
            } ?: resolveRemoteCountry(addresses.first())
        }
    }

    private suspend fun resolveRemoteCountry(address: InetAddress): String? {
        val ip = address.hostAddress?.substringBefore('%') ?: return null
        remoteCountryCache[ip]?.let { return it }

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
        }.getOrNull()?.also { remoteCountryCache[ip] = it }
    }

    private suspend fun resolvePublicAddresses(server: String): List<InetAddress> =
        withContext(Dispatchers.IO) {
            val host = server.trim().removePrefix("[").removeSuffix("]")
            if (host.isBlank()) return@withContext emptyList()

            runCatching {
                InetAddress.getAllByName(host)
                    .asSequence()
                    .filter(::isPublicAddress)
                    .distinctBy { it.hostAddress?.substringBefore('%') }
                    .toList()
            }.getOrDefault(emptyList())
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
