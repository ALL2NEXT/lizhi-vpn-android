package io.github.HanJason233.chimera.network.nodetest.unlock

import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import io.github.HanJason233.chimera.network.nodetest.common.Socks5HttpProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.net.URLEncoder
import java.security.SecureRandom

/** 通过当前 VPN 会话的内部 SOCKS 入口执行服务可用性探测。 */
internal class UnlockTestRunner(private val endpoint: LocalSocksEndpoint) : Closeable {

    suspend fun run(definition: UnlockTestDefinition): UnlockTestResult = withContext(Dispatchers.IO) {
        when (definition.id) {
            "google_cn" -> UnlockTestClassifier.googleChina(
                definition,
                request("https://www.google.com/generate_204", followRedirects = false)
            )
            "chatgpt" -> testOpenAi(definition)
            "copilot" -> UnlockTestClassifier.copilot(definition, request(COPILOT_URL))
            "claude" -> testClaude(definition)
            "gemini" -> UnlockTestClassifier.gemini(definition, request(GEMINI_URL))
            "llama" -> UnlockTestClassifier.llama(definition, request(LLAMA_API_URL, accept = "application/json"))
            "perplexity" -> testPerplexity(definition)
            "sora" -> testSora(definition)
            "grok" -> UnlockTestClassifier.grok(definition, request(GROK_URL))
            "netflix" -> UnlockTestClassifier.netflix(
                definition,
                request(NETFLIX_NON_ORIGINAL_URL),
                request(NETFLIX_ORIGINAL_URL)
            )
            "disney" -> UnlockTestClassifier.disney(
                definition,
                request(DISNEY_PLUS_URL, followRedirects = false)
            )
            "bilibili" -> testBilibili(definition)
            "dazn" -> UnlockTestClassifier.dazn(
                definition,
                request(
                    DAZN_STARTUP_URL,
                    method = "POST",
                    requestBody = DAZN_STARTUP_BODY,
                    headers = mapOf("Content-Type" to "application/json"),
                    accept = "application/json"
                )
            )
            "abema" -> UnlockTestClassifier.abema(definition, request(ABEMA_URL))
            "bahamut" -> testBahamut(definition)
            "steam" -> UnlockTestClassifier.steam(definition, request(STEAM_APP_URL))
            "max" -> UnlockTestClassifier.max(definition, request("https://auth.max.com/"))
            "prime" -> UnlockTestClassifier.prime(definition, request("https://www.primevideo.com/"))
            "youtube" -> UnlockTestClassifier.youtube(definition, request(YOUTUBE_PREMIUM_URL))
            "tiktok" -> UnlockTestClassifier.tiktok(definition, request(TIKTOK_SHARE_URL))
            "spotify" -> UnlockTestClassifier.spotify(definition, request(SPOTIFY_SIGNUP_URL))
            "wikipedia" -> UnlockTestClassifier.wikipedia(
                definition,
                request("https://en.wikipedia.org/w/api.php?action=query&meta=userinfo&uiprop=blockinfo&format=json")
            )
            else -> error("未知检测项：${definition.id}")
        }
    }

    private fun testOpenAi(definition: UnlockTestDefinition): UnlockTestResult {
        val trace = request(OPENAI_TRACE_URL)
        val compliance = request(
            OPENAI_COMPLIANCE_URL,
            accept = "application/json",
            headers = mapOf(
                "Authorization" to "Bearer null",
                "Content-Type" to "application/json",
                "Origin" to "https://platform.openai.com",
                "Referer" to "https://platform.openai.com/"
            )
        )
        val ios = request(OPENAI_IOS_URL)
        return UnlockTestClassifier.openAi(definition, trace, compliance, ios)
    }

    private fun testClaude(definition: UnlockTestDefinition): UnlockTestResult {
        val page = request(CLAUDE_URL)
        val trace = requestOrNull(CLAUDE_TRACE_URL)
        return UnlockTestClassifier.claude(definition, page, trace)
    }

    private fun testPerplexity(definition: UnlockTestDefinition): UnlockTestResult {
        val api = request(
            PERPLEXITY_API_URL,
            method = "POST",
            requestBody = "{}",
            headers = mapOf("Content-Type" to "application/json"),
            accept = "application/json"
        )
        val trace = requestOrNull(PERPLEXITY_TRACE_URL)
        return UnlockTestClassifier.perplexity(definition, api, trace)
    }

    private fun testSora(definition: UnlockTestDefinition): UnlockTestResult {
        val api = request(SORA_API_URL, accept = "application/json")
        val trace = requestOrNull(OPENAI_TRACE_URL)
        return UnlockTestClassifier.sora(definition, api, trace)
    }

    private fun testBilibili(definition: UnlockTestDefinition): UnlockTestResult {
        val url = "$BILIBILI_PLAY_URL&session=${randomHex16()}"
        return UnlockTestClassifier.bilibili(definition, request(url, accept = "application/json"))
    }

    private fun testBahamut(definition: UnlockTestDefinition): UnlockTestResult {
        val device = request(BAHAMUT_DEVICE_URL, accept = "application/json")
        val deviceId = runCatching { JSONObject(device.body).optString("deviceid") }.getOrDefault("")
        val deviceCookie = cookieHeader(device.setCookieHeaders)
        val token = deviceId.takeIf(String::isNotBlank)?.let {
            val encodedDevice = URLEncoder.encode(it, Charsets.UTF_8.name())
            request(
                "$BAHAMUT_TOKEN_URL&device=$encodedDevice",
                accept = "application/json",
                headers = deviceCookie?.let { cookie -> mapOf("Cookie" to cookie) }.orEmpty()
            )
        }
        val homeCookie = cookieHeader(device.setCookieHeaders, token?.setCookieHeaders.orEmpty())
        val home = requestOrNull(
            BAHAMUT_HOME_URL,
            headers = homeCookie?.let { mapOf("Cookie" to it) }.orEmpty()
        )
        return UnlockTestClassifier.bahamut(definition, device, token, home)
    }

    private fun requestOrNull(
        url: String,
        followRedirects: Boolean = true,
        headers: Map<String, String> = emptyMap()
    ): UnlockHttpResponse? = try {
        request(url, followRedirects = followRedirects, headers = headers)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun request(
        url: String,
        followRedirects: Boolean = true,
        userAgent: String = BROWSER_USER_AGENT,
        accept: String = BROWSER_ACCEPT,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        requestBody: String? = null
    ): UnlockHttpResponse {
        val requestHeaders = linkedMapOf(
            "User-Agent" to userAgent,
            "Accept" to accept,
            "Accept-Language" to "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7",
            "Cache-Control" to "no-cache"
        ).apply { putAll(headers) }
        val response = Socks5HttpProbe.fetch(
            endpoint = endpoint,
            targetUrl = url,
            timeoutMs = TIMEOUT_MILLISECONDS,
            headers = requestHeaders,
            followRedirects = followRedirects,
            maxBodyBytes = MAX_BODY_BYTES,
            method = method,
            requestBody = requestBody?.toByteArray(Charsets.UTF_8)
        )
        return UnlockHttpResponse(
            statusCode = response.statusCode,
            body = response.body,
            location = response.location,
            delayMs = response.delayMs,
            finalUrl = response.finalUrl,
            setCookieHeaders = response.setCookieHeaders
        )
    }

    private fun cookieHeader(vararg cookieGroups: List<String>): String? {
        val cookies = linkedMapOf<String, String>()
        cookieGroups.forEach { group ->
            group.forEach { header ->
                val pair = header.substringBefore(';').trim()
                val separator = pair.indexOf('=')
                if (separator > 0) cookies[pair.substring(0, separator).trim()] = pair
            }
        }
        return cookies.values.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun randomHex16(): String = ByteArray(16).also(secureRandom::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    override fun close() = Unit

    private companion object {
        const val TIMEOUT_MILLISECONDS = 15_000L
        const val MAX_BODY_BYTES = 1 * 1024 * 1024
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        const val BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7"
        const val OPENAI_TRACE_URL = "https://chat.openai.com/cdn-cgi/trace"
        const val OPENAI_COMPLIANCE_URL = "https://api.openai.com/compliance/cookie_requirements"
        const val OPENAI_IOS_URL = "https://ios.chat.openai.com/"
        const val COPILOT_URL = "https://copilot.microsoft.com/"
        const val GEMINI_URL = "https://gemini.google.com/app"
        const val LLAMA_API_URL = "https://api.llama.com/v1/models"
        const val CLAUDE_URL = "https://claude.ai/"
        const val CLAUDE_TRACE_URL = "https://claude.ai/cdn-cgi/trace"
        const val PERPLEXITY_API_URL = "https://api.perplexity.ai/chat/completions"
        const val PERPLEXITY_TRACE_URL = "https://www.perplexity.ai/cdn-cgi/trace"
        const val SORA_API_URL = "https://api.openai.com/v1/videos"
        const val GROK_URL = "https://grok.x.ai/"
        const val NETFLIX_NON_ORIGINAL_URL = "https://www.netflix.com/title/81280792"
        const val NETFLIX_ORIGINAL_URL = "https://www.netflix.com/title/80057281"
        const val DISNEY_PLUS_URL = "https://www.disneyplus.com/"
        const val YOUTUBE_PREMIUM_URL = "https://www.youtube.com/premium"
        const val TIKTOK_SHARE_URL = "https://www.tiktok.com/node/share/video/7049860927877885189"
        const val BILIBILI_PLAY_URL =
            "https://api.bilibili.com/pgc/player/web/playurl?avid=18281381&cid=29892777&qn=0&type=&otype=json&ep_id=183799&fourk=1&fnver=0&fnval=16&module=bangumi"
        const val DAZN_STARTUP_URL = "https://startup.core.indazn.com/misl/v5/Startup"
        const val DAZN_STARTUP_BODY = "{\"LandingPageKey\":\"generic\",\"Languages\":\"en\",\"Platform\":\"web\",\"Version\":\"2\"}"
        const val ABEMA_URL = "https://abema.tv/"
        const val BAHAMUT_DEVICE_URL = "https://ani.gamer.com.tw/ajax/getdeviceid.php"
        const val BAHAMUT_TOKEN_URL = "https://ani.gamer.com.tw/ajax/token.php?adID=89422&sn=37783"
        const val BAHAMUT_HOME_URL = "https://ani.gamer.com.tw/"
        const val STEAM_APP_URL = "https://store.steampowered.com/app/761830"
        const val SPOTIFY_SIGNUP_URL = "https://www.spotify.com/signup/"
        val secureRandom = SecureRandom()
    }
}
