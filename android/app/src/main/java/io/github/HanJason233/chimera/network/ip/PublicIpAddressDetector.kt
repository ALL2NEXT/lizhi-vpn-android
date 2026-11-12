package io.github.HanJason233.chimera.network.ip

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import io.github.HanJason233.chimera.data.model.IpAddressProbeResult
import io.github.HanJason233.chimera.data.model.IpAddressProbeSource
import io.github.HanJason233.chimera.data.model.IpAddressTestSettings
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.network.nodetest.common.Socks5HttpProbe
import okhttp3.OkHttpClient
import io.github.HanJason233.chimera.network.NetworkClient
import okhttp3.Request
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

internal data class PublicIpAddressLookup(
    val ipv4Results: List<IpAddressProbeResult>,
    val ipv6Results: List<IpAddressProbeResult>
)

/** 并发请求多个 IPv4、IPv6 回显服务，供设置页汇总出口地址。 */
internal object PublicIpAddressDetector {
    private data class ProbeEndpoint(
        val source: IpAddressProbeSource,
        val url: String,
        val isIpv6: Boolean
    )

    private data class ParsedAddress(
        val display: String,
        val normalized: String
    )

    private val baseClient = NetworkClient.withUserAgent(OkHttpClient.Builder()).build()

    /** VPN 在线时经当前会话的 SOCKS 入站探测，否则使用手机当前网络直连。 */
    suspend fun detect(
        socksEndpoint: LocalSocksEndpoint?,
        settings: IpAddressTestSettings
    ): PublicIpAddressLookup = coroutineScope {
        val normalized = settings.normalized()
        val sources = normalized.enabledProbeSources.sortedBy { it.ordinal }
        val endpoints = buildList {
            if (normalized.ipv4Enabled) {
                sources.forEach { source -> add(ProbeEndpoint(source, endpointUrl(source, false), false)) }
            }
            if (normalized.ipv6Enabled) {
                sources.forEach { source -> add(ProbeEndpoint(source, endpointUrl(source, true), true)) }
            }
        }
        val timeoutSeconds = normalized.timeoutSeconds.toLong()
        val timeoutMs = timeoutSeconds * 1_000L
        val client = baseClient.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        val results = endpoints.map { probe ->
            async(Dispatchers.IO) {
                probe to queryAddress(probe, socksEndpoint, client, timeoutMs)
            }
        }.awaitAll()
        PublicIpAddressLookup(
            ipv4Results = results.filterNot { (probe, _) -> probe.isIpv6 }.map { it.second },
            ipv6Results = results.filter { (probe, _) -> probe.isIpv6 }.map { it.second }
        )
    }

    private fun endpointUrl(source: IpAddressProbeSource, isIpv6: Boolean): String = when (source) {
        IpAddressProbeSource.IPIFY -> if (isIpv6) {
            "https://api6.ipify.org"
        } else {
            "https://api.ipify.org"
        }
        IpAddressProbeSource.IPLARK -> if (isIpv6) {
            "https://6.iplark.com/ip"
        } else {
            "https://iplark.com/ipapi/public/ip"
        }
        IpAddressProbeSource.ICANHAZIP -> if (isIpv6) {
            "https://ipv6.icanhazip.com"
        } else {
            "https://ipv4.icanhazip.com"
        }
    }

    private fun queryAddress(
        probe: ProbeEndpoint,
        endpoint: LocalSocksEndpoint?,
        client: OkHttpClient,
        timeoutMs: Long
    ): IpAddressProbeResult {
        return try {
            val value = if (endpoint != null) {
                val response = Socks5HttpProbe.fetch(
                    endpoint = endpoint,
                    targetUrl = probe.url,
                    timeoutMs = timeoutMs,
                    followRedirects = false,
                    maxBodyBytes = 2_048
                )
                response.body.takeIf { response.statusCode == 200 }
            } else {
                val request = Request.Builder().url(probe.url).get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
            }
            val parsed = value?.trim()?.let { parseAddress(it, probe.isIpv6) }
            IpAddressProbeResult(probe.source.displayName, parsed?.display, parsed?.normalized)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            IpAddressProbeResult(probe.source.displayName, null)
        }
    }

    /** 校验响应地址族并生成去重键，避免把探针响应当作任意主机名解析。 */
    private fun parseAddress(value: String, isIpv6: Boolean): ParsedAddress? {
        if (isIpv6) {
            if (':' !in value || !value.matches(Regex("[0-9a-fA-F:.]+"))) return null
            val address = runCatching { InetAddress.getByName(value) as? Inet6Address }.getOrNull()
                ?: return null
            val normalized = address.address.joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            return ParsedAddress(value, normalized)
        }

        val octets = value.split('.')
        if (octets.size != 4 || octets.any { octet ->
                octet.isEmpty() || octet.length > 3 || octet.toIntOrNull()?.let { it in 0..255 } != true
            }
        ) return null
        val normalized = octets.joinToString(".") { it.toInt().toString() }
        return ParsedAddress(value, normalized)
    }
}
