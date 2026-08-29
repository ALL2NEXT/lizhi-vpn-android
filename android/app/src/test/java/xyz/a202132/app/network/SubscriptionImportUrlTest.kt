package xyz.a202132.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionImportUrlTest {
    @Test
    fun acceptsHttpAndHttpsSubscriptionUrls() {
        assertEquals(
            "https://example.com/sub?token=abc",
            SubscriptionImportUrl.normalize("  https://example.com/sub?token=abc  ")
        )
        assertEquals(
            "http://127.0.0.1:8080/sub",
            SubscriptionImportUrl.normalize("http://127.0.0.1:8080/sub")
        )
    }

    @Test
    fun rejectsNodeLinksAndInvalidValues() {
        assertNull(SubscriptionImportUrl.normalize("vless://node@example.com:443"))
        assertNull(SubscriptionImportUrl.normalize("ftp://example.com/sub"))
        assertNull(SubscriptionImportUrl.normalize("http://example.com/sub"))
        assertNull(SubscriptionImportUrl.normalize("not a url"))
        assertNull(SubscriptionImportUrl.normalize(" "))
    }
}
