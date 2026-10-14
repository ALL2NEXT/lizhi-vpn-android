package io.github.HanJason233.chimera.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import io.github.HanJason233.chimera.data.model.BandwidthTestTargets
import io.github.HanJason233.chimera.data.model.DialogMaterialVersion
import io.github.HanJason233.chimera.data.model.DnsDomainStrategy
import io.github.HanJason233.chimera.data.model.DnsSettings
import io.github.HanJason233.chimera.data.model.IPv6RoutingMode
import io.github.HanJason233.chimera.data.model.HomeNavigationStyle
import io.github.HanJason233.chimera.data.model.HomeIconEffect
import io.github.HanJason233.chimera.data.model.HomeIconMotionEffect
import io.github.HanJason233.chimera.data.model.LanInboundSettings
import io.github.HanJason233.chimera.data.model.NodeGroupKey
import io.github.HanJason233.chimera.data.model.NodeType
import io.github.HanJason233.chimera.data.model.PerAppInboundSettings
import io.github.HanJason233.chimera.data.model.ProtocolCoreSettings
import io.github.HanJason233.chimera.data.model.ProxyCore
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.data.model.SkinScheme
import io.github.HanJason233.chimera.data.model.SkinSettings
import io.github.HanJason233.chimera.data.model.SkinThemeMode
import io.github.HanJason233.chimera.data.model.SpeedTestScheme
import io.github.HanJason233.chimera.data.model.SpeedTestSelectionMode
import io.github.HanJason233.chimera.data.model.SmartRoutingRule
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleAction
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleType
import io.github.HanJason233.chimera.data.model.SmartRoutingSettings
import io.github.HanJason233.chimera.data.model.UrlTestTargets
import io.github.HanJason233.chimera.feature.nodegrouping.model.NodeGroupingMode
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestCatalog
import io.github.HanJason233.chimera.util.CountryCodeResolver
import java.util.UUID

data class VpnRuntimeSettings(
    val proxyMode: ProxyMode,
    val ipv6RoutingMode: IPv6RoutingMode,
    val hysteriaUpMbps: Int,
    val hysteriaDownMbps: Int,
    val protocolCoreSettings: ProtocolCoreSettings,
    val lanInbound: LanInboundSettings = LanInboundSettings(),
    val dnsSettings: DnsSettings = DnsSettings(),
    val smartRoutingSettings: SmartRoutingSettings = SmartRoutingSettings(),
    val perAppInboundSettings: PerAppInboundSettings = PerAppInboundSettings()
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(
    context: Context,
    private val store: DataStore<Preferences> = context.dataStore
) {
    
    companion object {
        const val DEFAULT_SERVICE_REQUEST_TIMEOUT_MS = 25_000L
        private const val AUTO_REFRESH_SOURCE_PREFIX = "auto_refresh_source_on_startup:"
        /** 默认测速与默认主题方案分别维护版本；升级后保存新版本并尊重后续修改。 */
        private const val DEFAULT_SPEED_TEST_PRESET_REVISION = 3
        private const val DEFAULT_SKIN_PARTICLE_PRESET_REVISION = 1
        private const val DEFAULT_SKIN_PRESET_REVISION = 3
        private const val MIN_SERVICE_REQUEST_TIMEOUT_MS = 5_000L
        private const val MAX_SERVICE_REQUEST_TIMEOUT_MS = 60_000L

        private val SELECTED_NODE_ID = stringPreferencesKey("selected_node_id")
        private val PROXY_MODE = stringPreferencesKey("proxy_mode")
        // Keep these persisted names so the terminology change preserves existing selections.
        private val PER_APP_INBOUND_ENABLED = booleanPreferencesKey("per_app_proxy_enabled")
        private val PER_APP_INBOUND_BYPASS_MODE = booleanPreferencesKey("per_app_proxy_bypass_mode")
        private val PER_APP_INBOUND_PACKAGES = stringSetPreferencesKey("per_app_proxy_packages")
        private val LAN_INBOUND_ENABLED = booleanPreferencesKey("lan_inbound_enabled")
        private val LAN_INBOUND_PORT = intPreferencesKey("lan_inbound_port")
        private val LAN_INBOUND_USERNAME = stringPreferencesKey("lan_inbound_username")
        private val LAN_INBOUND_PASSWORD = stringPreferencesKey("lan_inbound_password")
        private val LAN_INBOUND_PROXY_MODE = stringPreferencesKey("lan_inbound_proxy_mode")
        private val HOME_CLICK_SPEED_TEST_SCHEME_ID =
            stringPreferencesKey("home_click_speed_test_scheme_id")
        private val HOME_LONG_PRESS_SPEED_TEST_SCHEME_ID =
            stringPreferencesKey("home_long_press_speed_test_scheme_id")
        private val GROUP_MANUAL_SPEED_TEST_SCHEME_ID =
            stringPreferencesKey("group_manual_speed_test_scheme_id")
        private val GROUP_MANUAL_SPEED_TEST_ENABLED =
            booleanPreferencesKey("group_manual_speed_test_enabled")
        private val HOME_CLICK_SPEED_TEST_ENABLED =
            booleanPreferencesKey("home_click_speed_test_enabled")
        private val HOME_LONG_PRESS_SPEED_TEST_ENABLED =
            booleanPreferencesKey("home_long_press_speed_test_enabled")
        private val LAST_NOTICE_ID = stringPreferencesKey("last_notice_id")
        private val IGNORED_UPDATE_VERSION_CODE = intPreferencesKey("ignored_update_version_code")
        private val UPDATE_DOWNLOAD_DIRECTORY_URI =
            stringPreferencesKey("update_download_directory_uri")
        
        // 订阅列表缓存
        private val SUBSCRIPTION_LIST_JSON = stringPreferencesKey("subscription_list_json")
        private val SUBSCRIPTION_LIST_SCOPE = stringPreferencesKey("subscription_list_scope")
        
        // IPv6 路由设置
        private val IPV6_ROUTING_MODE = stringPreferencesKey("ipv6_routing_mode")

        // DNS 设置（与 NekoBox 的远程/直连及策略分组对应）
        private val DNS_REMOTE = stringPreferencesKey("dns_remote")
        private val DNS_REMOTE_STRATEGY = stringPreferencesKey("dns_remote_strategy")
        private val DNS_DIRECT = stringPreferencesKey("dns_direct")
        private val DNS_DIRECT_STRATEGY = stringPreferencesKey("dns_direct_strategy")
        private val DNS_SERVER_STRATEGY = stringPreferencesKey("dns_server_strategy")
        private val DNS_ROUTING_ENABLED = booleanPreferencesKey("dns_routing_enabled")
        private val DNS_FAKE_ENABLED = booleanPreferencesKey("dns_fake_enabled")
        private val SMART_ROUTING_RULES_JSON = stringPreferencesKey("smart_routing_rules_json")
        
        // 自动化测试设置
        private val SELECTED_SUBSCRIPTION_SOURCE_ID = stringPreferencesKey("selected_subscription_source_id")
        private val SELECTED_COUNTRY_CODE = stringPreferencesKey("selected_country_code")
        private val COUNTRY_GROUPING_SOURCE_IDS =
            stringSetPreferencesKey("country_grouping_source_ids")
        private val WAREHOUSE_GROUPING_COUNTRY_CODES =
            stringSetPreferencesKey("warehouse_grouping_country_codes")
        private val NODE_GROUPING_MODE = stringPreferencesKey("node_grouping_mode")
        private val SELECTED_NODE_GROUP_ID = stringPreferencesKey("selected_node_group_id")
        private val TCPING_TEST_TIMEOUT_MS = longPreferencesKey("tcping_test_timeout_ms")
        // 请求超时保存在本机，首次获取后台配置时也能读取。
        private val SERVICE_REQUEST_TIMEOUT_MS = longPreferencesKey("service_request_timeout_ms")
        private val SPEED_TEST_DOWNLOAD_URL = stringPreferencesKey("speed_test_download_url")
        private val SPEED_TEST_DURATION_MS = longPreferencesKey("speed_test_duration_ms")
        private val TCPING_CONCURRENCY = intPreferencesKey("tcping_concurrency")
        private val BANDWIDTH_TEST_CONCURRENCY = intPreferencesKey("bandwidth_test_concurrency")
        private val DISCONNECT_RECOVERY_ENABLED = booleanPreferencesKey("disconnect_recovery_enabled")
        private val HYSTERIA_BANDWIDTH_ENABLED = booleanPreferencesKey("hysteria_bandwidth_enabled")
        private val HYSTERIA_UP_MBPS = intPreferencesKey("hysteria_up_mbps")
        private val HYSTERIA_DOWN_MBPS = intPreferencesKey("hysteria_down_mbps")
        private val CORE_ASSIGNMENTS = stringSetPreferencesKey("protocol_core_assignments")
        // 迁移旧版全局节点标签设置；新值按主题方案保存。
        private val NODE_LABEL_SHOW_PROTOCOL = booleanPreferencesKey("node_label_show_protocol")
        private val NODE_LABEL_SHOW_SECURITY = booleanPreferencesKey("node_label_show_security")
        private val NODE_LABEL_SHOW_TRANSPORT = booleanPreferencesKey("node_label_show_transport")
        private val NODE_LABEL_SHOW_CORE = booleanPreferencesKey("node_label_show_core")
        // 迁移旧版全局自动删除设置；新设置保存在测速方案各阶段中。
        private val AUTO_DELETE_TCPING_TIMEOUT_NODES =
            booleanPreferencesKey("auto_delete_tcping_timeout_nodes")
        private val AUTO_DELETE_URL_TEST_TIMEOUT_NODES =
            booleanPreferencesKey("auto_delete_url_test_timeout_nodes")
        private val AUTO_DELETE_LOW_BANDWIDTH_NODES =
            booleanPreferencesKey("auto_delete_low_bandwidth_nodes")
        private val AUTO_DELETE_BANDWIDTH_THRESHOLD_MBPS =
            intPreferencesKey("auto_delete_bandwidth_threshold_mbps")
        private val SKIN_SCHEMES_JSON = stringPreferencesKey("skin_schemes_json")
        private val ACTIVE_SKIN_SCHEME_ID = stringPreferencesKey("active_skin_scheme_id")
        private val SPEED_TEST_SCHEMES_JSON = stringPreferencesKey("speed_test_schemes_json")

    }
    
    val selectedNodeId: Flow<String?> = store.data.map { preferences ->
        preferences[SELECTED_NODE_ID]
    }

    /** 统一的当前节点组标识。 */
    val selectedNodeGroupId: Flow<String?> = store.data.map { preferences ->
        preferences[SELECTED_NODE_GROUP_ID]?.takeIf(String::isNotBlank)
    }
    
    val proxyMode: Flow<ProxyMode> = store.data.map { preferences ->
        val mode = preferences[PROXY_MODE] ?: ProxyMode.SMART.name
        try {
            ProxyMode.valueOf(mode)
        } catch (e: Exception) {
            ProxyMode.SMART
        }
    }

    val perAppInboundSettings: Flow<PerAppInboundSettings> = store.data.map { preferences ->
        PerAppInboundSettings(
            enabled = preferences[PER_APP_INBOUND_ENABLED] ?: false,
            bypassMode = preferences[PER_APP_INBOUND_BYPASS_MODE] ?: false,
            selectedPackages = preferences[PER_APP_INBOUND_PACKAGES]?.toSet() ?: emptySet()
        )
    }

    /** 用户保存的测速方案；首次使用时提供一套当前组内置方案。 */
    val speedTestSchemes: Flow<List<SpeedTestScheme>> = store.data.map { preferences ->
        decodeSpeedTestSchemes(preferences)
    }

    /** 局域网入站设置默认关闭，避免意外暴露代理端口。 */
    val lanInboundSettings: Flow<LanInboundSettings> = store.data.map { preferences ->
        decodeLanInboundSettings(preferences)
    }

    /** 主页图标单击使用的测速方案。 */
    val homeClickSpeedTestSchemeId: Flow<String> = store.data.map { preferences ->
        val schemes = decodeSpeedTestSchemes(preferences)
        resolveHomeSchemeId(
            preferences[HOME_CLICK_SPEED_TEST_SCHEME_ID],
            SpeedTestScheme.DEFAULT_ID,
            schemes
        )
    }

    /** 主页图标长按使用的测速方案。 */
    val homeLongPressSpeedTestSchemeId: Flow<String> = store.data.map { preferences ->
        val schemes = decodeSpeedTestSchemes(preferences)
        resolveHomeSchemeId(
            preferences[HOME_LONG_PRESS_SPEED_TEST_SCHEME_ID],
            SpeedTestScheme.DEFAULT_ID,
            schemes
        )
    }

    /** 分组线路详情中手动测试使用的测速方案。 */
    val groupManualSpeedTestSchemeId: Flow<String> = store.data.map { preferences ->
        val schemes = decodeSpeedTestSchemes(preferences)
        resolveHomeSchemeId(
            preferences[GROUP_MANUAL_SPEED_TEST_SCHEME_ID],
            SpeedTestScheme.DEFAULT_ID,
            schemes
        )
    }

    /** 分组线路详情中的手动测速是否启用；默认启用以保留现有入口。 */
    val groupManualSpeedTestEnabled: Flow<Boolean> = store.data.map { preferences ->
        preferences[GROUP_MANUAL_SPEED_TEST_ENABLED] ?: true
    }

    /** 主页图标单击是否启用测速方案；默认关闭，避免普通点击触发测速。 */
    val homeClickSpeedTestEnabled: Flow<Boolean> = store.data.map { preferences ->
        preferences[HOME_CLICK_SPEED_TEST_ENABLED] ?: false
    }

    /** 主页图标长按是否启用测速方案；默认开启以保持原有行为。 */
    val homeLongPressSpeedTestEnabled: Flow<Boolean> = store.data.map { preferences ->
        preferences[HOME_LONG_PRESS_SPEED_TEST_ENABLED] ?: true
    }

    suspend fun setHomeClickSpeedTestSchemeId(schemeId: String) {
        store.edit { preferences ->
            if (decodeSpeedTestSchemes(preferences).any { it.id == schemeId }) {
                preferences[HOME_CLICK_SPEED_TEST_SCHEME_ID] = schemeId
            }
        }
    }

    suspend fun setHomeLongPressSpeedTestSchemeId(schemeId: String) {
        store.edit { preferences ->
            if (decodeSpeedTestSchemes(preferences).any { it.id == schemeId }) {
                preferences[HOME_LONG_PRESS_SPEED_TEST_SCHEME_ID] = schemeId
            }
        }
    }

    suspend fun setGroupManualSpeedTestSchemeId(schemeId: String) {
        store.edit { preferences ->
            if (decodeSpeedTestSchemes(preferences).any { it.id == schemeId }) {
                preferences[GROUP_MANUAL_SPEED_TEST_SCHEME_ID] = schemeId
            }
        }
    }

    suspend fun setGroupManualSpeedTestEnabled(enabled: Boolean) {
        store.edit { preferences ->
            preferences[GROUP_MANUAL_SPEED_TEST_ENABLED] = enabled
        }
    }

    suspend fun setHomeClickSpeedTestEnabled(enabled: Boolean) {
        store.edit { preferences ->
            preferences[HOME_CLICK_SPEED_TEST_ENABLED] = enabled
        }
    }

    suspend fun setHomeLongPressSpeedTestEnabled(enabled: Boolean) {
        store.edit { preferences ->
            preferences[HOME_LONG_PRESS_SPEED_TEST_ENABLED] = enabled
        }
    }

    /** 皮肤配置，未设置时保持当前内置默认皮肤。 */
    val skinSettings: Flow<SkinSettings> = store.data.map { preferences ->
        val schemes = decodeSkinSchemes(preferences)
        val activeId = preferences[ACTIVE_SKIN_SCHEME_ID]
            ?.takeIf { id -> schemes.any { it.id == id } }
            ?: schemes.firstOrNull { !it.isDefault }?.id
            ?: SkinScheme.DEFAULT_SCHEME_ID
        val activeScheme = schemes.firstOrNull { it.id == activeId }
            ?: SkinScheme.default()
        SkinSettings(
            baseColorArgb = activeScheme.baseColorArgb,
            themeMode = activeScheme.themeMode,
            monetEnabled = activeScheme.monetEnabled,
            customImagePath = activeScheme.customImagePath,
            homeDisconnectedImagePath = activeScheme.homeDisconnectedImagePath,
            homeConnectingImagePath = activeScheme.homeConnectingImagePath,
            homeDisconnectingImagePath = activeScheme.homeDisconnectingImagePath,
            homeConnectedImagePath = activeScheme.homeConnectedImagePath,
            activeSchemeId = activeScheme.id,
            schemes = schemes
        )
    }
    
    val lastNoticeId: Flow<String?> = store.data.map { preferences ->
        preferences[LAST_NOTICE_ID]
    }

    val ignoredUpdateVersionCode: Flow<Int?> = store.data.map { preferences ->
        preferences[IGNORED_UPDATE_VERSION_CODE]
    }

    /** 用户选择的更新安装包目录；为空时使用应用缓存目录。 */
    val updateDownloadDirectoryUri: Flow<String?> = store.data.map { preferences ->
        preferences[UPDATE_DOWNLOAD_DIRECTORY_URI]
    }
    
    val selectedSubscriptionSourceId: Flow<String?> = store.data.map { preferences ->
        preferences[SELECTED_SUBSCRIPTION_SOURCE_ID]
    }

    /** 地区页选中的国家代码；存在时主页智能测试跨订阅组限定到该地区。 */
    val selectedCountryCode: Flow<String?> = store.data.map { preferences ->
        CountryCodeResolver.normalizeCountryCode(preferences[SELECTED_COUNTRY_CODE])
    }

    /** 仓库源地区筛选；null 表示默认包含全部仓库源。 */
    val countryGroupingSourceIds: Flow<Set<String>?> =
        store.data.map { preferences ->
            preferences[COUNTRY_GROUPING_SOURCE_IDS]?.toSet()
        }

    suspend fun setCountryGroupingSourceIds(sourceIds: Set<String>?) {
        store.edit { preferences ->
            if (sourceIds == null) {
                preferences.remove(COUNTRY_GROUPING_SOURCE_IDS)
            } else {
                preferences[COUNTRY_GROUPING_SOURCE_IDS] = sourceIds.toSet()
            }
        }
    }

    /** 仓库源地区筛选；null 表示默认包含全部地区。 */
    val warehouseGroupingCountryCodes: Flow<Set<String>?> =
        store.data.map { preferences ->
            preferences[WAREHOUSE_GROUPING_COUNTRY_CODES]
                ?.mapNotNull(CountryCodeResolver::normalizeCountryCode)
                ?.toSet()
        }

    suspend fun setWarehouseGroupingCountryCodes(countryCodes: Set<String>?) {
        val normalizedCodes = countryCodes
            ?.mapNotNull(CountryCodeResolver::normalizeCountryCode)
            ?.toSet()
        store.edit { preferences ->
            if (normalizedCodes == null) {
                preferences.remove(WAREHOUSE_GROUPING_COUNTRY_CODES)
            } else {
                preferences[WAREHOUSE_GROUPING_COUNTRY_CODES] = normalizedCodes
            }
        }
    }

    val nodeGroupingMode: Flow<NodeGroupingMode> = store.data.map { preferences ->
        when (preferences[NODE_GROUPING_MODE]) {
            "COUNTRY", "REGION" -> NodeGroupingMode.REGION
            "SUBSCRIPTION", "WAREHOUSE" -> NodeGroupingMode.WAREHOUSE
            else -> NodeGroupingMode.REGION
        }
    }

    fun subscriptionListJson(scope: String): Flow<String?> = store.data.map { preferences ->
        preferences[SUBSCRIPTION_LIST_JSON].takeIf { preferences[SUBSCRIPTION_LIST_SCOPE] == scope }
    }

    suspend fun setSubscriptionList(json: String, scope: String) {
        store.edit { preferences ->
            preferences[SUBSCRIPTION_LIST_JSON] = json
            preferences[SUBSCRIPTION_LIST_SCOPE] = scope
        }
    }

    suspend fun clearSubscriptionList() {
        store.edit { preferences ->
            preferences.remove(SUBSCRIPTION_LIST_JSON)
            preferences.remove(SUBSCRIPTION_LIST_SCOPE)
        }
    }

    /** Per-source overrides; absent entries use the source type's default. */
    val autoRefreshOnStartupOverrides: Flow<Map<String, Boolean>> = store.data.map { preferences ->
        preferences.asMap().mapNotNull { (key, value) ->
            key.name.takeIf { it.startsWith(AUTO_REFRESH_SOURCE_PREFIX) }
                ?.removePrefix(AUTO_REFRESH_SOURCE_PREFIX)
                ?.let { sourceId -> (value as? Boolean)?.let { sourceId to it } }
        }.toMap()
    }

    suspend fun setAutoRefreshOnStartup(sourceId: String, enabled: Boolean) {
        store.edit { preferences ->
            preferences[autoRefreshOnStartupKey(sourceId)] = enabled
        }
    }

    private fun autoRefreshOnStartupKey(sourceId: String) =
        booleanPreferencesKey(AUTO_REFRESH_SOURCE_PREFIX + sourceId)

    /** Reads all settings needed for one VPN start from a single DataStore snapshot. */
    suspend fun getVpnRuntimeSettings(): VpnRuntimeSettings {
        val preferences = store.data.first()
        val hysteriaBandwidthEnabled = preferences[HYSTERIA_BANDWIDTH_ENABLED]
            ?: io.github.HanJason233.chimera.AppConfig.HYSTERIA_BANDWIDTH_ENABLED
        return VpnRuntimeSettings(
            proxyMode = enumValueOrDefault(preferences[PROXY_MODE], ProxyMode.SMART),
            ipv6RoutingMode = enumValueOrDefault(
                preferences[IPV6_ROUTING_MODE],
                IPv6RoutingMode.DISABLED
            ),
            // 关闭 Hy2 带宽参数时必须向所有核心都传 0，避免运行时仍启用 Brutal。
            hysteriaUpMbps = if (hysteriaBandwidthEnabled) {
                (preferences[HYSTERIA_UP_MBPS]
                    ?: io.github.HanJason233.chimera.AppConfig.HYSTERIA_UP_MBPS).coerceAtLeast(0)
            } else {
                0
            },
            hysteriaDownMbps = if (hysteriaBandwidthEnabled) {
                (preferences[HYSTERIA_DOWN_MBPS]
                    ?: io.github.HanJason233.chimera.AppConfig.HYSTERIA_DOWN_MBPS).coerceAtLeast(0)
            } else {
                0
            },
            protocolCoreSettings = decodeProtocolCoreSettings(preferences[CORE_ASSIGNMENTS]),
            lanInbound = decodeLanInboundSettings(preferences),
            dnsSettings = decodeDnsSettings(preferences),
            smartRoutingSettings = decodeSmartRoutingSettings(preferences),
            perAppInboundSettings = PerAppInboundSettings(
                enabled = preferences[PER_APP_INBOUND_ENABLED] ?: false,
                bypassMode = preferences[PER_APP_INBOUND_BYPASS_MODE] ?: false,
                selectedPackages = preferences[PER_APP_INBOUND_PACKAGES]?.toSet() ?: emptySet()
            ),
        )
    }

    suspend fun setPerAppInboundSettings(settings: PerAppInboundSettings) {
        store.edit { preferences ->
            preferences[PER_APP_INBOUND_ENABLED] = settings.enabled
            preferences[PER_APP_INBOUND_BYPASS_MODE] = settings.bypassMode
            val cleanPackages = settings.selectedPackages.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            if (cleanPackages.isEmpty()) {
                preferences.remove(PER_APP_INBOUND_PACKAGES)
            } else {
                preferences[PER_APP_INBOUND_PACKAGES] = cleanPackages
            }
        }
    }

    val protocolCoreSettings: Flow<ProtocolCoreSettings> = store.data.map { preferences ->
        decodeProtocolCoreSettings(preferences[CORE_ASSIGNMENTS])
    }

    suspend fun setProtocolCore(nodeType: NodeType, core: ProxyCore) {
        require(core in io.github.HanJason233.chimera.core.CoreBackends.registry.availableFor(nodeType))
        store.edit { preferences ->
            val current = decodeProtocolCoreSettings(preferences[CORE_ASSIGNMENTS])
                .assignments.toMutableMap()
            current[nodeType] = core
            preferences[CORE_ASSIGNMENTS] = current.mapTo(mutableSetOf()) { (type, selected) ->
                "${type.protocol}=${selected.name}"
            }
        }
    }

    private fun decodeProtocolCoreSettings(entries: Set<String>?): ProtocolCoreSettings {
        val assignments = entries.orEmpty().mapNotNull { entry ->
            val pair = entry.split('=', limit = 2)
            if (pair.size != 2) return@mapNotNull null
            val type = NodeType.entries.firstOrNull { it.protocol == pair[0] } ?: return@mapNotNull null
            val selected = runCatching { ProxyCore.valueOf(pair[1]) }.getOrNull() ?: return@mapNotNull null
            type to selected
        }.toMap()
        return ProtocolCoreSettings(assignments = assignments)
    }

    private fun decodeLanInboundSettings(preferences: Preferences): LanInboundSettings =
        LanInboundSettings(
            enabled = preferences[LAN_INBOUND_ENABLED] ?: false,
            port = (preferences[LAN_INBOUND_PORT] ?: LanInboundSettings.DEFAULT_PORT)
                .coerceIn(1, 65_535),
            username = preferences[LAN_INBOUND_USERNAME]
                ?.trim()
                ?.ifBlank { LanInboundSettings.DEFAULT_USERNAME }
                ?: LanInboundSettings.DEFAULT_USERNAME,
            password = preferences[LAN_INBOUND_PASSWORD].orEmpty(),
            // 旧版本两个入口共用模式，首次独立设置前保留原有行为。
            proxyMode = enumValueOrDefault(
                preferences[LAN_INBOUND_PROXY_MODE],
                enumValueOrDefault(preferences[PROXY_MODE], ProxyMode.SMART)
            )
        )

    private fun decodeDnsSettings(preferences: Preferences): DnsSettings = DnsSettings(
        remoteDns = DnsSettings.normalizeServerList(preferences[DNS_REMOTE])
            ?: DnsSettings.DEFAULT_REMOTE_DNS,
        remoteDomainStrategy = DnsDomainStrategy.fromStorage(preferences[DNS_REMOTE_STRATEGY]),
        directDns = DnsSettings.normalizeServerList(preferences[DNS_DIRECT])
            ?: DnsSettings.DEFAULT_DIRECT_DNS,
        directDomainStrategy = DnsDomainStrategy.fromStorage(preferences[DNS_DIRECT_STRATEGY]),
        serverDomainStrategy = DnsDomainStrategy.fromStorage(preferences[DNS_SERVER_STRATEGY]),
        dnsRoutingEnabled = preferences[DNS_ROUTING_ENABLED] ?: true,
        fakeDnsEnabled = preferences[DNS_FAKE_ENABLED] ?: true
    )

    private fun decodeSmartRoutingSettings(preferences: Preferences): SmartRoutingSettings {
        val stored = preferences[SMART_ROUTING_RULES_JSON]
            ?.takeIf(String::isNotBlank)
            ?.let(::decodeSmartRoutingRules)
        if (stored != null) {
            return SmartRoutingSettings(ensureBuiltInSmartRules(stored))
        }

        return SmartRoutingSettings()
    }

    private fun decodeSmartRoutingRules(json: String): List<SmartRoutingRule>? = runCatching {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                val type = runCatching {
                    SmartRoutingRuleType.valueOf(item.optString("type"))
                }.getOrNull() ?: continue
                val action = runCatching {
                    SmartRoutingRuleAction.valueOf(item.optString("action"))
                }.getOrNull() ?: continue
                if (id.isBlank() || name.isBlank()) continue
                val values = buildList {
                    item.optJSONArray("values")?.let { valueArray ->
                        for (valueIndex in 0 until valueArray.length()) {
                            valueArray.optString(valueIndex)
                                .trim()
                                .takeIf(String::isNotBlank)
                                ?.let(::add)
                        }
                    }
                }
                add(
                    SmartRoutingRule(
                        id = id,
                        name = name,
                        type = type,
                        action = action,
                        values = values,
                        enabled = item.optBoolean("enabled", true),
                        locked = item.optBoolean(
                            "locked",
                            type == SmartRoutingRuleType.DOMESTIC_DOMAIN ||
                                type == SmartRoutingRuleType.DOMESTIC_IP
                        )
                    )
                )
            }
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun ensureBuiltInSmartRules(rules: List<SmartRoutingRule>): List<SmartRoutingRule> {
        val defaults = SmartRoutingSettings.defaultRules()
        val defaultDomain = defaults
            .first { it.id == SmartRoutingSettings.DOMESTIC_DOMAIN_RULE_ID }
            .copy(name = "国内域名（内置）", enabled = true)
        val defaultIp = defaults
            .first { it.id == SmartRoutingSettings.DOMESTIC_IP_RULE_ID }
            .copy(enabled = true)
        var domainPresent = false
        var ipPresent = false

        return buildList {
            rules.forEach { rule ->
                when (rule.id) {
                    SmartRoutingSettings.DOMESTIC_DOMAIN_RULE_ID -> {
                        if (!domainPresent) {
                            add(defaultDomain)
                            domainPresent = true
                        }
                    }
                    SmartRoutingSettings.DOMESTIC_IP_RULE_ID -> {
                        if (!ipPresent) {
                            add(defaultIp)
                            ipPresent = true
                        }
                    }
                    else -> if (rule.type == SmartRoutingRuleType.CUSTOM_DOMAIN) {
                        add(rule.copy(locked = false))
                    }
                }
            }
            if (!domainPresent) add(defaultDomain)
            if (!ipPresent) add(defaultIp)
        }
    }

    private fun encodeSmartRoutingRules(rules: List<SmartRoutingRule>): String =
        JSONArray().apply {
            rules.forEach { rule ->
                put(JSONObject().apply {
                    put("id", rule.id)
                    put("name", rule.name)
                    put("type", rule.type.name)
                    put("action", rule.action.name)
                    put("values", JSONArray().apply { rule.values.forEach(::put) })
                    put("enabled", rule.enabled)
                    put("locked", rule.locked)
                })
            }
        }.toString()

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        value: String?,
        default: T
    ): T = runCatching { enumValueOf<T>(value ?: default.name) }.getOrDefault(default)
    
    suspend fun setSelectedNodeId(nodeId: String?) {
        store.edit { preferences ->
            if (nodeId == null) {
                preferences.remove(SELECTED_NODE_ID)
            } else {
                preferences[SELECTED_NODE_ID] = nodeId
            }
        }
    }

    suspend fun setSelectedNodeGroupId(groupId: String?) {
        store.edit { preferences ->
            if (groupId.isNullOrBlank()) {
                preferences.remove(SELECTED_NODE_GROUP_ID)
            } else {
                preferences[SELECTED_NODE_GROUP_ID] = groupId
            }
        }
    }

    /** 分组和旧筛选字段一次保存，避免观察者读到混合状态。 */
    suspend fun setSelectedNodeGroup(group: NodeGroupKey) {
        store.edit { preferences ->
            if (preferences[SELECTED_NODE_GROUP_ID] != group.value) {
                preferences.remove(SELECTED_NODE_ID)
            }
            preferences[SELECTED_NODE_GROUP_ID] = group.value
            when (group) {
                is NodeGroupKey.Subscription -> {
                    preferences[SELECTED_SUBSCRIPTION_SOURCE_ID] = group.sourceId
                    preferences.remove(SELECTED_COUNTRY_CODE)
                    preferences[NODE_GROUPING_MODE] = NodeGroupingMode.WAREHOUSE.name
                }
                is NodeGroupKey.Imported -> {
                    preferences.remove(SELECTED_SUBSCRIPTION_SOURCE_ID)
                    preferences.remove(SELECTED_COUNTRY_CODE)
                    preferences[NODE_GROUPING_MODE] = NodeGroupingMode.WAREHOUSE.name
                }
                is NodeGroupKey.Country -> {
                    preferences[SELECTED_COUNTRY_CODE] = group.countryCode
                    preferences[NODE_GROUPING_MODE] = NodeGroupingMode.REGION.name
                }
            }
        }
    }
    
    suspend fun setProxyMode(mode: ProxyMode) {
        store.edit { preferences ->
            if (preferences[LAN_INBOUND_PROXY_MODE] == null) {
                // 修改本机模式前固定旧的共享模式，之后两个入口互不联动。
                preferences[LAN_INBOUND_PROXY_MODE] =
                    enumValueOrDefault(preferences[PROXY_MODE], ProxyMode.SMART).name
            }
            preferences[PROXY_MODE] = mode.name
        }
    }

    suspend fun setLanInboundProxyMode(mode: ProxyMode) {
        store.edit { preferences ->
            preferences[LAN_INBOUND_PROXY_MODE] = mode.name
        }
    }

    suspend fun setLanInboundEnabled(enabled: Boolean) {
        store.edit { preferences ->
            preferences[LAN_INBOUND_ENABLED] = enabled
            if (enabled && preferences[LAN_INBOUND_PASSWORD].isNullOrBlank()) {
                // 首次开启时生成随机密码，并持久化到设置中供局域网设备使用。
                preferences[LAN_INBOUND_PASSWORD] = UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
            }
        }
    }

    suspend fun setLanInboundDetails(
        port: Int,
        username: String,
        password: String
    ) {
        store.edit { preferences ->
            preferences[LAN_INBOUND_PORT] = port.coerceIn(1, 65_535)
            preferences[LAN_INBOUND_USERNAME] = username.trim().ifBlank {
                LanInboundSettings.DEFAULT_USERNAME
            }
            if (password.isNotBlank()) {
                preferences[LAN_INBOUND_PASSWORD] = password
            }
        }
    }

    suspend fun upsertSpeedTestScheme(scheme: SpeedTestScheme) {
        store.edit { preferences ->
            val normalized = normalizeSpeedTestScheme(scheme)
            val schemes = decodeSpeedTestSchemes(preferences)
                .filterNot { it.id == scheme.id } + normalized
            preferences[SPEED_TEST_SCHEMES_JSON] = encodeSpeedTestSchemes(schemes)
        }
    }

    suspend fun deleteSpeedTestScheme(schemeId: String) {
        store.edit { preferences ->
            val schemes = decodeSpeedTestSchemes(preferences)
            val target = schemes.firstOrNull { it.id == schemeId } ?: return@edit
            if (target.builtIn) return@edit
            preferences[SPEED_TEST_SCHEMES_JSON] = encodeSpeedTestSchemes(
                schemes.filterNot { it.id == schemeId }
            )
            if (preferences[HOME_CLICK_SPEED_TEST_SCHEME_ID] == schemeId) {
                preferences[HOME_CLICK_SPEED_TEST_SCHEME_ID] = SpeedTestScheme.DEFAULT_ID
            }
            if (preferences[HOME_LONG_PRESS_SPEED_TEST_SCHEME_ID] == schemeId) {
                preferences[HOME_LONG_PRESS_SPEED_TEST_SCHEME_ID] = SpeedTestScheme.DEFAULT_ID
            }
            if (preferences[GROUP_MANUAL_SPEED_TEST_SCHEME_ID] == schemeId) {
                preferences[GROUP_MANUAL_SPEED_TEST_SCHEME_ID] = SpeedTestScheme.DEFAULT_ID
            }
        }
    }

    suspend fun upsertSkinScheme(scheme: SkinScheme, activate: Boolean = false) {
        store.edit { preferences ->
            val normalizedScheme = scheme.copy(
                name = scheme.name.trim().ifBlank {
                    if (scheme.isDefault) "默认方案" else "我的方案"
                },
                baseColorArgb = normalizeSkinColor(scheme.baseColorArgb)
            )
            val schemes = decodeSkinSchemes(preferences)
                .filterNot { it.id == scheme.id } + normalizedScheme
            preferences[SKIN_SCHEMES_JSON] = encodeSkinSchemes(schemes)
            if (activate) {
                preferences[ACTIVE_SKIN_SCHEME_ID] = scheme.id
            }
        }
    }

    suspend fun setActiveSkinScheme(schemeId: String) {
        store.edit { preferences ->
            if (decodeSkinSchemes(preferences).any { it.id == schemeId }) {
                preferences[ACTIVE_SKIN_SCHEME_ID] = schemeId
            }
        }
    }

    suspend fun deleteSkinScheme(schemeId: String) {
        if (schemeId == SkinScheme.DEFAULT_SCHEME_ID) return
        store.edit { preferences ->
            val schemes = decodeSkinSchemes(preferences)
            if (schemes.none { it.id == schemeId }) return@edit
            preferences[SKIN_SCHEMES_JSON] = encodeSkinSchemes(
                schemes.filterNot { it.id == schemeId }
            )
            if (preferences[ACTIVE_SKIN_SCHEME_ID] == schemeId) {
                preferences[ACTIVE_SKIN_SCHEME_ID] = SkinScheme.DEFAULT_SCHEME_ID
            }
        }
    }
    
    suspend fun setLastNoticeId(noticeId: String) {
        store.edit { preferences ->
            preferences[LAST_NOTICE_ID] = noticeId
        }
    }

    suspend fun setIgnoredUpdateVersionCode(versionCode: Int) {
        store.edit { preferences ->
            preferences[IGNORED_UPDATE_VERSION_CODE] = versionCode
        }
    }

    suspend fun setUpdateDownloadDirectoryUri(uri: String?) {
        store.edit { preferences ->
            if (uri.isNullOrBlank()) {
                preferences.remove(UPDATE_DOWNLOAD_DIRECTORY_URI)
            } else {
                preferences[UPDATE_DOWNLOAD_DIRECTORY_URI] = uri
            }
        }
    }
    
    /** 注销账户时清除全部本地设置。 */
    suspend fun clearAll() {
        store.edit { preferences ->
            preferences.clear()
        }
    }
    
    // IPv6 路由模式
    val ipv6RoutingMode: Flow<IPv6RoutingMode> = store.data.map { preferences ->
        val mode = preferences[IPV6_ROUTING_MODE] ?: IPv6RoutingMode.DISABLED.name
        try {
            IPv6RoutingMode.valueOf(mode)
        } catch (e: Exception) {
            IPv6RoutingMode.DISABLED
        }
    }
    
    suspend fun setIPv6RoutingMode(mode: IPv6RoutingMode) {
        store.edit { preferences ->
            preferences[IPV6_ROUTING_MODE] = mode.name
        }
    }

    val dnsSettings: Flow<DnsSettings> = store.data.map { preferences ->
        decodeDnsSettings(preferences)
    }

    suspend fun setRemoteDns(value: String) {
        DnsSettings.normalizeServerList(value)?.let { normalized ->
            store.edit { preferences -> preferences[DNS_REMOTE] = normalized }
        }
    }

    suspend fun setRemoteDnsStrategy(strategy: DnsDomainStrategy) {
        store.edit { preferences -> preferences[DNS_REMOTE_STRATEGY] = strategy.name }
    }

    suspend fun setDirectDns(value: String) {
        DnsSettings.normalizeServerList(value)?.let { normalized ->
            store.edit { preferences -> preferences[DNS_DIRECT] = normalized }
        }
    }

    suspend fun setDirectDnsStrategy(strategy: DnsDomainStrategy) {
        store.edit { preferences -> preferences[DNS_DIRECT_STRATEGY] = strategy.name }
    }

    suspend fun setServerDnsStrategy(strategy: DnsDomainStrategy) {
        store.edit { preferences -> preferences[DNS_SERVER_STRATEGY] = strategy.name }
    }

    suspend fun setDnsRoutingEnabled(enabled: Boolean) {
        store.edit { preferences -> preferences[DNS_ROUTING_ENABLED] = enabled }
    }

    suspend fun setFakeDnsEnabled(enabled: Boolean) {
        store.edit { preferences -> preferences[DNS_FAKE_ENABLED] = enabled }
    }

    val smartRoutingSettings: Flow<SmartRoutingSettings> = store.data.map { preferences ->
        decodeSmartRoutingSettings(preferences)
    }

    suspend fun setSmartRoutingDomains(
        action: SmartRoutingRuleAction,
        values: List<String>
    ) {
        store.edit { preferences ->
            val current = decodeSmartRoutingSettings(preferences).rules
            val normalizedValues = values
                .flatMap { it.split(',', '\n') }
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
            val remaining = current.filterNot {
                it.type == SmartRoutingRuleType.CUSTOM_DOMAIN && it.action == action
            }
            val updated = if (normalizedValues.isEmpty()) {
                remaining
            } else {
                listOf(
                    SmartRoutingRule(
                        id = "custom_smart_${action.name.lowercase()}",
                        name = action.name,
                        type = SmartRoutingRuleType.CUSTOM_DOMAIN,
                        action = action,
                        values = normalizedValues
                    )
                ) + remaining
            }
            preferences[SMART_ROUTING_RULES_JSON] = encodeSmartRoutingRules(updated)
        }
    }

    val tcpingTestTimeoutMs: Flow<Long> = store.data.map { preferences ->
        (preferences[TCPING_TEST_TIMEOUT_MS] ?: io.github.HanJason233.chimera.AppConfig.TCPING_TEST_TIMEOUT).coerceAtLeast(500L)
    }

    val serviceRequestTimeoutMs: Flow<Long> = store.data.map { preferences ->
        (preferences[SERVICE_REQUEST_TIMEOUT_MS] ?: DEFAULT_SERVICE_REQUEST_TIMEOUT_MS)
            .coerceIn(MIN_SERVICE_REQUEST_TIMEOUT_MS, MAX_SERVICE_REQUEST_TIMEOUT_MS)
    }

    suspend fun setServiceRequestTimeoutMs(value: Long) {
        store.edit { preferences ->
            preferences[SERVICE_REQUEST_TIMEOUT_MS] = value.coerceIn(
                MIN_SERVICE_REQUEST_TIMEOUT_MS,
                MAX_SERVICE_REQUEST_TIMEOUT_MS
            )
        }
    }

    suspend fun setTcpingTestTimeoutMs(value: Long) {
        store.edit { preferences ->
            preferences[TCPING_TEST_TIMEOUT_MS] = value.coerceAtLeast(500L)
        }
    }

    val speedTestDownloadUrl: Flow<String> = store.data.map { preferences ->
        BandwidthTestTargets.normalize(preferences[SPEED_TEST_DOWNLOAD_URL])
            ?: io.github.HanJason233.chimera.AppConfig.SPEED_TEST_DOWNLOAD_URL
    }

    suspend fun setSpeedTestDownloadUrl(value: String) {
        val normalized = BandwidthTestTargets.normalize(value) ?: return
        store.edit { preferences ->
            preferences[SPEED_TEST_DOWNLOAD_URL] = normalized
        }
    }

    val speedTestDurationMs: Flow<Long> = store.data.map { preferences ->
        (preferences[SPEED_TEST_DURATION_MS]
            ?: io.github.HanJason233.chimera.AppConfig.AUTO_TEST_BANDWIDTH_DURATION_MS)
            .coerceAtLeast(1_000L)
    }

    suspend fun setSpeedTestDurationMs(value: Long) {
        store.edit { preferences ->
            preferences[SPEED_TEST_DURATION_MS] = value.coerceAtLeast(1_000L)
        }
    }

    val tcpingConcurrency: Flow<Int> = store.data.map { preferences ->
        (preferences[TCPING_CONCURRENCY] ?: io.github.HanJason233.chimera.AppConfig.TCPING_CONCURRENCY).coerceIn(1, 128)
    }

    suspend fun setTcpingConcurrency(value: Int) {
        store.edit { preferences ->
            preferences[TCPING_CONCURRENCY] = value.coerceIn(1, 128)
        }
    }

    val bandwidthTestConcurrency: Flow<Int> = store.data.map { preferences ->
        (preferences[BANDWIDTH_TEST_CONCURRENCY]
            ?: io.github.HanJason233.chimera.AppConfig.BANDWIDTH_TEST_CONCURRENCY).coerceIn(1, 3)
    }

    suspend fun setBandwidthTestConcurrency(value: Int) {
        store.edit { preferences ->
            preferences[BANDWIDTH_TEST_CONCURRENCY] = value.coerceIn(1, 3)
        }
    }

    suspend fun setSelectedSubscriptionSourceId(sourceId: String?) {
        store.edit { preferences ->
            if (sourceId.isNullOrBlank()) {
                preferences.remove(SELECTED_SUBSCRIPTION_SOURCE_ID)
            } else {
                preferences[SELECTED_SUBSCRIPTION_SOURCE_ID] = sourceId
            }
        }
    }

    suspend fun setSelectedCountryCode(countryCode: String?) {
        val normalizedCode = CountryCodeResolver.normalizeCountryCode(countryCode)
        store.edit { preferences ->
            if (normalizedCode == null) {
                preferences.remove(SELECTED_COUNTRY_CODE)
            } else {
                preferences[SELECTED_COUNTRY_CODE] = normalizedCode
            }
        }
    }

    suspend fun setNodeGroupingMode(mode: NodeGroupingMode) {
        store.edit { preferences ->
            preferences[NODE_GROUPING_MODE] = mode.name
        }
    }

    val disconnectRecoveryEnabled: Flow<Boolean> = store.data.map { preferences ->
        preferences[DISCONNECT_RECOVERY_ENABLED] ?: io.github.HanJason233.chimera.AppConfig.DISCONNECT_RECOVERY_ENABLED
    }

    suspend fun setDisconnectRecoveryEnabled(enabled: Boolean) {
        store.edit { preferences ->
            preferences[DISCONNECT_RECOVERY_ENABLED] = enabled
        }
    }

    val hysteriaBandwidthEnabled: Flow<Boolean> = store.data.map { preferences ->
        preferences[HYSTERIA_BANDWIDTH_ENABLED] ?: io.github.HanJason233.chimera.AppConfig.HYSTERIA_BANDWIDTH_ENABLED
    }

    val hysteriaUpMbps: Flow<Int> = store.data.map { preferences ->
        if (preferences[HYSTERIA_BANDWIDTH_ENABLED] == false) {
            0
        } else {
            (preferences[HYSTERIA_UP_MBPS] ?: io.github.HanJason233.chimera.AppConfig.HYSTERIA_UP_MBPS)
                .coerceIn(0, 100_000)
        }
    }

    suspend fun setHysteriaBandwidthEnabled(enabled: Boolean) {
        store.edit { preferences ->
            preferences[HYSTERIA_BANDWIDTH_ENABLED] = enabled
        }
    }

    suspend fun setHysteriaUpMbps(value: Int) {
        store.edit { preferences ->
            preferences[HYSTERIA_UP_MBPS] = value.coerceIn(0, 100_000)
        }
    }

    val hysteriaDownMbps: Flow<Int> = store.data.map { preferences ->
        if (preferences[HYSTERIA_BANDWIDTH_ENABLED] == false) {
            0
        } else {
            (preferences[HYSTERIA_DOWN_MBPS] ?: io.github.HanJason233.chimera.AppConfig.HYSTERIA_DOWN_MBPS)
                .coerceIn(0, 100_000)
        }
    }

    suspend fun setHysteriaDownMbps(value: Int) {
        store.edit { preferences ->
            preferences[HYSTERIA_DOWN_MBPS] = value.coerceIn(0, 100_000)
        }
    }

    private fun normalizeSkinColor(argb: Long): Long {
        return 0xFF000000L or (argb and 0x00FFFFFFL)
    }

    private fun resolveHomeSchemeId(
        storedId: String?,
        fallbackId: String,
        schemes: List<SpeedTestScheme>
    ): String {
        return listOf(storedId, fallbackId)
            .firstOrNull { id -> id != null && schemes.any { it.id == id } }
            ?: fallbackId
    }

    private fun decodeSpeedTestSchemes(preferences: Preferences): List<SpeedTestScheme> {
        val stored = preferences[SPEED_TEST_SCHEMES_JSON]
            ?.takeIf(String::isNotBlank)
            ?.let { json ->
                runCatching {
                    val array = JSONArray(json)
                    buildList {
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index) ?: continue
                            val id = item.optString("id").trim()
                            if (id.isBlank()) continue
                            // 仅在内置预置版本升级时刷新默认字段；自定义方案保持原样。
                            if (id == SpeedTestScheme.DEFAULT_ID &&
                                item.optInt("defaultsVersion", 0) < DEFAULT_SPEED_TEST_PRESET_REVISION
                            ) {
                                val defaults = SpeedTestScheme.default()
                                item.put("tcpingSelectionMode", defaults.tcpingSelectionMode.name)
                                item.put("tcpingSelectionValue", defaults.tcpingSelectionValue)
                                item.put("urlTestSelectionMode", defaults.urlTestSelectionMode.name)
                                item.put("urlTestSelectionValue", defaults.urlTestSelectionValue)
                                item.put("linkTypeTestEnabled", defaults.linkTypeTestEnabled)
                                item.put("tcpingConcurrencyMode", defaults.tcpingConcurrencyMode.name)
                                item.put("tcpingConcurrency", defaults.tcpingConcurrency)
                                item.put("urlTestConcurrencyMode", defaults.urlTestConcurrencyMode.name)
                                item.put("urlTestConcurrency", defaults.urlTestConcurrency)
                                item.put(
                                    "bandwidthTestConcurrencyMode",
                                    defaults.bandwidthTestConcurrencyMode.name
                                )
                                item.put("bandwidthTestConcurrency", defaults.bandwidthTestConcurrency)
                            }
                            add(
                                normalizeSpeedTestScheme(
                                    SpeedTestScheme(
                                        id = id,
                                        name = item.optString("name", "我的方案"),
                                        builtIn = item.optBoolean("builtIn", false),
                                        tcpingEnabled = item.optBoolean("tcpingEnabled", true),
                                        urlTestEnabled = item.optBoolean("urlTestEnabled", true),
                                        bandwidthEnabled = item.optBoolean("bandwidthEnabled", true),
                                        linkTypeTestEnabled = item.optBoolean("linkTypeTestEnabled", false),
                                        unlockTestsEnabled = item.optBoolean("unlockTestsEnabled", false),
                                        unlockTestIds = buildSet {
                                            item.optJSONArray("unlockTestIds")?.let { tests ->
                                                for (testIndex in 0 until tests.length()) {
                                                    tests.optString(testIndex).trim()
                                                        .takeIf { id -> UnlockTestCatalog.schemeDefinitions.any { it.id == id } }
                                                        ?.let(::add)
                                                }
                                            }
                                        },
                                        tcpingSelectionMode = readSelectionMode(
                                            item,
                                            "tcping",
                                            SpeedTestSelectionMode.PERCENT
                                        ),
                                        tcpingSelectionValue = readSelectionValue(
                                            item,
                                            "tcping",
                                            legacyKey = "tcpingPercent",
                                            legacyDefault = 100
                                        ),
                                        urlTestSelectionMode = readSelectionMode(
                                            item,
                                            "urlTest",
                                            SpeedTestSelectionMode.PERCENT
                                        ),
                                        urlTestSelectionValue = readSelectionValue(
                                            item,
                                            "urlTest",
                                            legacyKey = "urlTestPercent",
                                            legacyDefault = 50
                                        ),
                                        bandwidthSelectionMode = readSelectionMode(
                                            item,
                                            "bandwidth",
                                            SpeedTestSelectionMode.PERCENT
                                        ),
                                        bandwidthSelectionValue = readSelectionValue(
                                            item,
                                            "bandwidth",
                                            legacyKey = "bandwidthPercent",
                                            legacyDefault = 50
                                        ),
                                        tcpingAutoDeleteTimeoutNodes = item.optBoolean(
                                            "tcpingAutoDeleteTimeoutNodes",
                                            preferences[AUTO_DELETE_TCPING_TIMEOUT_NODES]
                                                ?: io.github.HanJason233.chimera.AppConfig.AUTO_DELETE_TCPING_TIMEOUT_NODES
                                        ),
                                        urlTestAutoDeleteTimeoutNodes = item.optBoolean(
                                            "urlTestAutoDeleteTimeoutNodes",
                                            preferences[AUTO_DELETE_URL_TEST_TIMEOUT_NODES]
                                                ?: io.github.HanJason233.chimera.AppConfig.AUTO_DELETE_URL_TEST_TIMEOUT_NODES
                                        ),
                                        bandwidthAutoDeleteLowSpeedNodes = item.optBoolean(
                                            "bandwidthAutoDeleteLowSpeedNodes",
                                            preferences[AUTO_DELETE_LOW_BANDWIDTH_NODES]
                                                ?: io.github.HanJason233.chimera.AppConfig.AUTO_DELETE_LOW_BANDWIDTH_NODES
                                        ),
                                        bandwidthAutoDeleteThresholdMbps = item.optInt(
                                            "bandwidthAutoDeleteThresholdMbps",
                                            preferences[AUTO_DELETE_BANDWIDTH_THRESHOLD_MBPS]
                                                ?: io.github.HanJason233.chimera.AppConfig.AUTO_DELETE_BANDWIDTH_THRESHOLD_MBPS
                                        ),
                                        tcpingTimeoutMs = item.optLong("tcpingTimeoutMs", 1_000L),
                                        urlTestWarmupTimeoutMs = item.optLong(
                                            "urlTestWarmupTimeoutMs",
                                            io.github.HanJason233.chimera.AppConfig.URL_TEST_WARMUP_TIMEOUT
                                        ),
                                        urlTestMeasurementTimeoutMs = item.optLong(
                                            "urlTestMeasurementTimeoutMs",
                                            io.github.HanJason233.chimera.AppConfig.URL_TEST_MEASUREMENT_TIMEOUT
                                        ),
                                        urlTestUrl = item.optString("urlTestUrl", UrlTestTargets.DEFAULT_URL),
                                        urlTestBackupUrl = item.optString(
                                            "urlTestBackupUrl",
                                            UrlTestTargets.DEFAULT_BACKUP_URL
                                        ),
                                        bandwidthTestDurationMs = item.optLong(
                                            "bandwidthTestDurationMs",
                                            3_000L
                                        ),
                                        bandwidthTestUrl = item.optString(
                                            "bandwidthTestUrl",
                                            io.github.HanJason233.chimera.AppConfig.SPEED_TEST_DOWNLOAD_URL
                                        ),
                                        tcpingConcurrencyMode = readConcurrencyMode(
                                            item,
                                            "tcpingConcurrencyMode"
                                        ),
                                        tcpingConcurrency = item.optInt("tcpingConcurrency", 16),
                                        urlTestConcurrencyMode = readConcurrencyMode(
                                            item,
                                            "urlTestConcurrencyMode"
                                        ),
                                        urlTestConcurrency = item.optInt("urlTestConcurrency", 10),
                                        bandwidthTestConcurrencyMode = readConcurrencyMode(
                                            item,
                                            "bandwidthTestConcurrencyMode"
                                        ),
                                        bandwidthTestConcurrency = item.optInt(
                                            "bandwidthTestConcurrency",
                                            1
                                        )
                                    )
                                )
                            )
                        }
                    }
                }.getOrNull()
            }

        val defaultScheme = stored?.firstOrNull { it.id == SpeedTestScheme.DEFAULT_ID }
            ?: SpeedTestScheme.default().copy(
                tcpingAutoDeleteTimeoutNodes = preferences[AUTO_DELETE_TCPING_TIMEOUT_NODES]
                    ?: io.github.HanJason233.chimera.AppConfig.AUTO_DELETE_TCPING_TIMEOUT_NODES,
                urlTestAutoDeleteTimeoutNodes = preferences[AUTO_DELETE_URL_TEST_TIMEOUT_NODES]
                    ?: io.github.HanJason233.chimera.AppConfig.AUTO_DELETE_URL_TEST_TIMEOUT_NODES,
                bandwidthAutoDeleteLowSpeedNodes = preferences[AUTO_DELETE_LOW_BANDWIDTH_NODES]
                    ?: io.github.HanJason233.chimera.AppConfig.AUTO_DELETE_LOW_BANDWIDTH_NODES,
                bandwidthAutoDeleteThresholdMbps = preferences[AUTO_DELETE_BANDWIDTH_THRESHOLD_MBPS]
                    ?: io.github.HanJason233.chimera.AppConfig.AUTO_DELETE_BANDWIDTH_THRESHOLD_MBPS
            )
        val custom = stored.orEmpty()
            .filterNot { it.id == SpeedTestScheme.DEFAULT_ID }
            .filterNot { it.builtIn }
            .distinctBy(SpeedTestScheme::id)
        return listOf(defaultScheme.copy(builtIn = true)) + custom
    }

    private fun readSelectionMode(
        item: JSONObject,
        prefix: String,
        default: SpeedTestSelectionMode
    ): SpeedTestSelectionMode = runCatching {
        SpeedTestSelectionMode.valueOf(item.optString("${prefix}SelectionMode"))
    }.getOrDefault(default)

    private fun readSelectionValue(
        item: JSONObject,
        prefix: String,
        legacyKey: String,
        legacyDefault: Int
    ): Int = item.optInt(
        "${prefix}SelectionValue",
        item.optInt(legacyKey, legacyDefault)
    )

    private fun readConcurrencyMode(item: JSONObject, key: String): SpeedTestSelectionMode = runCatching {
        SpeedTestSelectionMode.valueOf(item.optString(key))
    }.getOrDefault(SpeedTestSelectionMode.COUNT)

    private fun normalizeSelectionValue(
        mode: SpeedTestSelectionMode,
        value: Int
    ): Int = when (mode) {
        SpeedTestSelectionMode.PERCENT -> value.coerceIn(1, 100)
        SpeedTestSelectionMode.COUNT -> value.coerceAtLeast(1)
    }

    private fun normalizeSpeedTestScheme(scheme: SpeedTestScheme): SpeedTestScheme {
        // 规范空名称，同时保留用户对方案名称的自定义修改。
        val trimmedName = scheme.name.trim()
        val normalizedName = when {
            scheme.id == SpeedTestScheme.DEFAULT_ID && trimmedName.isBlank() -> "默认测速"
            else -> trimmedName.ifBlank { if (scheme.builtIn) "测速方案" else "我的方案" }
        }
        return scheme.copy(
            name = normalizedName,
            unlockTestIds = scheme.unlockTestIds.intersect(
                UnlockTestCatalog.schemeDefinitions.mapTo(mutableSetOf()) { it.id }
            ),
            tcpingSelectionValue = normalizeSelectionValue(
                scheme.tcpingSelectionMode,
                scheme.tcpingSelectionValue
            ),
            urlTestSelectionValue = normalizeSelectionValue(
                scheme.urlTestSelectionMode,
                scheme.urlTestSelectionValue
            ),
            bandwidthSelectionValue = normalizeSelectionValue(
                scheme.bandwidthSelectionMode,
                scheme.bandwidthSelectionValue
            ),
            bandwidthAutoDeleteThresholdMbps = scheme.bandwidthAutoDeleteThresholdMbps.coerceIn(0, 100_000),
            tcpingTimeoutMs = scheme.tcpingTimeoutMs.coerceAtLeast(500L),
            urlTestWarmupTimeoutMs = scheme.urlTestWarmupTimeoutMs.coerceIn(500L, 60_000L),
            urlTestMeasurementTimeoutMs = scheme.urlTestMeasurementTimeoutMs.coerceIn(500L, 60_000L),
            urlTestUrl = UrlTestTargets.normalize(scheme.urlTestUrl) ?: UrlTestTargets.DEFAULT_URL,
            urlTestBackupUrl = UrlTestTargets.normalizeOptional(scheme.urlTestBackupUrl) ?: "",
            bandwidthTestDurationMs = scheme.bandwidthTestDurationMs.coerceAtLeast(1_000L),
            bandwidthTestUrl = BandwidthTestTargets.normalize(scheme.bandwidthTestUrl)
                ?: io.github.HanJason233.chimera.AppConfig.SPEED_TEST_DOWNLOAD_URL,
            tcpingConcurrency = normalizeSelectionValue(
                scheme.tcpingConcurrencyMode,
                scheme.tcpingConcurrency
            ).coerceAtMost(if (scheme.tcpingConcurrencyMode == SpeedTestSelectionMode.COUNT) 512 else 100),
            urlTestConcurrency = normalizeSelectionValue(
                scheme.urlTestConcurrencyMode,
                scheme.urlTestConcurrency
            ).coerceAtMost(if (scheme.urlTestConcurrencyMode == SpeedTestSelectionMode.COUNT) 128 else 100),
            bandwidthTestConcurrency = normalizeSelectionValue(
                scheme.bandwidthTestConcurrencyMode,
                scheme.bandwidthTestConcurrency
            ).coerceAtMost(if (scheme.bandwidthTestConcurrencyMode == SpeedTestSelectionMode.COUNT) 3 else 100)
        )
    }

    private fun encodeSpeedTestSchemes(schemes: List<SpeedTestScheme>): String {
        val array = JSONArray()
        schemes.distinctBy(SpeedTestScheme::id).forEach { scheme ->
            val normalized = normalizeSpeedTestScheme(scheme)
            array.put(
                JSONObject().apply {
                    put("id", normalized.id)
                    if (normalized.id == SpeedTestScheme.DEFAULT_ID) {
                        put("defaultsVersion", DEFAULT_SPEED_TEST_PRESET_REVISION)
                    }
                    put("name", normalized.name)
                    put("builtIn", normalized.builtIn)
                    put("tcpingEnabled", normalized.tcpingEnabled)
                    put("urlTestEnabled", normalized.urlTestEnabled)
                    put("bandwidthEnabled", normalized.bandwidthEnabled)
                    put("linkTypeTestEnabled", normalized.linkTypeTestEnabled)
                    put("unlockTestsEnabled", normalized.unlockTestsEnabled)
                    put("unlockTestIds", JSONArray().apply {
                        normalized.unlockTestIds.forEach(::put)
                    })
                    put("tcpingSelectionMode", normalized.tcpingSelectionMode.name)
                    put("tcpingSelectionValue", normalized.tcpingSelectionValue)
                    put("urlTestSelectionMode", normalized.urlTestSelectionMode.name)
                    put("urlTestSelectionValue", normalized.urlTestSelectionValue)
                    put("bandwidthSelectionMode", normalized.bandwidthSelectionMode.name)
                    put("bandwidthSelectionValue", normalized.bandwidthSelectionValue)
                    put("tcpingAutoDeleteTimeoutNodes", normalized.tcpingAutoDeleteTimeoutNodes)
                    put("urlTestAutoDeleteTimeoutNodes", normalized.urlTestAutoDeleteTimeoutNodes)
                    put("bandwidthAutoDeleteLowSpeedNodes", normalized.bandwidthAutoDeleteLowSpeedNodes)
                    put("bandwidthAutoDeleteThresholdMbps", normalized.bandwidthAutoDeleteThresholdMbps)
                    // Keep the legacy percentage keys so older builds fall back to a safe full test.
                    put(
                        "tcpingPercent",
                        normalized.tcpingSelectionValue.takeIf {
                            normalized.tcpingSelectionMode == SpeedTestSelectionMode.PERCENT
                        } ?: 100
                    )
                    put(
                        "urlTestPercent",
                        normalized.urlTestSelectionValue.takeIf {
                            normalized.urlTestSelectionMode == SpeedTestSelectionMode.PERCENT
                        } ?: 100
                    )
                    put(
                        "bandwidthPercent",
                        normalized.bandwidthSelectionValue.takeIf {
                            normalized.bandwidthSelectionMode == SpeedTestSelectionMode.PERCENT
                        } ?: 100
                    )
                    put("tcpingTimeoutMs", normalized.tcpingTimeoutMs)
                    // Keep the old key for downgrade compatibility.
                    put("urlTestTimeoutMs", normalized.urlTestWarmupTimeoutMs)
                    put("urlTestWarmupTimeoutMs", normalized.urlTestWarmupTimeoutMs)
                    put("urlTestMeasurementTimeoutMs", normalized.urlTestMeasurementTimeoutMs)
                    put("urlTestUrl", normalized.urlTestUrl)
                    put("urlTestBackupUrl", normalized.urlTestBackupUrl)
                    put("bandwidthTestDurationMs", normalized.bandwidthTestDurationMs)
                    put("bandwidthTestUrl", normalized.bandwidthTestUrl)
                    put("tcpingConcurrencyMode", normalized.tcpingConcurrencyMode.name)
                    put("tcpingConcurrency", normalized.tcpingConcurrency)
                    put("urlTestConcurrencyMode", normalized.urlTestConcurrencyMode.name)
                    put("urlTestConcurrency", normalized.urlTestConcurrency)
                    put("bandwidthTestConcurrencyMode", normalized.bandwidthTestConcurrencyMode.name)
                    put("bandwidthTestConcurrency", normalized.bandwidthTestConcurrency)
                }
            )
        }
        return array.toString()
    }

    private fun decodeSkinSchemes(preferences: Preferences): List<SkinScheme> {
        fun defaultSchemeWithLegacyNodeDisplaySettings(): SkinScheme = SkinScheme.default().copy(
            showNodeProtocol = preferences[NODE_LABEL_SHOW_PROTOCOL] ?: false,
            showNodeSecurity = preferences[NODE_LABEL_SHOW_SECURITY] ?: false,
            showNodeTransport = preferences[NODE_LABEL_SHOW_TRANSPORT] ?: false,
            showNodeCore = preferences[NODE_LABEL_SHOW_CORE] ?: false
        )

        val storedJson = preferences[SKIN_SCHEMES_JSON]
        if (!storedJson.isNullOrBlank()) {
            val storedSchemes = runCatching {
                val array = JSONArray(storedJson)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val id = item.optString("id").trim()
                        if (id.isBlank()) continue
                        // 旧版统一旋转开关只作用于连接中和断开中，读取时映射到这两个状态。
                        val legacyHomeIconRotationEnabled = item.optBoolean(
                            "homeIconRotationEnabled",
                            false
                        )
                        val defaultsVersion = item.optInt("defaultsVersion", 0)
                        if (id == SkinScheme.DEFAULT_SCHEME_ID &&
                            defaultsVersion < DEFAULT_SKIN_PARTICLE_PRESET_REVISION
                        ) {
                            item.put("homeIconEffectEnabled", SkinScheme.default().homeIconEffectEnabled)
                        }
                        if (id == SkinScheme.DEFAULT_SCHEME_ID &&
                            defaultsVersion < DEFAULT_SKIN_PRESET_REVISION
                        ) {
                            val defaultScheme = SkinScheme.default()
                            // Revert the just introduced defaults when upgrading from revision 2.
                            if (defaultsVersion >= 2 &&
                                item.optString("homeDisconnectedIconEffect") ==
                                HomeIconMotionEffect.BREATHE.storageName
                            ) {
                                item.put(
                                    "homeDisconnectedIconEffect",
                                    HomeIconMotionEffect.NONE.storageName
                                )
                            }
                            if (defaultsVersion >= 2 &&
                                item.optString("homeConnectedIconEffect") ==
                                HomeIconMotionEffect.ROTATE.storageName
                            ) {
                                item.put(
                                    "homeConnectedIconEffect",
                                    HomeIconMotionEffect.NONE.storageName
                                )
                            }
                            if (item.optString("homeConnectingIconEffect") in setOf("", "none")) {
                                item.put(
                                    "homeConnectingIconEffect",
                                    defaultScheme.homeConnectingIconEffect.storageName
                                )
                            }
                            if (item.optString("homeDisconnectingIconEffect") in setOf("", "none") &&
                                !legacyHomeIconRotationEnabled
                            ) {
                                item.put(
                                    "homeDisconnectingIconEffect",
                                    defaultScheme.homeDisconnectingIconEffect.storageName
                                )
                            }
                        }
                        // 旧版只有统一粒子开关，新字段缺失时沿用旧设置作为四种状态的初始值。
                        val legacyHomeIconEffectEnabled = item.optBoolean(
                            "homeIconEffectEnabled",
                            id != SkinScheme.DEFAULT_SCHEME_ID
                        )
                        add(
                            SkinScheme(
                                id = id,
                                name = item.optString("name", "我的主题")
                                    .trim()
                                    .ifBlank { "我的主题" },
                                appDisplayName = item.optString(
                                    "appDisplayName",
                                    SkinScheme.DEFAULT_APP_DISPLAY_NAME
                                ).trim().ifBlank { SkinScheme.DEFAULT_APP_DISPLAY_NAME }
                                    .let { if (it == "Firefly") "Chimera" else it },
                                appIconPresetId = item.optString(
                                    "appIconPresetId",
                                    SkinScheme.DEFAULT_APP_ICON_PRESET_ID
                                ).trim().ifBlank { SkinScheme.DEFAULT_APP_ICON_PRESET_ID },
                                baseColorArgb = normalizeSkinColor(
                                    item.optLong(
                                        "baseColorArgb",
                                        SkinSettings.DEFAULT_BASE_COLOR_ARGB
                                    )
                                ),
                                showNodeProtocol = item.optBoolean(
                                    "showNodeProtocol",
                                    preferences[NODE_LABEL_SHOW_PROTOCOL] ?: false
                                ),
                                showNodeSecurity = item.optBoolean(
                                    "showNodeSecurity",
                                    preferences[NODE_LABEL_SHOW_SECURITY] ?: false
                                ),
                                showNodeTransport = item.optBoolean(
                                    "showNodeTransport",
                                    preferences[NODE_LABEL_SHOW_TRANSPORT] ?: false
                                ),
                                showNodeCore = item.optBoolean(
                                    "showNodeCore",
                                    preferences[NODE_LABEL_SHOW_CORE] ?: false
                                ),
                                themeMode = SkinThemeMode.fromStorage(
                                    item.optString("themeMode").takeIf { it.isNotBlank() }
                                ),
                                navigationStyle = HomeNavigationStyle.fromStorage(
                                    item.optString("navigationStyle").takeIf { it.isNotBlank() }
                                ),
                                dialogMaterialVersion = DialogMaterialVersion.fromStorage(
                                    item.optString("dialogMaterialVersion").takeIf { it.isNotBlank() }
                                ),
                                monetEnabled = item.optBoolean("monetEnabled", false),
                                customImagePath = item.optString("customImagePath")
                                    .takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() },
                                homeDisconnectedImagePath = item.optString("homeDisconnectedImagePath")
                                    .takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() },
                                homeConnectingImagePath = item.optString("homeConnectingImagePath")
                                    .takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() },
                                homeDisconnectingImagePath = item.optString("homeDisconnectingImagePath")
                                    .takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() },
                                homeConnectedImagePath = item.optString("homeConnectedImagePath")
                                    .takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() },
                                homeIconEffect = HomeIconEffect.fromStorage(
                                    item.optString("homeIconEffect").takeIf { it.isNotBlank() }
                                ),
                                homeIconEffectEnabled = item.optBoolean(
                                    "homeIconEffectEnabled",
                                    id != SkinScheme.DEFAULT_SCHEME_ID
                                ),
                                homeDisconnectedParticleEffectEnabled = item.optBoolean(
                                    "homeDisconnectedParticleEffectEnabled",
                                    legacyHomeIconEffectEnabled
                                ),
                                homeConnectingParticleEffectEnabled = item.optBoolean(
                                    "homeConnectingParticleEffectEnabled",
                                    legacyHomeIconEffectEnabled
                                ),
                                homeDisconnectingParticleEffectEnabled = item.optBoolean(
                                    "homeDisconnectingParticleEffectEnabled",
                                    legacyHomeIconEffectEnabled
                                ),
                                homeConnectedParticleEffectEnabled = item.optBoolean(
                                    "homeConnectedParticleEffectEnabled",
                                    legacyHomeIconEffectEnabled
                                ),
                                homeDisconnectedIconEffect = HomeIconMotionEffect.fromStorage(
                                    item.optString("homeDisconnectedIconEffect")
                                        .takeIf { it.isNotBlank() }
                                ),
                                homeConnectingIconEffect = HomeIconMotionEffect.fromStorage(
                                        item.optString("homeConnectingIconEffect")
                                            .takeIf { it.isNotBlank() }
                                        ?: if (legacyHomeIconRotationEnabled) {
                                            HomeIconMotionEffect.ROTATE.storageName
                                        } else {
                                            null
                                        }
                                ),
                                homeDisconnectingIconEffect = HomeIconMotionEffect.fromStorage(
                                        item.optString("homeDisconnectingIconEffect")
                                            .takeIf { it.isNotBlank() }
                                        ?: if (legacyHomeIconRotationEnabled) {
                                            HomeIconMotionEffect.ROTATE.storageName
                                        } else {
                                            null
                                        }
                                ),
                                homeConnectedIconEffect = HomeIconMotionEffect.fromStorage(
                                    item.optString("homeConnectedIconEffect")
                                        .takeIf { it.isNotBlank() }
                                )
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
            val defaultScheme = storedSchemes.firstOrNull { it.isDefault }
                ?: defaultSchemeWithLegacyNodeDisplaySettings()
            return listOf(defaultScheme) + storedSchemes
                .filterNot { it.isDefault }
                .distinctBy { it.id }
        }

        return listOf(defaultSchemeWithLegacyNodeDisplaySettings())
    }

    private fun encodeSkinSchemes(schemes: List<SkinScheme>): String {
        val array = JSONArray()
        schemes
            .distinctBy { it.id }
            .forEach { scheme ->
                array.put(
                    JSONObject().apply {
                        put("id", scheme.id)
                        if (scheme.isDefault) put("defaultsVersion", DEFAULT_SKIN_PRESET_REVISION)
                        put("name", scheme.name)
                        put("appDisplayName", scheme.appDisplayName)
                        put("appIconPresetId", scheme.appIconPresetId)
                        put("baseColorArgb", normalizeSkinColor(scheme.baseColorArgb))
                        put("showNodeProtocol", scheme.showNodeProtocol)
                        put("showNodeSecurity", scheme.showNodeSecurity)
                        put("showNodeTransport", scheme.showNodeTransport)
                        put("showNodeCore", scheme.showNodeCore)
                        put("themeMode", scheme.themeMode.storageName)
                        put("navigationStyle", scheme.navigationStyle.storageName)
                        put("dialogMaterialVersion", scheme.dialogMaterialVersion.storageName)
                        put("monetEnabled", scheme.monetEnabled)
                        scheme.customImagePath?.let { put("customImagePath", it) }
                        scheme.homeDisconnectedImagePath?.let { put("homeDisconnectedImagePath", it) }
                        scheme.homeConnectingImagePath?.let { put("homeConnectingImagePath", it) }
                        scheme.homeDisconnectingImagePath?.let { put("homeDisconnectingImagePath", it) }
                        scheme.homeConnectedImagePath?.let { put("homeConnectedImagePath", it) }
                        put("homeIconEffect", scheme.homeIconEffect.storageName)
                        put("homeIconEffectEnabled", scheme.homeIconEffectEnabled)
                        put("homeDisconnectedIconEffect", scheme.homeDisconnectedIconEffect.storageName)
                        put("homeConnectingIconEffect", scheme.homeConnectingIconEffect.storageName)
                        put("homeDisconnectingIconEffect", scheme.homeDisconnectingIconEffect.storageName)
                        put("homeConnectedIconEffect", scheme.homeConnectedIconEffect.storageName)
                        put("homeDisconnectedParticleEffectEnabled", scheme.homeDisconnectedParticleEffectEnabled)
                        put("homeConnectingParticleEffectEnabled", scheme.homeConnectingParticleEffectEnabled)
                        put("homeDisconnectingParticleEffectEnabled", scheme.homeDisconnectingParticleEffectEnabled)
                        put("homeConnectedParticleEffectEnabled", scheme.homeConnectedParticleEffectEnabled)
                    }
                )
            }
        return array.toString()
    }

}
