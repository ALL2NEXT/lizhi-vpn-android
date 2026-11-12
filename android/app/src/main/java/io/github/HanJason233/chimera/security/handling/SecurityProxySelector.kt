package io.github.HanJason233.chimera.security.handling

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import io.github.HanJason233.chimera.BuildConfig

/** 防护开启时始终绕过系统 HTTP/PAC 代理，首次扫描前和检测失败时也不会放开。 */
internal object SecurityProxySelector : ProxySelector() {
    private val systemSelector = ProxySelector.getDefault()

    @Volatile
    private var captureProxyBlocked = BuildConfig.SECURITY_BLOCK_HTTP_PROXY

    fun setCaptureProxyBlocked(enabled: Boolean) {
        captureProxyBlocked = BuildConfig.SECURITY_BLOCK_HTTP_PROXY || enabled
    }

    override fun select(uri: URI): MutableList<Proxy> {
        if (captureProxyBlocked) return mutableListOf(Proxy.NO_PROXY)
        return systemSelector?.select(uri)?.toMutableList()
            ?.takeIf { it.isNotEmpty() }
            ?: mutableListOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI, socketAddress: SocketAddress, error: IOException) {
        systemSelector?.connectFailed(uri, socketAddress, error)
    }
}
