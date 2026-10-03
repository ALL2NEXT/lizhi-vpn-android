package io.github.HanJason233.chimera.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 统一描述订阅入口：远程订阅链接或已经读取到的完整订阅内容。 */
internal sealed interface SubscriptionImportInput {
    data class RemoteSubscription(val url: String) : SubscriptionImportInput
    data class MultipleRemoteSubscriptions(val urls: List<String>) : SubscriptionImportInput
    data class MixedSubscription(
        val content: String,
        val urls: List<String>
    ) : SubscriptionImportInput
    data class FullContent(val content: String) : SubscriptionImportInput
}

/**
 * 统一判断剪贴板、二维码、文件和文本输入的内容类型。
 * 剪贴板和二维码可能得到两种结果，文件直接作为完整订阅内容处理。
 */
internal object SubscriptionInputDetector {
    fun detect(value: String): SubscriptionImportInput {
        val content = value.trim()
        val contentLines = mutableListOf<String>()
        val remoteUrls = mutableListOf<String>()

        // 只把整行的 HTTP/HTTPS 地址识别为远程订阅，避免误伤 YAML 内部字段。
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank()) return@forEach

            val remoteUrl = SubscriptionImportUrl.detect(line)
                ?: detectMarkdownRemoteUrl(line)
            if (remoteUrl != null) {
                remoteUrls += remoteUrl
            } else {
                // 保留原始行的缩进，避免破坏 YAML 结构。
                contentLines += rawLine
            }
        }

        val distinctRemoteUrls = remoteUrls.distinct()
        val contentPart = contentLines.joinToString("\n").trim()

        return when {
            contentPart.isNotBlank() && distinctRemoteUrls.isNotEmpty() -> {
                SubscriptionImportInput.MixedSubscription(contentPart, distinctRemoteUrls)
            }

            distinctRemoteUrls.size > 1 -> {
                SubscriptionImportInput.MultipleRemoteSubscriptions(distinctRemoteUrls)
            }

            distinctRemoteUrls.size == 1 -> {
                SubscriptionImportInput.RemoteSubscription(distinctRemoteUrls.single())
            }

            else -> SubscriptionImportInput.FullContent(content)
        }
    }

    private fun detectMarkdownRemoteUrl(line: String): String? {
        val normalizedLine = line.removePrefix("&#x20;").trim()
        val match = Regex(
            "^(?:[-*]\\s+)?\\[[^]]*]\\((https?://[^)\\s]+)\\)$",
            RegexOption.IGNORE_CASE
        ).matchEntire(normalizedLine) ?: return null
        return SubscriptionImportUrl.detect(match.groupValues[1])
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
