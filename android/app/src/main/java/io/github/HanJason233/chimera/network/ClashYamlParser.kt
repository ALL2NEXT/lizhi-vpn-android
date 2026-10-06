package io.github.HanJason233.chimera.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.Gson
import java.net.IDN
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import okio.ByteString.Companion.encodeUtf8
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Converts Clash/Clash.Meta `proxies` entries into the URI formats used by the app. */
internal object ClashYamlParser {
    private val proxiesMarker = Regex("(?m)^\\s*proxies\\s*:")
    private val yamlMarker = Regex("""(?m)^(?:-\s+)?[A-Za-z_][A-Za-z0-9_-]*\s*:(?:\s|$)|^-\s+\{""")
    fun isYamlContent(content: String): Boolean =
        yamlMarker.containsMatchIn(content)

    /** Returns null for non-YAML input; extracts nodes and discards other settings. */
    fun parseToUris(content: String): List<String>? {
        if (!proxiesMarker.containsMatchIn(content) && !isYamlContent(content)) return null

        return runCatching {
            val loaderOptions = LoaderOptions().apply {
                maxAliasesForCollections = 100
                codePointLimit = 4 * 1024 * 1024
            }
            val loaded = Yaml(SafeConstructor(loaderOptions)).load<Any?>(content)
            if (loaded is List<*>) {
                return@runCatching loaded.mapNotNull { entry ->
                    entry.asStringMap()?.let { proxyToUri(it, allowNativeTransport = true) }
                }
            }
            val root = loaded.asStringMap()
                ?: return@runCatching emptyList()
            if (!root.containsKey("proxies")) {
                if (root.containsKey("server") && root.containsKey("port") && root.containsKey("type")) {
                    return@runCatching listOfNotNull(proxyToUri(root, allowNativeTransport = true))
                }
                if (root.containsKey("outbounds") || root.containsKey("type") || root.containsKey("protocol")) {
                    return@runCatching JsonSubscriptionParser.parseToUris(Gson().toJson(root)).orEmpty()
                }
                return@runCatching emptyList()
            }
            val globalFingerprint = root.text("global-client-fingerprint")
            root.value("proxies").asList()
                .mapNotNull { it.asStringMap() }
                .mapNotNull { proxy -> proxyToUri(proxy, globalFingerprint, allowNativeTransport = true) }
        }.getOrDefault(emptyList())
    }

    internal fun proxyToUri(
        proxy: Map<String, Any?>,
        globalFingerprint: String? = null,
        allowNativeTransport: Boolean = false
    ): String? {
        val type = proxy.text("type")?.lowercase() ?: return null
        val server = proxy.text("server") ?: return null
        val port = proxy.int("port")?.takeIf { it in 1..65535 } ?: return null
        val name = proxy.text("name") ?: "$type $server:$port"

        return when (type) {
            "socks", "socks5" -> buildUri(
                scheme = "socks5",
                userInfo = credentials(proxy.text("username"), proxy.text("password")),
                server = server,
                port = port.toString(),
                name = name
            )
            "socks4", "socks4a" -> buildUri(
                scheme = type,
                userInfo = credentials(proxy.text("username"), proxy.text("password")),
                server = server,
                port = port.toString(),
                name = name
            )
            "http" -> httpUri(proxy, server, port, name)
            "ss" -> shadowsocksUri(proxy, server, port, name)
            "vmess" -> vmessUri(proxy, server, port, name, allowNativeTransport)
            "vless" -> vlessLikeUri("vless", proxy, server, port, name, globalFingerprint, allowNativeTransport)
            "trojan" -> vlessLikeUri("trojan", proxy, server, port, name, globalFingerprint, allowNativeTransport)
            "hysteria2", "hy2" -> hysteria2Uri(proxy, server, port, name)
            "anytls" -> anyTlsUri(proxy, server, port, name)
            "tuic" -> tuicUri(proxy, server, port, name)
            "naive" -> naiveUri(proxy, server, port, name)
            "wireguard", "wg" -> wireGuardUri(proxy, server, port, name)
            else -> null
        }
    }

    private fun httpUri(
        proxy: Map<String, Any?>,
        server: String,
        port: Int,
        name: String
    ): String {
        val query = linkedMapOf<String, String>()
        applyTlsQuery(query, proxy)
        proxy.text("sni", "servername")?.let { query["sni"] = it }
        if (proxy.bool("skip-cert-verify") == true) query["allowInsecure"] = "1"
        return buildUri(
            scheme = if (proxy.bool("tls") == true) "https" else "http",
            userInfo = credentials(proxy.text("username"), proxy.text("password")),
            server = server,
            port = port.toString(),
            query = query,
            name = name
        )
    }

    private fun shadowsocksUri(
        proxy: Map<String, Any?>,
        server: String,
        port: Int,
        name: String
    ): String? {
        val method = when (val cipher = proxy.text("cipher") ?: return null) {
            "dummy" -> "none"
            else -> cipher
        }
        val password = proxy.text("password") ?: return null
        val encodedCredentials = "$method:$password".encodeUtf8().base64Url().trimEnd('=')
        val query = linkedMapOf<String, String>()

        proxy.text("plugin")?.let { plugin ->
            val options = proxy.map("plugin-opts")
            val normalizedPlugin = if (plugin == "obfs") "obfs-local" else plugin
            val optionParts = mutableListOf<String>()
            options?.forEach { (key, value) ->
                val optionValue = value.scalarText() ?: return@forEach
                when {
                    normalizedPlugin == "obfs-local" && key == "mode" -> optionParts += "obfs=$optionValue"
                    normalizedPlugin == "obfs-local" && key == "host" -> optionParts += "obfs-host=$optionValue"
                    key == "tls" && value.asBoolean() == true -> optionParts += "tls"
                    key == "mux" && value.asBoolean() == true -> optionParts += "mux=8"
                    else -> optionParts += "$key=$optionValue"
                }
            }
            query["plugin"] = buildString {
                append(normalizedPlugin)
                if (optionParts.isNotEmpty()) append(';').append(optionParts.joinToString(";"))
            }
        }
        if (proxy.bool("udp-over-tcp", "udp_over_tcp") == true) query["udp_over_tcp"] = "1"

        return buildUri(
            scheme = "ss",
            encodedUserInfo = encodedCredentials,
            server = server,
            port = port.toString(),
            query = query,
            name = name
        )
    }

    private fun vmessUri(
        proxy: Map<String, Any?>,
        server: String,
        port: Int,
        name: String,
        allowNativeTransport: Boolean
    ): String? {
        val uuid = proxy.text("uuid") ?: return null
        val transport = transport(proxy)
        if (!allowNativeTransport && transport.type !in SUPPORTED_TRANSPORTS) return null
        val json = JsonObject().apply {
            addProperty("v", "2")
            addProperty("ps", name)
            addProperty("add", server)
            addProperty("port", port.toString())
            addProperty("id", uuid)
            addProperty("aid", proxy.int("alterId", "alter-id") ?: 0)
            addProperty("scy", proxy.text("cipher") ?: "auto")
            addProperty("net", transport.type)
            addProperty("type", "none")
            transport.path?.let { addProperty("path", it) }
            transport.hosts.takeIf { it.isNotEmpty() }?.let { hosts ->
                if (hosts.size == 1) addProperty("host", hosts.single())
                else add("host", JsonArray().apply { hosts.forEach(::add) })
            }
            transport.serviceName?.let { addProperty("serviceName", it) }
            transport.maxEarlyData?.let { addProperty("maxEarlyData", it) }
            transport.earlyDataHeaderName?.let { addProperty("earlyDataHeaderName", it) }
            if (proxy.bool("tls") == true) addProperty("tls", "tls")
            proxy.text("sni", "servername")?.let { addProperty("sni", it) }
            proxy.stringList("alpn").takeIf { it.isNotEmpty() }?.let { values ->
                add("alpn", JsonArray().apply { values.forEach(::add) })
            }
            proxy.text("client-fingerprint")?.let { addProperty("fp", it) }
            if (proxy.bool("skip-cert-verify") == true) addProperty("allowInsecure", true)
            proxy.text("packet-encoding", "packet_encoding")?.let { addProperty("packetEncoding", it) }
            linkedMapOf<String, String>().also { applyTlsQuery(it, proxy) }
                .forEach { (key, value) -> addProperty(key, value) }
        }
        return "vmess://${json.toString().encodeUtf8().base64()}"
    }

    private fun vlessLikeUri(
        scheme: String,
        proxy: Map<String, Any?>,
        server: String,
        port: Int,
        name: String,
        globalFingerprint: String?,
        allowNativeTransport: Boolean
    ): String? {
        val credential = if (scheme == "vless") proxy.text("uuid") else proxy.text("password")
        if (credential.isNullOrBlank()) return null
        val transport = transport(proxy)
        if (!allowNativeTransport && transport.type !in SUPPORTED_TRANSPORTS) return null
        val query = linkedMapOf<String, String>()
        applyTlsQuery(query, proxy)
        val reality = proxy.map("reality-opts", "reality_opts")
        val security = when {
            reality != null -> "reality"
            proxy.bool("tls") == true || scheme == "trojan" -> "tls"
            else -> "none"
        }
        query["security"] = security
        proxy.text("flow")?.let { query["flow"] = it }
        proxy.text("sni", "servername")?.let { query["sni"] = it }
        proxy.stringList("alpn").takeIf { it.isNotEmpty() }?.let { query["alpn"] = it.joinToString(",") }
        (proxy.text("client-fingerprint")
            ?: globalFingerprint?.takeIf { reality != null })
            ?.let { query["fp"] = it }
        if (proxy.bool("skip-cert-verify") == true) query["allowInsecure"] = "1"
        (proxy.text("packet-encoding", "packet_encoding")
            ?: "xudp".takeIf { scheme == "vless" })
            ?.let { query["packetEncoding"] = it }
        reality?.text("public-key", "public_key")?.let { query["pbk"] = it }
        reality?.text("short-id", "short_id")?.let { query["sid"] = it }
        applyTransportQuery(query, transport)

        return buildUri(
            scheme = scheme,
            userInfo = credential,
            server = server,
            port = port.toString(),
            query = query,
            name = name
        )
    }

    private fun hysteria2Uri(
        proxy: Map<String, Any?>,
        server: String,
        port: Int,
        name: String
    ): String? {
        val password = proxy.text("password", "auth", "auth-str") ?: return null
        val query = linkedMapOf<String, String>()
        applyTlsQuery(query, proxy)
        proxy.text("sni", "servername")?.let { query["sni"] = it }
        proxy.stringList("alpn").takeIf { it.isNotEmpty() }?.let { query["alpn"] = it.joinToString(",") }
        proxy.text("client-fingerprint")?.let { query["fp"] = it }
        if (proxy.bool("skip-cert-verify") == true) query["allowInsecure"] = "1"
        proxy.text("obfs")?.let { query["obfs"] = it }
        proxy.text("obfs-password", "obfs_password")
            ?.let { query["obfs-password"] = it }
        proxy.text("hop-interval", "hop_interval")?.let { query["hopInterval"] = it }
        val ports = proxy.text("ports")?.takeIf(String::isNotBlank) ?: port.toString()

        return buildUri(
            scheme = "hysteria2",
            userInfo = password,
            server = server,
            port = ports,
            query = query,
            name = name
        )
    }

    private fun anyTlsUri(
        proxy: Map<String, Any?>,
        server: String,
        port: Int,
        name: String
    ): String? {
        val password = proxy.text("password") ?: return null
        val query = linkedMapOf<String, String>()
        applyTlsQuery(query, proxy)
        proxy.text("sni", "servername")?.let { query["sni"] = it }
        proxy.stringList("alpn").takeIf { it.isNotEmpty() }?.let { query["alpn"] = it.joinToString(",") }
        proxy.text("client-fingerprint")?.let { query["fp"] = it }
        if (proxy.bool("skip-cert-verify") == true) query["allowInsecure"] = "1"
        listOf(
            "idle-session-check-interval" to "idle_session_check_interval",
            "idle-session-timeout" to "idle_session_timeout",
            "min-idle-session" to "min_idle_session",
            "max-idle-session" to "max_idle_session"
        ).forEach { (clashKey, uriKey) ->
            proxy.text(clashKey, uriKey)?.let { query[uriKey] = it }
        }
        return buildUri("anytls", password, server, port.toString(), query, name)
    }

    private fun tuicUri(
        proxy: Map<String, Any?>,
        server: String,
        port: Int,
        name: String
    ): String? {
        val uuid = proxy.text("uuid") ?: return null
        val password = proxy.text("password", "token")
        val query = linkedMapOf<String, String>()
        applyTlsQuery(query, proxy)
        proxy.text("sni", "servername")?.let { query["sni"] = it }
        proxy.stringList("alpn").takeIf { it.isNotEmpty() }?.let { query["alpn"] = it.joinToString(",") }
        proxy.text("client-fingerprint")?.let { query["fp"] = it }
        if (proxy.bool("skip-cert-verify") == true) query["allowInsecure"] = "1"
        proxy.text("congestion-controller", "congestion_control")?.let { query["congestion_control"] = it }
        proxy.text("udp-relay-mode", "udp_relay_mode")?.let { query["udp_relay_mode"] = it }
        if (proxy.bool("reduce-rtt", "reduce_rtt") == true) query["reduce_rtt"] = "1"

        return buildUri(
            scheme = "tuic",
            userInfo = if (password.isNullOrEmpty()) uuid else "$uuid:$password",
            server = server,
            port = port.toString(),
            query = query,
            name = name
        )
    }

    private fun naiveUri(
        proxy: Map<String, Any?>,
        server: String,
        port: Int,
        name: String
    ): String? {
        val username = proxy.text("username") ?: return null
        val password = proxy.text("password") ?: return null
        val query = linkedMapOf<String, String>()
        applyTlsQuery(query, proxy)
        proxy.text("sni", "servername")?.let { query["sni"] = it }
        if (proxy.bool("skip-cert-verify") == true) query["allowInsecure"] = "1"
        return buildUri("naive+https", "$username:$password", server, port.toString(), query, name)
    }

    private fun wireGuardUri(
        proxy: Map<String, Any?>,
        server: String,
        port: Int,
        name: String
    ): String? {
        val privateKey = proxy.text("private-key", "private_key") ?: return null
        val publicKey = proxy.text("public-key", "public_key") ?: return null
        val query = linkedMapOf("publickey" to publicKey)
        proxy.text("pre-shared-key", "preshared-key", "pre_shared_key")
            ?.let { query["presharedkey"] = it }
        val addresses = buildList {
            addAll(proxy.stringList("ip", "address"))
            addAll(proxy.stringList("ipv6"))
        }
        if (addresses.isNotEmpty()) query["address"] = addresses.joinToString(",")
        proxy.int("mtu")?.let { query["mtu"] = it.toString() }
        proxy.stringList("reserved").takeIf { it.isNotEmpty() }
            ?.let { query["reserved"] = it.joinToString(",") }
        return buildUri("wireguard", privateKey, server, port.toString(), query, name)
    }

    private data class Transport(
        val type: String,
        val path: String? = null,
        val hosts: List<String> = emptyList(),
        val serviceName: String? = null,
        val maxEarlyData: Int? = null,
        val earlyDataHeaderName: String? = null
    )

    private fun transport(proxy: Map<String, Any?>): Transport {
        val rawType = proxy.text("network")?.lowercase().orEmpty()
        val type = when (rawType) {
            "", "tcp", "raw", "none" -> "tcp"
            "h2" -> "http"
            "http-upgrade" -> "httpupgrade"
            else -> rawType
        }
        return when (type) {
            "ws" -> {
                val options = proxy.map("ws-opts", "ws_opts")
                if (options?.bool("v2ray-http-upgrade", "v2ray_http_upgrade") == true) {
                    return Transport(
                        type = "httpupgrade",
                        path = options.text("path") ?: "/",
                        hosts = headerValues(options.map("headers"), "Host")
                    )
                }
                Transport(
                    type = type,
                    path = options?.text("path") ?: "/",
                    hosts = headerValues(options?.map("headers"), "Host"),
                    maxEarlyData = options?.int("max-early-data", "max_early_data"),
                    earlyDataHeaderName = options?.text("early-data-header-name", "early_data_header_name")
                )
            }
            "grpc" -> {
                val options = proxy.map("grpc-opts", "grpc_opts")
                Transport(type = type, serviceName = options?.text("grpc-service-name", "service-name"))
            }
            "http" -> {
                val options = proxy.map("h2-opts", "h2_opts") ?: proxy.map("http-opts", "http_opts")
                Transport(
                    type = type,
                    path = options?.stringList("path")?.firstOrNull() ?: options?.text("path") ?: "/",
                    hosts = options?.stringList("host").orEmpty().ifEmpty {
                        headerValues(options?.map("headers"), "Host")
                    }
                )
            }
            "httpupgrade" -> {
                val options = proxy.map("http-upgrade-opts", "http_upgrade_opts")
                Transport(
                    type = type,
                    path = options?.text("path") ?: "/",
                    hosts = listOfNotNull(options?.text("host"))
                )
            }
            "xhttp" -> {
                val options = proxy.map("xhttp-opts")
                Transport(type = type, path = options?.text("path"),
                    hosts = options?.stringList("host").orEmpty())
            }
            else -> Transport(type = type)
        }
    }

    private fun applyTransportQuery(query: MutableMap<String, String>, transport: Transport) {
        query["type"] = transport.type
        transport.path?.let { query["path"] = it }
        if (transport.hosts.isNotEmpty()) query["host"] = transport.hosts.joinToString(",")
        transport.serviceName?.let { query["serviceName"] = it }
        transport.maxEarlyData?.let { query["ed"] = it.toString() }
        transport.earlyDataHeaderName?.let { query["eh"] = it }
    }

    private fun applyTlsQuery(query: MutableMap<String, String>, proxy: Map<String, Any?>) {
        proxy.map("ech-opts", "ech_opts")?.let { ech ->
            ech.bool("enable", "enabled")?.let { query["echEnabled"] = it.toString() }
            ech.text("config")?.let { query["echConfigList"] = it }
            ech.text("query-server-name", "query_server_name")?.let { query["echQueryServerName"] = it }
        }
        proxy.text("certificate", "cert")?.let { query["certificate"] = it }
        proxy.text("fingerprint")?.let { query["pinSHA256"] = it }
        proxy.stringList("certificate-public-key-sha256", "certificate_public_key_sha256")
            .takeIf { it.isNotEmpty() }?.let { query["certificate_public_key_sha256"] = it.joinToString(",") }
        proxy.text("tls-min-version", "min-version", "min_version")?.let { query["minVersion"] = it }
        proxy.text("tls-max-version", "max-version", "max_version")?.let { query["maxVersion"] = it }
    }

    private fun headerValues(headers: Map<String, Any?>?, name: String): List<String> {
        val value = headers?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        return value.asStringList()
    }

    private fun credentials(username: String?, password: String?): String? {
        if (username.isNullOrEmpty()) return null
        return if (password.isNullOrEmpty()) username else "$username:$password"
    }

    private fun buildUri(
        scheme: String,
        userInfo: String? = null,
        server: String,
        port: String,
        query: Map<String, String> = emptyMap(),
        name: String,
        encodedUserInfo: String? = null
    ): String {
        val host = normalizeHost(server)
        return buildString {
            append(scheme).append("://")
            when {
                encodedUserInfo != null -> append(encodedUserInfo).append('@')
                userInfo != null -> append(encode(userInfo)).append('@')
            }
            append(host).append(':').append(port)
            if (query.isNotEmpty()) {
                append('?')
                append(query.entries.joinToString("&") { (key, value) ->
                    "${encode(key)}=${encode(value)}"
                })
            }
            append('#').append(encode(name))
        }
    }

    private fun normalizeHost(value: String): String {
        val host = value.trim().removePrefix("[").removeSuffix("]")
        return if (':' in host) "[$host]" else runCatching { IDN.toASCII(host) }.getOrDefault(host)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun Any?.asStringMap(): Map<String, Any?>? = (this as? Map<*, *>)
        ?.entries
        ?.associate { it.key.toString() to it.value }

    private fun Any?.asList(): List<Any?> = this as? List<Any?> ?: emptyList()

    private fun Any?.scalarText(): String? = when (this) {
        null -> null
        is String -> trim().takeIf(String::isNotEmpty)
        is Number, is Boolean -> toString()
        else -> null
    }

    private fun Any?.asBoolean(): Boolean? = when (this) {
        is Boolean -> this
        is Number -> toInt() != 0
        is String -> when (trim().lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
        else -> null
    }

    private fun Any?.asStringList(): List<String> = when (this) {
        is List<*> -> mapNotNull { it.scalarText() }
        else -> scalarText()?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
    }

    private fun Map<String, Any?>.value(vararg keys: String): Any? {
        keys.forEach { key ->
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.let { return it.value }
        }
        return null
    }

    private fun Map<String, Any?>.text(vararg keys: String): String? = value(*keys).scalarText()

    private fun Map<String, Any?>.int(vararg keys: String): Int? =
        value(*keys)?.let { value ->
            when (value) {
                is Number -> value.toInt()
                else -> value.scalarText()?.toIntOrNull()
            }
        }

    private fun Map<String, Any?>.bool(vararg keys: String): Boolean? = value(*keys).asBoolean()

    private fun Map<String, Any?>.map(vararg keys: String): Map<String, Any?>? =
        value(*keys).asStringMap()

    private fun Map<String, Any?>.stringList(vararg keys: String): List<String> =
        value(*keys).asStringList()

    private val SUPPORTED_TRANSPORTS = setOf("tcp", "ws", "grpc", "http", "httpupgrade", "quic")
}
