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
import io.github.HanJason233.chimera.data.model.DnsDomainStrategy
import io.github.HanJason233.chimera.data.model.DnsSettings
import io.github.HanJason233.chimera.data.model.IpAddressFamily
import io.github.HanJason233.chimera.data.model.IpAddressProbeSource
import io.github.HanJason233.chimera.data.model.IpAddressTestSettings
import io.github.HanJason233.chimera.data.model.IpFamilyRoutingSettings
import io.github.HanJason233.chimera.data.model.HomeModuleSettings
import io.github.HanJason233.chimera.data.model.IPv6RoutingMode
import io.github.HanJason233.chimera.data.model.toIpFamilyRoutingSettings
import io.github.HanJason233.chimera.data.model.toLegacyIPv6RoutingMode
import io.github.HanJason233.chimera.data.model.LanInboundSettings
import io.github.HanJason233.chimera.data.model.FailoverDetectionSettings
import io.github.HanJason233.chimera.data.model.FailoverSettings
import io.github.HanJason233.chimera.data.model.FailoverTransferSettings
import io.github.HanJason233.chimera.data.model.FailoverTransferStrategy
import io.github.HanJason233.chimera.data.model.FailoverProbeStepSettings
import io.github.HanJason233.chimera.data.model.NodeGroupKey
import io.github.HanJason233.chimera.data.model.ManualGroupTestStage
import io.github.HanJason233.chimera.data.model.NodeTestResultDisplaySelection
import io.github.HanJason233.chimera.data.model.NodeType
import io.github.HanJason233.chimera.data.model.PerAppInboundSettings
import io.github.HanJason233.chimera.data.model.ProtocolCoreSettings
import io.github.HanJason233.chimera.data.model.ProxyCore
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.data.model.RegionLookupMethod
import io.github.HanJason233.chimera.data.model.SkinSettings
import io.github.HanJason233.chimera.data.model.SpeedTestSettings
import io.github.HanJason233.chimera.data.model.SmartRoutingRule
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleAction
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleType
import io.github.HanJason233.chimera.data.model.SmartRoutingDomainStrategy
import io.github.HanJason233.chimera.data.model.SmartRoutingSettings
import io.github.HanJason233.chimera.data.model.SmartRoutingRulesCodec
import io.github.HanJason233.chimera.data.model.decodeRoutingRuleValues
import io.github.HanJason233.chimera.feature.nodegrouping.model.NodeGroupingMode
import io.github.HanJason233.chimera.util.CountryCodeResolver
import java.util.UUID

data class VpnRuntimeSettings(
    val proxyMode: ProxyMode,
    val ipFamilySettings: IpFamilyRoutingSettings,
    val hysteriaUpMbps: Int,
    val hysteriaDownMbps: Int,
    val protocolCoreSettings: ProtocolCoreSettings,
    val lanInbound: LanInboundSettings = LanInboundSettings(),
    val dnsSettings: DnsSettings = DnsSettings(),
    val smartRoutingSettings: SmartRoutingSettings = SmartRoutingSettings(),
    val perAppInboundSettings: PerAppInboundSettings = PerAppInboundSettings()
) {
    @Deprecated("Use ipFamilySettings")
    val ipv6RoutingMode: IPv6RoutingMode
        get() = ipFamilySettings.toLegacyIPv6RoutingMode()

    @Deprecated("Use the IpFamilyRoutingSettings constructor")
    constructor(
        proxyMode: ProxyMode,
        ipv6RoutingMode: IPv6RoutingMode,
        hysteriaUpMbps: Int,
        hysteriaDownMbps: Int,
        protocolCoreSettings: ProtocolCoreSettings,
        lanInbound: LanInboundSettings = LanInboundSettings(),
        dnsSettings: DnsSettings = DnsSettings(),
        smartRoutingSettings: SmartRoutingSettings = SmartRoutingSettings(),
        perAppInboundSettings: PerAppInboundSettings = PerAppInboundSettings()
    ) : this(
        proxyMode,
        ipv6RoutingMode.toIpFamilyRoutingSettings(),
        hysteriaUpMbps,
        hysteriaDownMbps,
        protocolCoreSettings,
        lanInbound,
        dnsSettings,
        smartRoutingSettings,
        perAppInboundSettings
    )
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(
    context: Context,
    private val store: DataStore<Preferences> = context.dataStore
) {
    private val appContext = context.applicationContext
    private val initialRoutingRules: List<SmartRoutingRule> by lazy {
        runCatching {
            appContext.assets.open("routing-presets/china-whitelist.json")
                .bufferedReader(Charsets.UTF_8).use { SmartRoutingRulesCodec.decode(it.readText()) }
        }.getOrDefault(emptyList())
    }

    private val initialGlobalRoutingRules: List<SmartRoutingRule> by lazy {
        runCatching {
            appContext.assets.open("routing-presets/global.json")
                .bufferedReader(Charsets.UTF_8).use { SmartRoutingRulesCodec.decode(it.readText()) }
        }.getOrDefault(emptyList())
    }
    
    companion object {
        const val DEFAULT_SERVICE_REQUEST_TIMEOUT_MS = 25_000L
        private const val AUTO_REFRESH_SOURCE_PREFIX = "auto_refresh_source_on_startup:"
        /** 默认测速预置与旧版主题数据迁移分别维护版本。 */
        internal const val DEFAULT_SPEED_TEST_PRESET_REVISION = 4
        internal const val DEFAULT_SKIN_PARTICLE_PRESET_REVISION = 1
        internal const val DEFAULT_SKIN_PRESET_REVISION = 3
        private const val MIN_SERVICE_REQUEST_TIMEOUT_MS = 5_000L
        private const val MAX_SERVICE_REQUEST_TIMEOUT_MS = 60_000L

        private val SELECTED_NODE_ID = stringPreferencesKey("selected_node_id")
        private val PROXY_MODE = stringPreferencesKey("proxy_mode")
        // Keep these persisted names so the terminology change preserves existing selections.
        private val PER_APP_INBOUND_ENABLED = booleanPreferencesKey("per_app_proxy_enabled")
        private val PER_APP_INBOUND_BYPASS_MODE = booleanPreferencesKey("per_app_proxy_bypass_mode")
        private val PER_APP_INBOUND_PACKAGES = stringSetPreferencesKey("per_app_proxy_packages")
        // 旧版分应用范围作为智能模式配置保留，全局模式另存一份。
        private val GLOBAL_PER_APP_INBOUND_ENABLED = booleanPreferencesKey("global_per_app_proxy_enabled")
        private val GLOBAL_PER_APP_INBOUND_BYPASS_MODE = booleanPreferencesKey("global_per_app_proxy_bypass_mode")
        private val GLOBAL_PER_APP_INBOUND_PACKAGES = stringSetPreferencesKey("global_per_app_proxy_packages")
        private val LAN_INBOUND_ENABLED = booleanPreferencesKey("lan_inbound_enabled")
        private val LAN_INBOUND_PORT = intPreferencesKey("lan_inbound_port")
        private val LAN_INBOUND_USERNAME = stringPreferencesKey("lan_inbound_username")
        private val LAN_INBOUND_PASSWORD = stringPreferencesKey("lan_inbound_password")
        private val LAN_INBOUND_PROXY_MODE = stringPreferencesKey("lan_inbound_proxy_mode")
        private val FAILOVER_DETECTION_ENABLED = booleanPreferencesKey("failover_detection_enabled")
        // 旧探测列表键用于迁移已有配置；旧字段用于兼容降级版本读取。
        private val FAILOVER_PROBE_STEPS = stringPreferencesKey("failover_probe_steps")
        private val FAILOVER_DETECTION_METHOD = stringPreferencesKey("failover_detection_method")
        private val FAILOVER_PROBE_URL = stringPreferencesKey("failover_probe_url")
        private val FAILOVER_PROBE_CONFIGS = stringPreferencesKey("failover_probe_configs")
        private val FAILOVER_INTERVAL_SECONDS = intPreferencesKey("failover_interval_seconds")
        private val FAILOVER_TIMEOUT_MS = longPreferencesKey("failover_timeout_ms")
        private val FAILOVER_CONSECUTIVE_FAILURES = intPreferencesKey("failover_consecutive_failures")
        private val FAILOVER_TRANSFER_STRATEGY = stringPreferencesKey("failover_transfer_target")
        private val FAILOVER_TRANSFER_NODE_IDS = stringPreferencesKey("failover_transfer_node_ids")
        internal val LEGACY_HOME_SPEED_TEST_SETTINGS_ID =
            stringPreferencesKey("home_long_press_speed_test_scheme_id")
        internal val LEGACY_GROUP_MANUAL_SPEED_TEST_SETTINGS_ID =
            stringPreferencesKey("group_manual_speed_test_scheme_id")
        internal val LEGACY_GROUP_MANUAL_SPEED_TEST_ENABLED =
            booleanPreferencesKey("group_manual_speed_test_enabled")
        internal val LEGACY_HOME_LONG_PRESS_SPEED_TEST_ENABLED =
            booleanPreferencesKey("home_long_press_speed_test_enabled")
        private val LEGACY_MANUAL_IP_ADDRESS_TEST_PANEL_ENABLED =
            booleanPreferencesKey("manual_ip_address_test_panel_enabled")
        
        // 订阅列表缓存
        private val SUBSCRIPTION_LIST_JSON = stringPreferencesKey("subscription_list_json")
        private val SUBSCRIPTION_LIST_SCOPE = stringPreferencesKey("subscription_list_scope")
        private val OFFICIAL_SUBSCRIPTION_VERSIONS =
            stringPreferencesKey("official_subscription_versions")
        private val OFFICIAL_SUBSCRIPTION_VERSIONS_SCOPE =
            stringPreferencesKey("official_subscription_versions_scope")
        
        // 旧版模式仅用于兼容读取，并迁移到新的地址族设置。
        private val LEGACY_IPV6_ROUTING_MODE = stringPreferencesKey("ipv6_routing_mode")
        private val IPV4_ROUTING_ENABLED = booleanPreferencesKey("ipv4_routing_enabled")
        private val IPV6_ROUTING_ENABLED = booleanPreferencesKey("ipv6_routing_enabled")
        private val IP_ROUTING_PREFERRED_FAMILY = stringPreferencesKey("ip_routing_preferred_family")

        // DNS 设置（与 NekoBox 的远程/直连及策略分组对应）
        private val DNS_REMOTE = stringPreferencesKey("dns_remote")
        private val DNS_REMOTE_STRATEGY = stringPreferencesKey("dns_remote_strategy")
        private val DNS_DIRECT = stringPreferencesKey("dns_direct")
        private val DNS_DIRECT_STRATEGY = stringPreferencesKey("dns_direct_strategy")
        private val DNS_SERVER_STRATEGY = stringPreferencesKey("dns_server_strategy")
        private val DNS_ROUTING_ENABLED = booleanPreferencesKey("dns_routing_enabled")
        private val DNS_FAKE_ENABLED = booleanPreferencesKey("dns_fake_enabled")
        private val SMART_ROUTING_RULES_JSON = stringPreferencesKey("smart_routing_rules_json")
        private val SMART_ROUTING_DOMAIN_STRATEGY =
            stringPreferencesKey("smart_routing_domain_strategy")
        private val GLOBAL_ROUTING_RULES_JSON = stringPreferencesKey("global_routing_rules_json")
        private val GLOBAL_ROUTING_DOMAIN_STRATEGY =
            stringPreferencesKey("global_routing_domain_strategy")
        
        // 自动化测试设置
        private val SELECTED_SUBSCRIPTION_SOURCE_ID = stringPreferencesKey("selected_subscription_source_id")
        private val SELECTED_COUNTRY_CODE = stringPreferencesKey("selected_country_code")
        private val COUNTRY_GROUPING_SOURCE_IDS =
            stringSetPreferencesKey("country_grouping_source_ids")
        private val WAREHOUSE_GROUPING_COUNTRY_CODES =
            stringSetPreferencesKey("warehouse_grouping_country_codes")
        private val NODE_GROUPING_MODE = stringPreferencesKey("node_grouping_mode")
        private val SELECTED_NODE_GROUP_ID = stringPreferencesKey("selected_node_group_id")
        private val NODE_LIST_TEST_RESULT_STAGE = stringPreferencesKey("node_list_test_result_stage")
        private val NODE_LIST_TEST_RUN_STARTED_AT = longPreferencesKey("node_list_test_run_started_at")
        private val TCPING_TEST_TIMEOUT_MS = longPreferencesKey("tcping_test_timeout_ms")
        // 请求超时保存在本机，首次获取后台配置时也能读取。
        private val SERVICE_REQUEST_TIMEOUT_MS = longPreferencesKey("service_request_timeout_ms")
        internal val SPEED_TEST_DOWNLOAD_URL = stringPreferencesKey("speed_test_download_url")
        private val SPEED_TEST_DURATION_MS = longPreferencesKey("speed_test_duration_ms")
        private val TCPING_CONCURRENCY = intPreferencesKey("tcping_concurrency")
        private val BANDWIDTH_TEST_CONCURRENCY = intPreferencesKey("bandwidth_test_concurrency")
        private val REGION_LOOKUP_METHODS = stringPreferencesKey("region_lookup_methods")
        private val HYSTERIA_BANDWIDTH_ENABLED = booleanPreferencesKey("hysteria_bandwidth_enabled")
        private val HYSTERIA_UP_MBPS = intPreferencesKey("hysteria_up_mbps")
        private val HYSTERIA_DOWN_MBPS = intPreferencesKey("hysteria_down_mbps")
        private val CORE_ASSIGNMENTS = stringSetPreferencesKey("protocol_core_assignments")
        // 迁移旧版全局节点标签设置；新值并入全局外观配置。
        internal val NODE_LABEL_SHOW_PROTOCOL = booleanPreferencesKey("node_label_show_protocol")
        internal val NODE_LABEL_SHOW_SECURITY = booleanPreferencesKey("node_label_show_security")
        internal val NODE_LABEL_SHOW_TRANSPORT = booleanPreferencesKey("node_label_show_transport")
        internal val NODE_LABEL_SHOW_CORE = booleanPreferencesKey("node_label_show_core")
        // 迁移旧版全局自动删除设置；新设置保存在各测试项中。
        internal val AUTO_DELETE_TCPING_TIMEOUT_NODES =
            booleanPreferencesKey("auto_delete_tcping_timeout_nodes")
        internal val AUTO_DELETE_URL_TEST_TIMEOUT_NODES =
            booleanPreferencesKey("auto_delete_url_test_timeout_nodes")
        internal val AUTO_DELETE_LOW_BANDWIDTH_NODES =
            booleanPreferencesKey("auto_delete_low_bandwidth_nodes")
        internal val AUTO_DELETE_BANDWIDTH_THRESHOLD_MBPS =
            intPreferencesKey("auto_delete_bandwidth_threshold_mbps")
        internal val SKIN_SETTINGS_JSON = stringPreferencesKey("skin_settings_json")
        internal val LEGACY_SKIN_SCHEMES_JSON = stringPreferencesKey("skin_schemes_json")
        internal val LEGACY_ACTIVE_SKIN_SCHEME_ID = stringPreferencesKey("active_skin_scheme_id")
        internal val LEGACY_SPEED_TEST_SETTINGS_JSON = stringPreferencesKey("speed_test_schemes_json")
        internal val HOME_SPEED_TEST_SETTINGS_JSON = stringPreferencesKey("home_speed_test_settings_json")
        internal val GROUP_MANUAL_SPEED_TEST_SETTINGS_JSON =
            stringPreferencesKey("group_manual_speed_test_settings_json")
        private val IP_ADDRESS_TEST_SETTINGS_JSON =
            stringPreferencesKey("ip_address_test_settings_json")
        private val HOME_MODULE_SETTINGS_JSON =
            stringPreferencesKey("home_module_settings_json")

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

    val perAppInboundSettings: Flow<PerAppInboundSettings> = perAppInboundSettings(ProxyMode.SMART)
    val globalPerAppInboundSettings: Flow<PerAppInboundSettings> = perAppInboundSettings(ProxyMode.GLOBAL)

    /** 按全局/智能模式分别读取 VPN 作用范围。 */
    fun perAppInboundSettings(mode: ProxyMode): Flow<PerAppInboundSettings> = store.data.map { preferences ->
        decodePerAppInboundSettings(preferences, mode)
    }

    val homeSpeedTestSettings: Flow<SpeedTestSettings> = store.data.map { preferences ->
        SpeedTestSettingsCodec.decodeSpeedTestSettings(
            preferences = preferences,
            settingsKey = HOME_SPEED_TEST_SETTINGS_JSON,
            legacySelectionKey = LEGACY_HOME_SPEED_TEST_SETTINGS_ID
        )
    }

    val groupManualSpeedTestSettings: Flow<SpeedTestSettings> = store.data.map { preferences ->
        SpeedTestSettingsCodec.decodeSpeedTestSettings(
            preferences = preferences,
            settingsKey = GROUP_MANUAL_SPEED_TEST_SETTINGS_JSON,
            legacySelectionKey = LEGACY_GROUP_MANUAL_SPEED_TEST_SETTINGS_ID
        )
    }

    /** 节点列表当前展示的测试项和本轮起始时间，进程重启后仍可恢复。 */
    val nodeTestResultDisplaySelection: Flow<NodeTestResultDisplaySelection> =
        store.data.map { preferences ->
            val stage = runCatching {
                ManualGroupTestStage.valueOf(
                    preferences[NODE_LIST_TEST_RESULT_STAGE].orEmpty()
                )
            }.getOrDefault(ManualGroupTestStage.TCPING)
            NodeTestResultDisplaySelection(
                stage = stage,
                runStartedAt = preferences[NODE_LIST_TEST_RUN_STARTED_AT] ?: 0L
            )
        }

    /** 局域网入站设置默认关闭，避免意外暴露代理端口。 */
    val lanInboundSettings: Flow<LanInboundSettings> = store.data.map { preferences ->
        decodeLanInboundSettings(preferences)
    }

    /** Detection policy and ordered transfer nodes are persisted independently. */
    val failoverSettings: Flow<FailoverSettings> = store.data.map { preferences ->
        decodeFailoverSettings(preferences)
    }

    suspend fun migrateLegacySpeedTestSettings() {
        store.edit { preferences -> SpeedTestSettingsCodec.migrateLegacySpeedTestSettings(preferences) }
    }

    suspend fun setHomeSpeedTestSettings(settings: SpeedTestSettings) {
        store.edit { preferences ->
            SpeedTestSettingsCodec.migrateLegacySpeedTestSettings(preferences)
            preferences[HOME_SPEED_TEST_SETTINGS_JSON] = SpeedTestSettingsCodec.encodeSpeedTestSettings(settings)
        }
    }

    suspend fun setGroupManualSpeedTestSettings(settings: SpeedTestSettings) {
        store.edit { preferences ->
            SpeedTestSettingsCodec.migrateLegacySpeedTestSettings(preferences)
            preferences[GROUP_MANUAL_SPEED_TEST_SETTINGS_JSON] = SpeedTestSettingsCodec.encodeSpeedTestSettings(settings)
        }
    }

    suspend fun setNodeTestResultDisplaySelection(
        selection: NodeTestResultDisplaySelection
    ) {
        store.edit { preferences ->
            preferences[NODE_LIST_TEST_RESULT_STAGE] = selection.stage.name
            preferences[NODE_LIST_TEST_RUN_STARTED_AT] = selection.runStartedAt
        }
    }

    /** 全局外观配置；旧版多主题数据在首次修改时迁移当前选中的主题。 */
    val skinSettings: Flow<SkinSettings> = store.data.map { preferences ->
        SkinSettingsCodec.decodeSkinSettings(preferences)
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

    suspend fun officialSubscriptionVersions(scope: String): Map<String, Long>? {
        val preferences = store.data.first()
        if (preferences[OFFICIAL_SUBSCRIPTION_VERSIONS_SCOPE] != scope) return null
        val encoded = preferences[OFFICIAL_SUBSCRIPTION_VERSIONS] ?: return null
        return runCatching {
            val json = JSONObject(encoded)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val subscriptionId = keys.next()
                    val version = json.optLong(subscriptionId, -1L)
                    if (version >= 0L) put(subscriptionId, version)
                }
            }
        }.getOrNull()
    }

    suspend fun setOfficialSubscriptionVersions(versions: Map<String, Long>, scope: String) {
        store.edit { preferences ->
            preferences[OFFICIAL_SUBSCRIPTION_VERSIONS] = JSONObject().apply {
                versions.forEach { (subscriptionId, version) -> put(subscriptionId, version.coerceAtLeast(0L)) }
            }.toString()
            preferences[OFFICIAL_SUBSCRIPTION_VERSIONS_SCOPE] = scope
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
        val mode = enumValueOrDefault(preferences[PROXY_MODE], ProxyMode.SMART)
        val hysteriaBandwidthEnabled = preferences[HYSTERIA_BANDWIDTH_ENABLED]
            ?: io.github.HanJason233.chimera.AppConfig.HYSTERIA_BANDWIDTH_ENABLED
        return VpnRuntimeSettings(
            proxyMode = mode,
            ipFamilySettings = decodeIpFamilySettings(preferences),
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
            // VPN 启动时原子读取当前模式对应的规则与应用范围。
            smartRoutingSettings = decodeSmartRoutingSettings(preferences, mode),
            perAppInboundSettings = decodePerAppInboundSettings(preferences, mode),
        )
    }

    suspend fun setPerAppInboundSettings(
        settings: PerAppInboundSettings,
        mode: ProxyMode = ProxyMode.SMART
    ) {
        val enabledKey = perAppEnabledKey(mode)
        val bypassModeKey = perAppBypassModeKey(mode)
        val packagesKey = perAppPackagesKey(mode)
        store.edit { preferences ->
            preferences[enabledKey] = settings.enabled
            preferences[bypassModeKey] = settings.bypassMode
            val cleanPackages = settings.selectedPackages.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            if (cleanPackages.isEmpty()) {
                preferences.remove(packagesKey)
            } else {
                preferences[packagesKey] = cleanPackages
            }
        }
    }

    private fun decodePerAppInboundSettings(
        preferences: Preferences,
        mode: ProxyMode
    ): PerAppInboundSettings {
        // 首次升级时旧版范围同时用于两种模式；全局配置首次保存后即与智能配置分离。
        val hasGlobalProfile = preferences[GLOBAL_PER_APP_INBOUND_ENABLED] != null ||
            preferences[GLOBAL_PER_APP_INBOUND_BYPASS_MODE] != null ||
            preferences[GLOBAL_PER_APP_INBOUND_PACKAGES] != null
        val storageMode = if (mode == ProxyMode.GLOBAL && !hasGlobalProfile) {
            ProxyMode.SMART
        } else {
            mode
        }
        return PerAppInboundSettings(
            enabled = preferences[perAppEnabledKey(storageMode)] ?: false,
            bypassMode = preferences[perAppBypassModeKey(storageMode)] ?: false,
            selectedPackages = preferences[perAppPackagesKey(storageMode)]?.toSet() ?: emptySet()
        )
    }

    private fun perAppEnabledKey(mode: ProxyMode) =
        if (mode == ProxyMode.GLOBAL) GLOBAL_PER_APP_INBOUND_ENABLED else PER_APP_INBOUND_ENABLED

    private fun perAppBypassModeKey(mode: ProxyMode) =
        if (mode == ProxyMode.GLOBAL) GLOBAL_PER_APP_INBOUND_BYPASS_MODE else PER_APP_INBOUND_BYPASS_MODE

    private fun perAppPackagesKey(mode: ProxyMode) =
        if (mode == ProxyMode.GLOBAL) GLOBAL_PER_APP_INBOUND_PACKAGES else PER_APP_INBOUND_PACKAGES

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

    private fun decodeFailoverSettings(preferences: Preferences): FailoverSettings {
        val transferStrategy = if (preferences.contains(FAILOVER_TRANSFER_STRATEGY)) {
            decodeFailoverTransferStrategy(preferences[FAILOVER_TRANSFER_STRATEGY])
        } else {
            // 将旧版备用节点列表迁移为列表中的第一个节点。
            decodeLegacyFailoverTransferStrategy(preferences[FAILOVER_TRANSFER_NODE_IDS])
        }
        // 将旧版单地址设置迁移为第一次探测地址，其余参数使用新版本默认值。
        val legacyProbeUrl = decodeFailoverProbeUrl(preferences)
        val probeSteps = decodeFailoverProbeSteps(
            serialized = preferences[FAILOVER_PROBE_CONFIGS],
            legacyFirstProbeUrl = legacyProbeUrl
        )
        return FailoverSettings(
            detection = FailoverDetectionSettings(
                enabled = preferences[FAILOVER_DETECTION_ENABLED] ?: false,
                firstProbe = probeSteps[0],
                secondProbe = probeSteps[1],
                thirdProbe = probeSteps[2]
            ),
            transfer = FailoverTransferSettings(transferStrategy)
        )
    }

    private fun decodeFailoverProbeSteps(
        serialized: String?,
        legacyFirstProbeUrl: String
    ): List<FailoverProbeStepSettings> {
        val defaults = FailoverDetectionSettings().probeSteps
        val array = runCatching { JSONArray(serialized.orEmpty()) }.getOrNull()
        return defaults.mapIndexed { index, default ->
            val fallback = if (index == 0) default.copy(probeUrl = legacyFirstProbeUrl) else default
            val item = array?.optJSONObject(index) ?: return@mapIndexed fallback
            val probeUrl = normalizeFailoverProbeUrl(item.optString("probeUrl")) ?: fallback.probeUrl
            FailoverProbeStepSettings(
                probeUrl = probeUrl,
                intervalSeconds = item.optInt("intervalSeconds", fallback.intervalSeconds).coerceIn(
                    FailoverDetectionSettings.MIN_PROBE_INTERVAL_SECONDS,
                    FailoverDetectionSettings.MAX_PROBE_INTERVAL_SECONDS
                ),
                timeoutMs = item.optLong("timeoutMs", fallback.timeoutMs).coerceIn(
                    FailoverDetectionSettings.MIN_PROBE_TIMEOUT_MS,
                    FailoverDetectionSettings.MAX_PROBE_TIMEOUT_MS
                )
            )
        }
    }

    private fun decodeFailoverTransferStrategy(serialized: String?): FailoverTransferStrategy? {
        val json = runCatching { JSONObject(serialized.orEmpty()) }.getOrNull() ?: return null
        val id = json.optString("id").trim().takeIf(String::isNotEmpty)
        return when (json.optString("type")) {
            "NODE", "SPECIFIED_NODE" -> id?.let(FailoverTransferStrategy::SpecifiedNode)
            "GROUP", "SPECIFIED_GROUP" -> id?.let { groupId ->
                NodeGroupKey.parse(groupId)?.let { FailoverTransferStrategy.SpecifiedGroup(it.value) }
            }
            "SECOND_BEST_TEST_RESULT" -> FailoverTransferStrategy.SecondBestTestResult
            else -> null
        }
    }

    private fun decodeLegacyFailoverTransferStrategy(serialized: String?): FailoverTransferStrategy? {
        val array = runCatching { JSONArray(serialized.orEmpty()) }.getOrNull() ?: return null
        val nodeId = array.optString(0).trim().takeIf { it.isNotEmpty() && it != "null" }
            ?: return null
        return FailoverTransferStrategy.SpecifiedNode(nodeId)
    }

    private fun encodeFailoverTransferStrategy(strategy: FailoverTransferStrategy?): String =
        strategy?.let { value ->
            JSONObject().apply {
                when (value) {
                    is FailoverTransferStrategy.SpecifiedNode -> {
                        put("type", "SPECIFIED_NODE")
                        put("id", value.nodeId)
                    }
                    is FailoverTransferStrategy.SpecifiedGroup -> {
                        put("type", "SPECIFIED_GROUP")
                        put("id", value.groupId)
                    }
                    FailoverTransferStrategy.SecondBestTestResult -> {
                        put("type", "SECOND_BEST_TEST_RESULT")
                    }
                }
            }.toString()
        }.orEmpty()

    suspend fun setFailoverDetection(settings: FailoverDetectionSettings) {
        val normalizedProbeSteps = settings.probeSteps.map { step ->
            val probeUrl = normalizeFailoverProbeUrl(step.probeUrl) ?: return
            step.copy(
                probeUrl = probeUrl,
                intervalSeconds = step.intervalSeconds.coerceIn(
                    FailoverDetectionSettings.MIN_PROBE_INTERVAL_SECONDS,
                    FailoverDetectionSettings.MAX_PROBE_INTERVAL_SECONDS
                ),
                timeoutMs = step.timeoutMs.coerceIn(
                    FailoverDetectionSettings.MIN_PROBE_TIMEOUT_MS,
                    FailoverDetectionSettings.MAX_PROBE_TIMEOUT_MS
                )
            )
        }
        store.edit { preferences ->
            preferences[FAILOVER_PROBE_CONFIGS] = JSONArray().apply {
                normalizedProbeSteps.forEach { step ->
                    put(
                        JSONObject()
                            .put("probeUrl", step.probeUrl)
                            .put("intervalSeconds", step.intervalSeconds)
                            .put("timeoutMs", step.timeoutMs)
                    )
                }
            }.toString()
            // 保留首项旧键，便于旧版本回退时继续读取探测地址。
            preferences[FAILOVER_PROBE_URL] = normalizedProbeSteps.first().probeUrl
            preferences.remove(FAILOVER_PROBE_STEPS)
            // 同步首项参数和三次失败阈值，兼容旧版本读取。
            preferences[FAILOVER_DETECTION_METHOD] = "HTTPS_PROBE"
            preferences[FAILOVER_CONSECUTIVE_FAILURES] =
                FailoverDetectionSettings.CONSECUTIVE_FAILURE_THRESHOLD
            preferences[FAILOVER_INTERVAL_SECONDS] = normalizedProbeSteps.first().intervalSeconds
            preferences[FAILOVER_TIMEOUT_MS] = normalizedProbeSteps.first().timeoutMs
        }
    }

    private fun decodeFailoverProbeUrl(preferences: Preferences): String {
        val array = runCatching { JSONArray(preferences[FAILOVER_PROBE_STEPS].orEmpty()) }
            .getOrNull()
        if (array != null) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (item.optString("method") != "HTTPS_PROBE") continue
                normalizeFailoverProbeUrl(item.optString("probeUrl"))?.let { return it }
            }
        }
        return normalizeFailoverProbeUrl(preferences[FAILOVER_PROBE_URL])
            ?: FailoverDetectionSettings.DEFAULT_FIRST_PROBE_URL
    }

    suspend fun setFailoverDetectionEnabled(enabled: Boolean) {
        store.edit { preferences -> preferences[FAILOVER_DETECTION_ENABLED] = enabled }
    }

    suspend fun setFailoverTransferStrategy(strategy: FailoverTransferStrategy?) {
        val normalizedStrategy = when (strategy) {
            is FailoverTransferStrategy.SpecifiedNode -> strategy.nodeId.trim()
                .takeIf(String::isNotEmpty)
                ?.let(FailoverTransferStrategy::SpecifiedNode)
            is FailoverTransferStrategy.SpecifiedGroup -> NodeGroupKey.parse(strategy.groupId)
                ?.let { FailoverTransferStrategy.SpecifiedGroup(it.value) }
            FailoverTransferStrategy.SecondBestTestResult -> strategy
            null -> null
        }
        val legacyNodeIds = JSONArray().apply {
            (normalizedStrategy as? FailoverTransferStrategy.SpecifiedNode)?.nodeId?.let { put(it) }
        }.toString()
        store.edit { preferences ->
            // 空字符串表示用户明确清除了策略，避免重新读取旧版节点列表。
            preferences[FAILOVER_TRANSFER_STRATEGY] = encodeFailoverTransferStrategy(normalizedStrategy)
            // 保留指定线路时的旧版读取兼容；其他策略无法映射到旧版节点列表。
            preferences[FAILOVER_TRANSFER_NODE_IDS] = legacyNodeIds
        }
    }

    private fun normalizeFailoverProbeUrl(value: String?): String? = runCatching {
        val uri = java.net.URI(value?.trim().orEmpty())
        value?.trim()?.takeIf {
            uri.scheme?.equals("https", ignoreCase = true) == true && !uri.host.isNullOrBlank()
        }
    }.getOrNull()

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

    private fun decodeSmartRoutingSettings(
        preferences: Preferences,
        mode: ProxyMode = ProxyMode.SMART
    ): SmartRoutingSettings {
        val rulesKey = routingRulesKey(mode)
        val strategyKey = routingStrategyKey(mode)
        val rawRules = preferences[rulesKey]
        val stored = rawRules?.takeIf(String::isNotBlank)?.let(::decodeSmartRoutingRules)
        val defaultRules = if (mode == ProxyMode.GLOBAL) {
            initialGlobalRoutingRules
        } else {
            initialRoutingRules
        }
        // Remove the old Chimera-only injected domestic rules. When they were the
        // entire stored list, migrate to v2rayNG's default white-list preset.
        val migratedRules = stored?.filterNot {
            it.type == SmartRoutingRuleType.DOMESTIC_DOMAIN ||
                it.type == SmartRoutingRuleType.DOMESTIC_IP ||
                it.id == "builtin_smart_domestic_domain" ||
                it.id == "builtin_smart_domestic_ip"
        }
        // 未保存规则时按模式加载默认预设；用户保存的空列表仍表示主动清空。
        val rules = when {
            rawRules.isNullOrBlank() -> defaultRules
            stored == null -> defaultRules
            stored.isEmpty() -> emptyList()
            migratedRules.isNullOrEmpty() -> defaultRules
            else -> migratedRules
        }
        val rawStrategy = preferences[strategyKey]
        return SmartRoutingSettings(
            rules = rules,
            domainStrategy = SmartRoutingDomainStrategy.entries.firstOrNull {
                it.name == rawStrategy || it.xrayValue == rawStrategy
            } ?: SmartRoutingDomainStrategy.AS_IS
        )
    }

    private fun routingRulesKey(mode: ProxyMode) =
        if (mode == ProxyMode.GLOBAL) GLOBAL_ROUTING_RULES_JSON else SMART_ROUTING_RULES_JSON

    private fun routingStrategyKey(mode: ProxyMode) =
        if (mode == ProxyMode.GLOBAL) GLOBAL_ROUTING_DOMAIN_STRATEGY else SMART_ROUTING_DOMAIN_STRATEGY

    private fun decodeSmartRoutingRules(json: String): List<SmartRoutingRule>? = runCatching {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim().ifBlank { UUID.randomUUID().toString() }
                val name = item.optString("name").trim()
                    .ifBlank { item.optString("remarks").trim().ifBlank { "导入规则" } }
                val type = runCatching { SmartRoutingRuleType.valueOf(item.optString("type")) }
                    .getOrDefault(SmartRoutingRuleType.CUSTOM_RULE)
                val outboundTag = item.optString("outboundTag")
                    .takeUnless { it.isBlank() || it == "null" }
                    ?: runCatching { SmartRoutingRuleAction.valueOf(item.optString("action")).configTag }
                        .getOrDefault(SmartRoutingRuleAction.PROXY.configTag)
                val action = runCatching { SmartRoutingRuleAction.valueOf(item.optString("action")) }
                    .getOrElse { SmartRoutingRuleAction.fromOutboundTag(outboundTag) }
                fun readValues(vararg keys: String): List<String> {
                    for (key in keys) {
                        val array = item.optJSONArray(key)
                        if (array != null) return buildList {
                            for (valueIndex in 0 until array.length()) {
                                addAll(decodeRoutingRuleValues(array.optString(valueIndex)))
                            }
                        }.distinct()
                        val value = item.optString(key).takeUnless { it == "null" }.orEmpty()
                        if (value.isNotBlank()) return decodeRoutingRuleValues(value)
                    }
                    return emptyList()
                }
                val values = readValues("values")
                val domains = readValues("domains", "domain").ifEmpty {
                    if (type == SmartRoutingRuleType.CUSTOM_DOMAIN) values else emptyList()
                }
                val ips = readValues("ips", "ip").ifEmpty {
                    if (type == SmartRoutingRuleType.CUSTOM_IP) values else emptyList()
                }
                add(
                    SmartRoutingRule(
                        id = id,
                        name = name,
                        type = type,
                        action = action,
                        outboundTag = outboundTag,
                        values = values,
                        enabled = item.optBoolean("enabled", true),
                        locked = item.optBoolean("locked", false),
                        domains = domains,
                        ips = ips,
                        ports = item.optString("port").takeUnless { it == "null" || it.isBlank() }
                            ?: item.optString("ports").takeUnless { it == "null" }.orEmpty(),
                        network = item.optString("network").takeUnless { it == "null" }.orEmpty(),
                        protocols = readValues("protocols", "protocol"),
                        processes = readValues("processes", "process")
                    )
                )
            }
        }
    }.getOrNull()

    private fun encodeSmartRoutingRules(rules: List<SmartRoutingRule>): String =
        JSONArray().apply {
            rules.forEach { rule ->
                put(JSONObject().apply {
                    put("id", rule.id)
                    put("name", rule.name)
                    put("type", rule.type.name)
                    put("action", rule.action.name)
                    put("outboundTag", rule.outboundTag)
                    put("values", JSONArray().apply { rule.values.forEach(::put) })
                    put("domains", JSONArray().apply { rule.domains.forEach(::put) })
                    put("ips", JSONArray().apply { rule.ips.forEach(::put) })
                    put("ports", rule.ports)
                    put("network", rule.network)
                    put("protocols", JSONArray().apply { rule.protocols.forEach(::put) })
                    put("process", JSONArray().apply { rule.processes.forEach(::put) })
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

    suspend fun updateSkinSettings(update: (SkinSettings) -> SkinSettings) {
        store.edit { preferences ->
            val settings = update(SkinSettingsCodec.decodeSkinSettings(preferences))
            val normalizedSettings = settings.copy(
                baseColorArgb = SkinSettingsCodec.normalizeSkinColor(settings.baseColorArgb)
            )
            preferences[SKIN_SETTINGS_JSON] = SkinSettingsCodec.encodeSkinSettings(normalizedSettings)
            preferences.remove(LEGACY_SKIN_SCHEMES_JSON)
            preferences.remove(LEGACY_ACTIVE_SKIN_SCHEME_ID)
            preferences.remove(NODE_LABEL_SHOW_PROTOCOL)
            preferences.remove(NODE_LABEL_SHOW_SECURITY)
            preferences.remove(NODE_LABEL_SHOW_TRANSPORT)
            preferences.remove(NODE_LABEL_SHOW_CORE)
        }
    }
    
    /** 注销账户时清除全部本地设置。 */
    suspend fun clearAll() {
        store.edit { preferences ->
            preferences.clear()
        }
    }
    
    val ipFamilySettings: Flow<IpFamilyRoutingSettings> = store.data.map(::decodeIpFamilySettings)

    val ipAddressTestSettings: Flow<IpAddressTestSettings> = store.data.map(::decodeIpAddressTestSettings)
    val homeModuleSettings: Flow<HomeModuleSettings> = store.data.map(::decodeHomeModuleSettings)

    suspend fun setIpAddressTestSettings(settings: IpAddressTestSettings) {
        store.edit { preferences ->
            // 写入新版检测设置前，先把旧版面板开关迁移到首页模块配置。
            if (HOME_MODULE_SETTINGS_JSON !in preferences) {
                preferences[HOME_MODULE_SETTINGS_JSON] =
                    encodeHomeModuleSettings(decodeHomeModuleSettings(preferences))
            }
            val normalized = settings.normalized()
            preferences[IP_ADDRESS_TEST_SETTINGS_JSON] = encodeIpAddressTestSettings(normalized)
            preferences.remove(LEGACY_MANUAL_IP_ADDRESS_TEST_PANEL_ENABLED)
        }
    }

    suspend fun setHomeModuleSettings(settings: HomeModuleSettings) {
        store.edit { preferences ->
            preferences[HOME_MODULE_SETTINGS_JSON] = encodeHomeModuleSettings(settings)
            preferences.remove(LEGACY_MANUAL_IP_ADDRESS_TEST_PANEL_ENABLED)
        }
    }

    suspend fun setIpFamilySettings(settings: IpFamilyRoutingSettings) {
        store.edit { preferences ->
            preferences.writeIpFamilySettings(settings)
        }
    }

    suspend fun setIpFamilyEnabled(family: IpAddressFamily, enabled: Boolean) {
        store.edit { preferences ->
            val current = decodeIpFamilySettings(preferences)
            val updated = when (family) {
                IpAddressFamily.IPV4 -> current.withIpv4Enabled(enabled)
                IpAddressFamily.IPV6 -> current.withIpv6Enabled(enabled)
            }
            preferences.writeIpFamilySettings(updated)
        }
    }

    suspend fun setPreferredIpFamily(family: IpAddressFamily) {
        store.edit { preferences ->
            val current = decodeIpFamilySettings(preferences)
            preferences.writeIpFamilySettings(current.withPreferredFamily(family))
        }
    }

    @Deprecated("Use ipFamilySettings")
    val ipv6RoutingMode: Flow<IPv6RoutingMode> = ipFamilySettings.map { it.toLegacyIPv6RoutingMode() }

    @Deprecated("Use setIpFamilySettings")
    suspend fun setIPv6RoutingMode(mode: IPv6RoutingMode) = setIpFamilySettings(mode.toIpFamilyRoutingSettings())

    private fun decodeIpFamilySettings(preferences: Preferences): IpFamilyRoutingSettings {
        val hasNewSettings = IPV4_ROUTING_ENABLED in preferences ||
            IPV6_ROUTING_ENABLED in preferences || IP_ROUTING_PREFERRED_FAMILY in preferences
        if (!hasNewSettings) {
            val legacyMode = preferences[LEGACY_IPV6_ROUTING_MODE]
                ?.let { runCatching { IPv6RoutingMode.valueOf(it) }.getOrNull() }
            return (legacyMode?.toIpFamilyRoutingSettings() ?: IpFamilyRoutingSettings.DEFAULT).normalized()
        }

        val preferredFamily = preferences[IP_ROUTING_PREFERRED_FAMILY]
            ?.let { runCatching { IpAddressFamily.valueOf(it) }.getOrNull() }
            ?: IpAddressFamily.IPV4
        return IpFamilyRoutingSettings(
            ipv4Enabled = preferences[IPV4_ROUTING_ENABLED] ?: true,
            ipv6Enabled = preferences[IPV6_ROUTING_ENABLED] ?: false,
            preferredFamily = preferredFamily
        ).normalized()
    }

    private fun MutablePreferences.writeIpFamilySettings(settings: IpFamilyRoutingSettings) {
        val normalized = settings.normalized()
        this[IPV4_ROUTING_ENABLED] = normalized.ipv4Enabled
        this[IPV6_ROUTING_ENABLED] = normalized.ipv6Enabled
        this[IP_ROUTING_PREFERRED_FAMILY] = normalized.preferredFamily.name
        remove(LEGACY_IPV6_ROUTING_MODE)
    }

    private fun decodeIpAddressTestSettings(preferences: Preferences): IpAddressTestSettings {
        val defaults = IpAddressTestSettings.DEFAULT
        val json = runCatching {
            JSONObject(preferences[IP_ADDRESS_TEST_SETTINGS_JSON].orEmpty())
        }.getOrNull() ?: return defaults
        val enabledSources = json.optJSONArray("enabledProbeSources")?.let { array ->
            buildSet {
                for (index in 0 until array.length()) {
                    runCatching { IpAddressProbeSource.valueOf(array.optString(index)) }
                        .getOrNull()
                        ?.let(::add)
                }
            }
        } ?: defaults.enabledProbeSources
        return IpAddressTestSettings(
            ipv4Enabled = json.optBoolean("ipv4Enabled", defaults.ipv4Enabled),
            ipv6Enabled = json.optBoolean("ipv6Enabled", defaults.ipv6Enabled),
            enabledProbeSources = enabledSources,
            timeoutSeconds = json.optInt("timeoutSeconds", defaults.timeoutSeconds)
        ).normalized()
    }

    private fun encodeIpAddressTestSettings(settings: IpAddressTestSettings): String =
        JSONObject().apply {
            put("ipv4Enabled", settings.ipv4Enabled)
            put("ipv6Enabled", settings.ipv6Enabled)
            put("enabledProbeSources", JSONArray().apply {
                IpAddressProbeSource.entries
                    .filter(settings.enabledProbeSources::contains)
                    .forEach { put(it.name) }
            })
            put("timeoutSeconds", settings.timeoutSeconds)
        }.toString()

    private fun decodeHomeModuleSettings(preferences: Preferences): HomeModuleSettings {
        val legacyIpPanelVisible = runCatching {
            JSONObject(preferences[IP_ADDRESS_TEST_SETTINGS_JSON].orEmpty())
                .optBoolean(
                    "showOnHome",
                    preferences[LEGACY_MANUAL_IP_ADDRESS_TEST_PANEL_ENABLED]
                        ?: HomeModuleSettings.DEFAULT.showIpAddressTest
                )
        }.getOrElse {
            preferences[LEGACY_MANUAL_IP_ADDRESS_TEST_PANEL_ENABLED]
                ?: HomeModuleSettings.DEFAULT.showIpAddressTest
        }
        val defaults = HomeModuleSettings.DEFAULT.copy(showIpAddressTest = legacyIpPanelVisible)
        val json = runCatching {
            JSONObject(preferences[HOME_MODULE_SETTINGS_JSON].orEmpty())
        }.getOrNull() ?: return defaults
        return HomeModuleSettings(
            showIpAddressTest = json.optBoolean("showIpAddressTest", defaults.showIpAddressTest),
            showSelectedGroup = json.optBoolean("showSelectedGroup", defaults.showSelectedGroup),
            showSelectedNode = json.optBoolean("showSelectedNode", defaults.showSelectedNode)
        )
    }

    private fun encodeHomeModuleSettings(settings: HomeModuleSettings): String =
        JSONObject().apply {
            put("showIpAddressTest", settings.showIpAddressTest)
            put("showSelectedGroup", settings.showSelectedGroup)
            put("showSelectedNode", settings.showSelectedNode)
        }.toString()

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
        decodeSmartRoutingSettings(preferences, ProxyMode.SMART)
    }
    val globalRoutingSettings: Flow<SmartRoutingSettings> = store.data.map { preferences ->
        decodeSmartRoutingSettings(preferences, ProxyMode.GLOBAL)
    }

    suspend fun setSmartRoutingDomains(
        action: SmartRoutingRuleAction,
        values: List<String>,
        mode: ProxyMode = ProxyMode.SMART
    ) {
        store.edit { preferences ->
            val current = decodeSmartRoutingSettings(preferences, mode).rules
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
                        values = normalizedValues,
                        domains = normalizedValues
                    )
                ) + remaining
            }
            preferences[routingRulesKey(mode)] = encodeSmartRoutingRules(updated)
        }
    }

    suspend fun setSmartRoutingDomainStrategy(
        strategy: SmartRoutingDomainStrategy,
        mode: ProxyMode = ProxyMode.SMART
    ) {
        store.edit { preferences -> preferences[routingStrategyKey(mode)] = strategy.xrayValue }
    }

    suspend fun saveSmartRoutingRule(rule: SmartRoutingRule, mode: ProxyMode = ProxyMode.SMART) {
        if (rule.name.isBlank()) return
        val domains = normalizeRoutingValues(rule.domains)
        val ips = normalizeRoutingValues(rule.ips)
        val protocols = normalizeRoutingValues(rule.protocols)
        val processes = normalizeRoutingValues(rule.processes)
        val normalized = rule.copy(
            name = rule.name.trim(),
            outboundTag = rule.outboundTag.trim().ifBlank { SmartRoutingRuleAction.PROXY.configTag },
            values = if (domains.isNotEmpty()) domains else ips,
            domains = domains,
            ips = ips,
            ports = rule.ports.trim(),
            network = rule.network.trim(),
            protocols = protocols,
            processes = processes
        )
        store.edit { preferences ->
            val rules = decodeSmartRoutingSettings(preferences, mode).rules
            val currentIndex = rules.indexOfFirst { it.id == rule.id }
            val updated = if (currentIndex >= 0) {
                rules.toMutableList().apply { this[currentIndex] = normalized }
            } else {
                rules.toMutableList().apply { add(0, normalized) }
            }
            preferences[routingRulesKey(mode)] = encodeSmartRoutingRules(updated)
        }
    }

    suspend fun deleteSmartRoutingRule(ruleId: String, mode: ProxyMode = ProxyMode.SMART) {
        store.edit { preferences ->
            val rules = decodeSmartRoutingSettings(preferences, mode).rules
            if (rules.none { it.id == ruleId }) return@edit
            preferences[routingRulesKey(mode)] = encodeSmartRoutingRules(
                rules.filterNot { it.id == ruleId }
            )
        }
    }

    suspend fun setSmartRoutingRuleEnabled(
        ruleId: String,
        enabled: Boolean,
        mode: ProxyMode = ProxyMode.SMART
    ) {
        store.edit { preferences ->
            val rules = decodeSmartRoutingSettings(preferences, mode).rules
            if (rules.none { it.id == ruleId }) return@edit
            preferences[routingRulesKey(mode)] = encodeSmartRoutingRules(
                rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it }
            )
        }
    }

    /** 对齐 v2rayNG：重置时先保留旧列表的锁定项，再追加导入规则。 */
    suspend fun replaceSmartRoutingRules(
        importedRules: List<SmartRoutingRule>,
        mode: ProxyMode = ProxyMode.SMART
    ) {
        if (importedRules.isEmpty()) return
        store.edit { preferences ->
            val current = decodeSmartRoutingSettings(preferences, mode).rules
            val imported = importedRules
                .map { rule ->
                    val domains = normalizeRoutingValues(rule.domains.ifEmpty {
                        if (rule.type == SmartRoutingRuleType.CUSTOM_DOMAIN) rule.values else emptyList()
                    })
                    val ips = normalizeRoutingValues(rule.ips.ifEmpty {
                        if (rule.type == SmartRoutingRuleType.CUSTOM_IP) rule.values else emptyList()
                    })
                    rule.copy(
                        id = rule.id.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString(),
                        name = rule.name.trim().ifBlank { "导入规则" },
                        outboundTag = rule.outboundTag.trim().ifBlank {
                            SmartRoutingRuleAction.PROXY.configTag
                        },
                        values = if (domains.isNotEmpty()) domains else ips,
                        domains = domains,
                        ips = ips,
                        ports = rule.ports.trim(),
                        network = rule.network.trim(),
                        protocols = normalizeRoutingValues(rule.protocols),
                        processes = normalizeRoutingValues(rule.processes)
                    )
                }
            val keptLocked = current.filter { it.locked }
            preferences[routingRulesKey(mode)] = encodeSmartRoutingRules(
                keptLocked + imported
            )
        }
    }

    suspend fun moveSmartRoutingRule(
        fromRuleId: String,
        toRuleId: String,
        mode: ProxyMode = ProxyMode.SMART
    ) {
        if (fromRuleId == toRuleId) return
        store.edit { preferences ->
            val rules = decodeSmartRoutingSettings(preferences, mode).rules.toMutableList()
            val fromIndex = rules.indexOfFirst { it.id == fromRuleId }
            val toIndex = rules.indexOfFirst { it.id == toRuleId }
            if (fromIndex < 0 || toIndex < 0) return@edit
            val moved = rules.removeAt(fromIndex)
            rules.add(toIndex, moved)
            preferences[routingRulesKey(mode)] = encodeSmartRoutingRules(rules)
        }
    }

    // 按界面传入的规则 ID 顺序重排，确认规则集合一致后再保存。
    suspend fun reorderSmartRoutingRules(
        orderedRuleIds: List<String>,
        mode: ProxyMode = ProxyMode.SMART
    ) {
        store.edit { preferences ->
            val rules = decodeSmartRoutingSettings(preferences, mode).rules
            val rulesById = rules.associateBy(SmartRoutingRule::id)
            if (orderedRuleIds.size != rules.size || orderedRuleIds.toSet() != rulesById.keys) {
                return@edit
            }
            val reorderedRules = orderedRuleIds.map(rulesById::getValue)
            if (reorderedRules != rules) {
                preferences[routingRulesKey(mode)] = encodeSmartRoutingRules(reorderedRules)
            }
        }
    }

    private fun normalizeRoutingValues(values: List<String>): List<String> = values
        .flatMap(::decodeRoutingRuleValues)
        .filter(String::isNotBlank)
        .distinct()

    val tcpingTestTimeoutMs: Flow<Long> = store.data.map { preferences ->
        (preferences[TCPING_TEST_TIMEOUT_MS] ?: io.github.HanJason233.chimera.AppConfig.TCPING_TEST_TIMEOUT).coerceAtLeast(500L)
    }

    val serviceRequestTimeoutMs: Flow<Long> = store.data.map { preferences ->
        (preferences[SERVICE_REQUEST_TIMEOUT_MS] ?: DEFAULT_SERVICE_REQUEST_TIMEOUT_MS)
            .coerceIn(MIN_SERVICE_REQUEST_TIMEOUT_MS, MAX_SERVICE_REQUEST_TIMEOUT_MS)
    }

    /** 地区组查询规则按卡片顺序保存在本机。 */
    val regionLookupMethods: Flow<List<RegionLookupMethod>> = store.data.map { preferences ->
        if (preferences.contains(REGION_LOOKUP_METHODS)) {
            decodeRegionLookupMethods(preferences[REGION_LOOKUP_METHODS].orEmpty())
        } else {
            RegionLookupMethod.defaultRules
        }
    }

    suspend fun setRegionLookupMethods(methods: List<RegionLookupMethod>) {
        val normalizedMethods = methods.distinct()
        store.edit { preferences ->
            preferences[REGION_LOOKUP_METHODS] = normalizedMethods.joinToString(",") {
                it.name
            }
        }
    }

    private fun decodeRegionLookupMethods(serialized: String): List<RegionLookupMethod> =
        serialized.split(',')
            .mapNotNull { value ->
                RegionLookupMethod.entries.firstOrNull { it.name == value }
            }
            .distinct()

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

}
