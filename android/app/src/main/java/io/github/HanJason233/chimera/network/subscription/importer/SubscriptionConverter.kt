package io.github.HanJason233.chimera.network.subscription.importer

/** 本地内容和远程响应共用的 Base64 / YAML / sing-box JSON / Xray JSON / URI 转换入口。 */
internal object SubscriptionConverter {
    fun toStandardUris(content: String): List<String> {
        return toStandardUris(LocalSubscriptionContent(SubscriptionTextNormalizer.fragments(content)))
    }

    /** 本地输入已经归一化，直接转换片段，避免再次拼接和解码。 */
    fun toStandardUris(content: LocalSubscriptionContent): List<String> {
        return content.fragments.flatMap { fragment ->
            ClashYamlParser.parseToUris(fragment)
                ?: JsonSubscriptionParser.parseToUris(fragment)
                ?: SubscriptionTextNormalizer.uris(fragment)
        }.distinct()
    }
}
