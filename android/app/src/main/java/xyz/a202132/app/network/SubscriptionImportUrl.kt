package xyz.a202132.app.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 校验用户手动输入的收藏组订阅地址，仅允许标准 HTTP(S) 链接。 */
internal object SubscriptionImportUrl {
    private const val MAX_URL_LENGTH = 8_192

    fun normalize(value: String): String? {
        val raw = value.trim()
        if (raw.isEmpty() || raw.length > MAX_URL_LENGTH) return null

        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.scheme !in setOf("http", "https") || url.host.isBlank()) return null
        // Android 默认禁止明文流量，仅保留本机调试地址的 HTTP 兼容。
        if (url.scheme == "http" && url.host != "127.0.0.1") return null
        return url.toString()
    }
}
