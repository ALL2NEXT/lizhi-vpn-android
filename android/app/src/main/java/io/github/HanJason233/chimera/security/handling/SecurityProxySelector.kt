package io.github.HanJason233.chimera.security.handling

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** 只在发现系统 HTTP 代理时让应用网络请求绕过该代理；VPN 路由不受影响。 */
internal object SecurityProxySelector : ProxySelector() {
    private val systemSelector = ProxySelector.getDefault()

    @Volatile
    private var captureProxyBlocked = false

    fun setCaptureProxyBlocked(enabled: Boolean) {
        captureProxyBlocked = enabled
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
