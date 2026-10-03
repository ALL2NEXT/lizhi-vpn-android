package io.github.HanJason233.chimera.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 统一描述订阅入口：远程订阅链接或已经读取到的完整订阅内容。 */
internal sealed interface SubscriptionImportInput {
    data class RemoteSubscription(val url: String) : SubscriptionImportInput
    data class MultipleRemoteSubscriptions(val urls: List<String>) : SubscriptionImportInput
    data class FullContent(val content: String) : SubscriptionImportInput
}

/**
 * 统一判断剪贴板、二维码、文件和文本输入的内容类型。
 * 剪贴板和二维码可能得到两种结果，文件直接作为完整订阅内容处理。
 */
internal object SubscriptionInputDetector {
    fun detect(value: String): SubscriptionImportInput {
        val content = value.trim()
        val lines = content.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        val remoteUrls = lines.mapNotNull(SubscriptionImportUrl::detect).distinct()

        return when {
            lines.size > 1 && remoteUrls.size == lines.size -> {
                SubscriptionImportInput.MultipleRemoteSubscriptions(remoteUrls)
            }

            remoteUrls.size == 1 && lines.size == 1 -> {
                SubscriptionImportInput.RemoteSubscription(remoteUrls.single())
            }

            else -> SubscriptionImportInput.FullContent(content)
        }
    }
}

/** 从文本输入中识别远程订阅链接，所有合法 HTTP/HTTPS 地址均按链接处理。 */
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

        return url.toString()
    }
}
