package xyz.a202132.app.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 从剪贴板或二维码内容中识别订阅地址，避免将其误解析成 HTTP 代理节点。 */
internal object SubscriptionImportUrl {
    private const val MAX_URL_LENGTH = 8_192

    fun detect(value: String): String? {
        val raw = value.trim()
        if (
            raw.isEmpty() ||
            raw.length > MAX_URL_LENGTH ||
            raw.contains('\n') ||
            raw.contains('\r')
        ) {
            return null
        }

        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.scheme !in setOf("http", "https") || url.host.isBlank()) return null

        // HTTP 代理 URI 通常只有 authority，并带账号、显式端口或节点名 fragment。
        // 无这些代理特征的根地址，以及带路径/查询令牌的地址，按订阅链接处理。
        val hasCredentials = url.username.isNotEmpty() || url.password.isNotEmpty()
        val authority = raw.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        val endpoint = authority.substringAfterLast('@')
        val hasExplicitPort = if (endpoint.startsWith("[")) {
            endpoint.substringAfter(']', "").startsWith(":")
        } else {
            endpoint.substringAfterLast(':', "").toIntOrNull() != null
        }
        val hasProxyName = url.fragment != null
        val hasOnlyRootPath = url.encodedPath == "/" && url.query == null
        val isHttpProxyNode = hasOnlyRootPath &&
            (hasCredentials || hasExplicitPort || hasProxyName)
        if (isHttpProxyNode) return null

        return url.toString()
    }
}
