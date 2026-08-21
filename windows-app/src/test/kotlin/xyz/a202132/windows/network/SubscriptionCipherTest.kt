package xyz.a202132.windows.network

import kotlin.test.Test
import kotlin.test.assertEquals

class SubscriptionCipherTest {
    @Test
    fun decodesDocumentedCompatibilityVector() {
        assertEquals("abc", SubscriptionCipher.decode("Rb9u", "1.2.3.4"))
    }

    @Test
    fun ipv4AndMappedIpv6UseTheLastIpv4Octet() {
        assertEquals(4, SubscriptionCipher.shiftFromClientIp("1.2.3.68"))
        assertEquals(4, SubscriptionCipher.shiftFromClientIp("::ffff:1.2.3.4"))
    }

    @Test
    fun ipv6UsesLastHexGroup() {
        assertEquals(63, SubscriptionCipher.shiftFromClientIp("2001:db8::3f"))
        assertEquals(0, SubscriptionCipher.shiftFromClientIp("2001:db8::"))
    }
}
