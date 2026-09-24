package xyz.a202132.app.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileDescriptor
import java.net.NetworkInterface

internal data class IpPrefix(val address: String, val length: Int)
internal data class TunnelSpec(
    val mtu: Int,
    val addresses: List<IpPrefix>,
    val routes: List<IpPrefix>,
    val dnsServers: List<String>
)

/** Core-facing view of the physical network. It never exposes the Android TUN. */
internal interface UnderlayController {
    val vpnService: VpnService
    val currentNetwork: Network?
    var onInterfaceChanged: ((String, Int) -> Unit)?
}

/** Data-plane-facing TUN owner. It never exposes core/network integration. */
internal interface TunProvider {
    fun openTun(spec: TunnelSpec): Int
    fun tunDescriptor(): FileDescriptor
    fun closeTun()
}

/** Coordinates one system-default underlay and one Android TUN. */
internal class AndroidVpnHost(
    private val service: VpnService,
    private val onCellular: () -> Unit = {}
) : UnderlayController, TunProvider, AutoCloseable {
    override val vpnService: VpnService get() = service
    private val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Volatile override var currentNetwork: Network? = null
        private set
    @Volatile private var closed = false
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var tun: ParcelFileDescriptor? = null
    @Volatile override var onInterfaceChanged: ((String, Int) -> Unit)? = null
        set(value) {
            field = value
            publishInterface()
        }

    fun start() {
        check(!closed)
        if (callback != null) return
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivity.getNetworkCapabilities(network)?.let { updateDefaultNetwork(network, it) }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                updateDefaultNetwork(network, capabilities)
            }

            override fun onLost(network: Network) {
                synchronized(this@AndroidVpnHost) {
                    if (!closed && currentNetwork == network) setDefaultNetwork(null)
                }
            }
        }
        callback = listener
        connectivity.activeNetwork?.let { network ->
            connectivity.getNetworkCapabilities(network)?.let { updateDefaultNetwork(network, it) }
        }
        connectivity.registerDefaultNetworkCallback(listener)
    }

    private fun updateDefaultNetwork(network: Network, capabilities: NetworkCapabilities) {
        if (closed) return
        val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        synchronized(this) {
            if (closed) return
            if (usable) setDefaultNetwork(network)
            else if (currentNetwork == network) setDefaultNetwork(null)
        }
    }

    private fun setDefaultNetwork(network: Network?) {
        if (network == currentNetwork) return
        currentNetwork = network
        applyUnderlyingNetwork()
        publishInterface()
        if (network?.let { connectivity.getNetworkCapabilities(it) }
                ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) onCellular()
    }

    private fun applyUnderlyingNetwork() {
        if (tun == null) return
        if (!service.setUnderlyingNetworks(currentNetwork?.let { arrayOf(it) })) {
            Log.w(TAG, "Unable to update VPN underlying network")
        }
    }

    private fun publishInterface() {
        val name = currentNetwork?.let { connectivity.getLinkProperties(it)?.interfaceName }.orEmpty()
        val index = runCatching { NetworkInterface.getByName(name)?.index }.getOrNull() ?: -1
        onInterfaceChanged?.invoke(name, index)
    }

    @Synchronized override fun openTun(spec: TunnelSpec): Int {
        check(!closed && tun == null) { "TUN is already owned by another session" }
        require(spec.addresses.isNotEmpty() && spec.routes.isNotEmpty()) {
            "TUN addresses and routes must not be empty"
        }
        val builder = service.Builder().setSession("萤火加速器").setMtu(spec.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        spec.addresses.forEach { builder.addAddress(it.address, it.length) }
        spec.routes.forEach { builder.addRoute(it.address, it.length) }
        spec.dnsServers.forEach(builder::addDnsServer)
        currentNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
        // The core process shares the app UID, so exclude the client itself to
        // keep core traffic on the physical default network and avoid a VPN loop.
        builder.addDisallowedApplication(service.packageName)
        tun = builder.establish() ?: error("系统未授予 VPN 接口")
        applyUnderlyingNetwork()
        return tun!!.fd
    }

    @Synchronized override fun tunDescriptor(): FileDescriptor =
        tun?.fileDescriptor ?: error("TUN 尚未建立")

    @Synchronized override fun closeTun() {
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
        currentNetwork = null
        runCatching { service.setUnderlyingNetworks(null) }
        runCatching { closeTun() }.onFailure { Log.w(TAG, "TUN close failed", it) }
    }

    private companion object {
        const val TAG = "AndroidVpnHost"
    }
}
