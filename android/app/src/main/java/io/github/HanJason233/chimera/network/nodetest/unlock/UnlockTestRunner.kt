package io.github.HanJason233.chimera.network.nodetest.unlock

import io.github.HanJason233.chimera.network.nodetest.common.Socks5HttpProbe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.HanJason233.chimera.core.LocalSocksEndpoint
import java.io.Closeable

/** 通过当前 VPN 会话的内部 SOCKS 入口执行服务可用性探测。 */
internal class UnlockTestRunner(private val endpoint: LocalSocksEndpoint) : Closeable {

    suspend fun run(definition: UnlockTestDefinition): UnlockTestResult = withContext(Dispatchers.IO) {
        when (definition.id) {
            "google_cn" -> UnlockTestClassifier.googleChina(
                definition,
                request("https://www.google.com/generate_204", followRedirects = false)
            )
            "chatgpt" -> testChatGpt(definition)
            "claude" -> UnlockTestClassifier.claude(
                definition,
                request("https://claude.ai/login")
            )
            "gemini" -> UnlockTestClassifier.gemini(
                definition,
                request("https://gemini.google.com/")
            )
            "netflix" -> UnlockTestClassifier.netflix(
                definition,
                request("https://www.netflix.com/title/81280792"),
                request("https://www.netflix.com/title/80018499")
            )
            "disney" -> UnlockTestClassifier.disney(
                definition,
                request("https://www.disneyplus.com/")
            )
            "max" -> UnlockTestClassifier.max(
                definition,
                request("https://auth.max.com/")
            )
            "prime" -> UnlockTestClassifier.prime(
                definition,
                request("https://www.primevideo.com/")
            )
            "youtube" -> UnlockTestClassifier.youtube(
                definition,
                request("https://www.youtube.com/premium")
            )
            "tiktok" -> UnlockTestClassifier.tiktok(
                definition,
                request("https://www.tiktok.com/")
            )
            "spotify" -> UnlockTestClassifier.spotify(
                definition,
                request("https://www.spotify.com/")
            )
            "wikipedia" -> UnlockTestClassifier.wikipedia(
                definition,
                request("https://en.wikipedia.org/w/api.php?action=query&meta=userinfo&uiprop=blockinfo&format=json")
            )
            else -> error("未知检测项：${definition.id}")
        }
    }

    private fun testChatGpt(definition: UnlockTestDefinition): UnlockTestResult {
        val web = request("https://chatgpt.com/")
        val needsFallback = web.statusCode !in 200..399 ||
            web.statusCode in setOf(403, 429, 451) ||
            listOf("cf-mitigated", "Attention Required", "error code: 1020", "Just a moment")
                .any { web.body.contains(it, ignoreCase = true) }
        val mobile = if (needsFallback) {
            request(
                url = "https://ios.chat.openai.com/public-api/mobile/server_status/v1",
                userAgent = "ChatGPT/1.2024.135 (iOS 17.5.1; iPhone15,2)",
                accept = "application/json"
            )
        } else {
            null
        }
        return UnlockTestClassifier.chatGpt(definition, web, mobile)
    }

    private fun request(
        url: String,
        followRedirects: Boolean = true,
        userAgent: String = BROWSER_USER_AGENT,
        accept: String = BROWSER_ACCEPT
    ): UnlockHttpResponse {
        val response = Socks5HttpProbe.fetch(
            endpoint = endpoint,
            targetUrl = url,
            timeoutMs = TIMEOUT_MILLISECONDS,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Accept" to accept,
                "Accept-Language" to "en-US,en;q=0.9,zh-CN;q=0.8,zh;q=0.7",
                "Cache-Control" to "no-cache"
            ),
            followRedirects = followRedirects,
            maxBodyBytes = MAX_BODY_BYTES
        )
        return UnlockHttpResponse(
            statusCode = response.statusCode,
            body = response.body,
            location = response.location,
            delayMs = response.delayMs
        )
    }

    override fun close() = Unit

    private companion object {
        const val TIMEOUT_MILLISECONDS = 12_000L
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        const val BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    }
}
