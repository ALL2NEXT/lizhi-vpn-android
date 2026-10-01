package io.github.HanJason233.chimera.util

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

object TrafficUsageFormatter {
    private val beijingZone = ZoneId.of("Asia/Shanghai")
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun formatBytes(value: Long): String {
        val bytes = max(value, 0L)
        if (bytes < 1024L) return "$bytes B"

        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var amount = bytes.toDouble()
        var unitIndex = -1
        while (amount >= 1024.0 && unitIndex < units.lastIndex) {
            amount /= 1024.0
            unitIndex += 1
        }
        val pattern = when {
            amount >= 100.0 -> "%.0f %s"
            amount >= 10.0 -> "%.1f %s"
            else -> "%.2f %s"
        }
        return String.format(Locale.US, pattern, amount, units[unitIndex])
    }

    fun formatTimestamp(value: String?): String {
        if (value.isNullOrBlank()) return "尚未上报"
        return runCatching {
            OffsetDateTime.parse(value)
                .atZoneSameInstant(beijingZone)
                .format(timestampFormatter)
        }.getOrDefault(value)
    }
}
