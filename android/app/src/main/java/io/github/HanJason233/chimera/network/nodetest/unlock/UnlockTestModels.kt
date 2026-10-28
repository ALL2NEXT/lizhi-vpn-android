package io.github.HanJason233.chimera.network.nodetest.unlock

import org.json.JSONObject

internal enum class UnlockTestCategory(val title: String) {
    NETWORK("网络安全"),
    AI("AI 服务"),
    STREAMING("流媒体"),
    OTHER("音乐与知识")
}

internal enum class UnlockTestState {
    IDLE,
    TESTING,
    UNLOCKED,
    PARTIAL,
    BLOCKED,
    WARNING,
    ERROR,
    NOT_CONNECTED
}

internal data class UnlockTestDefinition(
    val id: String,
    val name: String,
    val category: UnlockTestCategory,
    val testingDescription: String
)

internal data class UnlockTestResult(
    val definition: UnlockTestDefinition,
    val state: UnlockTestState = UnlockTestState.IDLE,
    val statusText: String = "等待检测",
    val description: String = "连接 VPN 后可检测当前节点的可用性。",
    val region: String = "",
    val delayMs: Int? = null
)

internal data class UnlockHttpResponse(
    val statusCode: Int,
    val body: String = "",
    val location: String? = null,
    val delayMs: Int? = null,
    val finalUrl: String = "",
    val setCookieHeaders: List<String> = emptyList()
)

internal object UnlockTestCatalog {
    val definitions = listOf(
        UnlockTestDefinition("google_cn", "Google 送中检测", UnlockTestCategory.NETWORK, "检测 Google 请求是否被重定向到 google.cn。"),
        UnlockTestDefinition("chatgpt", "ChatGPT / OpenAI", UnlockTestCategory.AI, "使用 OpenAI 地区与代理检测接口判断可用性。"),
        UnlockTestDefinition("copilot", "Microsoft Copilot", UnlockTestCategory.AI, "检测 Copilot 网页端地区可用性。"),
        UnlockTestDefinition("claude", "Claude", UnlockTestCategory.AI, "检测 Claude 登录页的地区限制与风控。"),
        UnlockTestDefinition("gemini", "Google Gemini", UnlockTestCategory.AI, "检测 Gemini 网页端的地区开放状态。"),
        UnlockTestDefinition("llama", "Meta Llama API", UnlockTestCategory.AI, "检测 Llama API 端点是否受地区限制。"),
        UnlockTestDefinition("perplexity", "Perplexity", UnlockTestCategory.AI, "检测 Perplexity API 端点和地区状态。"),
        UnlockTestDefinition("sora", "Sora", UnlockTestCategory.AI, "检测 OpenAI 视频 API 的地区可用性。"),
        UnlockTestDefinition("grok", "Grok", UnlockTestCategory.AI, "检测 xAI 网页端地区可用性。"),
        UnlockTestDefinition("netflix", "Netflix", UnlockTestCategory.STREAMING, "区分完整版权库、仅自制内容和不可用。"),
        UnlockTestDefinition("disney", "Disney+", UnlockTestCategory.STREAMING, "检测 Disney+ 的地区访问限制。"),
        UnlockTestDefinition("bilibili", "哔哩哔哩港澳台", UnlockTestCategory.STREAMING, "通过番剧播放接口检测港澳台内容权限。"),
        UnlockTestDefinition("dazn", "DAZN", UnlockTestCategory.STREAMING, "检测 DAZN 启动接口返回的地区权限。"),
        UnlockTestDefinition("abema", "AbemaTV", UnlockTestCategory.STREAMING, "检测 AbemaTV 日本区服务可访问性。"),
        UnlockTestDefinition("bahamut", "巴哈姆特动画疯", UnlockTestCategory.STREAMING, "通过设备和播放令牌接口检测地区权限。"),
        UnlockTestDefinition("steam", "Steam 商店区", UnlockTestCategory.OTHER, "读取 Steam 商店按出口地区返回的货币。"),
        UnlockTestDefinition("max", "Max (HBO)", UnlockTestCategory.STREAMING, "检测 Max 的地区访问限制。"),
        UnlockTestDefinition("prime", "Prime Video", UnlockTestCategory.STREAMING, "检测 Prime Video 的地区访问限制。"),
        UnlockTestDefinition("youtube", "YouTube Premium", UnlockTestCategory.STREAMING, "检测 Premium 是否开放并识别区域。"),
        UnlockTestDefinition("tiktok", "TikTok", UnlockTestCategory.STREAMING, "检测国际版内容访问与人机风控。"),
        UnlockTestDefinition("spotify", "Spotify", UnlockTestCategory.OTHER, "检测 Spotify 的地区访问限制。"),
        UnlockTestDefinition("wikipedia", "Wikipedia", UnlockTestCategory.OTHER, "检测访问以及匿名编辑限制。")
    )

}

/** 纯响应判定逻辑；与网络请求分离，便于用固定响应做回归测试。 */
internal object UnlockTestClassifier {
    fun googleChina(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult {
        val location = response.location.orEmpty()
        return when {
            response.statusCode == 204 -> result(
                definition, UnlockTestState.UNLOCKED, "未送中 ✓",
                "Google 返回 204，未被导向中国大陆服务器。", response = response
            )
            response.statusCode in 300..399 && location.contains("google.cn", ignoreCase = true) -> result(
                definition, UnlockTestState.BLOCKED, "已送中 ✗",
                "Google 请求被重定向到 ${location.substringBefore('?').take(100)}。", response = response
            )
            response.statusCode in 300..399 -> result(
                definition, UnlockTestState.WARNING, "重定向待确认",
                "Google 返回 ${response.statusCode}，目标为 ${location.ifBlank { "未知地址" }.take(100)}。", response = response
            )
            response.statusCode in 200..299 -> result(
                definition, UnlockTestState.UNLOCKED, "未送中 ✓",
                "Google 返回 ${response.statusCode}，未发现 google.cn 重定向。", response = response
            )
            else -> result(
                definition, UnlockTestState.WARNING, "无法确认",
                "Google 返回 HTTP ${response.statusCode}，无法可靠判断是否送中。", response = response
            )
        }
    }

    fun chatGpt(
        definition: UnlockTestDefinition,
        web: UnlockHttpResponse,
        mobile: UnlockHttpResponse?
    ): UnlockTestResult {
        val challenge = web.statusCode in setOf(403, 429, 451) || web.body.containsAny(
            "cf-mitigated", "Attention Required", "error code: 1020", "Just a moment", "Cloudflare Ray ID"
        )
        if (web.isReachable() && !challenge) {
            return result(
                definition, UnlockTestState.UNLOCKED, "支持",
                "ChatGPT 网页端可访问，未发现 Cloudflare 地区或风控拦截。", response = web
            )
        }
        if (mobile != null && mobile.statusCode in 200..299 && mobile.body.containsAny(
                "status", "ok", "normal", "maintenance"
            )
        ) {
            return result(
                definition, UnlockTestState.PARTIAL, "App/API 可用",
                "网页端触发验证，但官方 App/API 状态链路可访问。", response = mobile
            )
        }
        return if (challenge) {
            result(
                definition, UnlockTestState.BLOCKED, "受限/触发风控",
                "网页端触发 Cloudflare/OpenAI 风控，备用链路也未通过。", response = web
            )
        } else {
            result(
                definition, UnlockTestState.BLOCKED, "未解锁",
                "当前出口无法正常连接 ChatGPT 官方服务。", response = web
            )
        }
    }

    fun openAi(
        definition: UnlockTestDefinition,
        trace: UnlockHttpResponse,
        compliance: UnlockHttpResponse,
        ios: UnlockHttpResponse
    ): UnlockTestResult {
        val region = traceRegion(trace.body)
        val countryBlocked = compliance.body.contains("unsupported_country", ignoreCase = true)
        val vpnBlocked = ios.body.contains("vpn", ignoreCase = true)
        val status = when {
            countryBlocked && vpnBlocked -> "地区不支持 / IP 被识别为代理"
            countryBlocked -> "地区不支持"
            vpnBlocked -> "IP 被识别为代理"
            else -> "未发现地区或代理拦截"
        }
        return result(
            definition,
            if (countryBlocked || vpnBlocked) UnlockTestState.BLOCKED else UnlockTestState.UNLOCKED,
            status,
            when {
                countryBlocked && vpnBlocked -> "OpenAI 返回不支持地区信号，且 iOS 端点将当前 IP 识别为代理。"
                countryBlocked -> "OpenAI 地区接口返回 unsupported_country。"
                vpnBlocked -> "OpenAI iOS 端点将当前出口 IP 识别为 VPN 或代理。"
                else -> "OpenAI 地区和代理检测端点均未返回限制信号；这不代表已验证账号登录或模型调用。"
            },
            region,
            compliance
        )
    }

    fun copilot(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult {
        val blocked = response.statusCode != 200 || response.body.containsAny(
            "not available in your region", "unavailable in your market"
        )
        return if (blocked) {
            result(definition, UnlockTestState.BLOCKED, "不可用", "Copilot 返回地区限制或 HTTP ${response.statusCode}。", response = response)
        } else {
            result(definition, UnlockTestState.UNLOCKED, "页面可访问", "Copilot 首页返回 HTTP 200，未发现地区限制提示。", response = response)
        }
    }

    fun llama(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult {
        val body = response.body.lowercase()
        return when {
            response.statusCode == 200 || response.statusCode == 401 -> result(
                definition, UnlockTestState.UNLOCKED, "API 可达",
                "Llama API 返回 HTTP ${response.statusCode}；401 表示端点要求 API 密钥。", response = response
            )
            body.containsAny("country", "region", "not supported") -> result(
                definition, UnlockTestState.BLOCKED, "地区受限", "Llama API 响应包含地区限制提示。", response = response
            )
            else -> result(
                definition, UnlockTestState.WARNING, "结果不确定",
                "Llama API 返回 HTTP ${response.statusCode}，无法据此确认地区权限。", response = response
            )
        }
    }

    fun perplexity(
        definition: UnlockTestDefinition,
        api: UnlockHttpResponse,
        trace: UnlockHttpResponse?
    ): UnlockTestResult {
        val region = trace?.let { traceRegion(it.body) }.orEmpty()
        return when {
            api.statusCode == 401 || api.statusCode == 200 -> result(
                definition, UnlockTestState.UNLOCKED, "API 可达",
                "Perplexity API 可达；无密钥时返回 HTTP ${api.statusCode}。", region, api
            )
            api.body.containsAny("country", "region", "not supported", "not available") -> result(
                definition, UnlockTestState.BLOCKED, "地区受限", "Perplexity API 响应包含地区限制提示。", region, api
            )
            else -> result(
                definition, UnlockTestState.WARNING, "结果不确定",
                "Perplexity API 返回 HTTP ${api.statusCode}，无法确认地区权限。", region, api
            )
        }
    }

    fun sora(
        definition: UnlockTestDefinition,
        api: UnlockHttpResponse,
        trace: UnlockHttpResponse?
    ): UnlockTestResult {
        val region = trace?.let { traceRegion(it.body) }.orEmpty()
        return when {
            api.statusCode == 401 || api.statusCode == 200 -> result(
                definition, UnlockTestState.UNLOCKED, "API 可达",
                "OpenAI 视频 API 可达；无密钥时返回 HTTP ${api.statusCode}。", region, api
            )
            api.body.containsAny("unsupported_country", "not supported") -> result(
                definition, UnlockTestState.BLOCKED, "地区受限", "OpenAI 视频 API 响应包含地区限制提示。", region, api
            )
            else -> result(
                definition, UnlockTestState.WARNING, "结果不确定",
                "OpenAI 视频 API 返回 HTTP ${api.statusCode}，无法确认地区权限。", region, api
            )
        }
    }

    fun grok(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult {
        val blocked = response.body.containsAny("not available in your country", "unsupported_country")
        return when {
            blocked -> result(definition, UnlockTestState.BLOCKED, "地区受限", "Grok 返回地区限制提示。", response = response)
            response.statusCode == 200 -> result(definition, UnlockTestState.UNLOCKED, "页面可访问", "Grok 首页返回 HTTP 200。", response = response)
            else -> result(
                definition, UnlockTestState.BLOCKED, "不可用",
                "Grok 返回 HTTP ${response.statusCode}。", response = response
            )
        }
    }

    fun claude(
        definition: UnlockTestDefinition,
        response: UnlockHttpResponse,
        trace: UnlockHttpResponse? = null
    ): UnlockTestResult {
        val region = trace?.let { traceRegion(it.body) }.orEmpty()
        val blockedByRegion = response.body.contains("not available in your country", ignoreCase = true)
        return when {
            blockedByRegion -> result(
                definition, UnlockTestState.BLOCKED, "地区受限", "Claude 页面返回当前地区不可用提示。", region, response
            )
            response.statusCode != 200 -> result(
                definition, UnlockTestState.BLOCKED, "不可用",
                "Claude 首页返回 HTTP ${response.statusCode}。", region, response
            )
            else -> result(
                definition, UnlockTestState.UNLOCKED, "页面可访问",
                "Claude 首页返回 HTTP 200，未发现地区限制提示。", region, response
            )
        }
    }

    fun gemini(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult {
        val blockedByRegion = response.body.containsAny(
            "isn't currently supported in your country", "not available in your country"
        )
        return if (response.statusCode >= 400 || blockedByRegion) {
            result(
                definition, UnlockTestState.BLOCKED, "未解锁",
                "Gemini 返回地区限制提示或 HTTP ${response.statusCode}。", response = response
            )
        } else {
            result(
                definition, UnlockTestState.UNLOCKED, "页面可访问",
                "Gemini 网页端返回 HTTP ${response.statusCode}，未发现地区限制提示。", response = response
            )
        }
    }

    fun netflix(
        definition: UnlockTestDefinition,
        licensed: UnlockHttpResponse,
        original: UnlockHttpResponse?
    ): UnlockTestResult {
        val licensedUnavailable = licensed.body.contains("Oh no!")
        val originalUnavailable = original?.body?.contains("Oh no!") ?: true
        val region = Regex("\"requestCountry\"\\s*:\\s*\\{[^}]*\"id\"\\s*:\\s*\"([A-Za-z]{2})\"", RegexOption.IGNORE_CASE)
            .find(licensed.body)?.groupValues?.getOrNull(1)?.uppercase()
            ?: Regex("geolocation_country.*?([A-Za-z]{2})", RegexOption.IGNORE_CASE)
                .find(licensed.body)?.groupValues?.getOrNull(1)?.uppercase().orEmpty()
        if (!licensedUnavailable) {
            val label = regionLabel(region)
            return result(
                definition, UnlockTestState.UNLOCKED,
                if (label.isBlank()) "完整解锁" else "完整解锁 $label",
                "Netflix 非自制内容测试页未返回地区不可用标记。", region, licensed
            )
        }
        return if (!originalUnavailable) {
            result(
                definition, UnlockTestState.PARTIAL, "仅自制剧",
                "仅支持 Netflix 自制内容，非自制版权内容受限。", response = original
            )
        } else {
            result(
                definition, UnlockTestState.BLOCKED, "未解锁",
                "当前出口无法播放 Netflix 内容，或被识别为代理。", response = licensed
            )
        }
    }

    fun disney(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult {
        val location = response.location.orEmpty()
        val blocked = location.containsAny("blocked", "unavailable") ||
            response.body.contains("not available in your region", ignoreCase = true) ||
            response.statusCode >= 400
        return if (blocked) {
            result(
                definition, UnlockTestState.BLOCKED, "地区受限",
                "Disney+ 返回地区限制跳转、提示或 HTTP ${response.statusCode}。", response = response
            )
        } else {
            result(
                definition, UnlockTestState.UNLOCKED, "入口可访问",
                "Disney+ 首页请求未发现地区限制信号。", response = response
            )
        }
    }

    fun bilibili(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult {
        val code = runCatching { JSONObject(response.body).optInt("code", Int.MIN_VALUE) }
            .getOrDefault(Int.MIN_VALUE)
        return when {
            response.statusCode == 200 && code == 0 -> result(
                definition, UnlockTestState.UNLOCKED, "港澳台内容可播",
                "哔哩哔哩番剧播放接口返回 code=0。", "港澳台", response
            )
            response.statusCode == 200 && code != Int.MIN_VALUE -> result(
                definition, UnlockTestState.BLOCKED, "港澳台内容受限",
                "番剧播放接口返回 code=$code，${runCatching { JSONObject(response.body).optString("message") }.getOrDefault("")}",
                response = response
            )
            else -> result(
                definition, UnlockTestState.WARNING, "结果不确定",
                "哔哩哔哩播放接口返回 HTTP ${response.statusCode} 或非 JSON 响应。", response = response
            )
        }
    }

    fun dazn(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult {
        val body = response.body
        val explicitlyDenied = Regex("\"isAllowed\"\\s*:\\s*false", RegexOption.IGNORE_CASE).containsMatchIn(body)
        val region = Regex("\"(?i:geoLocatedCountry|country|region)[A-Za-z]*\"\\s*:\\s*\"([A-Z]{2})\"")
            .find(body)?.groupValues?.getOrNull(1).orEmpty()
        return when {
            response.statusCode != 200 || explicitlyDenied -> result(
                definition, UnlockTestState.BLOCKED, "不可用",
                "DAZN 启动接口拒绝访问或返回 isAllowed=false。", region, response
            )
            region.isNotBlank() -> result(
                definition, UnlockTestState.UNLOCKED, "支持 ${regionLabel(region)}",
                "DAZN 启动接口允许当前出口所在地区。", region, response
            )
            else -> result(
                definition, UnlockTestState.WARNING, "结果不确定",
                "DAZN 启动接口可访问，但响应中没有可识别的地区字段。", response = response
            )
        }
    }

    fun abema(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult =
        if (response.statusCode == 200) {
            result(definition, UnlockTestState.UNLOCKED, "日本区入口可访问", "AbemaTV 首页返回 HTTP 200。", "JP", response)
        } else {
            result(
                definition, UnlockTestState.BLOCKED, "不可用",
                "AbemaTV 首页返回 HTTP ${response.statusCode}。", response = response
            )
        }

    fun bahamut(
        definition: UnlockTestDefinition,
        device: UnlockHttpResponse,
        token: UnlockHttpResponse?,
        home: UnlockHttpResponse?
    ): UnlockTestResult {
        val deviceId = runCatching { JSONObject(device.body).optString("deviceid") }.getOrDefault("")
        if (deviceId.isBlank()) {
            return result(
                definition, UnlockTestState.BLOCKED, "無法取得播放設備",
                "動畫瘋設備接口沒有返回 deviceid。", response = device
            )
        }
        if (token == null || !token.body.contains("animeSn", ignoreCase = true)) {
            return result(
                definition, UnlockTestState.BLOCKED, "播放令牌受限",
                "動畫瘋令牌接口沒有返回 animeSn。", response = token ?: device
            )
        }
        val extractedRegion = Regex("data-geo=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(home?.body.orEmpty())?.groupValues?.getOrNull(1).orEmpty()
        val region = extractedRegion.ifBlank { "TW" }
        return result(
            definition, UnlockTestState.UNLOCKED, "動畫接口可用",
            "動畫瘋成功返回播放令牌。", region, token
        )
    }

    fun steam(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult {
        val currency = Regex("itemprop=\"priceCurrency\"\\s+content=\"([A-Z]{3})\"")
            .find(response.body)?.groupValues?.getOrNull(1)
        return when {
            response.statusCode != 200 -> result(
                definition, UnlockTestState.BLOCKED, "商店不可用",
                "Steam 商店返回 HTTP ${response.statusCode}。", response = response
            )
            currency != null -> result(
                definition, UnlockTestState.UNLOCKED, "商店可访问 $currency",
                "Steam 商店按当前出口返回货币 $currency。", response = response
            )
            else -> result(
                definition, UnlockTestState.WARNING, "结果不确定",
                "Steam 商店可访问，但没有提取到货币代码。", response = response
            )
        }
    }

    fun max(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult =
        classifyRegionalPage(
            definition, response,
            arrayOf("not available in your region", "unsupported_location"),
            arrayOf("Max", "HBO", "sign in", "login"),
            "Max 登录服务可访问，未发现地区限制。",
            "当前出口地区不支持 Max 服务。"
        )

    fun prime(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult =
        classifyRegionalPage(
            definition, response,
            arrayOf("georestricted", "not-available-in-your-country", "not available in your location"),
            arrayOf("Prime Video", "primevideo", "Amazon"),
            "Prime Video 页面可访问，未发现地区限制。",
            "当前出口无法正常访问 Prime Video，或受地域限制。"
        )

    fun youtube(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult {
        val body = response.body
        val region = Regex("\"gl\"\\s*:\\s*\"([A-Z]{2})\"")
            .find(body)?.groupValues?.getOrNull(1)
            ?: Regex("[?&]gl=([A-Z]{2})")
                .find(response.finalUrl)?.groupValues?.getOrNull(1).orEmpty()
        return when {
            response.statusCode != 200 -> result(
                definition, UnlockTestState.BLOCKED, "检测失败",
                "YouTube Premium 页面返回 HTTP ${response.statusCode}。", response = response
            )
            region.isBlank() || region == "CN" -> result(
                definition, UnlockTestState.BLOCKED, "送中 CN",
                "YouTube 地区码为空或为 CN，按 NekoSpeed 规则判定为送中。", "CN", response
            )
            else -> result(
                definition, UnlockTestState.UNLOCKED, "支持 ${regionLabel(region)}",
                "YouTube Premium 已开放。", region, response
            )
        }
    }

    fun tiktok(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult {
        val region = Regex("\"region\"\\s*:\\s*\"([A-Za-z]{2})\"")
            .find(response.body)?.groupValues?.getOrNull(1)?.uppercase().orEmpty()
        return when {
            response.statusCode == 200 && region.isNotBlank() -> result(
                definition, UnlockTestState.UNLOCKED,
                "支持 ${regionLabel(region)}",
                "TikTok 分享页返回 region 字段。", region, response
            )
            else -> result(
                definition, UnlockTestState.BLOCKED, "未解锁",
                if (response.statusCode != 200) {
                    "TikTok 分享接口返回 HTTP ${response.statusCode}。"
                } else {
                    "TikTok 分享页没有 region 字段。"
                },
                region, response
            )
        }
    }

    fun spotify(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult {
        val blockedByRegion = response.body.containsAny(
            "not available in your country", "spotify is currently not available"
        )
        return if (response.statusCode != 200 || blockedByRegion) {
            result(
                definition, UnlockTestState.BLOCKED, "未解锁",
                "Spotify 注册页返回地区限制提示或 HTTP ${response.statusCode}。", response = response
            )
        } else {
            result(
                definition, UnlockTestState.UNLOCKED, "注册页可访问",
                "Spotify 注册页返回 HTTP 200，未发现地区限制提示。", response = response
            )
        }
    }

    fun wikipedia(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult = when {
        response.statusCode == 403 -> result(
            definition, UnlockTestState.BLOCKED, "访问被拦截",
            "Wikipedia 返回 403 Forbidden。", response = response
        )
        response.body.containsAny("\"blockid\"", "\"blockedby\"") -> result(
            definition, UnlockTestState.PARTIAL, "仅只读",
            "页面可访问，但当前出口被限制匿名编辑。", response = response
        )
        response.isReachable() && response.body.containsAny("\"userinfo\"", "\"id\":0", "\"anon\"") -> result(
            definition, UnlockTestState.UNLOCKED, "支持完整编辑",
            "Wikipedia API 可访问，未发现匿名编辑封禁。", response = response
        )
        else -> result(
            definition, UnlockTestState.BLOCKED, "访问受限",
            "无法确认 Wikipedia 的正常访问与编辑能力。", response = response
        )
    }

    fun error(definition: UnlockTestDefinition, throwable: Throwable): UnlockTestResult {
        val message = throwable.message.orEmpty().ifBlank { throwable.javaClass.simpleName }.take(140)
        return result(
            definition, UnlockTestState.ERROR, "检测失败",
            "连接超时或网络异常：$message"
        )
    }

    private fun classifyRegionalPage(
        definition: UnlockTestDefinition,
        response: UnlockHttpResponse,
        blockedMarkers: Array<String>,
        successMarkers: Array<String>,
        successDescription: String,
        blockedDescription: String
    ): UnlockTestResult {
        val blocked = response.statusCode in setOf(403, 451) || response.body.containsAny(*blockedMarkers)
        val success = response.isReachable() && response.body.containsAny(*successMarkers)
        return when {
            blocked -> result(definition, UnlockTestState.BLOCKED, "未解锁", blockedDescription, response = response)
            success -> result(definition, UnlockTestState.UNLOCKED, "支持", successDescription, response = response)
            response.isReachable() -> result(
                definition, UnlockTestState.WARNING, "结果不确定",
                "服务可连接，但响应中没有足够信息确认地区授权。", response = response
            )
            else -> result(
                definition, UnlockTestState.BLOCKED, "不可用",
                "服务返回 HTTP ${response.statusCode}。", response = response
            )
        }
    }

    private fun result(
        definition: UnlockTestDefinition,
        state: UnlockTestState,
        status: String,
        description: String,
        region: String = "",
        response: UnlockHttpResponse? = null
    ) = UnlockTestResult(definition, state, status, description, region, response?.delayMs)

    private fun traceRegion(body: String): String =
        Regex("(?m)^loc=([A-Z]{2})$").find(body)?.groupValues?.getOrNull(1).orEmpty()

    private fun UnlockHttpResponse.isReachable(): Boolean = statusCode in 200..399

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it, ignoreCase = true) }

    private fun regionLabel(code: String): String {
        val normalized = code.uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) } ?: return ""
        val flag = normalized.map { character ->
            String(Character.toChars(REGIONAL_INDICATOR_A + character.code - 'A'.code))
        }.joinToString("")
        return "$flag $normalized"
    }

    private const val REGIONAL_INDICATOR_A = 0x1F1E6
}
