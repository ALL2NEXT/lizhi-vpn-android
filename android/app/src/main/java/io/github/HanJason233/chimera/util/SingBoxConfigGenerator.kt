package io.github.HanJason233.chimera.util

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeType
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.data.model.IPv6RoutingMode
import io.github.HanJason233.chimera.data.model.LanInboundSettings
import io.github.HanJason233.chimera.data.model.DnsDomainStrategy
import io.github.HanJason233.chimera.data.model.DnsSettings
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleAction
import io.github.HanJason233.chimera.data.model.SmartRoutingRule
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleType
import io.github.HanJason233.chimera.data.model.SmartRoutingSettings
import io.github.HanJason233.chimera.data.model.UrlTestTargets
import io.github.HanJason233.chimera.data.model.LinkTypeTestTargets
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

    // 紧凑 JSON 可以减少配置解析开销；调试日志本身会截断配置，无需美化输出。
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
        dnsSettings: DnsSettings = DnsSettings(),
        internalSocksEndpoint: LocalSocksEndpoint? = null,
        lanInbound: LanInboundSettings = LanInboundSettings(),
        hysteriaUpMbps: Int = AppConfig.HYSTERIA_UP_MBPS,
        hysteriaDownMbps: Int = AppConfig.HYSTERIA_DOWN_MBPS,
        smartRoutingSettings: SmartRoutingSettings = SmartRoutingSettings()
    ): String {
        // 如果没有节点，生成一个空配置防止崩溃
        if (nodes.isEmpty()) {
            return generateEmptyConfig()
        }

        val smartInboundTags = buildList {
            if (proxyMode == ProxyMode.SMART) add("internal-socks-in")
            if (lanInbound.enabled && lanInbound.proxyMode == ProxyMode.SMART) add("lan-socks-in")
        }

        val config = JsonObject().apply {
            // 从节点中提取唯一域名（忽略 IP 地址），以防止路由环路/DNS 死锁
            // 提取唯一域名和 IP 地址
            val uniqueServers = nodes.map { it.server }.distinct()
            val (nodeIPs, nodeDomains) = uniqueServers.partition {
                IPV4_ADDRESS.matches(it) || it.contains(":")
            }
                
            add("log", createLogConfig())
            add(
                "dns",
                createDnsConfig(
                    nodeDomains,
                    ipv6Mode,
                    dnsSettings,
                    lanInbound.enabled,
                    smartRoutingSettings,
                    smartInboundTags
                )
            )
            add("inbounds", createInbounds(internalSocksEndpoint, lanInbound))
            add(
                "outbounds",
                createOutbounds(
                    nodes,
                    selectedNodeId,
                    hysteriaUpMbps,
                    hysteriaDownMbps
                )
            )
            add(
                "route",
                createRoute(
                    nodeDomains,
                    nodeIPs,
                    smartRoutingSettings,
                    dnsSettings.dnsRoutingEnabled,
                    smartInboundTags
                )
            )
            add("experimental", createExperimental())
        }
        return gson.toJson(completeConfig(config, nodes))
    }
    
    private fun generateEmptyConfig(): String {
        val config = JsonObject().apply {
            add("log", createLogConfig())
            add(
                "inbounds",
                createInbounds(null, LanInboundSettings())
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
                        addProperty("type", "udp")
                        addProperty("tag", "dns-direct")
                        addProperty("server", AppConfig.VPN_DNS_PRIMARY)
                    })
                    add(JsonObject().apply {
                        addProperty("type", "udp")
                        addProperty("tag", "dns-local")
                        addProperty("server", AppConfig.VPN_DNS_CHINA)
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
            })
            add("route", JsonObject().apply {
                addProperty("default_domain_resolver", "dns-local")
                add("rules", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("protocol", "dns")
                    addProperty("action", "hijack-dns")
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
        return gson.toJson(completeConfig(config, listOf(node)))
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
                        addProperty("type", "udp")
                        addProperty("tag", "dns-direct")
                        addProperty("server", AppConfig.VPN_DNS_PRIMARY)
                    })
                    add(JsonObject().apply {
                        addProperty("type", "udp")
                        addProperty("tag", "dns-local")
                        addProperty("server", AppConfig.VPN_DNS_CHINA)
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
                addProperty("default_domain_resolver", "dns-direct")
            })
            
            // 实验性 - ClashAPI
            add("experimental", JsonObject().apply {
                add("clash_api", JsonObject().apply {
                    addProperty("external_controller", "127.0.0.1:$clashApiPort")
                })
            })
        }
        return gson.toJson(completeConfig(config, nodes))
    }

    /**
     * 生成一个进程承载多个独立 SOCKS 入站的批量测速配置。
     *
     * 每个入站固定路由到同下标节点。应用层因此可以让 sing-box 与 Xray 共用同一套
     * SOCKS/HTTP 探测逻辑，避免 Clash API 修改 HTTP 测试地址而造成双核心口径不一致。
     */
    fun generateUrlTestBatchConfig(
        nodes: List<Node>,
        endpoints: List<LocalSocksEndpoint>,
        hysteriaUpMbps: Int = AppConfig.HYSTERIA_UP_MBPS,
        hysteriaDownMbps: Int = AppConfig.HYSTERIA_DOWN_MBPS
    ): String {
        require(nodes.isNotEmpty()) { "URL Test 批次至少需要一个节点" }
        require(nodes.size == endpoints.size) { "节点与 SOCKS 入站数量不一致" }
        require(nodes.map(Node::id).toSet().size == nodes.size) { "URL Test 节点 ID 必须唯一" }
        val config = JsonParser.parseString(
            generateUrlTestConfig(
                nodes = nodes,
                hysteriaUpMbps = hysteriaUpMbps,
                hysteriaDownMbps = hysteriaDownMbps
            )
        ).asJsonObject
        return composeUrlTestBatchConfig(config, nodes, endpoints)
    }

    internal fun composeUrlTestBatchConfig(
        config: JsonObject,
        nodes: List<Node>,
        endpoints: List<LocalSocksEndpoint>
    ): String {
        require(nodes.isNotEmpty()) { "URL Test 批次至少需要一个节点" }
        require(nodes.size == endpoints.size) { "节点与 SOCKS 入站数量不一致" }
        // 应用通过每个节点独立的 SOCKS 入站执行同一 HTTP 探测，不再依赖 Clash API。
        config.remove("experimental")
        config.add("inbounds", JsonArray().apply {
            endpoints.forEachIndexed { index, endpoint ->
                add(JsonObject().apply {
                    addProperty("type", "socks")
                    addProperty("tag", "urltest-in-$index")
                    addProperty("listen", endpoint.address)
                    addProperty("listen_port", endpoint.port)
                })
            }
        })
        val outbounds = config.getAsJsonArray("outbounds")
        outbounds.remove(0) // 移除自动测速组，只保留真实节点出站与 direct。
        config.add("route", JsonObject().apply {
            add("rules", JsonArray().apply {
                nodes.forEachIndexed { index, node ->
                    add(JsonObject().apply {
                        add("inbound", JsonArray().apply { add("urltest-in-$index") })
                        addProperty("action", "route")
                        addProperty("outbound", node.id)
                    })
                }
            })
            addProperty("final", "direct")
            addProperty("default_domain_resolver", "dns-direct")
        })
        return gson.toJson(config)
    }
    
    private fun createLogConfig(): JsonObject {
        return JsonObject().apply {
            addProperty("level", "info")
            addProperty("timestamp", true)
        }
    }
    
    private fun createDnsConfig(
        proxyMode: ProxyMode,
        nodeDomains: List<String>,
        ipv6Mode: IPv6RoutingMode,
        dnsSettings: DnsSettings,
        lanInboundEnabled: Boolean
    ): JsonObject = createDnsConfig(
        nodeDomains,
        ipv6Mode,
        dnsSettings,
        lanInboundEnabled,
        SmartRoutingSettings(),
        if (proxyMode == ProxyMode.SMART) {
            buildList {
                add("internal-socks-in")
                if (lanInboundEnabled) add("lan-socks-in")
            }
        } else emptyList()
    )

    private fun createDnsConfig(
        nodeDomains: List<String>,
        ipv6Mode: IPv6RoutingMode,
        dnsSettings: DnsSettings,
        lanInboundEnabled: Boolean,
        smartRoutingSettings: SmartRoutingSettings,
        smartInboundTags: List<String>
    ): JsonObject {
        val servers = JsonArray().apply {
            // 使用系统 DNS 解析自定义 DNS 服务器的域名，避免解析链路自引用。
            add(JsonObject().apply {
                addProperty("type", "local")
                addProperty("tag", "dns-local")
            })
            dnsSettings.directDns.lineSequence()
                .map(String::trim)
                .firstOrNull(String::isNotEmpty)
                ?.let { address ->
                    addDnsServer(
                        address = address,
                        tag = "dns-direct",
                        detour = null,
                        domainResolver = "dns-local",
                        serverAddressStrategy = resolveDnsStrategy(
                            dnsSettings.serverDomainStrategy,
                            ipv6Mode,
                            forServerAddress = true
                        )
                    )
                }
            dnsSettings.remoteDns.lineSequence()
                .map(String::trim)
                .firstOrNull(String::isNotEmpty)
                ?.let { address ->
                    addDnsServer(
                        address = address,
                        tag = "dns-remote",
                        detour = "proxy",
                        domainResolver = "dns-direct",
                        serverAddressStrategy = resolveDnsStrategy(
                            dnsSettings.serverDomainStrategy,
                            ipv6Mode,
                            forServerAddress = true
                        )
                    )
                }
        }

        val rules = JsonArray()

        // 1. 通过直连 DNS 解析代理服务器域名，避免代理出口和 DNS 之间互相等待。
        if (nodeDomains.isNotEmpty()) {
            rules.add(JsonObject().apply {
                add("domain", JsonArray().apply { nodeDomains.forEach { add(it) } })
                addProperty("server", "dns-direct")
                addProperty("strategy", resolveDnsStrategy(
                    dnsSettings.directDomainStrategy,
                    ipv6Mode
                ))
            })
        }

        // 2. DNS 路由开启时，智能模式的国内域名使用直连 DNS。
        if (dnsSettings.dnsRoutingEnabled && smartInboundTags.isNotEmpty()) {
            val overriddenDomains = smartRoutingSettings.rules
                .filter {
                    it.enabled &&
                        it.type == SmartRoutingRuleType.CUSTOM_DOMAIN &&
                        (it.action == SmartRoutingRuleAction.PROXY ||
                            it.action == SmartRoutingRuleAction.BLOCK)
                }
                .flatMap { it.values }
                .map(::routingDomainKey)
                .toSet()
            val customDirectDomains = smartRoutingSettings.rules
                .filter {
                    it.enabled &&
                        it.type == SmartRoutingRuleType.CUSTOM_DOMAIN &&
                        it.action == SmartRoutingRuleAction.DIRECT
                }
                .flatMap { it.values }
                .filterNot { routingDomainKey(it) in overriddenDomains }
                .distinct()
            if (customDirectDomains.isNotEmpty()) {
                rules.add(JsonObject().apply {
                    add("inbound", JsonArray().apply { smartInboundTags.forEach(::add) })
                    add("domain", JsonArray().apply { customDirectDomains.forEach(::add) })
                    addProperty("server", "dns-direct")
                    addProperty("strategy", resolveDnsStrategy(
                        dnsSettings.directDomainStrategy,
                        ipv6Mode
                    ))
                })
            }
            rules.add(JsonObject().apply {
                add("inbound", JsonArray().apply { smartInboundTags.forEach(::add) })
                add("rule_set", JsonArray().apply { add("geosite-cn") })
                addProperty("server", "dns-direct")
                addProperty("strategy", resolveDnsStrategy(
                    dnsSettings.directDomainStrategy,
                    ipv6Mode
                ))
            })
        }

        // FakeIP 服务器是独立的 DNS 传输，仅为来自代理入站的请求返回映射地址。
        if (dnsSettings.fakeDnsEnabled) {
            servers.add(JsonObject().apply {
                addProperty("type", "fakeip")
                addProperty("tag", "dns-fake")
                addProperty("inet4_range", "198.18.0.0/15")
                addProperty("inet6_range", "fc00::/18")
            })
            rules.add(JsonObject().apply {
                add("inbound", JsonArray().apply {
                    add("internal-socks-in")
                    if (lanInboundEnabled) add("lan-socks-in")
                })
                addProperty("server", "dns-fake")
                addProperty("disable_cache", true)
            })
        }

        return JsonObject().apply {
            add("servers", servers)
            add("rules", rules)
            addProperty("final", "dns-remote")
            addProperty("strategy", resolveDnsStrategy(
                dnsSettings.remoteDomainStrategy,
                ipv6Mode
            ))
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
            // 当前端点策略用于 DNS 结果，服务器地址策略用于解析上面的 server 字段。
        })
    }

    private fun resolveDnsStrategy(
        strategy: DnsDomainStrategy,
        ipv6Mode: IPv6RoutingMode,
        forServerAddress: Boolean = false
    ): String = when (strategy) {
        DnsDomainStrategy.AUTO -> if (forServerAddress) {
            // 与 NekoBox 一致：DNS 服务器地址默认优先使用 IPv4，减少启动时的解析失败。
            "prefer_ipv4"
        } else {
            when (ipv6Mode) {
                IPv6RoutingMode.PREFER -> "prefer_ipv6"
                IPv6RoutingMode.ENABLED -> "prefer_ipv4"
                IPv6RoutingMode.DISABLED -> "ipv4_only"
            }
        }
        else -> strategy.configValue
    }
    
    private fun createInbounds(endpoint: LocalSocksEndpoint?): JsonArray =
        createInbounds(endpoint, LanInboundSettings())

    private fun createInbounds(
        endpoint: LocalSocksEndpoint?,
        lanInbound: LanInboundSettings
    ): JsonArray {
        return JsonArray().apply {
            // HEV 独占 Android VPN TUN，并将数据包转发到这个环回 SOCKS5 入口。
            endpoint?.let { socks ->
                add(JsonObject().apply {
                    addProperty("type", "socks")
                    addProperty("tag", "internal-socks-in")
                    addProperty("listen", socks.address)
                    addProperty("listen_port", socks.port)
                    socks.credentials?.let { credentials ->
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("username", credentials.username)
                                addProperty("password", credentials.password)
                            })
                        })
                    }
                })
            }

            // 局域网入站不经过 HEV TUN，直接复用当前核心的出站和分流规则。
            if (lanInbound.enabled) {
                add(JsonObject().apply {
                    addProperty("type", "socks")
                    addProperty("tag", "lan-socks-in")
                    addProperty("listen", "0.0.0.0")
                    addProperty("listen_port", lanInbound.port)
                    if (lanInbound.username.isNotBlank() && lanInbound.password.isNotBlank()) {
                        add("users", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("username", lanInbound.username)
                                addProperty("password", lanInbound.password)
                            })
                        })
                    }
                })
            }
        }
    }

    private fun createOutbounds(
        nodes: List<Node>,
        selectedNodeId: String?,
        hysteriaUpMbps: Int,
        hysteriaDownMbps: Int
    ): JsonArray {
        val outbounds = JsonArray()
        
        // 1. 手动选择组
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

    private fun completeConfig(config: JsonObject, nodes: List<Node>): JsonObject {
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
                add("tls", createSingBoxTls(uriTlsParameters(uri), node.server, reality = security == "reality"))
            }
            
            val transport = checkedSingBoxTransport(resolveUriTransportType(uri))
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
            
            val network = checkedSingBoxTransport(resolveVmessTransportType(vmessConfig))
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
            
            val transport = checkedSingBoxTransport(resolveUriTransportType(uri))
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

    private fun createRoute(
        nodeDomains: List<String>,
        nodeIPs: List<String>,
        smartRoutingSettings: SmartRoutingSettings,
        dnsRoutingEnabled: Boolean,
        smartInboundTags: List<String>
    ): JsonObject {
        val rules = JsonArray()
        
        // 1. 劫持 DNS 流量
        rules.add(JsonObject().apply {
            // HEV 会原样转发所有 DNS 记录类型，因此按端口匹配，避免依赖协议嗅探。
            addProperty("port", 53)
            addProperty("action", "hijack-dns")
        })
        // HEV 仅负责数据面，应用通常连接已解析的 IP；执行智能分流规则前需恢复 HTTP/TLS/QUIC 域名。
        rules.add(JsonObject().apply {
            addProperty("action", "sniff")
        })

        // Exit-IP checks must not measure the phone's direct IP due to a smart rule.
        rules.add(JsonObject().apply {
            add("domain", JsonArray().apply { LinkTypeTestTargets.hosts.forEach(::add) })
            addProperty("outbound", "proxy")
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
        
        if (smartInboundTags.isNotEmpty()) {
            fun addRuleSetRule(tag: String, action: SmartRoutingRuleAction) {
                rules.add(JsonObject().apply {
                    add("inbound", JsonArray().apply { smartInboundTags.forEach(::add) })
                    add("rule_set", JsonArray().apply { add(tag) })
                    addProperty("outbound", action.configTag)
                })
            }

            fun addValueRule(key: String, values: List<String>, action: SmartRoutingRuleAction) {
                if (values.isEmpty()) return
                rules.add(JsonObject().apply {
                    add("inbound", JsonArray().apply { smartInboundTags.forEach(::add) })
                    add(key, JsonArray().apply { values.forEach(::add) })
                    addProperty("outbound", action.configTag)
                })
            }

            smartRoutingSettings.rules
                .filter { it.enabled }
                .sortedBy(::smartRulePriority)
                .forEach { smartRule ->
                    when (smartRule.type) {
                        SmartRoutingRuleType.DOMESTIC_DOMAIN ->
                            addRuleSetRule("geosite-cn", smartRule.action)
                        SmartRoutingRuleType.DOMESTIC_IP ->
                            addRuleSetRule("geoip-cn", smartRule.action)
                        SmartRoutingRuleType.CUSTOM_DOMAIN ->
                            addValueRule("domain", smartRule.values, smartRule.action)
                        SmartRoutingRuleType.CUSTOM_IP ->
                            addValueRule(
                                "ip_cidr",
                                smartRule.values.map(::normalizeIpCidr),
                                smartRule.action
                            )
                    }
                }
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
            val hasDomesticDomainRule = smartInboundTags.isNotEmpty() &&
                smartRoutingSettings.rules.any {
                    it.enabled && it.type == SmartRoutingRuleType.DOMESTIC_DOMAIN
                }
            val hasDomesticIpRule = smartInboundTags.isNotEmpty() &&
                smartRoutingSettings.rules.any {
                    it.enabled && it.type == SmartRoutingRuleType.DOMESTIC_IP
                }
            if (smartInboundTags.isNotEmpty() && (
                    hasDomesticDomainRule || hasDomesticIpRule || dnsRoutingEnabled
                )) {
                add("rule_set", JsonArray().apply {
                    if (hasDomesticDomainRule || dnsRoutingEnabled) {
                        add(JsonObject().apply {
                            addProperty("tag", "geosite-cn")
                            addProperty("type", "local")
                            addProperty("format", "binary")
                            addProperty("path", "geosite-cn.srs")
                        })
                    }
                    if (hasDomesticIpRule) {
                        add(JsonObject().apply {
                            addProperty("tag", "geoip-cn")
                            addProperty("type", "local")
                            addProperty("format", "binary")
                            addProperty("path", "geoip-cn.srs")
                        })
                    }
                })
            }
            
            // The core is now a standalone child process and HEV owns the
            // Android VPN TUN. The child shares the app UID and is excluded
            // from the VPN, so native interface auto-detection is neither
            // needed nor available through the old libbox platform bridge.
            addProperty("final", "proxy")
            addProperty("default_domain_resolver", "dns-direct")
        }
    }

    private fun normalizeIpCidr(value: String): String {
        val normalized = value.trim().removePrefix("[").removeSuffix("]")
        if (normalized.contains("/")) return normalized
        return if (normalized.contains(":")) "$normalized/128" else "$normalized/32"
    }

    private fun routingDomainKey(value: String): String = value
        .trim()
        .removePrefix("domain:")
        .removePrefix("full:")
        .trimEnd('.')
        .lowercase()

    private fun smartRulePriority(rule: SmartRoutingRule): Int = when {
        rule.action == SmartRoutingRuleAction.BLOCK -> 0
        rule.action == SmartRoutingRuleAction.PROXY -> 1
        rule.type == SmartRoutingRuleType.CUSTOM_DOMAIN -> 2
        rule.type == SmartRoutingRuleType.DOMESTIC_DOMAIN -> 3
        rule.type == SmartRoutingRuleType.DOMESTIC_IP -> 4
        else -> 5
    }
    

    
    private fun createExperimental(): JsonObject {
        return JsonObject().apply {
            add("cache_file", JsonObject().apply { addProperty("enabled", true) })
            add("clash_api", JsonObject().apply { addProperty("external_controller", "127.0.0.1:9090") })
        }
    }


}
