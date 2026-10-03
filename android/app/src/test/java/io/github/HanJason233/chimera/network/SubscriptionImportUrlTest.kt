package io.github.HanJason233.chimera.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionImportUrlTest {
    @Test
    fun detectsHttpAndHttpsSubscriptionUrls() {
        assertEquals(
            "https://example.com/sub?token=abc",
            SubscriptionImportUrl.detect("  https://example.com/sub?token=abc  ")
        )
        assertEquals(
            "http://127.0.0.1:8080/sub",
            SubscriptionImportUrl.detect("http://127.0.0.1:8080/sub")
        )
        assertEquals(
            "https://subscriptions.example.com/",
            SubscriptionImportUrl.detect("https://subscriptions.example.com")
        )
    }

    @Test
    fun leavesNonHttpNodeUrisForLocalParsing() {
        assertNull(SubscriptionImportUrl.detect("vless://node@example.com:443"))
        assertNotNull(SubscriptionImportUrl.detect("http://user:pass@proxy.example:8080#node"))
        assertNotNull(SubscriptionImportUrl.detect("https://proxy.example:443#node"))
        assertNull(SubscriptionImportUrl.detect("vless://one\nvmess://two"))
    }

    @Test
    fun rejectsUnsupportedAndInvalidValues() {
        assertNull(SubscriptionImportUrl.detect("ftp://example.com/sub"))
        assertNull(SubscriptionImportUrl.detect("not a url"))
        assertNull(SubscriptionImportUrl.detect(" "))
    }

    @Test
    fun unifiedDetectorClassifiesRemoteUrlAndFullContent() {
        assertEquals(
            SubscriptionImportInput.RemoteSubscription("https://example.com/sub?token=abc"),
            SubscriptionInputDetector.detect("  https://example.com/sub?token=abc  ")
        )
        assertEquals(
            SubscriptionImportInput.FullContent("vless://node@example.com:443"),
            SubscriptionInputDetector.detect("vless://node@example.com:443")
        )
        assertEquals(
            SubscriptionImportInput.FullContent("proxies:\n  - name: node"),
            SubscriptionInputDetector.detect("proxies:\n  - name: node")
        )
    }

    @Test
    fun unifiedDetectorClassifiesMultipleRemoteSubscriptions() {
        assertEquals(
            SubscriptionImportInput.MultipleRemoteSubscriptions(
                listOf(
                    "https://one.example.com/sub",
                    "https://two.example.com/sub?token=abc"
                )
            ),
            SubscriptionInputDetector.detect(
                "https://one.example.com/sub\nhttps://two.example.com/sub?token=abc"
            )
        )
    }

    @Test
    fun mixedRemoteUrlsAndNodeUrisAreSplitIntoContentAndRemoteUrls() {
        assertEquals(
            SubscriptionImportInput.MixedSubscription(
                content = "vless://node@example.com:443",
                urls = listOf("https://one.example.com/sub")
            ),
            SubscriptionInputDetector.detect(
                "https://one.example.com/sub\nvless://node@example.com:443"
            )
        )
    }

    @Test
    fun mixedYamlContentKeepsItsIndentation() {
        val yaml = """
            proxies:
              - name: node
                type: vmess
                server: example.com
        """.trimIndent()

        assertEquals(
            SubscriptionImportInput.MixedSubscription(
                content = yaml,
                urls = listOf("https://example.com/sub")
            ),
            SubscriptionInputDetector.detect("$yaml\nhttps://example.com/sub")
        )
    }

    @Test
    fun detectsMarkdownRemoteLinks() {
        assertEquals(
            SubscriptionImportInput.MultipleRemoteSubscriptions(
                listOf(
                    "https://one.example.com/sub",
                    "http://two.example.com:8080/sub"
                )
            ),
            SubscriptionInputDetector.detect(
                "[one](https://one.example.com/sub)\n" +
                    "- [two](http://two.example.com:8080/sub)"
            )
        )
    }
}
