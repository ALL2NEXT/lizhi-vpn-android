package xyz.a202132.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestSchemeTest {
    @Test
    fun `default scheme uses current group tcping and 204 defaults`() {
        val scheme = SpeedTestScheme.default()

        assertEquals(SpeedTestScheme.DEFAULT_ID, scheme.id)
        assertEquals(SpeedTestScope.CURRENT_GROUP, scheme.scope)
        assertTrue(scheme.tcpingEnabled)
        assertEquals(100, scheme.tcpingPercent)
        assertEquals(1_000L, scheme.tcpingTimeoutMs)
        assertEquals(16, scheme.tcpingConcurrency)
        assertTrue(scheme.urlTestEnabled)
        assertEquals(50, scheme.urlTestPercent)
        assertEquals(1_000L, scheme.urlTestTimeoutMs)
        assertEquals(10, scheme.urlTestConcurrency)
        assertEquals(UrlTestTargets.DEFAULT_URL, scheme.urlTestUrl)
        assertFalse(scheme.bandwidthEnabled)
    }
}
