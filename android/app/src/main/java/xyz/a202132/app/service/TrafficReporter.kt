package xyz.a202132.app.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.a202132.app.data.local.LocalTrafficUsageStore
import xyz.a202132.app.data.local.PendingDailyTrafficRecord
import xyz.a202132.app.network.AccountManager

/** Uploads persisted traffic without tying the request lifecycle to the VPN service. */
object TrafficReporter {
    private const val TAG = "TrafficReporter"
    private const val REPORT_TIMEOUT_MS = 8_000L

    private val reportingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reportMutex = Mutex()

    fun reportPendingAsync(context: Context, reason: String) {
        val appContext = context.applicationContext
        reportingScope.launch {
            try {
                reportPending(appContext)
            } catch (error: Exception) {
                Log.w(TAG, "Traffic report attempt failed: reason=$reason", error)
            }
        }
    }

    suspend fun reportPending(context: Context) = reportMutex.withLock {
        val appContext = context.applicationContext
        val records = LocalTrafficUsageStore.pending(appContext)
        if (records.isEmpty()) return

        val accountManager = AccountManager(appContext)
        processPendingTrafficRecords(
            records = records,
            report = { record ->
                val result = accountManager.reportTraffic(
                    // Keep the historical prefix so records retained across an app
                    // update preserve the backend idempotency key.
                    sessionId = "daily-${record.id}",
                    subscriptionGroupName = "",
                    startedAt = LocalTrafficUsageStore.dayStartMillis(record.day),
                    endedAt = record.endedAt,
                    uploadBytes = record.uploadBytes,
                    downloadBytes = record.downloadBytes,
                    timeoutMs = REPORT_TIMEOUT_MS,
                )
                if (result.isFailure) {
                    Log.w(
                        TAG,
                        "Traffic report retained for next connection: day=${record.day}",
                        result.exceptionOrNull(),
                    )
                }
                result.isSuccess
            },
            acknowledge = { record ->
                LocalTrafficUsageStore.acknowledge(
                    context = appContext,
                    recordId = record.id,
                    uploadedUploadBytes = record.uploadBytes,
                    uploadedDownloadBytes = record.downloadBytes,
                )
                Log.i(TAG, "Traffic report acknowledged: day=${record.day}")
            },
        )
    }
}

internal suspend fun processPendingTrafficRecords(
    records: List<PendingDailyTrafficRecord>,
    report: suspend (PendingDailyTrafficRecord) -> Boolean,
    acknowledge: (PendingDailyTrafficRecord) -> Unit,
) {
    records.forEach { record ->
        if (report(record)) acknowledge(record)
    }
}
