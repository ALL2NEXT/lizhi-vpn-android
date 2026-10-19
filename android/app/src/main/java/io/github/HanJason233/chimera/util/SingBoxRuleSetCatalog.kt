package io.github.HanJason233.chimera.util

internal enum class SingBoxRuleSetKind(val tagPrefix: String, val routePrefix: String) {
    GEOSITE("geosite", "geosite"),
    GEOIP("geoip", "geoip")
}

internal enum class SingBoxRuleSetProvider(
    val id: String,
    val title: String,
    val description: String
) {
    SAGER_NET("sagernet", "SagerNet", "官方 GeoSite / GeoIP 基础规则"),
    META_CUBE_X("metacubex", "MetaCubeX", "细分服务、地区与应用类别"),
    DUSTIN_WIN("dustinwin", "DustinWin", "常用整合分流规则，提供 sing-box 兼容版"),
    KARING_X("karingx", "KaringX", "广告拦截、AdGuard 和 ACL4SSR 规则")
}

internal data class SingBoxRuleSetCatalogEntry(
    val provider: SingBoxRuleSetProvider,
    val kind: SingBoxRuleSetKind,
    /** 可直接用于用户分流规则的标签后缀，例如 meta-category-games-cn。 */
    val routeName: String,
    val title: String,
    val sourcePath: String,
    val url: String
) {
    val tag: String get() = "${kind.tagPrefix}-$routeName"
    val fileName: String get() = "$tag.srs"
    val routeExpression: String get() = "${kind.routePrefix}:$routeName"
}

internal object SingBoxRuleSetCatalog {
    val providers = SingBoxRuleSetProvider.entries

    val entries: List<SingBoxRuleSetCatalogEntry> = buildList {
        fun addSagerNet(kind: SingBoxRuleSetKind, name: String, title: String, sourceName: String = name) {
            val fileName = "${kind.tagPrefix}-$sourceName.srs"
            val family = if (kind == SingBoxRuleSetKind.GEOSITE) "sing-geosite" else "sing-geoip"
            add(
                SingBoxRuleSetCatalogEntry(
                    provider = SingBoxRuleSetProvider.SAGER_NET,
                    kind = kind,
                    routeName = name,
                    title = title,
                    sourcePath = fileName,
                    url = "https://testingcf.jsdelivr.net/gh/SagerNet/$family@rule-set/$fileName"
                )
            )
        }

        fun addMeta(kind: SingBoxRuleSetKind, name: String, title: String, sourceName: String = name) {
            val directory = if (kind == SingBoxRuleSetKind.GEOSITE) "geosite" else "geoip"
            val sourcePath = "geo/$directory/$sourceName.srs"
            add(
                SingBoxRuleSetCatalogEntry(
                    provider = SingBoxRuleSetProvider.META_CUBE_X,
                    kind = kind,
                    routeName = "meta-$name",
                    title = title,
                    sourcePath = sourcePath,
                    url = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/$sourcePath"
                )
            )
        }

        fun addDustin(kind: SingBoxRuleSetKind, name: String, title: String, sourceName: String = name) {
            val sourcePath = "$sourceName.srs"
            add(
                SingBoxRuleSetCatalogEntry(
                    provider = SingBoxRuleSetProvider.DUSTIN_WIN,
                    kind = kind,
                    routeName = "dustin-$name",
                    title = title,
                    sourcePath = sourcePath,
                    url = "https://github.com/DustinWin/ruleset_geodata/releases/download/sing-box-ruleset/$sourcePath"
                )
            )
        }

        fun addKaring(
            kind: SingBoxRuleSetKind,
            name: String,
            title: String,
            sourcePath: String
        ) {
            add(
                SingBoxRuleSetCatalogEntry(
                    provider = SingBoxRuleSetProvider.KARING_X,
                    kind = kind,
                    routeName = "karing-$name",
                    title = title,
                    sourcePath = sourcePath,
                    url = "https://raw.githubusercontent.com/KaringX/karing-ruleset/sing/$sourcePath"
                )
            )
        }

        // SagerNet 官方规则。geoip-cn.srs 和 geosite-cn.srs 已随 APK 内置。
        listOf(
            "google" to "Google",
            "apple" to "Apple",
            "microsoft" to "Microsoft",
            "telegram" to "Telegram",
            "netflix" to "Netflix",
            "youtube" to "YouTube",
            "category-ads-all" to "广告域名",
            "geolocation-cn" to "中国大陆域名",
            "private" to "私有域名",
            "category-games-cn" to "中国游戏服务",
            "sagernet-global-sites" to "非中国大陆域名"
        ).forEach { (name, title) ->
            addSagerNet(
                SingBoxRuleSetKind.GEOSITE,
                name,
                title,
                sourceName = if (name == "sagernet-global-sites") "geolocation-!cn" else name
            )
        }
        listOf("hk", "jp", "kr", "sg", "tw", "us", "de", "fr", "gb", "ca", "au").forEach { code ->
            addSagerNet(SingBoxRuleSetKind.GEOIP, code, "IP · ${code.uppercase()}")
        }

        // MetaCubeX 规则集保留独立标签前缀，避免覆盖 SagerNet 同名规则。
        listOf(
            "apple" to ("Apple" to "apple"),
            "apple-cn" to ("Apple 中国" to "apple-cn"),
            "apple-music" to ("Apple Music" to "apple-music"),
            "bilibili" to ("哔哩哔哩" to "bilibili"),
            "ads" to ("广告域名" to "category-ads-all"),
            "ecommerce" to ("电商平台" to "category-ecommerce"),
            "education-cn" to ("中国教育服务" to "category-education-cn"),
            "entertainment-cn" to ("中国娱乐服务" to "category-entertainment-cn"),
            "games-cn" to ("中国游戏服务" to "category-games-cn"),
            "games" to ("游戏服务" to "category-games"),
            "scholar-cn" to ("中国学术服务" to "category-scholar-cn"),
            "scholar-global" to ("国际学术服务" to "category-scholar-!cn"),
            "social-global" to ("国际社交媒体" to "category-social-media-!cn")
        ).forEach { (name, detail) ->
            addMeta(SingBoxRuleSetKind.GEOSITE, name, detail.first, detail.second)
        }
        listOf(
            "cn", "hk", "tw", "jp", "kr", "sg", "us", "ca", "au", "gb", "de", "fr", "ru", "ir", "in"
        ).forEach { code ->
            addMeta(SingBoxRuleSetKind.GEOIP, code, "IP · ${code.uppercase()}")
        }
        listOf("google", "netflix", "telegram").forEach { service ->
            addMeta(SingBoxRuleSetKind.GEOIP, service, "${service.replaceFirstChar(Char::uppercase)} IP")
        }

        // DustinWin 发布预编译 sing-box .srs 版本，适合直接由核心加载。
        listOf(
            "ads" to "广告域名",
            "ai" to "AI 服务",
            "apple-cn" to "Apple 中国",
            "appletv" to "Apple TV+",
            "applications" to "常用应用",
            "bilibili" to "哔哩哔哩",
            "disney" to "Disney+",
            "fakeip-filter" to "FakeIP 过滤",
            "fakeip-filter-lite" to "FakeIP 过滤（精简）",
            "games-cn" to "中国游戏服务",
            "games" to "游戏服务",
            "gfw" to "GFW",
            "google-cn" to "Google 中国",
            "max" to "Max",
            "media" to "流媒体服务",
            "microsoft-cn" to "Microsoft 中国",
            "netflix" to "Netflix",
            "primevideo" to "Prime Video",
            "private" to "私有域名",
            "proxy" to "代理域名",
            "spotify" to "Spotify",
            "tiktok" to "TikTok",
            "tld-proxy" to "代理顶级域名",
            "trackerslist" to "跟踪器域名",
            "youtube" to "YouTube"
        ).forEach { (name, title) -> addDustin(SingBoxRuleSetKind.GEOSITE, name, title) }
        listOf(
            "cn-ip" to ("中国 IP" to "cnip"),
            "private-ip" to ("私有 IP" to "privateip"),
            "telegram-ip" to ("Telegram IP" to "telegramip"),
            "netflix-ip" to ("Netflix IP" to "netflixip"),
            "media-ip" to ("流媒体 IP" to "mediaip")
        ).forEach { (name, detail) ->
            addDustin(SingBoxRuleSetKind.GEOIP, name, detail.first, detail.second)
        }

        // KaringX 提供 ACL4SSR、AdGuard HostlistsRegistry 等 sing-box 兼容规则。
        listOf(
            "banad" to ("广告拦截" to "BanAD"),
            "banad-company" to ("广告拦截（企业）" to "BanADCompany"),
            "ban-easylist" to ("EasyList 广告规则" to "BanEasyList"),
            "ban-easylist-china" to ("EasyList China 广告规则" to "BanEasyListChina"),
            "ban-easyprivacy" to ("EasyPrivacy 跟踪器规则" to "BanEasyPrivacy"),
            "cn" to ("中国大陆域名" to "CN"),
            "google" to ("Google" to "Google"),
            "google-cn" to ("Google 中国" to "GoogleCN"),
            "netflix" to ("Netflix" to "Netflix"),
            "telegram" to ("Telegram" to "Telegram"),
            "steam-cn" to ("Steam 中国" to "SteamCN"),
            "top-blocked-sites" to ("常见受限站点" to "TopBlockedSites")
        ).forEach { (name, detail) ->
            addKaring(SingBoxRuleSetKind.GEOSITE, name, detail.first, "ACL4SSR/${detail.second}.srs")
        }
        listOf(
            "china-ip" to ("中国 IP" to "ChinaIp"),
            "china-ipv6" to ("中国 IPv6" to "ChinaIpV6"),
            "china-company-ip" to ("中国企业 IP" to "ChinaCompanyIp"),
            "netflix-ip" to ("Netflix IP" to "NetflixIP"),
            "google-cn-proxy-ip" to ("Google 中国代理 IP" to "GoogleCNProxyIP")
        ).forEach { (name, detail) ->
            addKaring(SingBoxRuleSetKind.GEOIP, name, detail.first, "ACL4SSR/${detail.second}.srs")
        }
        listOf(
            "adguard-dns-filter" to ("AdGuard DNS Filter" to "adguard_dns_filter"),
            "adguard-china-anti-ad" to ("中国反广告规则" to "CHN_anti_ad"),
            "adguard-1hosts-lite" to ("1Hosts Lite" to "1hosts_lite"),
            "adguard-oisd-basic" to ("OISD Basic" to "oisd_basic"),
            "adguard-oisd-full" to ("OISD Full" to "oisd_full"),
            "adguard-hagezi-pro" to ("HaGeZi Pro" to "hagezi_pro"),
            "adguard-hagezi-ultimate" to ("HaGeZi Ultimate" to "hagezi_ultimate")
        ).forEach { (name, detail) ->
            addKaring(
                SingBoxRuleSetKind.GEOSITE,
                name,
                detail.first,
                "AdGuard/HostlistsRegistry/${detail.second}.srs"
            )
        }
    }.distinctBy(SingBoxRuleSetCatalogEntry::tag)

    fun entries(provider: SingBoxRuleSetProvider): List<SingBoxRuleSetCatalogEntry> =
        entries.filter { it.provider == provider }

    fun find(tag: String): SingBoxRuleSetCatalogEntry? = entries.firstOrNull { it.tag == tag }
}
