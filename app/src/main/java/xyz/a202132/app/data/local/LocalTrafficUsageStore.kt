package xyz.a202132.app.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Stores traffic locally until the backend acknowledges it.
 * Records are aggregated by the Beijing calendar day so that a node switch
 * does not create a reporting gap or reset the daily total.
 */
data class PendingDailyTrafficRecord(
    val id: String = UUID.randomUUID().toString(),
    val day: String,
    var endedAt: Long,
    var uploadBytes: Long,
    var downloadBytes: Long,
)

object LocalTrafficUsageStore {
    private const val TAG = "LocalTrafficUsageStore"
    private const val PREFERENCES_NAME = "traffic_usage_local"
    private const val RECORDS_KEY = "pending_daily_records"
    private const val BEIJING_TIME_ZONE = "Asia/Shanghai"
    private const val DAY_PATTERN = "yyyy-MM-dd"

    private val gson = Gson()
    private val lock = Any()
    private val recordType = object : TypeToken<MutableMap<String, PendingDailyTrafficRecord>>() {}.type

    /** Adds only the newly observed bytes to today's pending record. */
    fun addDelta(
        context: Context,
        timestamp: Long,
        uploadBytes: Long,
        downloadBytes: Long,
    ): Boolean {
        val uploadDelta = uploadBytes.coerceAtLeast(0L)
        val downloadDelta = downloadBytes.coerceAtLeast(0L)
        if (uploadDelta == 0L && downloadDelta == 0L) return true

        synchronized(lock) {
            val preferences = preferences(context)
            val records = read(preferences)
            val day = dayKey(timestamp)
            val record = records.values.firstOrNull { it.day == day }
                ?: PendingDailyTrafficRecord(
                    day = day,
                    endedAt = timestamp,
                    uploadBytes = 0L,
                    downloadBytes = 0L,
                ).also { records[it.id] = it }

            record.endedAt = maxOf(record.endedAt, timestamp)
            record.uploadBytes = safeAdd(record.uploadBytes, uploadDelta)
            record.downloadBytes = safeAdd(record.downloadBytes, downloadDelta)
            return write(preferences, records)
        }
    }

    fun pending(context: Context): List<PendingDailyTrafficRecord> = synchronized(lock) {
        read(preferences(context)).values
            .map { it.copy() }
            .sortedWith(compareBy<PendingDailyTrafficRecord> { it.day }.thenBy { it.id })
    }

    /**
     * Acknowledge the snapshot that was uploaded. If traffic arrived while the
     * request was in flight, only the acknowledged bytes are removed.
     */
    fun acknowledge(
        context: Context,
        recordId: String,
        uploadedUploadBytes: Long,
        uploadedDownloadBytes: Long,
    ) {
        synchronized(lock) {
            val preferences = preferences(context)
            val records = read(preferences)
            val current = records[recordId] ?: return

            val remainingUpload = (current.uploadBytes - uploadedUploadBytes.coerceAtLeast(0L))
                .coerceAtLeast(0L)
            val remainingDownload = (current.downloadBytes - uploadedDownloadBytes.coerceAtLeast(0L))
                .coerceAtLeast(0L)

            if (remainingUpload == 0L && remainingDownload == 0L) {
                records.remove(recordId)
            } else {
                // A successful upload has already claimed this session id on the
                // backend. Keep post-upload traffic under a new id so the
                // backend's idempotent MAX update cannot hide those bytes.
                records.remove(recordId)
                val remainder = PendingDailyTrafficRecord(
                    day = current.day,
                    endedAt = current.endedAt,
                    uploadBytes = remainingUpload,
                    downloadBytes = remainingDownload,
                )
                records[remainder.id] = remainder
            }
            write(preferences, records)
        }
    }

    fun dayStartMillis(day: String): Long {
        val formatter = dayFormatter()
        return runCatching {
            formatter.parse(day)?.time ?: System.currentTimeMillis()
        }.getOrDefault(System.currentTimeMillis())
    }

    fun dayKey(timestamp: Long): String = dayFormatter().format(Date(timestamp))

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private fun read(preferences: android.content.SharedPreferences): MutableMap<String, PendingDailyTrafficRecord> {
        val json = preferences.getString(RECORDS_KEY, null).orEmpty()
        if (json.isBlank()) return linkedMapOf()
        return runCatching {
            gson.fromJson<MutableMap<String, PendingDailyTrafficRecord>>(json, recordType)
                ?: linkedMapOf()
        }.getOrElse {
            Log.w(TAG, "Unable to read local traffic records; preserving a clean store", it)
            linkedMapOf()
        }
    }

    private fun write(
        preferences: android.content.SharedPreferences,
        records: Map<String, PendingDailyTrafficRecord>,
    ): Boolean {
        val committed = preferences.edit()
            .putString(RECORDS_KEY, gson.toJson(records))
            .commit()
        if (!committed) {
            Log.w(TAG, "Unable to persist local traffic records")
        }
        return committed
    }

    private fun safeAdd(current: Long, delta: Long): Long {
        if (delta <= 0L) return current.coerceAtLeast(0L)
        if (current >= Long.MAX_VALUE - delta) return Long.MAX_VALUE
        return current + delta
    }

    private fun dayFormatter(): SimpleDateFormat = SimpleDateFormat(
        DAY_PATTERN,
        Locale.US,
    ).apply {
        timeZone = TimeZone.getTimeZone(BEIJING_TIME_ZONE)
        isLenient = false
    }
}
