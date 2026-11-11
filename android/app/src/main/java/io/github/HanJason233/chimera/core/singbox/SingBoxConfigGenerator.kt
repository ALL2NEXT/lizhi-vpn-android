package io.github.HanJason233.chimera.core.singbox

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeType
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.data.model.IpFamilyRoutingSettings
import io.github.HanJason233.chimera.data.model.IPv6RoutingMode
import io.github.HanJason233.chimera.data.model.toIpFamilyRoutingSettings
import io.github.HanJason233.chimera.data.model.LanInboundSettings
import io.github.HanJason233.chimera.data.model.DnsDomainStrategy
import io.github.HanJason233.chimera.data.model.DnsSettings
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleAction
import io.github.HanJason233.chimera.data.model.SmartRoutingRule
import io.github.HanJason233.chimera.data.model.SmartRoutingRuleType
import io.github.HanJason233.chimera.data.model.SmartRoutingSettings
import io.github.HanJason233.chimera.data.model.effectiveOutboundTag
import io.github.HanJason233.chimera.data.model.decodeRoutingRuleValues
import io.github.HanJason233.chimera.data.model.UrlTestTargets
import io.github.HanJason233.chimera.data.model.LinkTypeTestTargets
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
import java.io.File

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
    private val outboundBuilder = SingBoxOutboundBuilder()
    
    /**
     * 生成完整的sing-box配置
     * @param nodes 所有可用节点列表
     * @param selectedNodeId 当前选择的节点ID（如果为null则默认选择auto）
     * @param ipv6Mode 兼容旧调用方的 IPv6 路由模式。
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
        smartRoutingSettings: SmartRoutingSettings = SmartRoutingSettings(),
        ruleSetDirectory: File? = null,
        ruleSetUrls: Map<String, String> = emptyMap(),
        failoverProbeEndpoints: Map<String, LocalSocksEndpoint> = emptyMap(),
        ipFamilySettings: IpFamilyRoutingSettings = ipv6Mode.toIpFamilyRoutingSettings()
    ): String {
        // 如果没有节点，生成一个空配置防止崩溃
        if (nodes.isEmpty()) {
            return generateEmptyConfig()
        }

        val smartInboundTags = buildList {
            if (proxyMode == ProxyMode.SMART) add("internal-socks-in")
            if (lanInbound.enabled && lanInbound.proxyMode == ProxyMode.SMART) add("lan-socks-in")
        }
        // 用户路由规则始终作用于 VPN 流量；智能 LAN 入站仅在跟随当前模式时共享规则。
        val routingInboundTags = buildList {
            add("internal-socks-in")
            if (lanInbound.enabled && lanInbound.proxyMode == proxyMode) add("lan-socks-in")
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
                    ipFamilySettings,
                    dnsSettings,
                    lanInbound.enabled,
                    smartRoutingSettings,
                    smartInboundTags
                )
            )
            add("inbounds", createInbounds(internalSocksEndpoint, lanInbound, nodes, failoverProbeEndpoints))
            add(
                "outbounds",
                outboundBuilder.createOutbounds(
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
                    smartInboundTags,
                    routingInboundTags,
                    nodes,
                    failoverProbeEndpoints,
                    ruleSetDirectory,
                    ruleSetUrls
                )
            )
            add("experimental", createExperimental())
        }
        return gson.toJson(outboundBuilder.completeConfig(config, nodes))
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
                    outboundBuilder.createNodeOutbound(node, "proxy", hysteriaUpMbps, hysteriaDownMbps)
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
        return gson.toJson(outboundBuilder.completeConfig(config, listOf(node)))
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
                    add(outboundBuilder.createNodeOutbound(node, node.id, hysteriaUpMbps, hysteriaDownMbps))
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
        return gson.toJson(outboundBuilder.completeConfig(config, nodes))
    }

    /**
     * 生成一个进程承载多个独立 SOCKS 入站的批量测速配置。
     *
     * 每个入站固定路由到同下标节点。应用层的各核心共用一套 SOCKS/HTTP 探测逻辑，
     * 避免 Clash API 修改 HTTP 测试地址而造成不同核心的测速口径不一致。
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
    
    @Deprecated("Use IpFamilyRoutingSettings")
    private fun createDnsConfig(
        proxyMode: ProxyMode,
        nodeDomains: List<String>,
        ipv6Mode: IPv6RoutingMode,
        dnsSettings: DnsSettings,
        lanInboundEnabled: Boolean
    ): JsonObject = createDnsConfig(
        proxyMode,
        nodeDomains,
        ipv6Mode.toIpFamilyRoutingSettings(),
        dnsSettings,
        lanInboundEnabled
    )

    private fun createDnsConfig(
        proxyMode: ProxyMode,
        nodeDomains: List<String>,
        ipFamilySettings: IpFamilyRoutingSettings,
        dnsSettings: DnsSettings,
        lanInboundEnabled: Boolean
    ): JsonObject = createDnsConfig(
        nodeDomains,
        ipFamilySettings,
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

    @Deprecated("Use IpFamilyRoutingSettings")
    private fun createDnsConfig(
        nodeDomains: List<String>,
        ipv6Mode: IPv6RoutingMode,
        dnsSettings: DnsSettings,
        lanInboundEnabled: Boolean,
        smartRoutingSettings: SmartRoutingSettings,
        smartInboundTags: List<String>
    ): JsonObject = createDnsConfig(
        nodeDomains,
        ipv6Mode.toIpFamilyRoutingSettings(),
        dnsSettings,
        lanInboundEnabled,
        smartRoutingSettings,
        smartInboundTags
    )

    private fun createDnsConfig(
        nodeDomains: List<String>,
        ipFamilySettings: IpFamilyRoutingSettings,
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
                            ipFamilySettings,
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
                            ipFamilySettings,
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
                    ipFamilySettings
                ))
            })
        }

        // 2. DNS 路由开启时，智能模式的国内域名使用直连 DNS。
        if (dnsSettings.dnsRoutingEnabled && smartInboundTags.isNotEmpty()) {
            val overriddenDomains = smartRoutingSettings.rules
                .filter {
                    it.enabled &&
                        routingRuleDomains(it).isNotEmpty() &&
                        routingRuleIps(it).isEmpty() &&
                        it.ports.isBlank() && it.network.isBlank() && it.protocols.isEmpty() &&
                        (it.action == SmartRoutingRuleAction.PROXY ||
                            it.action == SmartRoutingRuleAction.BLOCK)
                }
                .flatMap(::routingRuleDomains)
                .filterNot { it.startsWith("geosite:", ignoreCase = true) }
                .map(::routingDomainKey)
                .toSet()
            val customDirectDomains = smartRoutingSettings.rules
                .filter {
                    it.enabled &&
                        routingRuleDomains(it).isNotEmpty() &&
                        routingRuleIps(it).isEmpty() &&
                        it.ports.isBlank() && it.network.isBlank() && it.protocols.isEmpty() &&
                        it.action == SmartRoutingRuleAction.DIRECT
                }
                .flatMap(::routingRuleDomains)
                .filterNot { it.startsWith("geosite:", ignoreCase = true) }
                .filterNot { routingDomainKey(it) in overriddenDomains }
                .distinct()
            if (customDirectDomains.isNotEmpty()) {
                rules.add(JsonObject().apply {
                    add("inbound", JsonArray().apply { smartInboundTags.forEach(::add) })
                    add("domain", JsonArray().apply { customDirectDomains.forEach(::add) })
                    addProperty("server", "dns-direct")
                    addProperty("strategy", resolveDnsStrategy(
                        dnsSettings.directDomainStrategy,
                        ipFamilySettings
                    ))
                })
            }
            rules.add(JsonObject().apply {
                add("inbound", JsonArray().apply { smartInboundTags.forEach(::add) })
                add("rule_set", JsonArray().apply { add("geosite-cn") })
                addProperty("server", "dns-direct")
                addProperty("strategy", resolveDnsStrategy(
                    dnsSettings.directDomainStrategy,
                    ipFamilySettings
                ))
            })
        }

        // FakeIP 服务器是独立的 DNS 传输，仅为来自代理入站的请求返回映射地址。
        if (dnsSettings.fakeDnsEnabled) {
            servers.add(JsonObject().apply {
                addProperty("type", "fakeip")
                addProperty("tag", "dns-fake")
                if (ipFamilySettings.normalized().ipv4Enabled) {
                    addProperty("inet4_range", "198.18.0.0/15")
                }
                if (ipFamilySettings.normalized().ipv6Enabled) {
                    addProperty("inet6_range", "fc00::/18")
                }
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
                ipFamilySettings
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

    @Deprecated("Use IpFamilyRoutingSettings")
    private fun resolveDnsStrategy(
        strategy: DnsDomainStrategy,
        ipv6Mode: IPv6RoutingMode,
        forServerAddress: Boolean = false
    ): String = resolveDnsStrategy(strategy, ipv6Mode.toIpFamilyRoutingSettings(), forServerAddress)

    private fun resolveDnsStrategy(
        strategy: DnsDomainStrategy,
        ipFamilySettings: IpFamilyRoutingSettings,
        forServerAddress: Boolean = false
    ): String = ipFamilySettings.resolveDnsStrategy(strategy, forServerAddress).configValue
    
    private fun createInbounds(endpoint: LocalSocksEndpoint?): JsonArray =
        createInbounds(endpoint, LanInboundSettings())

    private fun createInbounds(
        endpoint: LocalSocksEndpoint?,
        lanInbound: LanInboundSettings,
        nodes: List<Node> = emptyList(),
        failoverProbeEndpoints: Map<String, LocalSocksEndpoint> = emptyMap()
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

            // 仅在当前出站首次探测超时时，才通过专属入口对备用节点做一次预检。
            nodes.forEachIndexed { index, node ->
                val probeEndpoint = failoverProbeEndpoints[node.id] ?: return@forEachIndexed
                add(JsonObject().apply {
                    addProperty("type", "socks")
                    addProperty("tag", failoverProbeInboundTag(index))
                    addProperty("listen", probeEndpoint.address)
                    addProperty("listen_port", probeEndpoint.port)
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

    private fun createRoute(
        nodeDomains: List<String>,
        nodeIPs: List<String>,
        smartRoutingSettings: SmartRoutingSettings,
        dnsRoutingEnabled: Boolean,
        smartInboundTags: List<String>,
        routingInboundTags: List<String>,
        nodes: List<Node>,
        failoverProbeEndpoints: Map<String, LocalSocksEndpoint>,
        ruleSetDirectory: File?,
        ruleSetUrls: Map<String, String>
    ): JsonObject {
        val rules = JsonArray()

        // 预检入站固定走对应节点，避免探测请求被主出站选择器接管。
        nodes.forEachIndexed { index, node ->
            if (node.id !in failoverProbeEndpoints) return@forEachIndexed
            rules.add(JsonObject().apply {
                add("inbound", JsonArray().apply { add(failoverProbeInboundTag(index)) })
                addProperty("action", "route")
                addProperty("outbound", node.id)
            })
        }
        
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
        
        val requiredRuleSetTags = linkedSetOf<String>()
        if (routingInboundTags.isNotEmpty()) {
            fun addRuleSetRule(tag: String, outbound: String) {
                rules.add(JsonObject().apply {
                    add("inbound", JsonArray().apply { routingInboundTags.forEach(::add) })
                    add("rule_set", JsonArray().apply { add(tag) })
                    addProperty("outbound", outbound)
                })
                requiredRuleSetTags += tag
            }

            smartRoutingSettings.rules.filter { it.enabled }.forEach { smartRule ->
                when (smartRule.type) {
                    SmartRoutingRuleType.DOMESTIC_DOMAIN,
                    SmartRoutingRuleType.DOMESTIC_IP -> Unit
                    SmartRoutingRuleType.CUSTOM_DOMAIN,
                    SmartRoutingRuleType.CUSTOM_IP,
                    SmartRoutingRuleType.CUSTOM_RULE -> {
                        val outbound = smartRule.effectiveOutboundTag
                        val domains = routingRuleDomains(smartRule)
                        val ips = routingRuleIps(smartRule)
                        val exactDomains = mutableListOf<String>()
                        val suffixDomains = mutableListOf<String>()
                        val keywordDomains = mutableListOf<String>()
                        val regexpDomains = mutableListOf<String>()
                        val ipCidrs = mutableListOf<String>()
                        val isPrivateIp = mutableListOf<Boolean>()
                        val ruleSetTags = mutableListOf<String>()

                        domains.forEach { raw ->
                            val value = raw.trim()
                            when {
                                value.startsWith("geosite:", ignoreCase = true) ->
                                    value.substringAfter(':').toRuleSetTag("geosite-")
                                        ?.let(ruleSetTags::add)
                                value.startsWith("full:", ignoreCase = true) ->
                                    exactDomains += value.substringAfter(':')
                                value.startsWith("domain:", ignoreCase = true) ->
                                    suffixDomains += value.substringAfter(':')
                                value.startsWith("keyword:", ignoreCase = true) ->
                                    keywordDomains += value.substringAfter(':')
                                value.startsWith("regexp:", ignoreCase = true) ->
                                    regexpDomains += value.substringAfter(':')
                                else -> suffixDomains += value
                            }
                        }
                        ips.forEach { raw ->
                            val value = raw.trim()
                            if (value.startsWith("geoip:", ignoreCase = true)) {
                                val tag = value.substringAfter(':').lowercase()
                                if (tag == "private") isPrivateIp += true
                                else tag.toRuleSetTag("geoip-")?.let(ruleSetTags::add)
                            } else {
                                ipCidrs += normalizeIpCidr(value)
                            }
                        }
                        requiredRuleSetTags += ruleSetTags

                        val (singlePorts, portRanges) = parseRoutingPorts(smartRule.ports)
                        val networks = smartRule.network.split(',').map(String::trim)
                            .filter(String::isNotBlank).distinct()
                        // 源项目在不支持 process 时移除进程条件后仍保留原规则。
                        // 因此即使规则没有其他匹配条件，也不能静默丢弃它。
                        rules.add(JsonObject().apply {
                            add("inbound", JsonArray().apply { routingInboundTags.forEach(::add) })
                            if (exactDomains.isNotEmpty()) add("domain", JsonArray().apply {
                                exactDomains.forEach(::add)
                            })
                            if (suffixDomains.isNotEmpty()) add("domain_suffix", JsonArray().apply {
                                suffixDomains.forEach(::add)
                            })
                            if (keywordDomains.isNotEmpty()) add("domain_keyword", JsonArray().apply {
                                keywordDomains.forEach(::add)
                            })
                            if (regexpDomains.isNotEmpty()) add("domain_regex", JsonArray().apply {
                                regexpDomains.forEach(::add)
                            })
                            if (ipCidrs.isNotEmpty()) add("ip_cidr", JsonArray().apply {
                                ipCidrs.forEach(::add)
                            })
                            if (isPrivateIp.isNotEmpty()) addProperty("ip_is_private", true)
                            if (ruleSetTags.isNotEmpty()) add("rule_set", JsonArray().apply {
                                ruleSetTags.forEach(::add)
                            })
                            if (singlePorts.isNotEmpty()) add("port", JsonArray().apply {
                                singlePorts.forEach(::add)
                            })
                            if (portRanges.isNotEmpty()) add("port_range", JsonArray().apply {
                                portRanges.forEach(::add)
                            })
                            if (networks.isNotEmpty()) add("network", JsonArray().apply {
                                networks.forEach(::add)
                            })
                            if (smartRule.protocols.isNotEmpty()) add("protocol", JsonArray().apply {
                                smartRule.protocols.forEach(::add)
                            })
                            addProperty("outbound", outbound)
                        })
                    }
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
            if (smartInboundTags.isNotEmpty() && dnsRoutingEnabled) {
                requiredRuleSetTags += "geosite-cn"
            }
            if (requiredRuleSetTags.isNotEmpty()) {
                add("rule_set", JsonArray().apply {
                    requiredRuleSetTags.forEach { tag ->
                        add(JsonObject().apply {
                            addProperty("tag", tag)
                            addProperty("format", "binary")
                            val localFile = ruleSetDirectory?.let { File(it, "$tag.srs") }
                                ?.takeIf(File::isFile)
                            if (localFile != null) {
                                addProperty("type", "local")
                                addProperty("path", localFile.absolutePath)
                            } else {
                                addProperty("type", "remote")
                                val family = if (tag.startsWith("geosite-")) "sing-geosite" else "sing-geoip"
                                addProperty(
                                    "url",
                                    ruleSetUrls[tag] ?:
                                        "https://testingcf.jsdelivr.net/gh/SagerNet/$family@rule-set/$tag.srs"
                                )
                                addProperty("download_detour", "direct")
                            }
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

    private fun routingRuleDomains(rule: SmartRoutingRule): List<String> =
        (rule.domains.ifEmpty {
            if (rule.type == SmartRoutingRuleType.CUSTOM_DOMAIN) rule.values else emptyList()
        }).flatMap(::decodeRoutingRuleValues)
    
    private fun routingRuleIps(rule: SmartRoutingRule): List<String> =
        (rule.ips.ifEmpty {
            if (rule.type == SmartRoutingRuleType.CUSTOM_IP) rule.values else emptyList()
        }).flatMap(::decodeRoutingRuleValues)

    private fun String.toRuleSetTag(prefix: String): String? {
        val name = trim().lowercase()
        if (name.isEmpty() || !name.matches(Regex("[a-z0-9_-]+"))) return null
        return "$prefix$name"
    }

    private fun parseRoutingPorts(value: String): Pair<List<Int>, List<String>> {
        val ports = mutableListOf<Int>()
        val ranges = mutableListOf<String>()
        value.split(',', '\n').map(String::trim).filter(String::isNotBlank).forEach { part ->
            val separator = when {
                '-' in part -> '-'
                ':' in part -> ':'
                else -> null
            }
            if (separator == null) {
                part.toIntOrNull()?.takeIf { it in 0..65_535 }?.let(ports::add)
            } else {
                val bounds = part.split(separator, limit = 2)
                val start = bounds.getOrNull(0)?.toIntOrNull()
                val end = bounds.getOrNull(1)?.toIntOrNull()
                if (start != null && end != null && start in 0..65_535 && end in start..65_535) {
                    ranges += "$start:$end"
                }
            }
        }
        return ports.distinct() to ranges.distinct()
    }
    
    private fun createExperimental(): JsonObject {
        return JsonObject().apply {
            add("cache_file", JsonObject().apply { addProperty("enabled", true) })
            add("clash_api", JsonObject().apply { addProperty("external_controller", "127.0.0.1:9090") })
        }
    }

    private fun failoverProbeInboundTag(index: Int): String = "chimera-failover-probe-$index"


}
