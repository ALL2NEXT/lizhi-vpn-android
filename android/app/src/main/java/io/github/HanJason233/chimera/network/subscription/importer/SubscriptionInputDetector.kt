package io.github.HanJason233.chimera.network.subscription.importer

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 已展开为明文的本地片段；直接用于转换，保留每份 YAML/JSON 的边界。 */
internal data class LocalSubscriptionContent(val fragments: List<String>) {
    fun isBlank(): Boolean = fragments.all(String::isBlank)
}

/** 统一描述订阅入口：远程订阅链接或已经读取到的完整订阅内容。 */
internal sealed interface SubscriptionImportInput {
    data class RemoteSubscription(val url: String) : SubscriptionImportInput
    data class MultipleRemoteSubscriptions(val urls: List<String>) : SubscriptionImportInput
    data class MixedSubscription(
        val content: LocalSubscriptionContent,
        val urls: List<String>
    ) : SubscriptionImportInput
    data class FullContent(val content: LocalSubscriptionContent) : SubscriptionImportInput
}

/**
 * 统一判断剪贴板、二维码、文件和文本输入的内容类型。
 * 先展开混合的明文与 Base64，再按配置边界提取远程地址。
 */
internal object SubscriptionInputDetector {
    fun detect(value: String): SubscriptionImportInput {
        val contentLines = mutableListOf<String>()
        val remoteUrls = mutableListOf<String>()

        SubscriptionTextNormalizer.fragments(value).forEach { fragment ->
            val line = fragment.trim()
            // 结构化配置中的 URL 是字段值，不能拆成新的远程来源。
            if (SubscriptionTextNormalizer.isJsonContent(line) ||
                SubscriptionTextNormalizer.isYamlContent(line)
            ) {
                contentLines += fragment
            } else {
                val uris = SubscriptionTextNormalizer.uris(fragment)
                uris.forEach { uri ->
                    val remoteUrl = SubscriptionImportUrl.detect(uri)
                    if (remoteUrl != null) remoteUrls += remoteUrl else contentLines += uri
                }
            }
        }

        val distinctRemoteUrls = remoteUrls.distinct()
        val contentPart = LocalSubscriptionContent(contentLines.toList())

        return when {
            !contentPart.isBlank() && distinctRemoteUrls.isNotEmpty() -> {
                SubscriptionImportInput.MixedSubscription(contentPart, distinctRemoteUrls)
            }

            distinctRemoteUrls.size > 1 -> {
                SubscriptionImportInput.MultipleRemoteSubscriptions(distinctRemoteUrls)
            }

            distinctRemoteUrls.size == 1 -> {
                SubscriptionImportInput.RemoteSubscription(distinctRemoteUrls.single())
            }

            else -> SubscriptionImportInput.FullContent(contentPart)
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
