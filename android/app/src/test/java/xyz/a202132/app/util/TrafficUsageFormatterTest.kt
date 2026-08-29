package xyz.a202132.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficUsageFormatterTest {
    @Test
    fun formatBytesUsesReadableBinaryUnits() {
        assertEquals("0 B", TrafficUsageFormatter.formatBytes(-1L))
        assertEquals("1023 B", TrafficUsageFormatter.formatBytes(1023L))
        assertEquals("1.00 KB", TrafficUsageFormatter.formatBytes(1024L))
        assertEquals("1.50 MB", TrafficUsageFormatter.formatBytes(1572864L))
        assertEquals("2.00 GB", TrafficUsageFormatter.formatBytes(2147483648L))
    }

    @Test
    fun formatTimestampUsesBeijingTime() {
        assertEquals("尚未上报", TrafficUsageFormatter.formatTimestamp(null))
        assertEquals(
            "2026-08-29 16:30",
            TrafficUsageFormatter.formatTimestamp("2026-08-29T08:30:00Z")
        )
        assertEquals("unknown", TrafficUsageFormatter.formatTimestamp("unknown"))
    }
}
