package io.github.HanJason233.chimera.util

import android.net.Uri
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal fun queryParamEnabled(uri: Uri, vararg names: String): Boolean = names.any { name ->
    when (uri.getQueryParameter(name)?.trim()?.lowercase()) {
        "1", "true", "yes", "on" -> true
        else -> false
    }
}

internal fun queryParamFirst(uri: Uri, vararg names: String): String? = names.asSequence()
    .mapNotNull { name ->
        // URI query values may contain literal '+' (notably base64 ECH configs).
        // Android getQueryParameter treats it as a form-encoded space.
        uri.encodedQuery?.split('&')?.firstOrNull { Uri.decode(it.substringBefore('=')) == name }
            ?.substringAfter('=', "")?.let(Uri::decode)?.trim()
    }
    .firstOrNull { it.isNotEmpty() }

internal fun parseCsvParams(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

internal fun parseJsonString(element: JsonElement?): String? {
    if (element == null || element.isJsonNull) return null
    return runCatching {
        if (element.isJsonPrimitive) {
            element.asString.trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }
    }.getOrNull()
}

internal fun parseJsonStringList(element: JsonElement?): List<String> {
    if (element == null || element.isJsonNull) return emptyList()
    return runCatching {
        when {
            element.isJsonArray -> buildList {
                element.asJsonArray.forEach { item -> parseJsonString(item)?.let(::add) }
            }
            element.isJsonPrimitive -> parseCsvParams(element.asString)
            else -> emptyList()
        }
    }.getOrDefault(emptyList())
}

internal fun parseJsonBoolean(element: JsonElement?): Boolean? {
    if (element == null || element.isJsonNull) return null
    return runCatching {
        when {
            element.isJsonPrimitive && element.asJsonPrimitive.isBoolean -> element.asBoolean
            element.isJsonPrimitive -> when (element.asString.trim().lowercase()) {
                "1", "true", "yes", "on" -> true
                "0", "false", "no", "off" -> false
                else -> null
            }
            else -> null
        }
    }.getOrNull()
}

internal fun parseJsonInt(element: JsonElement?): Int? {
    if (element == null || element.isJsonNull) return null
    return runCatching {
        when {
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asInt
            element.isJsonPrimitive -> element.asString.trim().toIntOrNull()
            else -> null
        }
    }.getOrNull()
}

internal fun parseWireGuardReserved(rawValue: String?): List<Int>? {
    if (rawValue.isNullOrBlank()) return null
    val values = rawValue.split(",").mapNotNull { it.trim().toIntOrNull() }
    return values.takeIf { it.size == 3 }
}

internal fun parseHysteria2ServerPorts(link: String): List<String> {
    val authority = link.substringAfter("@", "").substringBefore("?").substringBefore("#").trim()
    if (authority.isEmpty()) return emptyList()

    val portSpec = if (authority.startsWith("[")) {
        authority.substringAfter("]", "").removePrefix(":")
    } else {
        authority.substringAfterLast(":", "")
    }.trim()
    if (portSpec.isEmpty()) return emptyList()

    return portSpec.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { token -> if ('-' in token) token.replace("-", ":") else token }
}

internal fun decodeBase64Compat(rawValue: String): String {
    val normalized = rawValue.trim().replace('-', '+').replace('_', '/')
    val padding = (4 - normalized.length % 4) % 4
    val padded = normalized + "=".repeat(padding)
    return String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
}

internal fun parseShadowsocksMethodPassword(linkContent: String): String {
    val authority = linkContent.substringBefore("?")
    val hasAuthority = authority.contains("@")
    val credentials = Uri.decode(authority.substringBeforeLast("@", authority))
    val decoded = when {
        credentials.contains(":") -> credentials
        credentials.isNotBlank() -> runCatching {
            decodeBase64Compat(credentials)
        }.getOrElse { throw IllegalArgumentException("Shadowsocks 凭据不是有效的 Base64", it) }
        else -> throw IllegalArgumentException("Shadowsocks 链接缺少凭据")
    }
    // 旧版 SS 链接会将 method:password@server:port 整体编码为 Base64。
    return if (hasAuthority) decoded else decoded.substringBeforeLast("@", decoded)
}

/** Normalizes subscription transport aliases to values supported by sing-box. */
internal fun normalizeTransportType(type: String?): String = when (type?.trim()?.lowercase()) {
    null, "", "tcp", "raw" -> "tcp"
    "h2" -> "http"
    "websocket" -> "ws"
    "gun" -> "grpc"
    "http-upgrade" -> "httpupgrade"
    "splithttp", "split-http", "xhttp" -> "unsupported"
    else -> type.trim().lowercase()
}

internal fun resolveUriTransportType(uri: Uri): String {
    val transport = normalizeTransportType(queryParamFirst(uri, "type", "network"))
    if (transport == "tcp" || transport == "none") {
        val headerType = queryParamFirst(uri, "headerType", "header_type")
        if (headerType.equals("http", ignoreCase = true)) return "http"
    }
    return transport
}

internal fun resolveVmessTransportType(config: JsonObject): String {
    val network = normalizeTransportType(
        parseJsonString(config.get("net")) ?: parseJsonString(config.get("network"))
    )
    if (network == "tcp" || network == "none") {
        val headerType = parseJsonString(config.get("headerType"))
            ?: parseJsonString(config.get("type"))
        if (headerType.equals("http", ignoreCase = true)) return "http"
    }
    return network
}

internal fun isSupportedTransport(type: String): Boolean =
    type == "ws" || type == "grpc" || type == "http" ||
        type == "httpupgrade" || type == "quic"

internal fun checkedSingBoxTransport(type: String): String {
    require(type == "tcp" || type == "none" || isSupportedTransport(type)) {
        "sing-box 不支持此节点的传输类型，请为该协议选择 Xray"
    }
    return type
}

internal fun createTransport(type: String, uri: Uri): JsonObject {
    val hostValues = parseCsvParams(queryParamFirst(uri, "host"))
    return JsonObject().apply {
        addProperty("type", type)
        when (type) {
            "ws" -> {
                addProperty("path", uri.getQueryParameter("path") ?: "/")
                hostValues.firstOrNull()?.let {
                    add("headers", JsonObject().apply { addProperty("Host", it) })
                }
                queryParamFirst(uri, "ed", "maxEarlyData", "max_early_data")
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { addProperty("max_early_data", it) }
                queryParamFirst(uri, "eh", "earlyDataHeaderName", "early_data_header_name")
                    ?.let { addProperty("early_data_header_name", it) }
            }
            "grpc" -> {
                addProperty(
                    "service_name",
                    queryParamFirst(uri, "serviceName", "service_name", "path") ?: ""
                )
                queryParamFirst(uri, "idleTimeout", "idle_timeout")
                    ?.let { addProperty("idle_timeout", it) }
                queryParamFirst(uri, "pingTimeout", "ping_timeout")
                    ?.let { addProperty("ping_timeout", it) }
                if (queryParamEnabled(uri, "permitWithoutStream", "permit_without_stream")) {
                    addProperty("permit_without_stream", true)
                }
            }
            "http" -> {
                addProperty("path", uri.getQueryParameter("path") ?: "/")
                if (hostValues.isNotEmpty()) {
                    add("host", JsonArray().apply { hostValues.forEach { add(it) } })
                }
                queryParamFirst(uri, "method")?.let { addProperty("method", it) }
                queryParamFirst(uri, "idleTimeout", "idle_timeout")
                    ?.let { addProperty("idle_timeout", it) }
                queryParamFirst(uri, "pingTimeout", "ping_timeout")
                    ?.let { addProperty("ping_timeout", it) }
            }
            "httpupgrade" -> {
                addProperty("path", uri.getQueryParameter("path") ?: "/")
                hostValues.firstOrNull()?.let { addProperty("host", it) }
            }
        }
    }
}

internal fun createVmessTransport(network: String, config: JsonObject): JsonObject {
    val hostValues = parseJsonStringList(config.get("host"))
    return JsonObject().apply {
        addProperty("type", network)
        when (network) {
            "ws" -> {
                addProperty("path", config.get("path")?.asString ?: "/")
                hostValues.firstOrNull()?.let {
                    add("headers", JsonObject().apply { addProperty("Host", it) })
                }
                (parseJsonInt(config.get("ed"))
                    ?: parseJsonInt(config.get("maxEarlyData"))
                    ?: parseJsonInt(config.get("max_early_data")))
                    ?.takeIf { it > 0 }
                    ?.let { addProperty("max_early_data", it) }
                (parseJsonString(config.get("eh"))
                    ?: parseJsonString(config.get("earlyDataHeaderName"))
                    ?: parseJsonString(config.get("early_data_header_name")))
                    ?.let { addProperty("early_data_header_name", it) }
            }
            "grpc" -> {
                addProperty(
                    "service_name",
                    parseJsonString(config.get("serviceName")) ?: config.get("path")?.asString ?: ""
                )
                parseJsonString(config.get("idleTimeout"))?.let { addProperty("idle_timeout", it) }
                parseJsonString(config.get("pingTimeout"))?.let { addProperty("ping_timeout", it) }
                parseJsonBoolean(config.get("permitWithoutStream"))
                    ?.let { addProperty("permit_without_stream", it) }
            }
            "http" -> {
                addProperty("path", config.get("path")?.asString ?: "/")
                if (hostValues.isNotEmpty()) {
                    add("host", JsonArray().apply { hostValues.forEach { add(it) } })
                }
                parseJsonString(config.get("method"))?.let { addProperty("method", it) }
                parseJsonString(config.get("idleTimeout"))?.let { addProperty("idle_timeout", it) }
                parseJsonString(config.get("pingTimeout"))?.let { addProperty("ping_timeout", it) }
            }
            "httpupgrade" -> {
                addProperty("path", config.get("path")?.asString ?: "/")
                hostValues.firstOrNull()?.let { addProperty("host", it) }
            }
        }
    }
}
