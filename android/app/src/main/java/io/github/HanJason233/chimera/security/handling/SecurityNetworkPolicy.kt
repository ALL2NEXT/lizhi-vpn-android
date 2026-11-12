package io.github.HanJason233.chimera.security.handling

import io.github.HanJason233.chimera.BuildConfig
import io.github.HanJason233.chimera.security.detection.NativeSecurityDetector
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import javax.net.ssl.SSLPeerUnverifiedException
import java.security.cert.CertificateException
import java.security.cert.CertPathValidatorException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** 业务 HTTP 客户端统一入口；显式的节点 HTTP 探测可以单独保留明文目标。 */
internal object SecurityNetworkPolicy {
    fun configure(
        builder: OkHttpClient.Builder,
        allowCleartextProbe: Boolean = false,
        checkRuntime: Boolean = true
    ): OkHttpClient.Builder {
        if (!BuildConfig.SECURITY_BLOCK_HTTP_PROXY) return builder
        return RequestSecurityPolicy(
            checkRuntime = if (checkRuntime) ({ NativeSecurityDetector.checkNetworkRequest() }) else ({}),
            reportFinding = { NativeSecurityDetector.reportNetworkFinding(it) },
            allowCleartextProbe = allowCleartextProbe
        ).configure(builder)
    }

    fun reportTlsFailure(error: Throwable) {
        if (BuildConfig.SECURITY_BLOCK_HTTP_PROXY && isTlsValidationFailure(error)) {
            NativeSecurityDetector.reportNetworkFinding("tls_validation_failed")
        }
    }
}

/** 注入检查和事件接收器，便于通过真实 HTTP/TLS 连接验证策略。 */
internal class RequestSecurityPolicy(
    private val checkRuntime: () -> Unit,
    private val reportFinding: (String) -> Unit,
    private val allowCleartextProbe: Boolean = false
) {
    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        builder.proxySelector(SecurityProxySelector)
            .followSslRedirects(false)
            .addInterceptor(::interceptRequest)
            .addNetworkInterceptor(::interceptNetwork)

    private fun interceptRequest(chain: Interceptor.Chain): Response {
        checkTarget(chain.request().url)
        checkRuntime()
        return try {
            chain.proceed(chain.request())
        } catch (error: IOException) {
            if (isTlsValidationFailure(error)) reportFinding("tls_validation_failed")
            throw error
        }
    }

    private fun interceptNetwork(chain: Interceptor.Chain): Response {
        // 网络拦截器会在每个重定向 hop 执行，连接池复用也不能跳过路由检查。
        checkTarget(chain.request().url)
        val connection = chain.connection() ?: throw IOException("网络连接不可用")
        val proxy = connection.route().proxy
        if (proxy.type() != Proxy.Type.DIRECT && !isInternalSocks(proxy)) {
            reportFinding("http_proxy_route_blocked")
            throw IOException("已阻止通过外部代理发送应用请求")
        }
        if (chain.request().url.isHttps && connection.handshake() == null) {
            throw SSLPeerUnverifiedException("HTTPS connection has no verified TLS session")
        }
        return chain.proceed(chain.request())
    }

    private fun checkTarget(url: HttpUrl) {
        if (!url.isHttps && !allowCleartextProbe && !isLoopbackHost(url.host)) {
            reportFinding("cleartext_traffic_blocked")
            throw IOException("已阻止向外部地址发送未加密的应用请求")
        }
    }

    private fun isInternalSocks(proxy: Proxy): Boolean {
        val address = proxy.address() as? InetSocketAddress ?: return false
        return proxy.type() == Proxy.Type.SOCKS && isLoopbackHost(address.hostString)
    }

    private fun isLoopbackHost(host: String): Boolean =
        host == "127.0.0.1" || host == "::1" || host == "[::1]"
}

internal fun isTlsValidationFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    repeat(8) {
        if (current is SSLPeerUnverifiedException || current is CertificateException ||
            current is CertPathValidatorException) return true
        val next = current?.cause
        if (next == null || next === current) return false
        current = next
    }
    return false
}
