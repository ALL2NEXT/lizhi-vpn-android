package io.github.HanJason233.chimera.network.subscription.importer

import com.google.gson.Gson
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** 读取 YAML 节点字段，交由统一编码器生成 URI。 */
internal object ClashYamlParser {
    private val proxiesMarker = Regex("(?m)^\\s*proxies\\s*:")

    /** 非 YAML 返回 null；格式匹配时只提取节点，丢弃其余配置。 */
    fun parseToUris(content: String): List<String>? {
        if (!proxiesMarker.containsMatchIn(content) &&
            !SubscriptionTextNormalizer.isYamlContent(content)
        ) return null

        return runCatching {
            val loaderOptions = LoaderOptions().apply {
                maxAliasesForCollections = 100
                codePointLimit = 4 * 1024 * 1024
            }
            val loaded = Yaml(SafeConstructor(loaderOptions)).load<Any?>(content)
            if (loaded is List<*>) {
                return@runCatching loaded.mapNotNull { entry ->
                    entry.asStringMap()?.let { NodeUriEncoder.encode(it, allowNativeTransport = true) }
                }
            }
            val root = loaded.asStringMap() ?: return@runCatching emptyList()
            if (!root.containsKey("proxies")) {
                if (root.containsKey("server") && root.containsKey("port") && root.containsKey("type")) {
                    return@runCatching listOfNotNull(NodeUriEncoder.encode(root, allowNativeTransport = true))
                }
                // YAML 表示的原生出站配置由 JSON 适配器读取；编码器不反向依赖解析器。
                if (root.containsKey("outbounds") || root.containsKey("type") || root.containsKey("protocol")) {
                    return@runCatching JsonSubscriptionParser.parseToUris(Gson().toJson(root)).orEmpty()
                }
                return@runCatching emptyList()
            }
            val globalFingerprint = root.value("global-client-fingerprint").scalarText()
            val proxies = root.value("proxies") as? List<*> ?: emptyList<Any?>()
            proxies.mapNotNull { it.asStringMap() }.mapNotNull { proxy ->
                NodeUriEncoder.encode(proxy, globalFingerprint, allowNativeTransport = true)
            }
        }.getOrDefault(emptyList())
    }

    private fun Any?.asStringMap(): Map<String, Any?>? = (this as? Map<*, *>)
        ?.entries?.associate { it.key.toString() to it.value }

    private fun Map<String, Any?>.value(key: String): Any? =
        entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    private fun Any?.scalarText(): String? = when (this) {
        null -> null
        is String -> trim().takeIf(String::isNotEmpty)
        is Number, is Boolean -> toString()
        else -> null
    }
}
