package xyz.a202132.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.data.model.NodeListCategory
import xyz.a202132.app.data.model.PerAppProxyMode
import xyz.a202132.app.data.model.ProxyMode

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    
    companion object {
        private val SELECTED_NODE_ID = stringPreferencesKey("selected_node_id")
        private val PROXY_MODE = stringPreferencesKey("proxy_mode")
        private val LAST_NOTICE_ID = stringPreferencesKey("last_notice_id")
        private val IGNORED_UPDATE_VERSION_CODE = intPreferencesKey("ignored_update_version_code")
        private val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        private val USER_AGREEMENT_ACCEPTED = booleanPreferencesKey("user_agreement_accepted")
        
        // 分应用代理设置
        private val PER_APP_PROXY_ENABLED = booleanPreferencesKey("per_app_proxy_enabled")
        private val PER_APP_PROXY_MODE = stringPreferencesKey("per_app_proxy_mode")
        private val SELECTED_PACKAGES = stringSetPreferencesKey("selected_packages")
        
        // 绕过局域网设置
        private val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
        
        // IPv6 路由设置
        private val IPV6_ROUTING_MODE = stringPreferencesKey("ipv6_routing_mode")
        
        // 备用节点
        private val BACKUP_NODE_ENABLED = booleanPreferencesKey("backup_node_enabled")
        private val BACKUP_NODE_URL = stringPreferencesKey("backup_node_url")

        // 自动化测试设置
        private val REMEMBER_LAST_SELECTED_NODE_ENABLED = booleanPreferencesKey("remember_last_selected_node_enabled")
        private val LAST_SELECTED_NODE_ID = stringPreferencesKey("last_selected_node_id")
        private val NODE_LIST_CATEGORY = stringPreferencesKey("node_list_category")
        private val NODE_IP_INFO_TEST_ON_VPN_START = booleanPreferencesKey("node_ip_info_test_on_vpn_start")
        private val SCHEDULED_NODE_UPDATE_ENABLED = booleanPreferencesKey("scheduled_node_update_enabled")
        private val SCHEDULED_NODE_UPDATE_HOURS = intPreferencesKey("scheduled_node_update_hours")
        private val SCHEDULED_NODE_UPDATE_MINUTES = intPreferencesKey("scheduled_node_update_minutes")
        private val NODE_AUTO_RECONNECT = booleanPreferencesKey("node_auto_reconnect")
        private val SCHEDULED_NODE_UPDATE_TOAST_ENABLED = booleanPreferencesKey("scheduled_node_update_toast_enabled")
        private val TCPING_TEST_TIMEOUT_MS = longPreferencesKey("tcping_test_timeout_ms")
        private val URL_TEST_TIMEOUT_MS = longPreferencesKey("url_test_timeout_ms")
        private val NODE_IP_INFO_TIMEOUT_MS = longPreferencesKey("node_ip_info_timeout_ms")
        private val SPEED_TEST_DOWNLOAD_TIMEOUT_MS = longPreferencesKey("speed_test_download_timeout_ms")
        private val TCPING_CONCURRENCY = intPreferencesKey("tcping_concurrency")
        private val URL_TEST_CONCURRENCY = intPreferencesKey("url_test_concurrency")
        private val BANDWIDTH_TEST_CONCURRENCY = intPreferencesKey("bandwidth_test_concurrency")
        private val VPN_MTU = intPreferencesKey("vpn_mtu")
        private val SMART_CONNECT_TCPING_PERCENT = intPreferencesKey("smart_connect_tcping_percent")
        private val SMART_CONNECT_URL_TEST_PERCENT = intPreferencesKey("smart_connect_url_test_percent")
        private val SMART_CONNECT_SPEED_TEST_PERCENT = intPreferencesKey("smart_connect_speed_test_percent")
        private val HYSTERIA_UP_MBPS = intPreferencesKey("hysteria_up_mbps")
        private val HYSTERIA_DOWN_MBPS = intPreferencesKey("hysteria_down_mbps")

    }
    
    val selectedNodeId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_NODE_ID]
    }

    val nodeListCategory: Flow<NodeListCategory> = context.dataStore.data.map { preferences ->
        runCatching {
            NodeListCategory.valueOf(preferences[NODE_LIST_CATEGORY] ?: NodeListCategory.PRIMARY.name)
        }.getOrDefault(NodeListCategory.PRIMARY)
    }
    
    val proxyMode: Flow<ProxyMode> = context.dataStore.data.map { preferences ->
        val mode = preferences[PROXY_MODE] ?: ProxyMode.SMART.name
        try {
            ProxyMode.valueOf(mode)
        } catch (e: Exception) {
            ProxyMode.SMART
        }
    }
    
    val lastNoticeId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[LAST_NOTICE_ID]
    }

    val ignoredUpdateVersionCode: Flow<Int?> = context.dataStore.data.map { preferences ->
        preferences[IGNORED_UPDATE_VERSION_CODE]
    }
    
    val autoConnect: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_CONNECT] ?: false
    }
    
    // 分应用代理 - 是否启用
    val perAppProxyEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PER_APP_PROXY_ENABLED] ?: false
    }
    
    val perAppProxyMode: Flow<PerAppProxyMode> = context.dataStore.data.map { preferences ->
        val mode = preferences[PER_APP_PROXY_MODE] ?: PerAppProxyMode.WHITELIST.name
        try {
            PerAppProxyMode.valueOf(mode)
        } catch (e: Exception) {
            PerAppProxyMode.WHITELIST
        }
    }
    
    // 分应用代理 - 选中的应用包名列表
    val selectedPackages: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_PACKAGES] ?: emptySet()
    }
    
    // 绕过局域网
    val bypassLan: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[BYPASS_LAN] ?: true // 默认开启
    }
    
    suspend fun setSelectedNodeId(nodeId: String?) {
        context.dataStore.edit { preferences ->
            if (nodeId == null) {
                preferences.remove(SELECTED_NODE_ID)
            } else {
                preferences[SELECTED_NODE_ID] = nodeId
                if (preferences[REMEMBER_LAST_SELECTED_NODE_ENABLED] ?: true) {
                    preferences[LAST_SELECTED_NODE_ID] = nodeId
                }
            }
        }
    }

    suspend fun setNodeListCategory(category: NodeListCategory) {
        context.dataStore.edit { preferences ->
            preferences[NODE_LIST_CATEGORY] = category.name
        }
    }
    
    suspend fun setProxyMode(mode: ProxyMode) {
        context.dataStore.edit { preferences ->
            preferences[PROXY_MODE] = mode.name
        }
    }
    
    suspend fun setLastNoticeId(noticeId: String) {
        context.dataStore.edit { preferences ->
            preferences[LAST_NOTICE_ID] = noticeId
        }
    }

    suspend fun setIgnoredUpdateVersionCode(versionCode: Int) {
        context.dataStore.edit { preferences ->
            preferences[IGNORED_UPDATE_VERSION_CODE] = versionCode
        }
    }
    
    suspend fun setAutoConnect(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_CONNECT] = enabled
        }
    }
    
    val isUserAgreementAccepted: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[USER_AGREEMENT_ACCEPTED] ?: false
    }
    
    suspend fun setUserAgreementAccepted(accepted: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[USER_AGREEMENT_ACCEPTED] = accepted
        }
    }
    
    // 分应用代理设置方法
    suspend fun setPerAppProxyEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PER_APP_PROXY_ENABLED] = enabled
        }
    }
    
    suspend fun setPerAppProxyMode(mode: PerAppProxyMode) {
        context.dataStore.edit { preferences ->
            preferences[PER_APP_PROXY_MODE] = mode.name
        }
    }
    
    suspend fun setSelectedPackages(packages: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_PACKAGES] = packages
        }
    }
    
    suspend fun setBypassLan(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BYPASS_LAN] = enabled
        }
    }
    
    // IPv6 路由模式
    val ipv6RoutingMode: Flow<IPv6RoutingMode> = context.dataStore.data.map { preferences ->
        val mode = preferences[IPV6_ROUTING_MODE] ?: IPv6RoutingMode.DISABLED.name
        try {
            IPv6RoutingMode.valueOf(mode)
        } catch (e: Exception) {
            IPv6RoutingMode.DISABLED
        }
    }
    
    suspend fun setIPv6RoutingMode(mode: IPv6RoutingMode) {
        context.dataStore.edit { preferences ->
            preferences[IPV6_ROUTING_MODE] = mode.name
        }
    }
    
    // 备用节点
    val backupNodeEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[BACKUP_NODE_ENABLED] ?: false
    }
    
    suspend fun setBackupNodeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BACKUP_NODE_ENABLED] = enabled
        }
    }
    
    val backupNodeUrl: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[BACKUP_NODE_URL]
    }
    
    suspend fun setBackupNodeUrl(url: String?) {
        context.dataStore.edit { preferences ->
            if (url == null) {
                preferences.remove(BACKUP_NODE_URL)
            } else {
                preferences[BACKUP_NODE_URL] = url
            }
        }
    }

    val rememberLastSelectedNodeEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[REMEMBER_LAST_SELECTED_NODE_ENABLED] ?: true
    }

    suspend fun setRememberLastSelectedNodeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[REMEMBER_LAST_SELECTED_NODE_ENABLED] = enabled
            if (!enabled) {
                preferences.remove(LAST_SELECTED_NODE_ID)
            } else {
                preferences[SELECTED_NODE_ID]?.let { preferences[LAST_SELECTED_NODE_ID] = it }
            }
        }
    }

    val lastSelectedNodeId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[LAST_SELECTED_NODE_ID]
    }

    suspend fun setLastSelectedNodeId(nodeId: String?) {
        context.dataStore.edit { preferences ->
            if (nodeId == null) {
                preferences.remove(LAST_SELECTED_NODE_ID)
            } else {
                preferences[LAST_SELECTED_NODE_ID] = nodeId
            }
        }
    }

    val nodeIpInfoTestOnVpnStart: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[NODE_IP_INFO_TEST_ON_VPN_START] ?: false
    }

    suspend fun setNodeIpInfoTestOnVpnStart(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[NODE_IP_INFO_TEST_ON_VPN_START] = enabled
        }
    }

    val scheduledNodeUpdateEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SCHEDULED_NODE_UPDATE_ENABLED] ?: false
    }

    suspend fun setScheduledNodeUpdateEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SCHEDULED_NODE_UPDATE_ENABLED] = enabled
        }
    }

    val scheduledNodeUpdateHours: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[SCHEDULED_NODE_UPDATE_HOURS] ?: 0).coerceIn(0, 168)
    }

    suspend fun setScheduledNodeUpdateHours(hours: Int) {
        context.dataStore.edit { preferences ->
            preferences[SCHEDULED_NODE_UPDATE_HOURS] = hours.coerceIn(0, 168)
        }
    }

    val scheduledNodeUpdateMinutes: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[SCHEDULED_NODE_UPDATE_MINUTES] ?: 30).coerceIn(0, 59)
    }

    suspend fun setScheduledNodeUpdateMinutes(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[SCHEDULED_NODE_UPDATE_MINUTES] = minutes.coerceIn(0, 59)
        }
    }

    val nodeAutoReconnect: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[NODE_AUTO_RECONNECT] ?: false
    }

    suspend fun setNodeAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[NODE_AUTO_RECONNECT] = enabled
        }
    }

    val scheduledNodeUpdateToastEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SCHEDULED_NODE_UPDATE_TOAST_ENABLED] ?: true
    }

    suspend fun setScheduledNodeUpdateToastEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SCHEDULED_NODE_UPDATE_TOAST_ENABLED] = enabled
        }
    }

    val tcpingTestTimeoutMs: Flow<Long> = context.dataStore.data.map { preferences ->
        (preferences[TCPING_TEST_TIMEOUT_MS] ?: xyz.a202132.app.AppConfig.TCPING_TEST_TIMEOUT).coerceAtLeast(500L)
    }

    suspend fun setTcpingTestTimeoutMs(value: Long) {
        context.dataStore.edit { preferences ->
            preferences[TCPING_TEST_TIMEOUT_MS] = value.coerceAtLeast(500L)
        }
    }

    val urlTestTimeoutMs: Flow<Long> = context.dataStore.data.map { preferences ->
        (preferences[URL_TEST_TIMEOUT_MS] ?: xyz.a202132.app.AppConfig.URL_TEST_TIMEOUT).coerceAtLeast(500L)
    }

    suspend fun setUrlTestTimeoutMs(value: Long) {
        context.dataStore.edit { preferences ->
            preferences[URL_TEST_TIMEOUT_MS] = value.coerceAtLeast(500L)
        }
    }

    val nodeIpInfoTimeoutMs: Flow<Long> = context.dataStore.data.map { preferences ->
        (preferences[NODE_IP_INFO_TIMEOUT_MS] ?: xyz.a202132.app.AppConfig.NODE_IP_INFO_TIMEOUT_MS).coerceAtLeast(1000L)
    }

    suspend fun setNodeIpInfoTimeoutMs(value: Long) {
        context.dataStore.edit { preferences ->
            preferences[NODE_IP_INFO_TIMEOUT_MS] = value.coerceAtLeast(1000L)
        }
    }

    val speedTestDownloadTimeoutMs: Flow<Long> = context.dataStore.data.map { preferences ->
        (preferences[SPEED_TEST_DOWNLOAD_TIMEOUT_MS] ?: xyz.a202132.app.AppConfig.AUTO_TEST_BANDWIDTH_DOWNLOAD_TIMEOUT_MS)
            .coerceAtLeast(0L)
    }

    suspend fun setSpeedTestDownloadTimeoutMs(value: Long) {
        context.dataStore.edit { preferences ->
            preferences[SPEED_TEST_DOWNLOAD_TIMEOUT_MS] = value.coerceAtLeast(0L)
        }
    }

    val tcpingConcurrency: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[TCPING_CONCURRENCY] ?: xyz.a202132.app.AppConfig.TCPING_CONCURRENCY).coerceIn(1, 128)
    }

    suspend fun setTcpingConcurrency(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[TCPING_CONCURRENCY] = value.coerceIn(1, 128)
        }
    }

    val urlTestConcurrency: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[URL_TEST_CONCURRENCY] ?: xyz.a202132.app.AppConfig.URL_TEST_CONCURRENCY).coerceIn(1, 128)
    }

    suspend fun setUrlTestConcurrency(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[URL_TEST_CONCURRENCY] = value.coerceIn(1, 128)
        }
    }

    val bandwidthTestConcurrency: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[BANDWIDTH_TEST_CONCURRENCY] ?: 1).coerceIn(1, 3)
    }

    suspend fun setBandwidthTestConcurrency(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[BANDWIDTH_TEST_CONCURRENCY] = value.coerceIn(1, 3)
        }
    }

    val smartConnectTcpingPercent: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[SMART_CONNECT_TCPING_PERCENT] ?: xyz.a202132.app.AppConfig.SMART_CONNECT_TCPING_PERCENT)
            .coerceIn(1, 100)
    }

    suspend fun setSmartConnectTcpingPercent(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[SMART_CONNECT_TCPING_PERCENT] = value.coerceIn(1, 100)
        }
    }

    val smartConnectUrlTestPercent: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[SMART_CONNECT_URL_TEST_PERCENT] ?: xyz.a202132.app.AppConfig.SMART_CONNECT_URL_TEST_PERCENT)
            .coerceIn(1, 100)
    }

    suspend fun setSmartConnectUrlTestPercent(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[SMART_CONNECT_URL_TEST_PERCENT] = value.coerceIn(1, 100)
        }
    }

    val smartConnectSpeedTestPercent: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[SMART_CONNECT_SPEED_TEST_PERCENT] ?: xyz.a202132.app.AppConfig.SMART_CONNECT_SPEED_TEST_PERCENT)
            .coerceIn(1, 100)
    }

    suspend fun setSmartConnectSpeedTestPercent(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[SMART_CONNECT_SPEED_TEST_PERCENT] = value.coerceIn(1, 100)
        }
    }

    val hysteriaUpMbps: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[HYSTERIA_UP_MBPS] ?: xyz.a202132.app.AppConfig.HYSTERIA_UP_MBPS).coerceIn(0, 100_000)
    }

    suspend fun setHysteriaUpMbps(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[HYSTERIA_UP_MBPS] = value.coerceIn(0, 100_000)
        }
    }

    val hysteriaDownMbps: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[HYSTERIA_DOWN_MBPS] ?: xyz.a202132.app.AppConfig.HYSTERIA_DOWN_MBPS).coerceIn(0, 100_000)
    }

    suspend fun setHysteriaDownMbps(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[HYSTERIA_DOWN_MBPS] = value.coerceIn(0, 100_000)
        }
    }

    val vpnMtu: Flow<Int> = context.dataStore.data.map { preferences ->
        (preferences[VPN_MTU] ?: xyz.a202132.app.AppConfig.VPN_MTU).coerceIn(576, 9000)
    }

    suspend fun setVpnMtu(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[VPN_MTU] = value.coerceIn(576, 9000)
        }
    }

}

