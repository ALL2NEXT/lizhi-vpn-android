package io.github.HanJason233.chimera.network.nodetest.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockTestClassifierTest {
    @Test
    fun `Google 204 判定为未送中`() {
        val result = UnlockTestClassifier.googleChina(
            definition("google_cn"),
            UnlockHttpResponse(statusCode = 204)
        )

        assertEquals(UnlockTestState.UNLOCKED, result.state)
        assertTrue(result.statusText.contains("未送中"))
    }

    @Test
    fun `Google 重定向到 google cn 判定为送中`() {
        val result = UnlockTestClassifier.googleChina(
            definition("google_cn"),
            UnlockHttpResponse(
                statusCode = 302,
                location = "https://www.google.cn/?gws_rd=ssl"
            )
        )

        assertEquals(UnlockTestState.BLOCKED, result.state)
        assertTrue(result.statusText.contains("已送中"))
    }

    @Test
    fun `ChatGPT 网页风控但 App 状态接口可用判定为部分可用`() {
        val result = UnlockTestClassifier.chatGpt(
            definition("chatgpt"),
            UnlockHttpResponse(403, "Just a moment... Cloudflare"),
            UnlockHttpResponse(200, "{\"status\":\"normal\"}")
        )

        assertEquals(UnlockTestState.PARTIAL, result.state)
        assertTrue(result.statusText.contains("App/API"))
    }

    @Test
    fun `Gemini 地区限制页面判定为未解锁`() {
        val result = UnlockTestClassifier.gemini(
            definition("gemini"),
            UnlockHttpResponse(200, "Gemini is not supported in your country")
        )

        assertEquals(UnlockTestState.BLOCKED, result.state)
    }

    @Test
    fun `Netflix 仅原创内容可用判定为部分解锁`() {
        val result = UnlockTestClassifier.netflix(
            definition("netflix"),
            UnlockHttpResponse(404, "page-404"),
            UnlockHttpResponse(200, "Watch House of Cards title/80018499")
        )

        assertEquals(UnlockTestState.PARTIAL, result.state)
        assertEquals("仅自制剧", result.statusText)
    }

    @Test
    fun `YouTube Premium 提取区域并判定可用`() {
        val result = UnlockTestClassifier.youtube(
            definition("youtube"),
            UnlockHttpResponse(200, "{\"countryCode\":\"US\",\"product\":\"Premium\"}")
        )

        assertEquals(UnlockTestState.UNLOCKED, result.state)
        assertEquals("US", result.region)
        assertTrue(result.statusText.contains("US"))
    }

    private fun definition(id: String): UnlockTestDefinition =
        UnlockTestCatalog.definitions.first { it.id == id }
}
