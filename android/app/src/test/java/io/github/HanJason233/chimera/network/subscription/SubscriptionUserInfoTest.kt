package io.github.HanJason233.chimera.network.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionUserInfoTest {
    @Test
    fun parsesTrafficAndExpiryInAnyOrder() {
        val info = SubscriptionUserInfo.parse(
            "total=107374182400; download=2147483648; expire=1893456000; upload=1024"
        )!!

        assertEquals(1024L, info.uploadBytes)
        assertEquals(2147483648L, info.downloadBytes)
        assertEquals(107374182400L, info.totalBytes)
        assertEquals(1893456000000L, info.expireAtMillis)
    }

    @Test
    fun ignoresUnknownInvalidAndZeroExpiryValues() {
        val info = SubscriptionUserInfo.parse("web=ignored; upload=-1; download=0; expire=0")!!

        assertNull(info.uploadBytes)
        assertEquals(0L, info.downloadBytes)
        assertNull(info.expireAtMillis)
        assertNull(SubscriptionUserInfo.parse("web=ignored"))
    }
}
