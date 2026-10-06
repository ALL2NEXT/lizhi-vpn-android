package io.github.HanJason233.chimera.network

/** 本地内容和远程响应共用的 Base64 / YAML / sing-box JSON / Xray JSON / URI 转换入口。 */
internal object SubscriptionConverter {
    fun toStandardUris(content: String): List<String> {
        return SubscriptionTextNormalizer.fragments(content).flatMap { fragment ->
            ClashYamlParser.parseToUris(fragment)
                ?: JsonSubscriptionParser.parseToUris(fragment)
                ?: SubscriptionTextNormalizer.uris(fragment)
        }.distinct()
    }
}
