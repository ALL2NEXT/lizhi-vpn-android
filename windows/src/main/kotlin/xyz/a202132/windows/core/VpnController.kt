package xyz.a202132.windows.core

import kotlinx.coroutines.flow.StateFlow
import xyz.a202132.windows.model.AppScreen
import xyz.a202132.windows.model.DesktopAppState
import xyz.a202132.windows.model.DesktopSettings
import xyz.a202132.windows.model.NodeCategory
import xyz.a202132.windows.model.ProxyMode
import xyz.a202132.windows.model.NodeType
import xyz.a202132.windows.model.ProxyCore

interface VpnController : AutoCloseable {
    val state: StateFlow<DesktopAppState>

    fun refreshCore()
    fun setTunEnabled(enabled: Boolean)
    fun setProxyMode(mode: ProxyMode)
    fun openDrawer()
    fun closeDrawer()
    fun navigate(screen: AppScreen)
    fun navigateBack()
    fun setNodeCategory(category: NodeCategory)
    fun setSubscriptionSource(sourceId: String)
    fun importNodes(text: String)
    fun toggleFavorite(nodeId: String)
    fun testTcping()
    fun testUrl()
    fun refreshNodes()
    fun updateBackendUrl(value: String): Boolean
    fun updateNickname(nickname: String)
    fun bindAccount(email: String, password: String, registrationCode: String)
    fun loginAccount(email: String, password: String)
    fun deleteAccount(password: String)
    fun updateSettings(settings: DesktopSettings)
    fun setProtocolCore(nodeType: NodeType, core: ProxyCore)
    fun adaptHysteriaBandwidth()
    fun startSelectedOrFirstConnection()
    fun startSmartConnection()
    fun selectAndConnectNode(nodeId: String)
    fun requestDisconnect()
    fun clearMessage()
}
