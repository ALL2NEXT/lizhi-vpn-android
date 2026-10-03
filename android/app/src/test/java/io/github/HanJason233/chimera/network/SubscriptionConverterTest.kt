package io.github.HanJason233.chimera.network

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionConverterTest {
    @Test
    fun convertsPlainUrisWithoutChangingTheirOrder() {
        assertEquals(
            listOf(
                "vless://one@example.com:443",
                "trojan://password@example.net:443"
            ),
            SubscriptionConverter.toStandardUris(
                "vless://one@example.com:443\n trojan://password@example.net:443"
            )
        )
    }

    @Test
    fun decodesBase64SubscriptionContent() {
        val content = "vless://one@example.com:443\nvmess://ignored"
        val encoded = Base64.getEncoder().encodeToString(content.toByteArray())

        assertEquals(
            listOf("vless://one@example.com:443", "vmess://ignored"),
            SubscriptionConverter.toStandardUris(encoded)
        )
    }

    @Test
    fun convertsClashYamlToUris() {
        val uris = SubscriptionConverter.toStandardUris(
            """
            proxies:
              - name: node
                type: vless
                server: example.com
                port: 443
                uuid: 11111111-1111-1111-1111-111111111111
            """.trimIndent()
        )

        assertEquals(1, uris.size)
        assertTrue(uris.single().startsWith("vless://"))
    }
}
