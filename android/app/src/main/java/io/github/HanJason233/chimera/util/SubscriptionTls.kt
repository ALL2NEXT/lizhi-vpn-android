package io.github.HanJason233.chimera.util

import android.net.Uri
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.github.HanJason233.chimera.data.model.DnsSettings
import okio.ByteString.Companion.decodeBase64

internal fun uriTlsParameters(uri: Uri): JsonObject = JsonObject().apply {
    uri.queryParameterNames.forEach { name -> queryParamFirst(uri, name)?.let { addProperty(name, it) } }
}

internal fun JsonObject.firstString(vararg names: String): String? = names.asSequence()
    .mapNotNull { parseJsonString(get(it)) }.firstOrNull()

private fun JsonObject.firstList(vararg names: String): List<String> = names.asSequence()
    .map { parseJsonStringList(get(it)) }.firstOrNull { it.isNotEmpty() }.orEmpty()

private fun JsonObject.firstBoolean(vararg names: String): Boolean? = names.asSequence()
    .mapNotNull { parseJsonBoolean(get(it)) }.firstOrNull()

internal data class SubscriptionEch(
    val config: String? = null,
    val queryServerName: String? = null,
    val dnsServer: String? = null
)

/** The common URI retains ECHConfigList as base64; sing-box requires a PEM block. */
internal fun parseSubscriptionEch(parameters: JsonObject): SubscriptionEch? {
    val raw = parameters.firstString("ech")
    val enabled = parameters.firstBoolean("echEnabled", "ech_enabled")
        ?: parseJsonBoolean(parameters.get("ech"))
    if (enabled == false) return null
    var config = parameters.firstString(
        "echConfigList", "ech_config_list", "echConfig", "ech_config", "echconfig", "echConfigs"
    )
    var queryName = parameters.firstString("echQueryServerName", "ech_query_server_name", "query_server_name")
    var dnsServer: String? = null
    if (enabled == null && raw != null) config = config ?: raw
    if (config?.contains("://") == true) {
        val dnsValue = config
        val separator = dnsValue.indexOf('+').takeIf { it >= 0 && it < dnsValue.indexOf("://") }
        if (separator != null && separator >= 0) {
            queryName = queryName ?: dnsValue.substring(0, separator)
            dnsServer = dnsValue.substring(separator + 1)
        } else {
            dnsServer = dnsValue
        }
        require(runCatching { java.net.URI(dnsServer).scheme }.getOrNull() in setOf("https", "tls", "udp", "tcp")) {
            "ECH DNS 地址仅支持 https、tls、udp 或 tcp"
        }
        config = null
    }
    if (enabled != true && config == null && dnsServer == null && queryName == null) return null
    return SubscriptionEch(config?.let(::normalizeEchConfig), queryName, dnsServer)
}

internal fun normalizeEchConfig(raw: String): String {
    val value = raw.trim()
    val encoded = if (value.startsWith("-----BEGIN ECH CONFIGS-----")) {
        require(value.endsWith("-----END ECH CONFIGS-----")) { "ECH PEM 配置不完整" }
        value.removePrefix("-----BEGIN ECH CONFIGS-----").removeSuffix("-----END ECH CONFIGS-----")
    } else value
    val bytes = encoded.filterNot(Char::isWhitespace).decodeBase64()
        ?: throw IllegalArgumentException("ECH 配置不是有效的 Base64 ECHConfigList")
    require(bytes.size > 2 && ((bytes[0].toInt() and 255) shl 8 or (bytes[1].toInt() and 255)) == bytes.size - 2) {
        "ECHConfigList 长度无效"
    }
    return bytes.base64()
}

internal fun applyXrayEch(tls: JsonObject, parameters: JsonObject) {
    parseSubscriptionEch(parameters)?.let { ech ->
        val serverName = ech.queryServerName ?: tls.get("serverName").asString
        tls.addProperty("echConfigList", ech.config
            ?: "$serverName+${ech.dnsServer ?: DnsSettings.DEFAULT_DIRECT_DNS}")
        parameters.firstString("echForceQuery", "ech_force_query")
            ?.let { tls.addProperty("echForceQuery", it) }
    }
}

internal fun createSingBoxTls(
    parameters: JsonObject,
    server: String,
    reality: Boolean = false,
    allowUtls: Boolean = true,
    allowLxExtensions: Boolean = false
): JsonObject = JsonObject().apply {
    addProperty("enabled", true)
    addProperty("server_name", parameters.firstString("sni", "serverName", "server_name")
        ?: parameters.firstList("host").firstOrNull() ?: server)
    addProperty("insecure", parameters.firstBoolean("allowInsecure", "allow_insecure", "insecure", "skip-cert-verify") ?: false)
    parameters.firstList("alpn").takeIf { it.isNotEmpty() }
        ?.let { add("alpn", JsonArray().apply { it.forEach(::add) }) }
    parameters.firstString("fp", "fingerprint", "client-fingerprint")
        ?.takeUnless { it.equals("none", true) || it.equals("default", true) }
        .let { fingerprint ->
            if (allowUtls && (fingerprint != null || reality)) {
                add("utls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("fingerprint", fingerprint?.lowercase() ?: "chrome")
                })
            }
        }
    parameters.firstString("certificate", "cert")?.let { addProperty("certificate", it) }
    parameters.firstList("certificate_public_key_sha256", "certificate-public-key-sha256")
        .takeIf { it.isNotEmpty() }?.let { add("certificate_public_key_sha256", JsonArray().apply { it.forEach(::add) }) }
    require(parameters.firstString("pinSHA256", "pinnedPeerCertSha256", "pcs") == null) {
        "sing-box 1.14 不支持整张证书的 SHA-256 固定，请使用证书 PEM、公钥指纹或 Xray"
    }
    parameters.firstString("minVersion", "min_version")?.let { addProperty("min_version", it) }
    parameters.firstString("maxVersion", "max_version")?.let { addProperty("max_version", it) }
    if (allowLxExtensions) {
        parameters.firstBoolean("fragment")?.let { addProperty("fragment", it) }
        parameters.firstString("fragment_fallback_delay", "fragmentFallbackDelay")
            ?.let { addProperty("fragment_fallback_delay", it) }
        parameters.firstBoolean("record_fragment", "recordFragment")?.let { addProperty("record_fragment", it) }
    }
    if (reality) {
        add("reality", JsonObject().apply {
            addProperty("enabled", true)
            addProperty("public_key", parameters.firstString("pbk", "publicKey", "public_key") ?: "")
            addProperty("short_id", parameters.firstString("sid", "shortId", "short_id") ?: "")
            if (allowLxExtensions) {
                parameters.firstString("key_share", "keyShare")?.let { addProperty("key_share", it) }
            }
        })
    }
    parseSubscriptionEch(parameters)?.let { ech ->
        require(!reality) { "Reality 与 ECH 不能同时启用" }
        add("ech", JsonObject().apply {
            addProperty("enabled", true)
            ech.config?.let { config ->
                add("config", JsonArray().apply {
                    add("-----BEGIN ECH CONFIGS-----")
                    config.chunked(64).forEach(::add)
                    add("-----END ECH CONFIGS-----")
                })
            }
            ech.queryServerName?.let { addProperty("query_server_name", it) }
        })
    }
}
