package io.github.HanJason233.chimera.core.singbox

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeType
import io.github.HanJason233.chimera.util.checkedSingBoxTransport
import io.github.HanJason233.chimera.util.createSingBoxTls
import io.github.HanJason233.chimera.util.createTransport
import io.github.HanJason233.chimera.util.createVmessTransport
import io.github.HanJason233.chimera.util.decodeBase64Compat
import io.github.HanJason233.chimera.util.isSupportedTransport
import io.github.HanJason233.chimera.util.parseCsvParams
import io.github.HanJason233.chimera.util.parseHysteria2ServerPorts
import io.github.HanJason233.chimera.util.parseJsonBoolean
import io.github.HanJason233.chimera.util.parseJsonInt
import io.github.HanJason233.chimera.util.parseJsonString
import io.github.HanJason233.chimera.util.parseSubscriptionEch
import io.github.HanJason233.chimera.util.parseShadowsocksMethodPassword
import io.github.HanJason233.chimera.util.parseWireGuardReserved
import io.github.HanJason233.chimera.util.queryParamEnabled
import io.github.HanJason233.chimera.util.queryParamFirst
import io.github.HanJason233.chimera.util.resolveUriTransportType
import io.github.HanJason233.chimera.util.resolveVmessTransportType
import io.github.HanJason233.chimera.util.uriTlsParameters
import android.net.Uri

internal class SingBoxOutboundBuilder {
    private val gson: Gson = Gson()

    internal fun createOutbounds(
        nodes: List<Node>,
        selectedNodeId: String?,
        hysteriaUpMbps: Int,
        hysteriaDownMbps: Int
    ): JsonArray {
        val outbounds = JsonArray()

        // 核心只保留手动选择器；备用节点由服务在主节点超时时按需预检。
        val selectorGroup = JsonObject().apply {
            addProperty("type", "selector")
            addProperty("tag", "proxy")
            val outboundTags = JsonArray()
            nodes.forEach { outboundTags.add(it.id) } // 添加所有节点ID
            add("outbounds", outboundTags)

            // 连接指定节点时默认使用该节点；没有指定节点时回退到列表第一个节点。
            val defaultNodeId = selectedNodeId?.takeIf { id -> nodes.any { it.id == id } } ?: nodes.first().id
            addProperty("default", defaultNodeId)
        }
        outbounds.add(selectorGroup)

        // 2. 各节点独立出站
        nodes.forEach { node ->
            outbounds.add(createNodeOutbound(node, node.id, hysteriaUpMbps, hysteriaDownMbps))
        }

        // 3. 其他出站（直连、阻断和 DNS）
        outbounds.add(JsonObject().apply {
            addProperty("type", "direct")
            addProperty("tag", "direct")
        })

        outbounds.add(JsonObject().apply {
            addProperty("type", "block")
            addProperty("tag", "block")
        })

        return outbounds
    }

    internal fun createNodeOutbound(
        node: Node,
        tag: String,
        hysteriaUpMbps: Int = AppConfig.HYSTERIA_UP_MBPS,
        hysteriaDownMbps: Int = AppConfig.HYSTERIA_DOWN_MBPS
    ): JsonObject {
        val outbound = when (node.type) {
            NodeType.VLESS -> createVlessOutbound(node)
            NodeType.VMESS -> createVmessOutbound(node)
            NodeType.TROJAN -> createTrojanOutbound(node)
            NodeType.HYSTERIA2 -> createHysteria2Outbound(node, hysteriaUpMbps, hysteriaDownMbps)
            NodeType.ANYTLS -> createAnyTlsOutbound(node)
            NodeType.TUIC -> createTuicOutbound(node)
            NodeType.NAIVE -> createNaiveOutbound(node)
            NodeType.WIREGUARD -> createWireGuardOutbound(node)
            NodeType.MASQUE -> error("MASQUE 需要选择 Xray")
            NodeType.SHADOWSOCKS -> createShadowsocksOutbound(node)
            NodeType.SOCKS -> createSocksOutbound(node)
            NodeType.HTTP -> createHttpOutbound(node)
            else -> createVlessOutbound(node)
        }
        // 覆盖 tag
        outbound.addProperty("tag", tag)
        return outbound
    }

    internal fun completeConfig(config: JsonObject, nodes: List<Node>): JsonObject {
        val outbounds = config.getAsJsonArray("outbounds") ?: return config
        // WireGuard became an endpoint in sing-box 1.13; selectors and route
        // rules still address it using the same node tag.
        val endpoints = JsonArray()
        outbounds.filter { it.asJsonObject.get("type")?.asString == "wireguard" }
            .forEach { endpoints.add(it) }
        if (endpoints.size() > 0) {
            endpoints.forEach { outbounds.remove(it) }
            config.add("endpoints", endpoints)
        }

        val dns = config.getAsJsonObject("dns") ?: return config
        val rules = dns.getAsJsonArray("rules") ?: JsonArray()
        val bootstrapRules = JsonArray()
        outbounds.forEachIndexed { index, element ->
            val outbound = element.asJsonObject
            val tls = outbound.getAsJsonObject("tls") ?: return@forEachIndexed
            val ech = tls.getAsJsonObject("ech") ?: return@forEachIndexed
            if (ech.has("config")) return@forEachIndexed
            val node = nodes.firstOrNull { it.id == outbound.get("tag")?.asString }
                ?: nodes.singleOrNull() ?: return@forEachIndexed
            val parameters = if (node.type == NodeType.VMESS) {
                JsonParser.parseString(decodeBase64Compat(node.getRawLinkPlain().removePrefix("vmess://"))).asJsonObject
            } else uriTlsParameters(Uri.parse(node.getRawLinkPlain()))
            val options = parseSubscriptionEch(parameters) ?: return@forEachIndexed
            val domain = options.queryServerName ?: tls.get("server_name").asString
            val serverTag = options.dnsServer?.let { address ->
                val tag = "dns-ech-$index"
                dns.getAsJsonArray("servers").addDnsServer(
                    address, tag, null, "dns-local", "prefer_ipv4"
                )
                tag
            } ?: "dns-direct"
            // ECH has to be obtained before the proxy TLS handshake. Routing
            // this HTTPS query through dns-remote (detour=proxy) deadlocks it.
            bootstrapRules.add(JsonObject().apply {
                add("domain", JsonArray().apply { add(domain) })
                addProperty("server", serverTag)
            })
        }
        rules.forEach { bootstrapRules.add(it) }
        dns.add("rules", bootstrapRules)
        return config
    }

    private fun createVlessOutbound(node: Node): JsonObject {
        val rawLink = node.getRawLinkPlain()
        val uri = Uri.parse(rawLink)

        return JsonObject().apply {
            addProperty("type", "vless")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            addProperty("uuid", uri.userInfo ?: "")
            addProperty("flow", uri.getQueryParameter("flow") ?: "")
            queryParamFirst(uri, "packetEncoding", "packet_encoding")?.let { addProperty("packet_encoding", it) }

            val security = queryParamFirst(uri, "security")?.lowercase()
            if (security == "tls" || security == "reality") {
                add("tls", createSingBoxTls(
                    uriTlsParameters(uri), node.server,
                    reality = security == "reality"
                ))
            }

            val transport = checkedSingBoxTransport(
                resolveUriTransportType(uri)
            )
            if (isSupportedTransport(transport)) {
                add("transport", createTransport(transport, uri))
            }
        }
    }

    private fun createVmessOutbound(node: Node): JsonObject {
        val rawLink = node.getRawLinkPlain()
        val base64Content = rawLink.removePrefix("vmess://")
        val jsonStr = decodeBase64Compat(base64Content)
        val vmessConfig = gson.fromJson(jsonStr, JsonObject::class.java)

        return JsonObject().apply {
            addProperty("type", "vmess")
            addProperty("server", vmessConfig.get("add")?.asString ?: node.server)
            addProperty("server_port", vmessConfig.get("port")?.asInt ?: node.port)
            addProperty("uuid", vmessConfig.get("id")?.asString ?: "")
            addProperty("alter_id", parseJsonInt(vmessConfig.get("aid")) ?: 0)
            addProperty("security", parseJsonString(vmessConfig.get("scy")) ?: parseJsonString(vmessConfig.get("security")) ?: "auto")
            parseJsonBoolean(vmessConfig.get("global_padding"))?.let { addProperty("global_padding", it) }
            parseJsonBoolean(vmessConfig.get("authenticated_length"))?.let { addProperty("authenticated_length", it) }
            (parseJsonString(vmessConfig.get("packetEncoding")) ?: parseJsonString(vmessConfig.get("packet_encoding")))
                ?.let { addProperty("packet_encoding", it) }

            val tls = parseJsonString(vmessConfig.get("tls"))?.lowercase()
            if (tls == "tls") {
                add("tls", createSingBoxTls(vmessConfig, node.server))
            }

            val network = checkedSingBoxTransport(
                resolveVmessTransportType(vmessConfig)
            )
            if (isSupportedTransport(network)) {
                add("transport", createVmessTransport(network, vmessConfig))
            }
        }
    }


    private fun createTrojanOutbound(node: Node): JsonObject {
        val rawLink = node.getRawLinkPlain()
        val uri = Uri.parse(rawLink)

        return JsonObject().apply {
            addProperty("type", "trojan")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            addProperty("password", uri.userInfo ?: "")

            add("tls", createSingBoxTls(uriTlsParameters(uri), node.server))

            val transport = checkedSingBoxTransport(
                resolveUriTransportType(uri)
            )
            if (isSupportedTransport(transport)) {
                add("transport", createTransport(transport, uri))
            }
        }
    }

    private fun createHysteria2Outbound(
        node: Node,
        hysteriaUpMbps: Int,
        hysteriaDownMbps: Int
    ): JsonObject {
        val rawLink = node.getRawLinkPlain()
        val normalizedLink = rawLink.replace("hy2://", "hysteria2://")
        val uri = Uri.parse(normalizedLink)
        val serverPorts = parseHysteria2ServerPorts(normalizedLink)

        return JsonObject().apply {
            addProperty("type", "hysteria2")
            addProperty("server", node.server)
            if (serverPorts.size > 1 || serverPorts.any { it.contains(":") }) {
                add("server_ports", JsonArray().apply { serverPorts.forEach { add(it) } })
            } else {
                addProperty("server_port", serverPorts.firstOrNull()?.toIntOrNull() ?: node.port)
            }
            addProperty("password", uri.userInfo ?: "")
            queryParamFirst(uri, "hopInterval", "hop_interval", "interval")?.let {
                addProperty("hop_interval", if (it.toLongOrNull() != null) "${it}s" else it)
            }
            if (hysteriaUpMbps > 0) {
                addProperty("up_mbps", hysteriaUpMbps)
            }
            if (hysteriaDownMbps > 0) {
                addProperty("down_mbps", hysteriaDownMbps)
            }
            queryParamFirst(uri, "network")?.let { addProperty("network", it) }

            add("tls", createSingBoxTls(uriTlsParameters(uri), node.server, allowUtls = false))

            val obfsType = queryParamFirst(uri, "obfs")
            val obfsPassword = queryParamFirst(uri, "obfs-password", "obfs_password", "obfsPassword")

            if (!obfsType.isNullOrEmpty()) {
                add("obfs", JsonObject().apply {
                    addProperty("type", obfsType)
                    addProperty("password", obfsPassword ?: "")
                })
            }
        }
    }

    private fun createAnyTlsOutbound(node: Node): JsonObject {
        val rawLink = node.getRawLinkPlain()
        val uri = Uri.parse(rawLink)
        val password = uri.userInfo ?: queryParamFirst(uri, "password", "pass", "token") ?: ""
        val idleSessionCheckInterval = queryParamFirst(uri, "idle_session_check_interval")
        val idleSessionTimeout = queryParamFirst(uri, "idle_session_timeout")
        val minIdleSession = queryParamFirst(uri, "min_idle_session")?.toIntOrNull()
        require(queryParamFirst(uri, "max_idle_session") == null) {
            "当前 sing-box 不支持 AnyTLS max_idle_session，请移除此参数"
        }

        return JsonObject().apply {
            addProperty("type", "anytls")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            addProperty("password", password)
            idleSessionCheckInterval?.let { addProperty("idle_session_check_interval", it) }
            idleSessionTimeout?.let { addProperty("idle_session_timeout", it) }
            minIdleSession?.let { addProperty("min_idle_session", it) }
            add("tls", createSingBoxTls(uriTlsParameters(uri), node.server))
        }
    }

    private fun createTuicOutbound(node: Node): JsonObject {
        val rawLink = node.getRawLinkPlain()
        val uri = Uri.parse(rawLink)
        val userInfo = uri.userInfo ?: ""
        val userInfoParts = userInfo.split(":", limit = 2)
        val uuid = uri.getQueryParameter("uuid") ?: uri.getQueryParameter("id") ?: userInfoParts.getOrNull(0) ?: ""
        val password = uri.getQueryParameter("password")
            ?: uri.getQueryParameter("token")
            ?: userInfoParts.getOrNull(1)
            ?: ""

        return JsonObject().apply {
            addProperty("type", "tuic")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            addProperty("uuid", uuid)
            if (password.isNotEmpty()) {
                addProperty("password", password)
            }
            add("tls", createSingBoxTls(uriTlsParameters(uri), node.server, allowUtls = false))

            queryParamFirst(uri, "congestion_control", "cc")?.let { addProperty("congestion_control", it) }
            uri.getQueryParameter("udp_relay_mode")?.let { addProperty("udp_relay_mode", it) }
            uri.getQueryParameter("network")?.let { addProperty("network", it) }
            uri.getQueryParameter("heartbeat")?.let { addProperty("heartbeat", it) }

            if (queryParamEnabled(uri, "zero_rtt_handshake", "0rtt", "reduce_rtt", "reduce-rtt")) {
                addProperty("zero_rtt_handshake", true)
            }
            if (queryParamEnabled(uri, "udp_over_stream")) {
                addProperty("udp_over_stream", true)
            }
        }
    }

    private fun createNaiveOutbound(node: Node): JsonObject {
        val rawLink = node.getRawLinkPlain()
        val uri = Uri.parse(rawLink)
        val userInfo = uri.userInfo ?: ""
        val userInfoParts = userInfo.split(":", limit = 2)
        val username = uri.getQueryParameter("username") ?: userInfoParts.getOrNull(0) ?: ""
        val password = uri.getQueryParameter("password") ?: userInfoParts.getOrNull(1) ?: ""

        return JsonObject().apply {
            addProperty("type", "naive")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            addProperty("username", username)
            addProperty("password", password)
            queryParamFirst(uri, "insecure_concurrency", "insecure-concurrency")?.toIntOrNull()?.let {
                addProperty("insecure_concurrency", it)
            }
            queryParamFirst(uri, "quic_congestion_control", "quic-congestion-control", "qcc")?.let {
                addProperty("quic_congestion_control", it)
            }
            if (queryParamEnabled(uri, "quic")) {
                addProperty("quic", true)
            }
            if (queryParamEnabled(uri, "udp_over_tcp", "udp-over-tcp", "uot")) {
                addProperty("udp_over_tcp", true)
            }
            add("tls", createSingBoxTls(uriTlsParameters(uri), node.server, allowUtls = false))
        }
    }

    private fun createWireGuardOutbound(node: Node): JsonObject {
        val uri = Uri.parse(node.getRawLinkPlain())
        val privateKey = uri.userInfo ?: queryParamFirst(uri, "private_key", "privateKey") ?: ""
        val publicKey = queryParamFirst(uri, "publickey", "peer_public_key", "peerPublicKey") ?: ""
        val addresses = parseCsvParams(queryParamFirst(uri, "address", "local_address", "localAddress"))
            .ifEmpty { listOf("10.0.0.2/32") }
        return JsonObject().apply {
            addProperty("type", "wireguard")
            addProperty("private_key", privateKey)
            add("address", JsonArray().apply { addresses.forEach(::add) })
            queryParamFirst(uri, "mtu")?.toIntOrNull()?.let { addProperty("mtu", it) }
            queryParamFirst(uri, "workers")?.toIntOrNull()?.let { addProperty("workers", it) }
            add("peers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", node.server)
                    addProperty("port", node.port)
                    addProperty("public_key", publicKey)
                    queryParamFirst(uri, "presharedkey", "pre_shared_key", "preSharedKey")
                        ?.let { addProperty("pre_shared_key", it) }
                    add("allowed_ips", JsonArray().apply { add("0.0.0.0/0"); add("::/0") })
                    parseWireGuardReserved(queryParamFirst(uri, "reserved"))?.let { reserved ->
                        require(reserved.all { it in 0..255 }) { "WireGuard reserved ???????" }
                        add("reserved", JsonArray().apply { reserved.forEach(::add) })
                    }
                })
            })
        }
    }

    private fun createShadowsocksOutbound(node: Node): JsonObject {
        val rawLink = node.getRawLinkPlain()
        val uri = Uri.parse(rawLink)
        val linkContent = rawLink.removePrefix("ss://").substringBefore("#")
        val methodPassword = parseShadowsocksMethodPassword(linkContent)

        val parts = methodPassword.split(":", limit = 2)
        val method = parts.getOrNull(0) ?: "aes-256-gcm"
        val password = parts.getOrNull(1) ?: ""
        val pluginSpec = uri.getQueryParameter("plugin").orEmpty().trim()

        return JsonObject().apply {
            addProperty("type", "shadowsocks")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            addProperty("method", method)
            addProperty("password", password)
            queryParamFirst(uri, "network")?.let { addProperty("network", it) }
            if (queryParamEnabled(uri, "udp_over_tcp", "udp-over-tcp", "uot")) {
                addProperty("udp_over_tcp", true)
            }
            if (pluginSpec.isNotEmpty()) {
                val pluginName = pluginSpec.substringBefore(";").trim()
                val pluginOptions = pluginSpec.substringAfter(";", "").replace("\\=", "=").trim()
                if (pluginName.isNotEmpty()) {
                    addProperty("plugin", pluginName)
                }
                if (pluginOptions.isNotEmpty()) {
                    addProperty("plugin_opts", pluginOptions)
                }
            }
        }
    }

    private fun createSocksOutbound(node: Node): JsonObject {
        val rawLink = node.getRawLinkPlain()
        val uri = Uri.parse(rawLink)
        val userInfo = uri.userInfo ?: ""
        val parts = userInfo.split(":", limit = 2)
        val username = parts.getOrNull(0) ?: ""
        val password = parts.getOrNull(1) ?: ""

        // 检测 SOCKS 版本 (socks4:// -> 4, socks5:// 或 socks:// -> 5)
        val version = when {
            rawLink.startsWith("socks4a://") -> "4a"
            rawLink.startsWith("socks4://") -> "4"
            else -> "5"
        }

        return JsonObject().apply {
            addProperty("type", "socks")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            addProperty("version", version)
            if (username.isNotEmpty()) {
                addProperty("username", username)
                addProperty("password", password)
            }
            if (queryParamEnabled(uri, "udp_over_tcp", "udp-over-tcp", "uot")) {
                addProperty("udp_over_tcp", true)
            }
        }
    }

    private fun createHttpOutbound(node: Node): JsonObject {
        val rawLink = node.getRawLinkPlain()
        val uri = Uri.parse(rawLink)
        val userInfo = uri.userInfo ?: ""
        val parts = userInfo.split(":", limit = 2)
        val username = parts.getOrNull(0) ?: ""
        val password = parts.getOrNull(1) ?: ""
        val useTls = rawLink.startsWith("https://")

        return JsonObject().apply {
            addProperty("type", "http")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            if (username.isNotEmpty()) {
                addProperty("username", username)
                addProperty("password", password)
            }
            queryParamFirst(uri, "path")?.let { addProperty("path", it) }
            queryParamFirst(uri, "host")?.let { host ->
                add("headers", JsonObject().apply { addProperty("Host", host) })
            }
            if (useTls) {
            add("tls", createSingBoxTls(uriTlsParameters(uri), node.server))
            }
        }
    }
}

private fun JsonArray.addDnsServer(
    address: String,
    tag: String,
    detour: String?,
    domainResolver: String,
    serverAddressStrategy: String
) {
    val uri = runCatching {
        java.net.URI(if (address.contains("://")) address else "udp://$address")
    }.getOrNull() ?: java.net.URI("udp://${AppConfig.VPN_DNS_CHINA}")
    val scheme = uri.scheme?.lowercase() ?: "udp"
    val type = when (scheme) {
        "https" -> "https"
        "tls" -> "tls"
        "tcp", "h2c" -> "tcp"
        else -> "udp"
    }
    val server = uri.host ?: address.substringBefore(':')
    val defaultPort = when (type) {
        "https" -> 443
        "tls" -> 853
        else -> 53
    }
    val port = if (uri.port > 0) uri.port else defaultPort
    add(JsonObject().apply {
        addProperty("type", type)
        addProperty("tag", tag)
        addProperty("server", server)
        addProperty("server_port", port)
        detour?.let { addProperty("detour", it) }
        add("domain_resolver", JsonObject().apply {
            addProperty("server", domainResolver)
            addProperty("strategy", serverAddressStrategy)
        })
        if (type == "https") {
            addProperty("path", uri.path?.takeIf(String::isNotBlank) ?: "/dns-query")
        }
    })
}
