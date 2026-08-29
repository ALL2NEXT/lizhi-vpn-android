package xyz.a202132.app.network

import org.junit.Assert.assertEquals
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
    fun leavesNodeUrisAndSubscriptionContentForLocalParsing() {
        assertNull(SubscriptionImportUrl.detect("vless://node@example.com:443"))
        assertNull(SubscriptionImportUrl.detect("http://user:pass@proxy.example:8080#node"))
        assertNull(SubscriptionImportUrl.detect("https://proxy.example:443#node"))
        assertNull(SubscriptionImportUrl.detect("vless://one\nvmess://two"))
    }

    @Test
    fun rejectsUnsupportedAndInvalidValues() {
        assertNull(SubscriptionImportUrl.detect("ftp://example.com/sub"))
        assertNull(SubscriptionImportUrl.detect("not a url"))
        assertNull(SubscriptionImportUrl.detect(" "))
    }
}
