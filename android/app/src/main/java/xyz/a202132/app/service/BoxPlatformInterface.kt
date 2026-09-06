package xyz.a202132.app.service

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

/**
 * libbox 平台接口实现
 * 参考官方 sing-box-for-android 实现
 * 适配 libbox 1.13.0-rc.3 API
 */
internal class BoxPlatformInterface(
    private val service: android.net.VpnService,
    private val host: xyz.a202132.app.platform.AndroidVpnHost
) : PlatformInterface {
    private companion object { const val TAG = "BoxPlatformInterface" }
    private val connectivityManager by lazy {
        service.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    override fun openTun(options: TunOptions): Int {
        throw UnsupportedOperationException("Android TUN is owned by HEV")
    }

    override fun useProcFS(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }
    
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int
    ): io.nekohasekai.libbox.ConnectionOwner {
        val owner = io.nekohasekai.libbox.ConnectionOwner()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val uid = connectivityManager.getConnectionOwnerUid(
                    ipProtocol,
                    InetSocketAddress(sourceAddress, sourcePort),
                    InetSocketAddress(destinationAddress, destinationPort)
                )
                owner.userId = uid
                // 尝试获取包名
                try {
                    val packages = service.packageManager.getPackagesForUid(uid)
                    owner.androidPackageName = packages?.firstOrNull() ?: ""
                } catch (e: Exception) {
                    owner.androidPackageName = ""
                }
            } catch (e: Exception) {
                Log.e(TAG, "getConnectionOwnerUid failed", e)
            }
        }
        return owner
    }
    
    override fun usePlatformAutoDetectInterfaceControl(): Boolean {
        return true
    }
    
    override fun autoDetectInterfaceControl(fd: Int) {
        Log.d(TAG, "Protecting socket fd: $fd")
        check(host.protect(fd)) { "Unable to protect core socket" }
    }
    
    override fun clearDNSCache() {
        Log.d(TAG, "Clear DNS cache")
    }
    
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        Log.d(TAG, "Start default interface monitor")
        host.onInterfaceChanged = { name, index -> listener?.updateDefaultInterface(name, index, false, false) }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        host.onInterfaceChanged = null
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val networks = connectivityManager.allNetworks
        val networkInterfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        val interfaces = mutableListOf<LibboxNetworkInterface>()
        
        for (network in networks) {
            try {
                val linkProperties = connectivityManager.getLinkProperties(network) ?: continue
                val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
                
                val boxInterface = LibboxNetworkInterface()
                boxInterface.name = linkProperties.interfaceName ?: continue
                
                val networkInterface = networkInterfaces.find { it.name == boxInterface.name } ?: continue
                
                boxInterface.dnsServer = StringArray(
                    linkProperties.dnsServers.mapNotNull { it.hostAddress }.iterator()
                )
                
                boxInterface.type = when {
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                
                boxInterface.index = networkInterface.index
                
                try {
                    boxInterface.mtu = networkInterface.mtu
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get MTU for ${boxInterface.name}")
                }
                
                boxInterface.addresses = StringArray(
                    networkInterface.interfaceAddresses.map { it.toPrefix() }.iterator()
                )
                
                var flags = 0
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (networkInterface.isLoopback) {
                    flags = flags or OsConstants.IFF_LOOPBACK
                }
                if (networkInterface.isPointToPoint) {
                    flags = flags or OsConstants.IFF_POINTOPOINT
                }
                if (networkInterface.supportsMulticast()) {
                    flags = flags or OsConstants.IFF_MULTICAST
                }
                boxInterface.flags = flags
                
                boxInterface.metered = !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                
                interfaces.add(boxInterface)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting interface info", e)
            }
        }
        
        return InterfaceArray(interfaces.iterator())
    }
    
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification?) {
        notification?.let {
            Log.d(TAG, "Notification received")
        }
    }
    
    override fun readWIFIState(): io.nekohasekai.libbox.WIFIState? {
        return null
    }
    
    override fun includeAllNetworks(): Boolean {
        return false
    }
    
    override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport? {
        return null
    }
    
    override fun systemCertificates(): StringIterator? {
        return null
    }
    
    override fun underNetworkExtension(): Boolean {
        return false
    }
    
    private class InterfaceArray(private val iterator: Iterator<LibboxNetworkInterface>) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibboxNetworkInterface = iterator.next()
    }
    
    private class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }
    
    private fun InterfaceAddress.toPrefix(): String {
        return if (address is Inet6Address) {
            "${Inet6Address.getByAddress(address.address).hostAddress}/${networkPrefixLength}"
        } else {
            "${address.hostAddress}/${networkPrefixLength}"
        }
    }
}
