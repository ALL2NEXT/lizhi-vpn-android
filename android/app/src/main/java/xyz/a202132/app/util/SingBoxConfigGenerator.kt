package xyz.a202132.app.util

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import xyz.a202132.app.AppConfig
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeType
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.data.model.TunStack
import xyz.a202132.app.data.model.UrlTestTargets
import android.net.Uri

/**
 * sing-box 配置生成器
 */
class SingBoxConfigGenerator {
    private companion object {
        val IPV4_ADDRESS = Regex(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}" +
                "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
        )
    }

    // Compact JSON reduces allocations and libbox parsing work. The config is
    // already truncated before debug logging, so pretty printing has no value.
    private val gson: Gson = Gson()
    
    /**
     * 生成完整的sing-box配置
     * @param nodes 所有可用节点列表
     * @param selectedNodeId 当前选择的节点ID（如果为null则默认选择auto）
     * @param ipv6Mode IPv6 路由模式
     */
    fun generateConfig(
        nodes: List<Node>,
        selectedNodeId: String?,
        proxyMode: ProxyMode,
        ipv6Mode: IPv6RoutingMode = IPv6RoutingMode.DISABLED,
        mtu: Int = AppConfig.VPN_MTU,
        internalSocksPort: Int? = null,
        hysteriaUpMbps: Int = AppConfig.HYSTERIA_UP_MBPS,
        hysteriaDownMbps: Int = AppConfig.HYSTERIA_DOWN_MBPS,
        tunStack: TunStack = TunStack.GVISOR,
        xrayBridgePorts: Map<String, Int> = emptyMap()
    ): String {
        // 如果没有节点，生成一个空配置防止崩溃
        if (nodes.isEmpty()) {
            return generateEmptyConfig()
        }

        val config = JsonObject().apply {
            // 从节点中提取唯一域名（忽略 IP 地址），以防止路由环路/DNS 死锁
            // 提取唯一域名和 IP 地址
            val uniqueServers = nodes.map { it.server }.distinct()
            val (nodeIPs, nodeDomains) = uniqueServers.partition {
                IPV4_ADDRESS.matches(it) || it.contains(":")
            }
                
            add("log", createLogConfig())
            add("dns", createDnsConfig(proxyMode, nodeDomains, ipv6Mode))
            add("inbounds", createInbounds(ipv6Mode, mtu, internalSocksPort, tunStack))
            add(
                "outbounds",
                createOutbounds(
                    nodes,
                    selectedNodeId,
                    hysteriaUpMbps,
                    hysteriaDownMbps,
                    xrayBridgePorts
                )
            )
            add("route", createRoute(proxyMode, nodeDomains, nodeIPs))
            add("experimental", createExperimental())
        }
        return gson.toJson(config)
    }
    
    private fun generateEmptyConfig(): String {
        val config = JsonObject().apply {
            add("log", createLogConfig())
            add(
                "inbounds",
                createInbounds(
                    IPv6RoutingMode.DISABLED,
                    AppConfig.VPN_MTU,
                    null
                )
            )
            add("outbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "direct")
                    addProperty("tag", "direct")
                })
            })
        }
        return gson.toJson(config)
    }
    
    /**
     * 生成用于延迟测试的配置
     * 仍然支持单节点测试（用于Socket测试无法连接时的回退）
     */
    fun generateTestConfig(
        node: Node,
        localPort: Int = 10808,
        hysteriaUpMbps: Int = AppConfig.HYSTERIA_UP_MBPS,
        hysteriaDownMbps: Int = AppConfig.HYSTERIA_DOWN_MBPS
    ): String {
        val isDomainServer = !IPV4_ADDRESS.matches(node.server) && !node.server.contains(":")
        val config = JsonObject().apply {
            add("log", JsonObject().apply {
                addProperty("level", "error")
            })
            add("dns", JsonObject().apply {
                add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("tag", "dns-direct")
                        addProperty("address", AppConfig.VPN_DNS_PRIMARY)
                        addProperty("detour", "direct")
                    })
                    add(JsonObject().apply {
                        addProperty("tag", "dns-local")
                        addProperty("address", AppConfig.VPN_DNS_CHINA)
                        addProperty("detour", "direct")
                    })
                })
                add("rules", JsonArray().apply {
                    if (isDomainServer) {
                        add(JsonObject().apply {
                            add("domain", JsonArray().apply { add(node.server) })
                            addProperty("server", "dns-local")
                        })
                    }
                    add(JsonObject().apply {
                        addProperty("server", "dns-direct")
                    })
                })
            })
            add("inbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "socks")
                    addProperty("tag", "socks-in")
                    addProperty("listen", "127.0.0.1")
                    addProperty("listen_port", localPort)
                })
            })
            add("outbounds", JsonArray().apply {
                add(
                    createNodeOutbound(node, "proxy", hysteriaUpMbps, hysteriaDownMbps)
                ) // 单节点测试直接用proxy tag
                add(JsonObject().apply {
                    addProperty("type", "direct")
                    addProperty("tag", "direct")
                })
                add(JsonObject().apply {
                    addProperty("type", "dns")
                    addProperty("tag", "dns-out")
                })
            })
            add("route", JsonObject().apply {
                add("rules", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("protocol", "dns")
                        addProperty("outbound", "dns-out")
                    })
                    if (isDomainServer) {
                        add(JsonObject().apply {
                            add("domain", JsonArray().apply { add(node.server) })
                            addProperty("outbound", "direct")
                        })
                    }
                })
                addProperty("final", "proxy")
            })
        }
        return gson.toJson(config)
    }
    
    /**
     * 生成用于 URL Test 的轻量配置
     * 包含所有节点 + ClashAPI (无 TUN，不需要 VPN 权限)
     */
    fun generateUrlTestConfig(
        nodes: List<Node>,
        clashApiPort: Int = 19090,
        logFile: String? = null,
        hysteriaUpMbps: Int = AppConfig.HYSTERIA_UP_MBPS,
        hysteriaDownMbps: Int = AppConfig.HYSTERIA_DOWN_MBPS,
        urlTestUrl: String = AppConfig.URL_TEST_URL
    ): String {
        val config = JsonObject().apply {
            add("log", JsonObject().apply {
                addProperty("level", "debug")
                if (logFile != null) {
                    addProperty("output", logFile)
                }
            })
            
            // DNS - 必须走 direct 避免通过代理解析代理服务器域名
            add("dns", JsonObject().apply {
                add("servers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("tag", "dns-direct")
                        addProperty("address", AppConfig.VPN_DNS_PRIMARY)
                        addProperty("detour", "direct")
                    })
                    add(JsonObject().apply {
                        addProperty("tag", "dns-local")
                        addProperty("address", AppConfig.VPN_DNS_CHINA)
                        addProperty("detour", "direct")
                    })
                })
            })
            
            // Inbound - 仅 HTTP mixed，无 TUN
            add("inbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "mixed")
                    addProperty("tag", "mixed-in")
                    addProperty("listen", "127.0.0.1")
                    addProperty("listen_port", 17890)
                })
            })
            
            // Outbounds - 所有节点 + urltest 组
            add("outbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "urltest")
                    addProperty("tag", "auto")
                    val outboundTags = JsonArray()
                    nodes.forEach { outboundTags.add(it.id) }
                    add("outbounds", outboundTags)
                    addProperty(
                        "url",
                        UrlTestTargets.normalize(urlTestUrl) ?: UrlTestTargets.DEFAULT_URL
                    )
                    addProperty("interval", "10m")
                    addProperty("tolerance", 50)
                })
                
                nodes.forEach { node ->
                    add(createNodeOutbound(node, node.id, hysteriaUpMbps, hysteriaDownMbps))
                }
                
                add(JsonObject().apply {
                    addProperty("type", "direct")
                    addProperty("tag", "direct")
                })
            })
            
            // 路由 - 不设置 auto_detect_interface 和 default_interface
            // Go 的 net.InterfaceByName 在 Android 上无法工作
            // 无 VPN 时系统默认路由会自动走物理网络接口
            add("route", JsonObject().apply {
                addProperty("final", "auto")
            })
            
            // 实验性 - ClashAPI
            add("experimental", JsonObject().apply {
                add("clash_api", JsonObject().apply {
                    addProperty("external_controller", "127.0.0.1:$clashApiPort")
                })
            })
        }
        return gson.toJson(config)
    }
    
    private fun createLogConfig(): JsonObject {
        return JsonObject().apply {
            addProperty("level", "info")
            addProperty("timestamp", true)
        }
    }
    
    private fun createDnsConfig(proxyMode: ProxyMode, nodeDomains: List<String>, ipv6Mode: IPv6RoutingMode): JsonObject {
        val servers = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("tag", "google")
                addProperty("address", "tls://${AppConfig.VPN_DNS_PRIMARY}")
                addProperty("detour", "proxy") // DNS走代理组
            })
            add(JsonObject().apply {
                addProperty("tag", "local")
                addProperty("address", AppConfig.VPN_DNS_CHINA)
                addProperty("detour", "direct")
            })
        }
        
        val rules = JsonArray()
        
        // 1. 通过本地 DNS 解析代理服务器域名（对于基于域名的代理至关重要！）
        if (nodeDomains.isNotEmpty()) {
            rules.add(JsonObject().apply {
                add("domain", JsonArray().apply { nodeDomains.forEach { add(it) } })
                addProperty("server", "local")
            })
        }
        
        // 2. 通过本地 DNS 解析 CN 域名（使用 rule_set）
        if (proxyMode == ProxyMode.SMART) {
            rules.add(JsonObject().apply {
                add("rule_set", JsonArray().apply {
                    add("geosite-cn")
                })
                addProperty("server", "local")
            })
        }
        
        // 基于 IPv6 模式的 DNS 策略
        val dnsStrategy = when (ipv6Mode) {
            IPv6RoutingMode.ONLY -> "ipv6_only"
            IPv6RoutingMode.PREFER -> "prefer_ipv6"
            else -> "prefer_ipv4"
        }
        
        return JsonObject().apply {
            add("servers", servers)
            add("rules", rules)
            addProperty("final", "google")
            addProperty("strategy", dnsStrategy)
        }
    }
    
    private fun createInbounds(
        ipv6Mode: IPv6RoutingMode,
        mtu: Int,
        internalSocksPort: Int?,
        tunStack: TunStack = TunStack.GVISOR
    ): JsonArray {
        return JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "tun")
                addProperty("tag", "tun-in")
                addProperty("interface_name", "tun0")
                // IPv4 地址 (总是添加，否则 DNS 劫持会报错 "need one more IPv4 address")
                addProperty("inet4_address", "172.19.0.1/30")
                
                // IPv6 地址 (启用/优先/仅模式下添加)
                if (ipv6Mode != IPv6RoutingMode.DISABLED) {
                    // 使用 GUA 地址而非 ULA，防止 Android 认为无 IPv6 互联网连接
                    // 必须使用 /126 或更小掩码，因为 system stack 需要至少一个额外的 IPv6 地址用于网关/路由
                    addProperty("inet6_address", "2001:db8::1/126")
                }
                addProperty("mtu", mtu)
                addProperty("auto_route", true)
                addProperty("strict_route", true)
                
                // 显式配置路由地址 (sing-box 1.12.x 使用 route_address)
                add("route_address", JsonArray().apply {
                    // IPv4 全局路由 (仅IPv6模式下不添加)
                    if (ipv6Mode != IPv6RoutingMode.ONLY) {
                        add("0.0.0.0/0")
                    }
                    // IPv6 全局路由 (启用/优先/仅模式下添加)
                    if (ipv6Mode != IPv6RoutingMode.DISABLED) {
                        add("::/0")
                    }
                })
                
                addProperty("stack", tunStack.configValue)
                addProperty("sniff", true)
                addProperty("sniff_override_destination", true)
            })

            // 本应用被排除在 Android VPN TUN 之外。提供一个仅监听回环地址的
            // SOCKS 入站，让“当前已连接节点”的测速流量进入同一个 sing-box
            // 实例，从而由核心 TrafficManager 准确统计，且不会暴露到局域网。
            internalSocksPort?.let { port ->
                add(JsonObject().apply {
                    addProperty("type", "socks")
                    addProperty("tag", "internal-socks-in")
                    addProperty("listen", "127.0.0.1")
                    addProperty("listen_port", port)
                })
            }
        }
    }

    private fun createOutbounds(
        nodes: List<Node>,
        selectedNodeId: String?,
        hysteriaUpMbps: Int,
        hysteriaDownMbps: Int,
        xrayBridgePorts: Map<String, Int>
    ): JsonArray {
        val outbounds = JsonArray()
        
        // 1. Selector Group (手动选择组)
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
        
        // 2. Individual Node Outbounds (具体节点)
        nodes.forEach { node ->
            val xrayPort = xrayBridgePorts[node.id]
            outbounds.add(
                if (xrayPort != null) {
                    createXrayBridgeOutbound(node.id, xrayPort)
                } else {
                    createNodeOutbound(node, node.id, hysteriaUpMbps, hysteriaDownMbps)
                }
            )
        }
        
        // 3. Other Outbounds (Direct, Block, DNS)
        outbounds.add(JsonObject().apply {
            addProperty("type", "direct")
            addProperty("tag", "direct")
        })
        
        outbounds.add(JsonObject().apply {
            addProperty("type", "block")
            addProperty("tag", "block")
        })
        
        outbounds.add(JsonObject().apply {
            addProperty("type", "dns")
            addProperty("tag", "dns-out")
        })
        
        return outbounds
    }

    private fun createXrayBridgeOutbound(tag: String, port: Int): JsonObject = JsonObject().apply {
        require(port in 1..65_535) { "Xray bridge port is invalid for node $tag" }
        addProperty("type", "socks")
        addProperty("tag", tag)
        addProperty("server", "127.0.0.1")
        addProperty("server_port", port)
        addProperty("version", "5")
    }
    
    private fun createNodeOutbound(
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
            NodeType.SHADOWSOCKS -> createShadowsocksOutbound(node)
            NodeType.SOCKS -> createSocksOutbound(node)
            NodeType.HTTP -> createHttpOutbound(node)
            else -> createVlessOutbound(node)
        }
        // 覆盖 tag
        outbound.addProperty("tag", tag)
        return outbound
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
            
            val security = uri.getQueryParameter("security")
            if (security == "tls" || security == "reality") {
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host")
                    addProperty("server_name", sni ?: node.server)
                    val alpnValues = parseCsvParams(queryParamFirst(uri, "alpn"))
                    if (alpnValues.isNotEmpty()) {
                        add("alpn", JsonArray().apply { alpnValues.forEach { add(it) } })
                    }
                    if (security == "reality") {
                        add("reality", JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("public_key", uri.getQueryParameter("pbk") ?: "")
                            addProperty("short_id", uri.getQueryParameter("sid") ?: "")
                        })
                    }
                    val fingerprint = queryParamFirst(uri, "fp", "fingerprint", "client-fingerprint")
                    if (!fingerprint.isNullOrBlank()) {
                        add("utls", JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("fingerprint", fingerprint)
                        })
                    }
                    addProperty("insecure", queryParamEnabled(uri, "allowInsecure", "allow_insecure", "insecure", "skip-cert-verify"))
                })
            }
            
            val transport = resolveUriTransportType(uri)
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
            
            val tls = parseJsonString(vmessConfig.get("tls"))
            if (tls == "tls") {
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty(
                        "server_name",
                        parseJsonString(vmessConfig.get("sni"))
                            ?: parseJsonStringList(vmessConfig.get("host")).firstOrNull()
                            ?: node.server
                    )
                    val alpnValues = parseJsonStringList(vmessConfig.get("alpn"))
                    if (alpnValues.isNotEmpty()) {
                        add("alpn", JsonArray().apply { alpnValues.forEach { add(it) } })
                    }
                    val insecureField = vmessConfig.get("allowInsecure") ?: vmessConfig.get("allow_insecure")
                    val isInsecure = when {
                        insecureField == null -> false
                        insecureField.isJsonPrimitive && insecureField.asJsonPrimitive.isBoolean -> insecureField.asBoolean
                        insecureField.isJsonPrimitive -> insecureField.asString.let { it == "1" || it.equals("true", ignoreCase = true) }
                        else -> false
                    }
                    addProperty("insecure", isInsecure)
                    val fingerprint = parseJsonString(vmessConfig.get("fp"))
                        ?: parseJsonString(vmessConfig.get("fingerprint"))
                        ?: parseJsonString(vmessConfig.get("client-fingerprint"))
                    if (!fingerprint.isNullOrBlank()) {
                        add("utls", JsonObject().apply {
                            addProperty("enabled", true)
                            addProperty("fingerprint", fingerprint)
                        })
                    }
                })
            }
            
            val network = resolveVmessTransportType(vmessConfig)
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
            
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host")
                addProperty("server_name", sni ?: node.server)
                val alpnValues = parseCsvParams(queryParamFirst(uri, "alpn"))
                if (alpnValues.isNotEmpty()) {
                    add("alpn", JsonArray().apply { alpnValues.forEach { add(it) } })
                }
                val fingerprint = queryParamFirst(uri, "fp", "fingerprint", "client-fingerprint")
                if (!fingerprint.isNullOrBlank()) {
                    add("utls", JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("fingerprint", fingerprint)
                    })
                }
                addProperty("insecure", queryParamEnabled(uri, "allowInsecure", "allow_insecure", "insecure", "skip-cert-verify"))
            })
            
            val transport = resolveUriTransportType(uri)
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
        val alpnValues = parseCsvParams(queryParamFirst(uri, "alpn"))
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
            queryParamFirst(uri, "hopInterval", "hop_interval")?.let { addProperty("hop_interval", it) }
            if (hysteriaUpMbps > 0) {
                addProperty("up_mbps", hysteriaUpMbps)
            }
            if (hysteriaDownMbps > 0) {
                addProperty("down_mbps", hysteriaDownMbps)
            }
            queryParamFirst(uri, "network")?.let { addProperty("network", it) }
            
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("server_name", queryParamFirst(uri, "sni", "host") ?: node.server)
                addProperty("insecure", queryParamEnabled(uri, "allowInsecure", "allow_insecure", "insecure", "skip-cert-verify"))
                if (alpnValues.isNotEmpty()) {
                    add("alpn", JsonArray().apply { alpnValues.forEach { add(it) } })
                }
                val fingerprint = queryParamFirst(uri, "fp", "fingerprint", "client-fingerprint")
                if (!fingerprint.isNullOrBlank()) {
                    add("utls", JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("fingerprint", fingerprint)
                    })
                }
            })
            
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
        val sni = queryParamFirst(uri, "sni", "host") ?: node.server
        val insecure = queryParamEnabled(uri, "allowInsecure", "allow_insecure", "insecure", "skip-cert-verify")
        val alpnValues = parseCsvParams(queryParamFirst(uri, "alpn"))
        val fingerprint = queryParamFirst(uri, "fp", "fingerprint", "client-fingerprint")
        val idleSessionCheckInterval = queryParamFirst(uri, "idle_session_check_interval")
        val idleSessionTimeout = queryParamFirst(uri, "idle_session_timeout")
        val minIdleSession = queryParamFirst(uri, "min_idle_session")?.toIntOrNull()
        val maxIdleSession = queryParamFirst(uri, "max_idle_session")?.toIntOrNull()

        return JsonObject().apply {
            addProperty("type", "anytls")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            addProperty("password", password)
            idleSessionCheckInterval?.let { addProperty("idle_session_check_interval", it) }
            idleSessionTimeout?.let { addProperty("idle_session_timeout", it) }
            minIdleSession?.let { addProperty("min_idle_session", it) }
            maxIdleSession?.let { addProperty("max_idle_session", it) }
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("server_name", sni)
                addProperty("insecure", insecure)
                if (alpnValues.isNotEmpty()) {
                    add("alpn", JsonArray().apply { alpnValues.forEach { add(it) } })
                }
                if (!fingerprint.isNullOrBlank()) {
                    add("utls", JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("fingerprint", fingerprint)
                    })
                }
            })
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
        val alpnValues = parseCsvParams(queryParamFirst(uri, "alpn"))
        val fingerprint = queryParamFirst(uri, "fp", "fingerprint", "client-fingerprint")

        return JsonObject().apply {
            addProperty("type", "tuic")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            addProperty("uuid", uuid)
            if (password.isNotEmpty()) {
                addProperty("password", password)
            }
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("server_name", uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: node.server)
                addProperty("insecure", queryParamEnabled(uri, "allowInsecure", "allow_insecure", "insecure", "skip-cert-verify"))
                if (alpnValues.isNotEmpty()) {
                    add("alpn", JsonArray().apply { alpnValues.forEach { add(it) } })
                }
                if (!fingerprint.isNullOrBlank()) {
                    add("utls", JsonObject().apply {
                        addProperty("enabled", true)
                        addProperty("fingerprint", fingerprint)
                    })
                }
            })

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
            add("tls", JsonObject().apply {
                addProperty("enabled", true)
                addProperty("server_name", uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: node.server)
                addProperty("insecure", queryParamEnabled(uri, "allowInsecure", "allow_insecure", "insecure", "skip-cert-verify"))
            })
        }
    }

    private fun createWireGuardOutbound(node: Node): JsonObject {
        val rawLink = node.getRawLinkPlain()
        val uri = Uri.parse(rawLink)
        val privateKey = uri.userInfo ?: queryParamFirst(uri, "private_key", "privateKey") ?: ""
        val peerPublicKey = queryParamFirst(uri, "publickey", "peer_public_key", "peerPublicKey") ?: ""
        val preSharedKey = queryParamFirst(uri, "presharedkey", "pre_shared_key", "preSharedKey")
        val localAddresses = (queryParamFirst(uri, "address", "local_address", "localAddress") ?: "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("10.0.0.2/32") }

        return JsonObject().apply {
            addProperty("type", "wireguard")
            addProperty("server", node.server)
            addProperty("server_port", node.port)
            addProperty("private_key", privateKey)
            addProperty("peer_public_key", peerPublicKey)
            add("local_address", JsonArray().apply { localAddresses.forEach { add(it) } })

            if (!preSharedKey.isNullOrEmpty()) {
                addProperty("pre_shared_key", preSharedKey)
            }

            uri.getQueryParameter("mtu")?.toIntOrNull()?.let { addProperty("mtu", it) }
            queryParamFirst(uri, "workers")?.toIntOrNull()?.let { addProperty("workers", it) }
            queryParamFirst(uri, "network")?.let { addProperty("network", it) }

            parseWireGuardReserved(uri.getQueryParameter("reserved"))?.let { reserved ->
                add("reserved", JsonArray().apply { reserved.forEach { add(it) } })
            }
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
        val parts = userInfo.split(":")
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
        val parts = userInfo.split(":")
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
                add("tls", JsonObject().apply {
                    addProperty("enabled", true)
                    addProperty("server_name", queryParamFirst(uri, "sni", "host") ?: node.server)
                    addProperty("insecure", queryParamEnabled(uri, "allowInsecure", "allow_insecure", "insecure", "skip-cert-verify"))
                })
            }
        }
    }

    private fun createRoute(proxyMode: ProxyMode, nodeDomains: List<String>, nodeIPs: List<String>): JsonObject {
        val rules = JsonArray()
        
        // 1. 劫持 DNS 流量
        rules.add(JsonObject().apply {
            addProperty("protocol", "dns")
            addProperty("outbound", "dns-out")
        })
        
        // 2. 环回/私有 IP 地址 -> 始终直接连接，绕过局域网
        rules.add(JsonObject().apply {
            addProperty("ip_is_private", true)
            addProperty("outbound", "direct")
        })

        // 3. 代理服务器域名 -> 直接连接（避免循环）
        if (nodeDomains.isNotEmpty()) {
            rules.add(JsonObject().apply {
                add("domain", JsonArray().apply { nodeDomains.forEach { add(it) } })
                addProperty("outbound", "direct")
            })
        }
        
        if (proxyMode == ProxyMode.SMART) {
            // 4. 中国域名 -> 直接访问（使用 geosite-cn 规则集）
            rules.add(JsonObject().apply {
                add("rule_set", JsonArray().apply {
                    add("geosite-cn")
                })
                addProperty("outbound", "direct")
            })

            // 5. 中国 IP 地址 -> 直接访问（使用 geoip-cn 规则集）
            rules.add(JsonObject().apply {
                add("rule_set", JsonArray().apply {
                    add("geoip-cn")
                })
                addProperty("outbound", "direct")
            })
        }
        
        // 6. 代理服务器 IP -> 直接连接 (在所有模式下都需要，避免路由死循环)
        if (nodeIPs.isNotEmpty()) {
            rules.add(JsonObject().apply {
                add("ip_cidr", JsonArray().apply {
                    nodeIPs.forEach { 
                        if (it.contains(":")) {
                            // 移除可能存在的方括号 (sing-box ip_cidr 不支持方括号)
                            val cleanIp = it.replace("[", "").replace("]", "")
                            add("$cleanIp/128") // IPv6
                        } else {
                            add("$it/32") // IPv4
                        }
                    }
                })
                addProperty("outbound", "direct")
            })
        }
        
        return JsonObject().apply {
            add("rules", rules)
            
            // rule_set 声明（智能分流模式需要）
            if (proxyMode == ProxyMode.SMART) {
                add("rule_set", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("tag", "geosite-cn")
                        addProperty("type", "local")
                        addProperty("format", "binary")
                        addProperty("path", "geosite-cn.srs")
                    })
                    add(JsonObject().apply {
                        addProperty("tag", "geoip-cn")
                        addProperty("type", "local")
                        addProperty("format", "binary")
                        addProperty("path", "geoip-cn.srs")
                    })
                })
            }
            
            addProperty("final", "proxy")
            addProperty("auto_detect_interface", true)
        }
    }
    

    
    private fun createExperimental(): JsonObject {
        return JsonObject().apply {
            add("cache_file", JsonObject().apply { addProperty("enabled", true) })
            add("clash_api", JsonObject().apply { addProperty("external_controller", "127.0.0.1:9090") })
        }
    }


}
