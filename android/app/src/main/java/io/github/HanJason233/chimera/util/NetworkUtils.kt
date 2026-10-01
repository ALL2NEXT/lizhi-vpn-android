package io.github.HanJason233.chimera.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    
    /**
     * 检查网络是否可用（WiFi 或移动网络）
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false

        return activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** 获取可供局域网设备连接的当前 Wi-Fi/以太网 IPv4 地址。 */
    fun lanIpv4Addresses(context: Context): List<String> {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val connectedLanAddresses = connectivityManager.allNetworks
            .asSequence()
            .filter { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                    ?: return@filter false
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
            .flatMap { network ->
                connectivityManager.getLinkProperties(network)
                    ?.linkAddresses
                    .orEmpty()
                    .asSequence()
            }
            .mapNotNull { linkAddress -> linkAddress.address as? Inet4Address }
            .filter { address ->
                !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress &&
                    address.isSiteLocalAddress
            }
            .mapNotNull(Inet4Address::getHostAddress)
            .distinct()
            .toList()

        // 手机使用流量开启热点时，热点侧网卡通常不会出现在 ConnectivityManager
        // 的 Wi-Fi 网络列表中，因此还要直接扫描 wlan0/ap0/swlan0 等热点接口。
        val hotspotAddresses = runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                .orEmpty()
                .filter { networkInterface ->
                    networkInterface.isUp &&
                        !networkInterface.isLoopback &&
                        isLanInterface(networkInterface.name)
                }
                .flatMap { networkInterface -> networkInterface.inetAddresses.asSequence() }
                .mapNotNull { address -> address as? Inet4Address }
                .filter { address ->
                    !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress &&
                        address.isSiteLocalAddress
                }
                .mapNotNull(Inet4Address::getHostAddress)
                .toList()
        }.getOrDefault(emptyList())

        return (connectedLanAddresses + hotspotAddresses).distinct()
    }

    private fun isLanInterface(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized.startsWith("wlan") ||
            normalized.startsWith("ap") ||
            normalized.startsWith("swlan") ||
            normalized.startsWith("softap") ||
            normalized.startsWith("tether") ||
            normalized.startsWith("rndis") ||
            normalized.startsWith("eth") ||
            normalized.startsWith("bridge")
    }
}
