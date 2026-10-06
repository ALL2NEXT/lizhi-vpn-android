package io.github.HanJason233.chimera.network

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.encodeUtf8

/** 从 sing-box / Xray 配置、出站对象或对象数组中提取真实代理节点。 */
internal object JsonSubscriptionParser {
    private enum class Format { SING_BOX, XRAY }
    fun parseToUris(content: String): List<String>? {
        if (!content.trimStart().let { it.startsWith("{") || it.startsWith("[") }) return null
        val root = runCatching { JsonParser.parseString(content) }.getOrNull() ?: return emptyList()
        return extract(root)
    }

    private fun extract(value: JsonElement): List<String> {
        if (value.isJsonArray) return value.asJsonArray.flatMap(::extract)
        if (!value.isJsonObject) return emptyList()
        val json = value.asJsonObject
        if (json.has("outbounds") || json.has("endpoints")) {
            return json.objects("outbounds").flatMap(::extract) +
                json.objects("endpoints").flatMap(::extract)
        }
        if (json.has("proxies")) {
            return json.objects("proxies").mapNotNull {
                ClashYamlParser.proxyToUri(it.toMap(), allowNativeTransport = true)
            }
        }
        if (json.has("type") && json.has("server") && json.has("port") && !json.has("server_port")) {
            return listOfNotNull(ClashYamlParser.proxyToUri(json.toMap(), allowNativeTransport = true))
        }
        // 也接受分享 URI 内部使用的原始 VMess JSON。
        if (json.has("add") && json.has("id")) {
            val payload = JsonObject()
            listOf("v", "ps", "add", "port", "id", "aid", "scy", "net", "type", "host", "path",
                "tls", "sni", "alpn", "fp", "serviceName", "ed", "eh", "seed", "mode", "extra",
                "global_padding", "authenticated_length", "packetEncoding",
                "certificate", "certificate_public_key_sha256", "pinSHA256", "verifyPeerCertByName",
                "echEnabled", "echConfigList", "echQueryServerName").forEach {
                json.get(it)?.let { value -> payload.add(it, value.deepCopy()) }
            }
            return listOf("vmess://" + payload.toString().encodeUtf8().base64())
        }
        return runCatching {
            when {
                json.has("protocol") -> xray(json)
                json.has("type") -> singBox(json)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun singBox(outbound: JsonObject): List<String> {
        val type = when (outbound.text("type")) {
            "shadowsocks" -> "ss"
            "socks" -> when (outbound.text("version")) {
                "4" -> "socks4"
                "4a" -> "socks4a"
                else -> "socks5"
            }
            else -> outbound.text("type")
        } ?: return emptyList()
        val proxy = mutableMapOf<String, Any?>("type" to type, "name" to name(outbound, type))
        outbound.copyTo(proxy,
            "server" to "server", "server_port" to "port",
            "uuid" to "uuid", "password" to "password", "username" to "username",
            "method" to "cipher", "security" to "cipher", "alter_id" to "alterId",
            "flow" to "flow", "packet_encoding" to "packet-encoding",
            "congestion_control" to "congestion_control", "udp_relay_mode" to "udp_relay_mode",
            "zero_rtt_handshake" to "reduce-rtt", "mtu" to "mtu",
            "idle_session_check_interval" to "idle-session-check-interval",
            "idle_session_timeout" to "idle-session-timeout", "min_idle_session" to "min-idle-session"
        )
        if (outbound.has("plugin")) {
            proxy["plugin"] = outbound.text("plugin") +
                outbound.text("plugin_opts")?.let { ";" + it }.orEmpty()
        }
        outbound.obj("tls")?.let { tls ->
            proxy["tls"] = tls.boolean("enabled") ?: false
            tls.copyTo(proxy, "server_name" to "sni", "insecure" to "skip-cert-verify",
                "alpn" to "alpn", "min_version" to "min_version", "max_version" to "max_version",
                "certificate_public_key_sha256" to "certificate_public_key_sha256")
            tls.get("certificate")?.let { proxy["certificate"] = it.textLines() }
            tls.obj("utls")?.takeIf { it.boolean("enabled") == true }?.let {
                proxy["client-fingerprint"] = it.text("fingerprint")
            }
            tls.obj("reality")?.takeIf { it.boolean("enabled") == true }?.let {
                proxy["reality-opts"] = mapOf("public-key" to it.text("public_key"), "short-id" to it.text("short_id"))
            }
            tls.obj("ech")?.let {
                proxy["ech-opts"] = mapOf("enabled" to it.boolean("enabled"), "config" to it.get("config")?.textLines())
            }
        }
        outbound.obj("transport")?.let { transport ->
            val network = transport.text("type") ?: "tcp"
            proxy["network"] = network
            val opts = mutableMapOf<String, Any?>()
            transport.copyTo(opts, "path" to "path", "host" to "host", "headers" to "headers",
                "service_name" to "grpc-service-name", "max_early_data" to "max-early-data",
                "early_data_header_name" to "early-data-header-name")
            proxy[transportOptions(network)] = opts
        }
        outbound.obj("obfs")?.let {
            proxy["obfs"] = it.text("type")
            proxy["obfs-password"] = it.text("password")
        }
        outbound.get("server_ports")?.let { ports ->
            proxy["ports"] = ports.textList().joinToString(",") { it.replace(':', '-') }
        }
        if (type == "wireguard") {
            outbound.copyTo(proxy, "private_key" to "private-key", "local_address" to "address",
                "address" to "address")
            return outbound.objects("peers").mapNotNull { peer ->
                val copy = proxy.toMutableMap()
                peer.copyTo(copy, "address" to "server", "port" to "port", "public_key" to "public-key",
                    "pre_shared_key" to "pre-shared-key", "reserved" to "reserved")
                convert(copy, outbound, Format.SING_BOX)
            }.ifEmpty {
                outbound.copyTo(proxy, "peer_public_key" to "public-key", "pre_shared_key" to "pre-shared-key",
                    "reserved" to "reserved")
                listOfNotNull(convert(proxy, outbound, Format.SING_BOX))
            }
        }
        return listOfNotNull(convert(proxy, outbound, Format.SING_BOX))
    }

    private fun xray(outbound: JsonObject): List<String> {
        val protocol = outbound.text("protocol") ?: return emptyList()
        val settings = outbound.obj("settings") ?: return emptyList()
        val type = when (protocol) {
            "shadowsocks" -> "ss"
            "hysteria" -> if (settings.text("version") == "2") "hysteria2" else return emptyList()
            else -> protocol
        }
        val base = mutableMapOf<String, Any?>("type" to type, "name" to name(outbound, type))
        xrayStream(outbound.obj("streamSettings"), base)
        if (type == "wireguard") {
            settings.copyTo(base, "secretKey" to "private-key", "address" to "address", "mtu" to "mtu",
                "reserved" to "reserved")
            return settings.objects("peers").mapNotNull { peer ->
                val endpoint = ("http://" + peer.text("endpoint")).toHttpUrlOrNull() ?: return@mapNotNull null
                val proxy = base.toMutableMap().apply {
                    put("server", endpoint.host)
                    put("port", endpoint.port)
                }
                peer.copyTo(proxy, "publicKey" to "public-key", "preSharedKey" to "pre-shared-key")
                NodeUriParameters.add(
                    convert(proxy, outbound, Format.XRAY) ?: return@mapNotNull null,
                    buildMap {
                        peer.text("keepAlive")?.let { put("keepalive", it) }
                        peer.get("allowedIPs")?.let { put("allowed_ips", it.textList().joinToString(",")) }
                    }
                )
            }
        }
        if (type == "hysteria2") {
            settings.copyTo(base, "address" to "server", "port" to "port")
            val stream = outbound.obj("streamSettings")
            base["password"] = stream?.obj("hysteriaSettings")?.text("auth")
            stream?.obj("finalmask")?.obj("quicParams")?.obj("udpHop")?.let {
                base["ports"] = it.text("ports")
            }
            stream?.obj("finalmask")?.objects("udp")?.firstOrNull {
                it.text("type") == "salamander"
            }?.let {
                base["obfs"] = "salamander"
                base["obfs-password"] = it.obj("settings")?.text("password")
            }
            return listOfNotNull(convert(base, outbound, Format.XRAY))
        }
        val listKey = if (type == "vmess" || type == "vless") "vnext" else "servers"
        val servers = settings.objects(listKey).ifEmpty {
            if (settings.has("address")) listOf(settings) else emptyList()
        }
        return servers.flatMap { server ->
            val users = server.objects("users").ifEmpty { listOf(server) }
            users.mapNotNull { user ->
                val proxy = base.toMutableMap()
                server.copyTo(proxy, "address" to "server", "port" to "port",
                    "password" to "password", "method" to "cipher")
                user.copyTo(proxy, "id" to "uuid", "alterId" to "alterId", "security" to "cipher",
                    "flow" to "flow", "user" to "username", "pass" to "password")
                var result = convert(proxy, outbound, Format.XRAY)
                if (result != null && type == "vless") {
                    result = NodeUriParameters.add(result, mapOf("encryption" to (user.text("encryption") ?: "none")))
                }
                result
            }
        }
    }

    private fun xrayStream(stream: JsonObject?, proxy: MutableMap<String, Any?>) {
        if (stream == null) return
        val rawNetwork = stream.text("network") ?: "tcp"
        val network = when (rawNetwork) {
            "raw" -> "tcp"
            "h2" -> "http"
            "http-upgrade" -> "httpupgrade"
            "splithttp" -> "xhttp"
            else -> rawNetwork
        }
        proxy["network"] = network
        val transport = stream.obj(rawNetwork + "Settings") ?: stream.obj(network + "Settings")
        val opts = mutableMapOf<String, Any?>()
        transport?.copyTo(opts, "path" to "path", "host" to "host", "headers" to "headers",
            "serviceName" to "grpc-service-name", "maxEarlyData" to "max-early-data",
            "earlyDataHeaderName" to "early-data-header-name")
        proxy[transportOptions(network)] = opts
        val security = stream.text("security")
        proxy["tls"] = security == "tls" || security == "reality"
        val tls = stream.obj(if (security == "reality") "realitySettings" else "tlsSettings") ?: return
        tls.copyTo(proxy, "serverName" to "sni", "allowInsecure" to "skip-cert-verify",
            "alpn" to "alpn", "fingerprint" to "client-fingerprint",
            "pinnedPeerCertSha256" to "fingerprint")
        if (security == "reality") {
            proxy["reality-opts"] = mapOf(
                "public-key" to (tls.text("password") ?: tls.text("publicKey")),
                "short-id" to tls.text("shortId")
            )
        }
        tls.text("echConfigList")?.let {
            proxy["ech-opts"] = mapOf("enabled" to true, "config" to it)
        }
    }

    private fun transportOptions(network: String): String = when (network) {
        "grpc" -> "grpc-opts"
        "http" -> "h2-opts"
        "httpupgrade" -> "http-upgrade-opts"
        else -> network + "-opts"
    }

    private fun convert(proxy: Map<String, Any?>, native: JsonObject, format: Format): String? {
        val uri = ClashYamlParser.proxyToUri(proxy, allowNativeTransport = true) ?: return null
        var result = uri
        if (format == Format.SING_BOX) {
            val extras = mutableMapOf<String, String>()
            listOf("hop_interval", "network", "heartbeat",
                "zero_rtt_handshake", "udp_over_stream", "udp_over_tcp",
                "insecure_concurrency", "quic_congestion_control", "quic", "workers",
                "global_padding", "authenticated_length", "packet_encoding", "path").forEach {
                native.text(it)?.let { value -> extras[it] = value }
            }
            native.obj("headers")?.get("Host")?.textList()?.firstOrNull()?.let { extras["host"] = it }
            native.obj("transport")?.let { transport ->
                listOf("method" to "method", "idle_timeout" to "idleTimeout", "ping_timeout" to "pingTimeout")
                    .forEach { (source, key) -> transport.text(source)?.let { extras[key] = it } }
            }
            result = NodeUriParameters.add(result, extras)
        } else {
            val stream = native.obj("streamSettings")
            val xhttp = stream?.obj("xhttpSettings") ?: stream?.obj("splithttpSettings")
            val extras = mutableMapOf<String, String>()
            xhttp?.text("mode")?.let { extras["mode"] = it }
            xhttp?.get("extra")?.let { extras["extra"] = it.toString() }
            stream?.obj("grpcSettings")?.let {
                it.text("authority")?.let { value -> extras["authority"] = value }
                if (it.boolean("multiMode") == true) extras["mode"] = "multi"
            }
            stream?.obj("kcpSettings")?.let {
                it.text("seed")?.let { value -> extras["seed"] = value }
                it.obj("header")?.text("type")?.let { value -> extras["headerType"] = value }
            }
            stream?.obj("quicSettings")?.let {
                it.text("security")?.let { value -> extras["quicSecurity"] = value }
                it.text("key")?.let { value -> extras["key"] = value }
                it.obj("header")?.text("type")?.let { value -> extras["headerType"] = value }
            }
            (stream?.obj("tcpSettings") ?: stream?.obj("rawSettings"))?.obj("header")
                ?.takeIf { it.text("type") == "http" }?.let {
                    extras["headerType"] = "http"
                    it.obj("request")?.let { request ->
                        request.get("path")?.textList()?.firstOrNull()?.let { value -> extras["path"] = value }
                        request.obj("headers")?.get("Host")?.textList()?.let { values ->
                            extras["host"] = values.joinToString(",")
                        }
                    }
                }
            native.obj("settings")?.obj("headers")?.get("Host")?.textList()?.firstOrNull()
                ?.let { extras["host"] = it }
            (stream?.obj("tlsSettings") ?: stream?.obj("realitySettings"))?.let {
                it.text("verifyPeerCertByName")?.let { value -> extras["verifyPeerCertByName"] = value }
                it.text("spiderX")?.let { value -> extras["spx"] = value }
            }
            result = NodeUriParameters.add(result, extras)
        }
        return result
    }

    private fun name(json: JsonObject, fallback: String): String =
        json.text("remarks") ?: json.text("name") ?: json.text("tag") ?: fallback

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.objects(key: String): List<JsonObject> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
            it.takeIf(JsonElement::isJsonObject)?.asJsonObject
        }.orEmpty()

    private fun JsonObject.text(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)

    private fun JsonObject.boolean(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let {
            when (it.asString.lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> null }
        }

    private fun JsonObject.copyTo(target: MutableMap<String, Any?>, vararg keys: Pair<String, String>) {
        keys.forEach { (source, destination) ->
            get(source)?.takeUnless { it.isJsonNull }?.let { target[destination] = it.toValue() }
        }
    }

    private fun JsonElement.toValue(): Any? = when {
        isJsonNull -> null
        isJsonArray -> asJsonArray.map { it.toValue() }
        isJsonObject -> asJsonObject.toMap()
        asJsonPrimitive.isBoolean -> asBoolean
        asJsonPrimitive.isNumber -> asNumber
        else -> asString
    }

    private fun JsonObject.toMap(): Map<String, Any?> = entrySet().associate { it.key to it.value.toValue() }
    private fun JsonElement.textList(): List<String> =
        if (isJsonArray) asJsonArray.mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }
        else listOfNotNull(takeIf(JsonElement::isJsonPrimitive)?.asString)
    private fun JsonElement.textLines(): String = textList().joinToString("\n")
}
