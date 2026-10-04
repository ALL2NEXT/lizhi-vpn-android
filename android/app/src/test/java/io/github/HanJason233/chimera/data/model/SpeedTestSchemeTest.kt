package io.github.HanJason233.chimera.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestSchemeTest {
    @Test
    fun `default scheme uses current group tcping and 204 defaults`() {
        val scheme = SpeedTestScheme.default()

        assertEquals(SpeedTestScheme.DEFAULT_ID, scheme.id)
        assertTrue(scheme.builtIn)
        assertEquals(SpeedTestScope.CURRENT_GROUP, scheme.scope)
        assertTrue(scheme.tcpingEnabled)
        assertEquals(SpeedTestSelectionMode.PERCENT, scheme.tcpingSelectionMode)
        assertEquals(100, scheme.tcpingSelectionValue)
        assertEquals(1_000L, scheme.tcpingTimeoutMs)
        assertEquals(16, scheme.tcpingConcurrency)
        assertTrue(scheme.urlTestEnabled)
        assertEquals(SpeedTestSelectionMode.COUNT, scheme.urlTestSelectionMode)
        assertEquals(10, scheme.urlTestSelectionValue)
        assertEquals(4_000L, scheme.urlTestWarmupTimeoutMs)
        assertEquals(1_000L, scheme.urlTestMeasurementTimeoutMs)
        assertEquals(10, scheme.urlTestConcurrency)
        assertEquals(UrlTestTargets.DEFAULT_URL, scheme.urlTestUrl)
        assertEquals(UrlTestTargets.DEFAULT_BACKUP_URL, scheme.urlTestBackupUrl)
        assertFalse(scheme.bandwidthEnabled)
        assertTrue(scheme.linkTypeTestEnabled)
    }
}
