package io.github.HanJason233.chimera.network

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
    val delayMs: Int? = null
)

internal object UnlockTestCatalog {
    val definitions = listOf(
        UnlockTestDefinition("google_cn", "Google 送中检测", UnlockTestCategory.NETWORK, "检测 Google 请求是否被重定向到 google.cn。"),
        UnlockTestDefinition("chatgpt", "ChatGPT", UnlockTestCategory.AI, "检测网页端以及官方 App/API 备用链路。"),
        UnlockTestDefinition("claude", "Claude", UnlockTestCategory.AI, "检测 Claude 登录页的地区限制与风控。"),
        UnlockTestDefinition("gemini", "Google Gemini", UnlockTestCategory.AI, "检测 Gemini 网页端的地区开放状态。"),
        UnlockTestDefinition("netflix", "Netflix", UnlockTestCategory.STREAMING, "区分完整版权库、仅自制内容和不可用。"),
        UnlockTestDefinition("disney", "Disney+", UnlockTestCategory.STREAMING, "检测 Disney+ 的地区访问限制。"),
        UnlockTestDefinition("max", "Max (HBO)", UnlockTestCategory.STREAMING, "检测 Max 的地区访问限制。"),
        UnlockTestDefinition("prime", "Prime Video", UnlockTestCategory.STREAMING, "检测 Prime Video 的地区访问限制。"),
        UnlockTestDefinition("youtube", "YouTube Premium", UnlockTestCategory.STREAMING, "检测 Premium 是否开放并识别区域。"),
        UnlockTestDefinition("tiktok", "TikTok", UnlockTestCategory.STREAMING, "检测国际版内容访问与人机风控。"),
        UnlockTestDefinition("spotify", "Spotify", UnlockTestCategory.OTHER, "检测 Spotify 的地区访问限制。"),
        UnlockTestDefinition("wikipedia", "Wikipedia", UnlockTestCategory.OTHER, "检测访问以及匿名编辑限制。")
    )

    /** 测速方案第五步可选的全部服务检测项目。 */
    val schemeDefinitions = definitions
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

    fun claude(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult =
        classifyRegionalPage(
            definition = definition,
            response = response,
            blockedMarkers = arrayOf(
                "App unavailable in your region", "not available in your country",
                "not available in your region", "claude.ai/unavailable", "unsupported_location"
            ),
            successMarkers = arrayOf("Continue with Google", "email", "Claude", "login"),
            successDescription = "Claude 登录入口可访问，未发现地区限制。",
            blockedDescription = "当前出口地区未开放 Claude，或已触发平台限制。"
        )

    fun gemini(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult =
        classifyRegionalPage(
            definition = definition,
            response = response,
            blockedMarkers = arrayOf(
                "not supported in your country", "unavailable in your territory",
                "isn't currently supported in your country", "not available in your country"
            ),
            successMarkers = arrayOf("Gemini", "Google AI", "Sign in"),
            successDescription = "Gemini 网页端可访问，未发现地区未开放提示。",
            blockedDescription = "Google Gemini 暂未对该地区或当前出口开放。"
        )

    fun netflix(
        definition: UnlockTestDefinition,
        licensed: UnlockHttpResponse,
        original: UnlockHttpResponse?
    ): UnlockTestResult {
        val licensedAvailable = licensed.isReachable() &&
            !licensed.body.contains("page-404", ignoreCase = true) &&
            licensed.body.containsAny("title/81280792", "watch", "Breaking Bad")
        val region = Regex("geolocation_country.*?([A-Za-z]{2})", RegexOption.IGNORE_CASE)
            .find(licensed.body)?.groupValues?.getOrNull(1)?.uppercase().orEmpty()
        if (licensedAvailable) {
            val label = regionLabel(region)
            return result(
                definition, UnlockTestState.UNLOCKED,
                if (label.isBlank()) "完整解锁" else "完整解锁 $label",
                "可访问非自制版权内容与 Netflix 原创内容。", region, licensed
            )
        }
        val originalAvailable = original?.let {
            it.isReachable() && it.body.containsAny("title/80018499", "watch", "House of Cards")
        } == true
        return if (originalAvailable) {
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

    fun disney(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult =
        classifyRegionalPage(
            definition, response,
            arrayOf("not available in your region", "is not available in your area", "disneyplus.com/unavailable", "restricted"),
            arrayOf("Disney+", "Disney Plus", "DisneyPlus"),
            "Disney+ 页面可访问，未发现地区限制。",
            "当前地区不在 Disney+ 服务范围内或已被限制。"
        )

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
        val region = listOf(
            Regex("\"countryCode\"\\s*:\\s*\"([A-Za-z]{2})\""),
            Regex("\"INNERTUBE_CONTEXT_GL\"\\s*:\\s*\"([A-Za-z]{2})\"")
        ).firstNotNullOfOrNull { it.find(body)?.groupValues?.getOrNull(1) }?.uppercase().orEmpty()
        val unavailable = body.containsAny("not available in your country", "Premium is not available")
        return when {
            response.isReachable() && !unavailable && region.isNotBlank() -> result(
                definition, UnlockTestState.UNLOCKED, "支持 ${regionLabel(region)}",
                "YouTube Premium 已开放。", region, response
            )
            response.isReachable() && !unavailable && body.contains("Premium", ignoreCase = true) -> result(
                definition, UnlockTestState.UNLOCKED, "支持",
                "YouTube Premium 页面可访问。", response = response
            )
            else -> result(
                definition, UnlockTestState.BLOCKED, "未解锁",
                "该地区或当前出口不支持 YouTube Premium。", response = response
            )
        }
    }

    fun tiktok(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult {
        val region = Regex("\"region\"\\s*:\\s*\"([A-Za-z]{2})\"")
            .find(response.body)?.groupValues?.getOrNull(1)?.uppercase().orEmpty()
        val challenged = response.body.containsAny("tiktok-verify-page", "verify-center", "captcha")
        return when {
            region == "HK" -> result(
                definition, UnlockTestState.BLOCKED, "不支持 (HK)",
                "TikTok 官方已停止在中国香港提供服务。", region, response
            )
            response.isReachable() && !challenged -> result(
                definition, UnlockTestState.UNLOCKED,
                if (region.isBlank()) "支持" else "支持 ${regionLabel(region)}",
                "TikTok 国际版页面可正常访问。", region, response
            )
            else -> result(
                definition, UnlockTestState.BLOCKED, "未解锁",
                "无法正常访问 TikTok，或触发人机风控。", region, response
            )
        }
    }

    fun spotify(definition: UnlockTestDefinition, response: UnlockHttpResponse): UnlockTestResult =
        classifyRegionalPage(
            definition, response,
            arrayOf("not available in your country"),
            arrayOf("Spotify", "spotify.com"),
            "Spotify 页面可访问，未发现地区限制。",
            "当前出口所在地区暂未开放 Spotify。"
        )

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
