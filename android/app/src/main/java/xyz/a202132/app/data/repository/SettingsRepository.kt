package xyz.a202132.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import xyz.a202132.app.data.model.BandwidthTestTargets
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.data.model.LanInboundSettings
import xyz.a202132.app.data.model.NodeListCategory
import xyz.a202132.app.data.model.NodeDisplaySettings
import xyz.a202132.app.data.model.NodeType
import xyz.a202132.app.data.model.ProtocolCoreSettings
import xyz.a202132.app.data.model.ProxyCore
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.data.model.SkinScheme
import xyz.a202132.app.data.model.SkinSettings
import xyz.a202132.app.data.model.SpeedTestScheme
import xyz.a202132.app.data.model.SpeedTestScope
import xyz.a202132.app.data.model.UrlTestTargets
import xyz.a202132.app.feature.nodegrouping.model.NodeGroupingMode
import xyz.a202132.app.util.CountryCodeResolver
import java.util.UUID

data class PerAppProxySettings(
    val enabled: Boolean,
    val selectedPackages: Set<String>
)

data class VpnRuntimeSettings(
    val proxyMode: ProxyMode,
    val ipv6RoutingMode: IPv6RoutingMode,
    val hysteriaUpMbps: Int,
    val hysteriaDownMbps: Int,
    val protocolCoreSettings: ProtocolCoreSettings,
    val perAppProxy: PerAppProxySettings,
    val lanInbound: LanInboundSettings = LanInboundSettings()
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    
    companion object {
        private val SELECTED_NODE_ID = stringPreferencesKey("selected_node_id")
        private val PROXY_MODE = stringPreferencesKey("proxy_mode")
        private val LAN_INBOUND_ENABLED = booleanPreferencesKey("lan_inbound_enabled")
        private val LAN_INBOUND_PORT = intPreferencesKey("lan_inbound_port")
        private val LAN_INBOUND_USERNAME = stringPreferencesKey("lan_inbound_username")
        private val LAN_INBOUND_PASSWORD = stringPreferencesKey("lan_inbound_password")
        private val HOME_CLICK_SPEED_TEST_SCHEME_ID =
            stringPreferencesKey("home_click_speed_test_scheme_id")
        private val HOME_LONG_PRESS_SPEED_TEST_SCHEME_ID =
            stringPreferencesKey("home_long_press_speed_test_scheme_id")
        private val HOME_SWIPE_UP_SPEED_TEST_SCHEME_ID =
            stringPreferencesKey("home_swipe_up_speed_test_scheme_id")
        private val HOME_SWIPE_DOWN_SPEED_TEST_SCHEME_ID =
            stringPreferencesKey("home_swipe_down_speed_test_scheme_id")
        private val HOME_SWIPE_LEFT_SPEED_TEST_SCHEME_ID =
            stringPreferencesKey("home_swipe_left_speed_test_scheme_id")
        private val HOME_SWIPE_RIGHT_SPEED_TEST_SCHEME_ID =
            stringPreferencesKey("home_swipe_right_speed_test_scheme_id")
        private val HOME_CLICK_SPEED_TEST_ENABLED =
            booleanPreferencesKey("home_click_speed_test_enabled")
        private val HOME_LONG_PRESS_SPEED_TEST_ENABLED =
            booleanPreferencesKey("home_long_press_speed_test_enabled")
        private val HOME_SWIPE_UP_SPEED_TEST_ENABLED =
            booleanPreferencesKey("home_swipe_up_speed_test_enabled")
        private val HOME_SWIPE_DOWN_SPEED_TEST_ENABLED =
            booleanPreferencesKey("home_swipe_down_speed_test_enabled")
        private val HOME_SWIPE_LEFT_SPEED_TEST_ENABLED =
            booleanPreferencesKey("home_swipe_left_speed_test_enabled")
        private val HOME_SWIPE_RIGHT_SPEED_TEST_ENABLED =
            booleanPreferencesKey("home_swipe_right_speed_test_enabled")
        private val LAST_NOTICE_ID = stringPreferencesKey("last_notice_id")
        private val IGNORED_UPDATE_VERSION_CODE = intPreferencesKey("ignored_update_version_code")
        private val UPDATE_DOWNLOAD_DIRECTORY_URI =
            stringPreferencesKey("update_download_directory_uri")
        private val USER_AGREEMENT_ACCEPTED = booleanPreferencesKey("user_agreement_accepted")
        private val USER_AGREEMENT_VERSION = stringPreferencesKey("user_agreement_version")
        private val USER_AGREEMENT_ACCEPTED_AT = longPreferencesKey("user_agreement_accepted_at")
        private const val CURRENT_USER_AGREEMENT_VERSION = "2026-08-19-v2"
        
        // 分应用代理设置
        private val PER_APP_PROXY_ENABLED = booleanPreferencesKey("per_app_proxy_enabled")
        private val SELECTED_PACKAGES = stringSetPreferencesKey("selected_packages")

        // 订阅列表缓存
        private val SUBSCRIPTION_LIST_JSON = stringPreferencesKey("subscription_list_json")
        
        // IPv6 路由设置
        private val IPV6_ROUTING_MODE = stringPreferencesKey("ipv6_routing_mode")
        
        // 自动化测试设置
        private val SELECTED_SUBSCRIPTION_SOURCE_ID = stringPreferencesKey("selected_subscription_source_id")
        private val SELECTED_COUNTRY_CODE = stringPreferencesKey("selected_country_code")
        private val NODE_GROUPING_MODE = stringPreferencesKey("node_grouping_mode")
        private val NODE_LIST_CATEGORY = stringPreferencesKey("node_list_category")
        private val SELECTED_NODE_GROUP_ID = stringPreferencesKey("selected_node_group_id")
        private val TCPING_TEST_TIMEOUT_MS = longPreferencesKey("tcping_test_timeout_ms")
        private val URL_TEST_TIMEOUT_MS = longPreferencesKey("url_test_timeout_ms")
        private val URL_TEST_URL = stringPreferencesKey("url_test_url")
        private val SPEED_TEST_DOWNLOAD_URL = stringPreferencesKey("speed_test_download_url")
        private val SPEED_TEST_DURATION_MS = longPreferencesKey("speed_test_duration_ms")
        private val TCPING_CONCURRENCY = intPreferencesKey("tcping_concurrency")
        private val URL_TEST_CONCURRENCY = intPreferencesKey("url_test_concurrency")
        private val BANDWIDTH_TEST_CONCURRENCY = intPreferencesKey("bandwidth_test_concurrency")
        private val DISCONNECT_RECOVERY_ENABLED = booleanPreferencesKey("disconnect_recovery_enabled")
        private val HYSTERIA_BANDWIDTH_ENABLED = booleanPreferencesKey("hysteria_bandwidth_enabled")
        private val HYSTERIA_UP_MBPS = intPreferencesKey("hysteria_up_mbps")
        private val HYSTERIA_DOWN_MBPS = intPreferencesKey("hysteria_down_mbps")
        private val CORE_ASSIGNMENTS = stringSetPreferencesKey("protocol_core_assignments")
        private val XRAY_CORE_PROTOCOLS = stringSetPreferencesKey("xray_core_protocols")
        private val NODE_LABEL_SHOW_PROTOCOL = booleanPreferencesKey("node_label_show_protocol")
        private val NODE_LABEL_SHOW_SECURITY = booleanPreferencesKey("node_label_show_security")
        private val NODE_LABEL_SHOW_TRANSPORT = booleanPreferencesKey("node_label_show_transport")
        private val NODE_LABEL_SHOW_CORE = booleanPreferencesKey("node_label_show_core")
        private val AUTO_DELETE_TIMEOUT_NODES = booleanPreferencesKey("auto_delete_timeout_nodes")
        private val HIDE_FAVORITE_NODE_GROUP =
            booleanPreferencesKey("hide_favorite_node_group")
        private val SKIN_SCHEMES_JSON = stringPreferencesKey("skin_schemes_json")
        private val ACTIVE_SKIN_SCHEME_ID = stringPreferencesKey("active_skin_scheme_id")
        private val SPEED_TEST_SCHEMES_JSON = stringPreferencesKey("speed_test_schemes_json")
        // 兼容早期单皮肤版本的数据。
        private val SKIN_BASE_COLOR_ARGB = longPreferencesKey("skin_base_color_argb")
        private val SKIN_CUSTOM_IMAGE_PATH = stringPreferencesKey("skin_custom_image_path")
        private const val LEGACY_DEFAULT_BASE_COLOR_ARGB: Long = 0xFF00BFA5L

    }
    
    val selectedNodeId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_NODE_ID]
    }

    val nodeListCategory: Flow<NodeListCategory> = context.dataStore.data.map { preferences ->
        runCatching {
            NodeListCategory.valueOf(preferences[NODE_LIST_CATEGORY] ?: NodeListCategory.PRIMARY.name)
        }.getOrDefault(NodeListCategory.PRIMARY)
    }

    /** 统一的当前节点组标识。 */
    val selectedNodeGroupId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_NODE_GROUP_ID]?.takeIf(String::isNotBlank)
    }
    
    val proxyMode: Flow<ProxyMode> = context.dataStore.data.map { preferences ->
        val mode = preferences[PROXY_MODE] ?: ProxyMode.SMART.name
        try {
            ProxyMode.valueOf(mode)
        } catch (e: Exception) {
            ProxyMode.SMART
        }
    }

    /** 用户保存的测速方案；首次使用时只提供两套内置方案。 */
    val speedTestSchemes: Flow<List<SpeedTestScheme>> = context.dataStore.data.map { preferences ->
        decodeSpeedTestSchemes(preferences)
    }

    /** 局域网入站设置默认关闭，避免意外暴露代理端口。 */
    val lanInboundSettings: Flow<LanInboundSettings> = context.dataStore.data.map { preferences ->
        decodeLanInboundSettings(preferences)
    }

    /** 主页图标单击使用的测速方案。 */
    val homeClickSpeedTestSchemeId: Flow<String> = context.dataStore.data.map { preferences ->
        val schemes = decodeSpeedTestSchemes(preferences)
        resolveHomeSchemeId(
            preferences[HOME_CLICK_SPEED_TEST_SCHEME_ID],
            SpeedTestScheme.STANDARD_ID,
            schemes
        )
    }

    /** 主页图标长按使用的测速方案。 */
    val homeLongPressSpeedTestSchemeId: Flow<String> = context.dataStore.data.map { preferences ->
        val schemes = decodeSpeedTestSchemes(preferences)
        resolveHomeSchemeId(
            preferences[HOME_LONG_PRESS_SPEED_TEST_SCHEME_ID],
            SpeedTestScheme.DEEP_ID,
            schemes
        )
    }

    /** 主页图标上滑使用的测速方案。 */
    val homeSwipeUpSpeedTestSchemeId: Flow<String> = context.dataStore.data.map { preferences ->
        val schemes = decodeSpeedTestSchemes(preferences)
        resolveHomeSchemeId(
            preferences[HOME_SWIPE_UP_SPEED_TEST_SCHEME_ID],
            SpeedTestScheme.STANDARD_ID,
            schemes
        )
    }

    /** 主页图标下滑使用的测速方案。 */
    val homeSwipeDownSpeedTestSchemeId: Flow<String> = context.dataStore.data.map { preferences ->
        val schemes = decodeSpeedTestSchemes(preferences)
        resolveHomeSchemeId(
            preferences[HOME_SWIPE_DOWN_SPEED_TEST_SCHEME_ID],
            SpeedTestScheme.DEEP_ID,
            schemes
        )
    }

    /** 主页图标左滑使用的测速方案。 */
    val homeSwipeLeftSpeedTestSchemeId: Flow<String> = context.dataStore.data.map { preferences ->
        val schemes = decodeSpeedTestSchemes(preferences)
        resolveHomeSchemeId(
            preferences[HOME_SWIPE_LEFT_SPEED_TEST_SCHEME_ID],
            SpeedTestScheme.STANDARD_ID,
            schemes
        )
    }

    /** 主页图标右滑使用的测速方案。 */
    val homeSwipeRightSpeedTestSchemeId: Flow<String> = context.dataStore.data.map { preferences ->
        val schemes = decodeSpeedTestSchemes(preferences)
        resolveHomeSchemeId(
            preferences[HOME_SWIPE_RIGHT_SPEED_TEST_SCHEME_ID],
            SpeedTestScheme.DEEP_ID,
            schemes
        )
    }

    /** 主页图标单击是否启用测速方案；默认开启以保持原有行为。 */
    val homeClickSpeedTestEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[HOME_CLICK_SPEED_TEST_ENABLED] ?: true
    }

    /** 主页图标长按是否启用测速方案；默认开启以保持原有行为。 */
    val homeLongPressSpeedTestEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[HOME_LONG_PRESS_SPEED_TEST_ENABLED] ?: true
    }

    /** 主页图标上滑是否启用测速方案；默认关闭避免误触。 */
    val homeSwipeUpSpeedTestEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[HOME_SWIPE_UP_SPEED_TEST_ENABLED] ?: false
    }

    /** 主页图标下滑是否启用测速方案；默认关闭避免误触。 */
    val homeSwipeDownSpeedTestEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[HOME_SWIPE_DOWN_SPEED_TEST_ENABLED] ?: false
    }

    /** 主页图标左滑是否启用测速方案；默认关闭避免误触。 */
    val homeSwipeLeftSpeedTestEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[HOME_SWIPE_LEFT_SPEED_TEST_ENABLED] ?: false
    }

    /** 主页图标右滑是否启用测速方案；默认关闭避免误触。 */
    val homeSwipeRightSpeedTestEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[HOME_SWIPE_RIGHT_SPEED_TEST_ENABLED] ?: false
    }

    suspend fun setHomeClickSpeedTestSchemeId(schemeId: String) {
        context.dataStore.edit { preferences ->
            if (decodeSpeedTestSchemes(preferences).any { it.id == schemeId }) {
                preferences[HOME_CLICK_SPEED_TEST_SCHEME_ID] = schemeId
            }
        }
    }

    suspend fun setHomeLongPressSpeedTestSchemeId(schemeId: String) {
        context.dataStore.edit { preferences ->
            if (decodeSpeedTestSchemes(preferences).any { it.id == schemeId }) {
                preferences[HOME_LONG_PRESS_SPEED_TEST_SCHEME_ID] = schemeId
            }
        }
    }

    suspend fun setHomeSwipeUpSpeedTestSchemeId(schemeId: String) {
        context.dataStore.edit { preferences ->
            if (decodeSpeedTestSchemes(preferences).any { it.id == schemeId }) {
                preferences[HOME_SWIPE_UP_SPEED_TEST_SCHEME_ID] = schemeId
            }
        }
    }

    suspend fun setHomeSwipeDownSpeedTestSchemeId(schemeId: String) {
        context.dataStore.edit { preferences ->
            if (decodeSpeedTestSchemes(preferences).any { it.id == schemeId }) {
                preferences[HOME_SWIPE_DOWN_SPEED_TEST_SCHEME_ID] = schemeId
            }
        }
    }

    suspend fun setHomeSwipeLeftSpeedTestSchemeId(schemeId: String) {
        context.dataStore.edit { preferences ->
            if (decodeSpeedTestSchemes(preferences).any { it.id == schemeId }) {
                preferences[HOME_SWIPE_LEFT_SPEED_TEST_SCHEME_ID] = schemeId
            }
        }
    }

    suspend fun setHomeSwipeRightSpeedTestSchemeId(schemeId: String) {
        context.dataStore.edit { preferences ->
            if (decodeSpeedTestSchemes(preferences).any { it.id == schemeId }) {
                preferences[HOME_SWIPE_RIGHT_SPEED_TEST_SCHEME_ID] = schemeId
            }
        }
    }

    suspend fun setHomeClickSpeedTestEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HOME_CLICK_SPEED_TEST_ENABLED] = enabled
        }
    }

    suspend fun setHomeLongPressSpeedTestEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HOME_LONG_PRESS_SPEED_TEST_ENABLED] = enabled
        }
    }

    suspend fun setHomeSwipeUpSpeedTestEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HOME_SWIPE_UP_SPEED_TEST_ENABLED] = enabled
        }
    }

    suspend fun setHomeSwipeDownSpeedTestEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HOME_SWIPE_DOWN_SPEED_TEST_ENABLED] = enabled
        }
    }

    suspend fun setHomeSwipeLeftSpeedTestEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HOME_SWIPE_LEFT_SPEED_TEST_ENABLED] = enabled
        }
    }

    suspend fun setHomeSwipeRightSpeedTestEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HOME_SWIPE_RIGHT_SPEED_TEST_ENABLED] = enabled
        }
    }

    /** 皮肤配置，未设置时保持当前内置默认皮肤。 */
    val skinSettings: Flow<SkinSettings> = context.dataStore.data.map { preferences ->
        val schemes = decodeSkinSchemes(preferences)
        val activeId = preferences[ACTIVE_SKIN_SCHEME_ID]
            ?.takeIf { id -> schemes.any { it.id == id } }
            ?: schemes.firstOrNull { !it.isDefault }?.id
            ?: SkinScheme.DEFAULT_SCHEME_ID
        val activeScheme = schemes.firstOrNull { it.id == activeId }
            ?: SkinScheme.default()
        SkinSettings(
            baseColorArgb = activeScheme.baseColorArgb,
            monetEnabled = activeScheme.monetEnabled,
            customImagePath = activeScheme.customImagePath,
            homeDisconnectedImagePath = activeScheme.homeDisconnectedImagePath,
            homeConnectingImagePath = activeScheme.homeConnectingImagePath,
            homeConnectedImagePath = activeScheme.homeConnectedImagePath,
            activeSchemeId = activeScheme.id,
            schemes = schemes
        )
    }
    
    val lastNoticeId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[LAST_NOTICE_ID]
    }

    val ignoredUpdateVersionCode: Flow<Int?> = context.dataStore.data.map { preferences ->
        preferences[IGNORED_UPDATE_VERSION_CODE]
    }

    /** 用户选择的更新安装包目录；为空时使用应用缓存目录。 */
    val updateDownloadDirectoryUri: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[UPDATE_DOWNLOAD_DIRECTORY_URI]
    }
    
    // 分应用代理 - 是否启用
    val perAppProxyEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PER_APP_PROXY_ENABLED] ?: false
    }
    
    // 分应用代理 - 选中的应用包名列表
    val selectedPackages: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_PACKAGES] ?: emptySet()
    }

    val selectedSubscriptionSourceId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_SUBSCRIPTION_SOURCE_ID]
    }

    /** 地区页选中的国家代码；存在时主页智能测试跨订阅组限定到该地区。 */
    val selectedCountryCode: Flow<String?> = context.dataStore.data.map { preferences ->
        CountryCodeResolver.normalizeCountryCode(preferences[SELECTED_COUNTRY_CODE])
    }

    val nodeGroupingMode: Flow<NodeGroupingMode> = context.dataStore.data.map { preferences ->
        enumValueOrDefault(preferences[NODE_GROUPING_MODE], NodeGroupingMode.COUNTRY)
    }

    val subscriptionListJson: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[SUBSCRIPTION_LIST_JSON]
    }

    suspend fun setSubscriptionList(json: String) {
        context.dataStore.edit { preferences ->
            preferences[SUBSCRIPTION_LIST_JSON] = json
        }
    }

    /** Reads all settings needed for one VPN start from a single DataStore snapshot. */
    suspend fun getVpnRuntimeSettings(): VpnRuntimeSettings {
        val preferences = context.dataStore.data.first()
        val hysteriaBandwidthEnabled = preferences[HYSTERIA_BANDWIDTH_ENABLED]
            ?: xyz.a202132.app.AppConfig.HYSTERIA_BANDWIDTH_ENABLED
        return VpnRuntimeSettings(
            proxyMode = enumValueOrDefault(preferences[PROXY_MODE], ProxyMode.SMART),
            ipv6RoutingMode = normalizeIpv6RoutingMode(enumValueOrDefault(
                preferences[IPV6_ROUTING_MODE],
                IPv6RoutingMode.DISABLED
            )),
            // 关闭 Hy2 带宽参数时必须向两个核心都传 0，避免运行时仍启用 Brutal。
            hysteriaUpMbps = if (hysteriaBandwidthEnabled) {
                (preferences[HYSTERIA_UP_MBPS]
                    ?: xyz.a202132.app.AppConfig.HYSTERIA_UP_MBPS).coerceAtLeast(0)
            } else {
                0
            },
            hysteriaDownMbps = if (hysteriaBandwidthEnabled) {
                (preferences[HYSTERIA_DOWN_MBPS]
                    ?: xyz.a202132.app.AppConfig.HYSTERIA_DOWN_MBPS).coerceAtLeast(0)
            } else {
                0
            },
            protocolCoreSettings = decodeProtocolCoreSettings(preferences[XRAY_CORE_PROTOCOLS], preferences[CORE_ASSIGNMENTS]),
            perAppProxy = PerAppProxySettings(
                enabled = preferences[PER_APP_PROXY_ENABLED] ?: false,
                selectedPackages = preferences[SELECTED_PACKAGES] ?: emptySet()
            ),
            lanInbound = decodeLanInboundSettings(preferences),
        )
    }

    val protocolCoreSettings: Flow<ProtocolCoreSettings> = context.dataStore.data.map { preferences ->
        decodeProtocolCoreSettings(preferences[XRAY_CORE_PROTOCOLS], preferences[CORE_ASSIGNMENTS])
    }

    val nodeDisplaySettings: Flow<NodeDisplaySettings> = context.dataStore.data.map { preferences ->
        NodeDisplaySettings(
            showProtocol = preferences[NODE_LABEL_SHOW_PROTOCOL] ?: false,
            showSecurity = preferences[NODE_LABEL_SHOW_SECURITY] ?: false,
            showTransport = preferences[NODE_LABEL_SHOW_TRANSPORT] ?: false,
            showCore = preferences[NODE_LABEL_SHOW_CORE] ?: false
        )
    }

    suspend fun setNodeLabelShowProtocol(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[NODE_LABEL_SHOW_PROTOCOL] = show
        }
    }

    suspend fun setNodeLabelShowSecurity(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[NODE_LABEL_SHOW_SECURITY] = show
        }
    }

    suspend fun setNodeLabelShowTransport(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[NODE_LABEL_SHOW_TRANSPORT] = show
        }
    }

    suspend fun setNodeLabelShowCore(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[NODE_LABEL_SHOW_CORE] = show
        }
    }

    suspend fun setProtocolCore(nodeType: NodeType, core: ProxyCore) {
        require(core in xyz.a202132.app.core.CoreBackends.registry.availableFor(nodeType))
        context.dataStore.edit { preferences ->
            val current = decodeProtocolCoreSettings(preferences[XRAY_CORE_PROTOCOLS], preferences[CORE_ASSIGNMENTS])
                .assignments.toMutableMap()
            current[nodeType] = core
            preferences[CORE_ASSIGNMENTS] = current.mapTo(mutableSetOf()) { (type, selected) ->
                "${type.protocol}=${selected.name}"
            }
        }
    }

    private fun decodeProtocolCoreSettings(values: Set<String>?, entries: Set<String>?): ProtocolCoreSettings {
        val assignments = entries.orEmpty().mapNotNull { entry ->
            val pair = entry.split('=', limit = 2)
            if (pair.size != 2) return@mapNotNull null
            val type = NodeType.entries.firstOrNull { it.protocol == pair[0] } ?: return@mapNotNull null
            val selected = runCatching { ProxyCore.valueOf(pair[1]) }.getOrNull() ?: return@mapNotNull null
            type to selected
        }.toMap()
        val supported = ProtocolCoreSettings.XRAY_CAPABLE_PROTOCOLS.toSet()
        val storedValues = values ?: ProtocolCoreSettings.DEFAULT_XRAY_PROTOCOLS
            .mapTo(mutableSetOf()) { it.protocol }
        val selected = storedValues.mapNotNullTo(mutableSetOf()) { value ->
            NodeType.entries.firstOrNull {
                it.protocol.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true)
            }
        }.intersect(supported)
        return ProtocolCoreSettings(selected, assignments)
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
            password = preferences[LAN_INBOUND_PASSWORD].orEmpty()
        )

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        value: String?,
        default: T
    ): T = runCatching { enumValueOf<T>(value ?: default.name) }.getOrDefault(default)
    
    suspend fun setSelectedNodeId(nodeId: String?) {
        context.dataStore.edit { preferences ->
            if (nodeId == null) {
                preferences.remove(SELECTED_NODE_ID)
            } else {
                preferences[SELECTED_NODE_ID] = nodeId
            }
        }
    }

    suspend fun setNodeListCategory(category: NodeListCategory) {
        context.dataStore.edit { preferences ->
            preferences[NODE_LIST_CATEGORY] = category.name
        }
    }

    suspend fun setSelectedNodeGroupId(groupId: String?) {
        context.dataStore.edit { preferences ->
            if (groupId.isNullOrBlank()) {
                preferences.remove(SELECTED_NODE_GROUP_ID)
            } else {
                preferences[SELECTED_NODE_GROUP_ID] = groupId
            }
        }
    }
    
    suspend fun setProxyMode(mode: ProxyMode) {
        context.dataStore.edit { preferences ->
            preferences[PROXY_MODE] = mode.name
        }
    }

    suspend fun setLanInboundEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
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
        context.dataStore.edit { preferences ->
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
        context.dataStore.edit { preferences ->
            val normalized = normalizeSpeedTestScheme(scheme)
            val schemes = decodeSpeedTestSchemes(preferences)
                .filterNot { it.id == scheme.id } + normalized
            preferences[SPEED_TEST_SCHEMES_JSON] = encodeSpeedTestSchemes(schemes)
        }
    }

    suspend fun deleteSpeedTestScheme(schemeId: String) {
        context.dataStore.edit { preferences ->
            val schemes = decodeSpeedTestSchemes(preferences)
            val target = schemes.firstOrNull { it.id == schemeId } ?: return@edit
            if (target.builtIn) return@edit
            preferences[SPEED_TEST_SCHEMES_JSON] = encodeSpeedTestSchemes(
                schemes.filterNot { it.id == schemeId }
            )
            if (preferences[HOME_CLICK_SPEED_TEST_SCHEME_ID] == schemeId) {
                preferences[HOME_CLICK_SPEED_TEST_SCHEME_ID] = SpeedTestScheme.STANDARD_ID
            }
            if (preferences[HOME_LONG_PRESS_SPEED_TEST_SCHEME_ID] == schemeId) {
                preferences[HOME_LONG_PRESS_SPEED_TEST_SCHEME_ID] = SpeedTestScheme.DEEP_ID
            }
            if (preferences[HOME_SWIPE_UP_SPEED_TEST_SCHEME_ID] == schemeId) {
                preferences[HOME_SWIPE_UP_SPEED_TEST_SCHEME_ID] = SpeedTestScheme.STANDARD_ID
            }
            if (preferences[HOME_SWIPE_DOWN_SPEED_TEST_SCHEME_ID] == schemeId) {
                preferences[HOME_SWIPE_DOWN_SPEED_TEST_SCHEME_ID] = SpeedTestScheme.DEEP_ID
            }
            if (preferences[HOME_SWIPE_LEFT_SPEED_TEST_SCHEME_ID] == schemeId) {
                preferences[HOME_SWIPE_LEFT_SPEED_TEST_SCHEME_ID] = SpeedTestScheme.STANDARD_ID
            }
            if (preferences[HOME_SWIPE_RIGHT_SPEED_TEST_SCHEME_ID] == schemeId) {
                preferences[HOME_SWIPE_RIGHT_SPEED_TEST_SCHEME_ID] = SpeedTestScheme.DEEP_ID
            }
        }
    }

    suspend fun upsertSkinScheme(scheme: SkinScheme, activate: Boolean = false) {
        context.dataStore.edit { preferences ->
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
        context.dataStore.edit { preferences ->
            if (decodeSkinSchemes(preferences).any { it.id == schemeId }) {
                preferences[ACTIVE_SKIN_SCHEME_ID] = schemeId
            }
        }
    }

    suspend fun deleteSkinScheme(schemeId: String) {
        if (schemeId == SkinScheme.DEFAULT_SCHEME_ID) return
        context.dataStore.edit { preferences ->
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
        context.dataStore.edit { preferences ->
            preferences[LAST_NOTICE_ID] = noticeId
        }
    }

    suspend fun setIgnoredUpdateVersionCode(versionCode: Int) {
        context.dataStore.edit { preferences ->
            preferences[IGNORED_UPDATE_VERSION_CODE] = versionCode
        }
    }

    suspend fun setUpdateDownloadDirectoryUri(uri: String?) {
        context.dataStore.edit { preferences ->
            if (uri.isNullOrBlank()) {
                preferences.remove(UPDATE_DOWNLOAD_DIRECTORY_URI)
            } else {
                preferences[UPDATE_DOWNLOAD_DIRECTORY_URI] = uri
            }
        }
    }
    
    val isUserAgreementAccepted: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[USER_AGREEMENT_ACCEPTED] == true &&
                preferences[USER_AGREEMENT_VERSION] == CURRENT_USER_AGREEMENT_VERSION
    }
    
    suspend fun setUserAgreementAccepted(accepted: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[USER_AGREEMENT_ACCEPTED] = accepted
            if (accepted) {
                preferences[USER_AGREEMENT_VERSION] = CURRENT_USER_AGREEMENT_VERSION
                preferences[USER_AGREEMENT_ACCEPTED_AT] = System.currentTimeMillis()
            } else {
                preferences.remove(USER_AGREEMENT_VERSION)
                preferences.remove(USER_AGREEMENT_ACCEPTED_AT)
            }
        }
    }

    /** 注销账户时清除本地设置，下一次进入应用需要重新同意用户协议。 */
    suspend fun clearAll() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }
    
    // 分应用代理设置方法
    suspend fun setPerAppProxyEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PER_APP_PROXY_ENABLED] = enabled
        }
    }
    
    suspend fun setSelectedPackages(packages: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_PACKAGES] = packages
        }
    }
    
    // IPv6 路由模式
    val ipv6RoutingMode: Flow<IPv6RoutingMode> = context.dataStore.data.map { preferences ->
        val mode = preferences[IPV6_ROUTING_MODE] ?: IPv6RoutingMode.DISABLED.name
        normalizeIpv6RoutingMode(try {
            IPv6RoutingMode.valueOf(mode)
        } catch (e: Exception) {
            IPv6RoutingMode.DISABLED
        })
    }
    
    suspend fun setIPv6RoutingMode(mode: IPv6RoutingMode) {
        context.dataStore.edit { preferences ->
            preferences[IPV6_ROUTING_MODE] = normalizeIpv6RoutingMode(mode).name
        }
    }

    /** HEV does not provide an IPv6-only DNS/data path, so migrate old native-only values. */
    private fun normalizeIpv6RoutingMode(mode: IPv6RoutingMode): IPv6RoutingMode =
        if (mode == IPv6RoutingMode.ONLY) IPv6RoutingMode.ENABLED else mode

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

    val urlTestUrl: Flow<String> = context.dataStore.data.map { preferences ->
        UrlTestTargets.normalize(preferences[URL_TEST_URL]) ?: UrlTestTargets.DEFAULT_URL
    }

    suspend fun setUrlTestUrl(value: String) {
        val normalized = UrlTestTargets.normalize(value) ?: return
        context.dataStore.edit { preferences ->
            preferences[URL_TEST_URL] = normalized
        }
    }

    val speedTestDownloadUrl: Flow<String> = context.dataStore.data.map { preferences ->
        BandwidthTestTargets.normalize(preferences[SPEED_TEST_DOWNLOAD_URL])
            ?: xyz.a202132.app.AppConfig.SPEED_TEST_DOWNLOAD_URL
    }

    suspend fun setSpeedTestDownloadUrl(value: String) {
        val normalized = BandwidthTestTargets.normalize(value) ?: return
        context.dataStore.edit { preferences ->
            preferences[SPEED_TEST_DOWNLOAD_URL] = normalized
        }
    }

    val speedTestDurationMs: Flow<Long> = context.dataStore.data.map { preferences ->
        (preferences[SPEED_TEST_DURATION_MS]
            ?: xyz.a202132.app.AppConfig.AUTO_TEST_BANDWIDTH_DURATION_MS)
            .coerceAtLeast(1_000L)
    }

    suspend fun setSpeedTestDurationMs(value: Long) {
        context.dataStore.edit { preferences ->
            preferences[SPEED_TEST_DURATION_MS] = value.coerceAtLeast(1_000L)
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
        (preferences[BANDWIDTH_TEST_CONCURRENCY]
            ?: xyz.a202132.app.AppConfig.BANDWIDTH_TEST_CONCURRENCY).coerceIn(1, 3)
    }

    suspend fun setBandwidthTestConcurrency(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[BANDWIDTH_TEST_CONCURRENCY] = value.coerceIn(1, 3)
        }
    }

    suspend fun setSelectedSubscriptionSourceId(sourceId: String?) {
        context.dataStore.edit { preferences ->
            if (sourceId.isNullOrBlank()) {
                preferences.remove(SELECTED_SUBSCRIPTION_SOURCE_ID)
            } else {
                preferences[SELECTED_SUBSCRIPTION_SOURCE_ID] = sourceId
            }
        }
    }

    suspend fun setSelectedCountryCode(countryCode: String?) {
        val normalizedCode = CountryCodeResolver.normalizeCountryCode(countryCode)
        context.dataStore.edit { preferences ->
            if (normalizedCode == null) {
                preferences.remove(SELECTED_COUNTRY_CODE)
            } else {
                preferences[SELECTED_COUNTRY_CODE] = normalizedCode
            }
        }
    }

    suspend fun setNodeGroupingMode(mode: NodeGroupingMode) {
        context.dataStore.edit { preferences ->
            preferences[NODE_GROUPING_MODE] = mode.name
        }
    }

    val disconnectRecoveryEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[DISCONNECT_RECOVERY_ENABLED] ?: xyz.a202132.app.AppConfig.DISCONNECT_RECOVERY_ENABLED
    }

    suspend fun setDisconnectRecoveryEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DISCONNECT_RECOVERY_ENABLED] = enabled
        }
    }

    val hysteriaBandwidthEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[HYSTERIA_BANDWIDTH_ENABLED] ?: xyz.a202132.app.AppConfig.HYSTERIA_BANDWIDTH_ENABLED
    }

    val hysteriaUpMbps: Flow<Int> = context.dataStore.data.map { preferences ->
        if (preferences[HYSTERIA_BANDWIDTH_ENABLED] == false) {
            0
        } else {
            (preferences[HYSTERIA_UP_MBPS] ?: xyz.a202132.app.AppConfig.HYSTERIA_UP_MBPS)
                .coerceIn(0, 100_000)
        }
    }

    suspend fun setHysteriaBandwidthEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HYSTERIA_BANDWIDTH_ENABLED] = enabled
        }
    }

    suspend fun setHysteriaUpMbps(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[HYSTERIA_UP_MBPS] = value.coerceIn(0, 100_000)
        }
    }

    val hysteriaDownMbps: Flow<Int> = context.dataStore.data.map { preferences ->
        if (preferences[HYSTERIA_BANDWIDTH_ENABLED] == false) {
            0
        } else {
            (preferences[HYSTERIA_DOWN_MBPS] ?: xyz.a202132.app.AppConfig.HYSTERIA_DOWN_MBPS)
                .coerceIn(0, 100_000)
        }
    }

    suspend fun setHysteriaDownMbps(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[HYSTERIA_DOWN_MBPS] = value.coerceIn(0, 100_000)
        }
    }

    val autoDeleteTimeoutNodes: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_DELETE_TIMEOUT_NODES] ?: xyz.a202132.app.AppConfig.AUTO_DELETE_TIMEOUT_NODES
    }

    suspend fun setAutoDeleteTimeoutNodes(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_DELETE_TIMEOUT_NODES] = enabled
        }
    }

    val hideFavoriteNodeGroup: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[HIDE_FAVORITE_NODE_GROUP] ?: false
    }

    suspend fun setHideFavoriteNodeGroup(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HIDE_FAVORITE_NODE_GROUP] = enabled
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
                            add(
                                normalizeSpeedTestScheme(
                                    SpeedTestScheme(
                                        id = id,
                                        name = item.optString("name", "我的方案"),
                                        builtIn = item.optBoolean("builtIn", false),
                                        scope = runCatching {
                                            SpeedTestScope.valueOf(
                                                item.optString("scope", SpeedTestScope.CURRENT_GROUP.name)
                                            )
                                        }.getOrDefault(SpeedTestScope.CURRENT_GROUP),
                                        specifiedGroupIds = buildSet {
                                            item.optJSONArray("specifiedGroupIds")?.let { groups ->
                                                for (groupIndex in 0 until groups.length()) {
                                                    groups.optString(groupIndex).trim()
                                                        .takeIf(String::isNotBlank)
                                                        ?.let(::add)
                                                }
                                            }
                                        },
                                        tcpingEnabled = item.optBoolean("tcpingEnabled", true),
                                        urlTestEnabled = item.optBoolean("urlTestEnabled", true),
                                        bandwidthEnabled = item.optBoolean("bandwidthEnabled", true),
                                        tcpingPercent = item.optInt("tcpingPercent", 100),
                                        urlTestPercent = item.optInt("urlTestPercent", 50),
                                        bandwidthPercent = item.optInt("bandwidthPercent", 50),
                                        tcpingTimeoutMs = item.optLong("tcpingTimeoutMs", 1_000L),
                                        urlTestTimeoutMs = item.optLong("urlTestTimeoutMs", 1_000L),
                                        urlTestUrl = item.optString("urlTestUrl", UrlTestTargets.DEFAULT_URL),
                                        bandwidthTestDurationMs = item.optLong(
                                            "bandwidthTestDurationMs",
                                            3_000L
                                        ),
                                        bandwidthTestUrl = item.optString(
                                            "bandwidthTestUrl",
                                            xyz.a202132.app.AppConfig.SPEED_TEST_DOWNLOAD_URL
                                        ),
                                        tcpingConcurrency = item.optInt("tcpingConcurrency", 16),
                                        urlTestConcurrency = item.optInt("urlTestConcurrency", 10),
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

        val standard = stored?.firstOrNull { it.id == SpeedTestScheme.STANDARD_ID }
            ?: SpeedTestScheme.standard()
        val full = stored?.firstOrNull { it.id == SpeedTestScheme.DEEP_ID }
            ?: SpeedTestScheme.deep()
        val custom = stored.orEmpty()
            .filterNot { it.id == SpeedTestScheme.STANDARD_ID || it.id == SpeedTestScheme.DEEP_ID }
            .filterNot { it.builtIn }
            .distinctBy(SpeedTestScheme::id)
        return listOf(standard.copy(builtIn = true), full.copy(builtIn = true)) + custom
    }

    private fun normalizeSpeedTestScheme(scheme: SpeedTestScheme): SpeedTestScheme {
        // 兼容已保存的旧内置方案名称，同时保留用户对内置方案名称的自定义修改。
        val trimmedName = scheme.name.trim()
        val normalizedName = when {
            scheme.id == SpeedTestScheme.STANDARD_ID &&
                (trimmedName.isBlank() || trimmedName == "标准测速") -> "默认方案1"
            scheme.id == SpeedTestScheme.DEEP_ID &&
                (trimmedName.isBlank() || trimmedName == "全面测速") -> "默认方案2"
            else -> trimmedName.ifBlank { if (scheme.builtIn) "测速方案" else "我的方案" }
        }
        return scheme.copy(
            name = normalizedName,
            specifiedGroupIds = scheme.specifiedGroupIds
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet(),
            tcpingPercent = scheme.tcpingPercent.coerceIn(1, 100),
            urlTestPercent = scheme.urlTestPercent.coerceIn(1, 100),
            bandwidthPercent = scheme.bandwidthPercent.coerceIn(1, 100),
            tcpingTimeoutMs = scheme.tcpingTimeoutMs.coerceAtLeast(500L),
            urlTestTimeoutMs = scheme.urlTestTimeoutMs.coerceAtLeast(500L),
            urlTestUrl = UrlTestTargets.normalize(scheme.urlTestUrl) ?: UrlTestTargets.DEFAULT_URL,
            bandwidthTestDurationMs = scheme.bandwidthTestDurationMs.coerceAtLeast(1_000L),
            bandwidthTestUrl = BandwidthTestTargets.normalize(scheme.bandwidthTestUrl)
                ?: xyz.a202132.app.AppConfig.SPEED_TEST_DOWNLOAD_URL,
            tcpingConcurrency = scheme.tcpingConcurrency.coerceIn(1, 512),
            urlTestConcurrency = scheme.urlTestConcurrency.coerceIn(1, 128),
            bandwidthTestConcurrency = scheme.bandwidthTestConcurrency.coerceIn(1, 3)
        )
    }

    private fun encodeSpeedTestSchemes(schemes: List<SpeedTestScheme>): String {
        val array = JSONArray()
        schemes.distinctBy(SpeedTestScheme::id).forEach { scheme ->
            val normalized = normalizeSpeedTestScheme(scheme)
            array.put(
                JSONObject().apply {
                    put("id", normalized.id)
                    put("name", normalized.name)
                    put("builtIn", normalized.builtIn)
                    put("scope", normalized.scope.name)
                    put("specifiedGroupIds", JSONArray().apply {
                        normalized.specifiedGroupIds.forEach(::put)
                    })
                    put("tcpingEnabled", normalized.tcpingEnabled)
                    put("urlTestEnabled", normalized.urlTestEnabled)
                    put("bandwidthEnabled", normalized.bandwidthEnabled)
                    put("tcpingPercent", normalized.tcpingPercent)
                    put("urlTestPercent", normalized.urlTestPercent)
                    put("bandwidthPercent", normalized.bandwidthPercent)
                    put("tcpingTimeoutMs", normalized.tcpingTimeoutMs)
                    put("urlTestTimeoutMs", normalized.urlTestTimeoutMs)
                    put("urlTestUrl", normalized.urlTestUrl)
                    put("bandwidthTestDurationMs", normalized.bandwidthTestDurationMs)
                    put("bandwidthTestUrl", normalized.bandwidthTestUrl)
                    put("tcpingConcurrency", normalized.tcpingConcurrency)
                    put("urlTestConcurrency", normalized.urlTestConcurrency)
                    put("bandwidthTestConcurrency", normalized.bandwidthTestConcurrency)
                }
            )
        }
        return array.toString()
    }

    private fun decodeSkinSchemes(preferences: Preferences): List<SkinScheme> {
        val storedJson = preferences[SKIN_SCHEMES_JSON]
        if (!storedJson.isNullOrBlank()) {
            val storedSchemes = runCatching {
                val array = JSONArray(storedJson)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val id = item.optString("id").trim()
                        if (id.isBlank()) continue
                        add(
                            SkinScheme(
                                id = id,
                                name = item.optString("name", "我的方案")
                                    .trim()
                                    .ifBlank { "我的方案" },
                                baseColorArgb = normalizeSkinColor(
                                    item.optLong(
                                        "baseColorArgb",
                                        SkinSettings.DEFAULT_BASE_COLOR_ARGB
                                    )
                                ),
                                monetEnabled = item.optBoolean("monetEnabled", false),
                                customImagePath = item.optString("customImagePath")
                                    .takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() },
                                homeDisconnectedImagePath = item.optString("homeDisconnectedImagePath")
                                    .takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() },
                                homeConnectingImagePath = item.optString("homeConnectingImagePath")
                                    .takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() },
                                homeConnectedImagePath = item.optString("homeConnectedImagePath")
                                    .takeIf { it.isNotBlank() && it != JSONObject.NULL.toString() }
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
            val defaultScheme = storedSchemes.firstOrNull { it.isDefault } ?: SkinScheme.default()
            return listOf(defaultScheme) + storedSchemes
                .filterNot { it.isDefault }
                .distinctBy { it.id }
        }

        val legacyColor = preferences[SKIN_BASE_COLOR_ARGB]
        val legacyImage = preferences[SKIN_CUSTOM_IMAGE_PATH]
            ?.takeIf { it.isNotBlank() }
        val normalizedLegacyColor = legacyColor?.let(::normalizeSkinColor)
        val legacyScheme = if (
            legacyImage != null ||
            normalizedLegacyColor != null &&
                normalizedLegacyColor != SkinSettings.DEFAULT_BASE_COLOR_ARGB &&
                normalizedLegacyColor != LEGACY_DEFAULT_BASE_COLOR_ARGB
        ) {
            SkinScheme(
                id = "legacy_custom",
                name = "我的皮肤",
                baseColorArgb = normalizedLegacyColor
                    ?.takeUnless { it == LEGACY_DEFAULT_BASE_COLOR_ARGB }
                    ?: SkinSettings.DEFAULT_BASE_COLOR_ARGB,
                customImagePath = legacyImage
            )
        } else {
            null
        }
        return listOfNotNull(SkinScheme.default(), legacyScheme)
    }

    private fun encodeSkinSchemes(schemes: List<SkinScheme>): String {
        val array = JSONArray()
        schemes
            .distinctBy { it.id }
            .forEach { scheme ->
                array.put(
                    JSONObject().apply {
                        put("id", scheme.id)
                        put("name", scheme.name)
                        put("baseColorArgb", normalizeSkinColor(scheme.baseColorArgb))
                        put("monetEnabled", scheme.monetEnabled)
                        scheme.customImagePath?.let { put("customImagePath", it) }
                        scheme.homeDisconnectedImagePath?.let { put("homeDisconnectedImagePath", it) }
                        scheme.homeConnectingImagePath?.let { put("homeConnectingImagePath", it) }
                        scheme.homeConnectedImagePath?.let { put("homeConnectedImagePath", it) }
                    }
                )
            }
        return array.toString()
    }

}

