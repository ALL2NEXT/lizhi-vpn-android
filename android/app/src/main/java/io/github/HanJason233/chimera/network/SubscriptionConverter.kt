package io.github.HanJason233.chimera.network

import okio.ByteString.Companion.decodeBase64

/** 将 Base64、明文和 Clash YAML 订阅内容统一转换为标准节点 URI。 */
internal object SubscriptionConverter {
    fun toStandardUris(content: String): List<String> {
        val normalizedContent = content.trim().removePrefix("\uFEFF")
        ClashYamlParser.parseToUris(normalizedContent)?.let { return it }

        val decodedContent = decodeBase64IfNeeded(normalizedContent)
        ClashYamlParser.parseToUris(decodedContent)?.let { return it }

        return decodedContent.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
    }

    private fun decodeBase64IfNeeded(content: String): String {
        if (content.contains("://")) return content

        val normalized = content
            .replace('-', '+')
            .replace('_', '/')
        val padding = (4 - normalized.length % 4) % 4
        val padded = normalized + "=".repeat(padding)
        return padded.decodeBase64()?.utf8() ?: content
    }
}
