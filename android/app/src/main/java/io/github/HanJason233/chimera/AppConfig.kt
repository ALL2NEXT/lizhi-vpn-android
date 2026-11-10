package io.github.HanJason233.chimera

import android.content.Context
import java.net.URI
import io.github.HanJason233.chimera.data.model.BandwidthTestTargets
import io.github.HanJason233.chimera.data.model.UrlTestTargets

/**
 * 应用配置常量
 */
object AppConfig {
    // Cloudflare Worker 默认地址；主页连续点击标题五次可以切换到其他后端。
    const val DEFAULT_BACKEND_URL = "https://test.11145149.xyz"
    private const val LEGACY_DEFAULT_BACKEND_URL = "https://chimera.11145149.xyz"

    private const val PREFERENCES_NAME = "chimera_app_config"
    private const val BACKEND_URL_KEY = "backend_url"

    @Volatile
    private var configuredBackendUrl = DEFAULT_BACKEND_URL

    /** 在 Activity 启动时加载用户保存的后端地址。 */
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val currentPreferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val savedBackendUrl = normalizeBackendUrl(
            currentPreferences.getString(BACKEND_URL_KEY, null)
        )
        configuredBackendUrl = when {
            savedBackendUrl == null -> DEFAULT_BACKEND_URL
            // 将旧版默认地址迁移到新 Worker，保留其他自定义地址。
            savedBackendUrl.equals(LEGACY_DEFAULT_BACKEND_URL, ignoreCase = true) -> {
                currentPreferences.edit()
                    .putString(BACKEND_URL_KEY, DEFAULT_BACKEND_URL)
                    .apply()
                DEFAULT_BACKEND_URL
            }
            else -> savedBackendUrl
        }
    }

    val BACKEND_URL: String
        get() = configuredBackendUrl

    /** 保存并立即切换后端地址；返回 false 表示地址格式无效。 */
    fun updateBackendUrl(context: Context, value: String): Boolean {
        val normalized = normalizeBackendUrl(value) ?: return false
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(BACKEND_URL_KEY, normalized)
            .apply()
        configuredBackendUrl = normalized
        return true
    }

    /** 注销账户时恢复默认后端地址。 */
    fun resetBackendUrl(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(BACKEND_URL_KEY)
            .apply()
        configuredBackendUrl = DEFAULT_BACKEND_URL
    }

    private fun normalizeBackendUrl(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank() || raw.length > 2048) return null
        val candidate = if (
            raw.startsWith("https://", ignoreCase = true) ||
            raw.startsWith("http://", ignoreCase = true)
        ) {
            raw
        } else {
            "https://$raw"
        }
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme.lowercase()
        if (scheme !in setOf("http", "https")) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            return null
        }
        if (scheme == "http" && uri.host != "127.0.0.1") return null
        return candidate.trimEnd('/')
    }

    // Contact

    // Latency Test
    const val TCPING_TEST_TIMEOUT = 1000L // TCPing 超时（毫秒）
    
    const val URL_TEST_URL = UrlTestTargets.DEFAULT_URL // URL Test 默认测试 URL
    const val URL_TEST_WARMUP_TIMEOUT = 4000L // URL Test 首次建连与预热请求超时（毫秒）
    const val URL_TEST_MEASUREMENT_TIMEOUT = 1000L // URL Test 暖连接延迟测量超时（毫秒）
    const val URL_TEST_RETRY_COUNT = 1 // URL Test 自动重试次数，仅对503/504或异常生效
    const val URL_TEST_RECHECK_TOP_COUNT = 5 // 首轮最快节点进入稳态复测的数量
    const val URL_TEST_RECHECK_ATTEMPTS = 2 // 每个优胜节点额外执行暖连接复测的次数

    // Hysteria2 最大带宽（Mbps），设为0表示不设置，使用 BBR 拥塞控制
    const val HYSTERIA_BANDWIDTH_ENABLED = true
    const val HYSTERIA_UP_MBPS = 1000
    const val HYSTERIA_DOWN_MBPS = 1000

    // 旧版全局自动删除设置迁移默认值；新配置按测速方案和测试项保存
    const val AUTO_DELETE_TCPING_TIMEOUT_NODES = false
    const val AUTO_DELETE_URL_TEST_TIMEOUT_NODES = false
    const val AUTO_DELETE_LOW_BANDWIDTH_NODES = false
    const val AUTO_DELETE_BANDWIDTH_THRESHOLD_MBPS = 10
    
    // Concurrency
    const val TCPING_CONCURRENCY = 16 // TCPing并发数
    const val URL_TEST_CONCURRENCY = 10 // URL Test并发数
    const val BANDWIDTH_TEST_CONCURRENCY = 1 // 带宽测速并发数
    
    // VPN
    const val VPN_MTU = 9000 // 固定核心 MTU，不提供用户配置入口
    const val VPN_DNS_PRIMARY = "8.8.8.8"
    const val VPN_DNS_PRIMARY_IPV6 = "2001:4860:4860::8888"
    const val VPN_DNS_CHINA = "223.5.5.5" // 国内 DNS（智能分流模式使用）
    val HTTP_USER_AGENT: String
        get() = "Chimera/${io.github.HanJason233.chimera.BuildConfig.VERSION_NAME}"


    // Notification
    const val NOTIFICATION_CHANNEL_ID = "vpn_service"
    const val NOTIFICATION_ID = 1

    // Bandwidth test
    const val SPEED_TEST_DOWNLOAD_URL = BandwidthTestTargets.DEFAULT_URL
    const val SPEED_TEST_UPLOAD_URL = "https://speed.cloudflare.com/__up"
    const val AUTO_TEST_BANDWIDTH_DURATION_MS = 3000L // 单次带宽测试时长（毫秒）
}
