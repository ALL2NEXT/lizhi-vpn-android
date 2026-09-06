package xyz.a202132.app.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import xyz.a202132.app.data.repository.PerAppProxySettings
import java.io.FileDescriptor
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

internal data class IpPrefix(val address: String, val length: Int)
internal data class TunnelSpec(
    val mtu: Int,
    val addresses: List<IpPrefix>,
    val routes: List<IpPrefix>,
    val dnsServers: List<String>
)

/** Owns the Android TUN reference used by the HEV data-plane adapter. */
internal class AndroidVpnHost(
    private val service: VpnService,
    private val perApp: PerAppProxySettings,
    private val onCellular: () -> Unit = {}
) : AutoCloseable {
    val vpnService: VpnService get() = service
    private val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networks = ConcurrentHashMap<Network, NetworkCapabilities>()
    @Volatile private var currentNetwork: Network? = null
    @Volatile private var closed = false
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var tun: ParcelFileDescriptor? = null
    @Volatile var onInterfaceChanged: ((String, Int) -> Unit)? = null
        set(value) { field = value; publishInterface() }

    fun start() {
        check(!closed)
        if (callback != null) return
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivity.getNetworkCapabilities(network)?.let { onCapabilitiesChanged(network, it) }
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (closed || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return
                networks[network] = capabilities
                selectNetwork()
            }
            override fun onLost(network: Network) { networks.remove(network); selectNetwork() }
        }
        callback = listener
        connectivity.allNetworks.forEach { network ->
            connectivity.getNetworkCapabilities(network)?.takeIf {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }?.let { networks[network] = it }
        }
        selectNetwork()
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), listener)
    }

    @Synchronized private fun selectNetwork() {
        if (closed) return
        val best = networks.maxByOrNull { (_, caps) -> when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
            else -> 0
        } }?.key
        if (best == currentNetwork) return
        currentNetwork = best
        service.setUnderlyingNetworks(best?.let { arrayOf(it) })
        publishInterface()
        if (best?.let { networks[it] }?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) onCellular()
    }

    private fun publishInterface() {
        val name = currentNetwork?.let { connectivity.getLinkProperties(it)?.interfaceName }.orEmpty()
        val index = runCatching { NetworkInterface.getByName(name)?.index }.getOrNull() ?: -1
        onInterfaceChanged?.invoke(name, index)
    }

    @Synchronized fun openTun(spec: TunnelSpec): Int {
        check(!closed && tun == null) { "TUN 已被其他会话占用" }
        require(spec.addresses.isNotEmpty() && spec.routes.isNotEmpty()) { "TUN 地址或路由为空" }
        val builder = service.Builder().setSession("空空加速器").setMtu(spec.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        spec.addresses.forEach { builder.addAddress(it.address, it.length) }
        spec.routes.forEach { builder.addRoute(it.address, it.length) }
        spec.dnsServers.forEach(builder::addDnsServer)
        if (perApp.enabled) {
            val selected = perApp.selectedPackages - service.packageName
            var allowed = 0
            selected.forEach { name ->
                try { builder.addAllowedApplication(name); allowed++ }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { }
            }
            // An empty Android allow-list means ALL apps. Reject it explicitly.
            require(allowed > 0) { "请至少选择一个已安装的代理应用" }
        } else {
            builder.addDisallowedApplication(service.packageName)
        }
        tun = builder.establish() ?: error("系统未授予 VPN 接口")
        return tun!!.fd
    }

    @Synchronized fun tunDescriptor(): FileDescriptor = tun?.fileDescriptor ?: error("TUN 未建立")

    fun protect(fd: Int): Boolean = !closed && service.protect(fd)

    @Synchronized fun closeTun() {
        val previous = tun
        tun = null
        previous?.close()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        onInterfaceChanged = null
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        networks.clear()
        currentNetwork = null
        runCatching { service.setUnderlyingNetworks(null) }
        runCatching { closeTun() }.onFailure { Log.w("AndroidVpnHost", "TUN close failed", it) }
    }
}
