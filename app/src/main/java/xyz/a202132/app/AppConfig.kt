package xyz.a202132.app

import org.json.JSONArray

/**
 * 测速大小选项
 */
data class SpeedTestSize(
    val label: String,
    val bytes: Long
)

/**
 * 应用配置常量
 */
object AppConfig {
    const val STARTUP_SPLASH_DURATION_SECONDS = 10 // 启动图倒计时时长，设为0则不启用

    // Cloudflare Worker unified backend URL. App does not call upstream sources directly.
    const val BACKEND_URL = "https://liuying.11145149.xyz/"

    // Contact

    // Latency Test
    // 常用http://cp.cloudflare.com/generate_204或https://www.google.com/generate_204
    const val TCPING_TEST_TIMEOUT = 3000L // TCPing 超时（毫秒）
    
    const val URL_TEST_URL = "https://www.google.com/generate_204" // URL Test测试URL
    const val URL_TEST_TIMEOUT = 3000L // URL Test 超时（毫秒）
    const val URL_TEST_RETRY_COUNT = 1 // URL Test 自动重试次数，仅对503/504或异常生效

    // 智能连接候选比例
    const val SMART_CONNECT_TCPING_PERCENT = 100
    const val SMART_CONNECT_URL_TEST_PERCENT = 50
    const val SMART_CONNECT_SPEED_TEST_PERCENT = 50
    const val SMART_CONNECT_SPEED_TEST_SIZE_MB = 5

    // Hysteria2 最大带宽（Mbps），设为0表示不设置，使用 BBR 拥塞控制
    const val HYSTERIA_UP_MBPS = 1000
    const val HYSTERIA_DOWN_MBPS = 1000
    
    // Concurrency
    const val TCPING_CONCURRENCY = 16 // TCPing并发数
    const val URL_TEST_CONCURRENCY = 10 // URL Test并发数
    
    // VPN
    const val VPN_MTU = 9000
    const val VPN_DNS_PRIMARY = "8.8.8.8"
    const val VPN_DNS_SECONDARY = "8.8.4.4"
    const val VPN_DNS_CHINA = "223.5.5.5" // 国内 DNS（智能分流模式使用）
    val HTTP_USER_AGENT: String
        get() = "FireflyVPN/${xyz.a202132.app.BuildConfig.VERSION_NAME}"


    // Notification
    const val NOTIFICATION_CHANNEL_ID = "vpn_service"
    const val NOTIFICATION_ID = 1

    // Speed Test - Cloudflare 网速测试
    const val SPEED_TEST_DOWNLOAD_URL = "https://speed.cloudflare.com/__down"
    const val SPEED_TEST_UPLOAD_URL = "https://speed.cloudflare.com/__up"
    const val AUTO_TEST_BANDWIDTH_DOWNLOAD_TIMEOUT_MS = 25000L // 单次下载带宽测试超时（毫秒），设为0则无限制
    const val AUTO_TEST_BANDWIDTH_UPLOAD_TIMEOUT_MS = 30000L // 单次上传带宽测试超时（毫秒），设为0则无限制
    const val SPEED_TEST_JSON = """
[
  {"label": "1MB", "bytes": 1000000},
  {"label": "10MB", "bytes": 10000000},
  {"label": "25MB", "bytes": 25000000},
  {"label": "50MB", "bytes": 50000000}
]
"""

    /**
     * 解析测速大小 JSON 数据
     */
    fun getSpeedTestSizes(): List<SpeedTestSize> {
        val sizes = mutableListOf<SpeedTestSize>()
        try {
            val jsonArray = JSONArray(SPEED_TEST_JSON)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                sizes.add(
                    SpeedTestSize(
                        label = obj.getString("label"),
                        bytes = obj.getLong("bytes")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return sizes
    }

}
