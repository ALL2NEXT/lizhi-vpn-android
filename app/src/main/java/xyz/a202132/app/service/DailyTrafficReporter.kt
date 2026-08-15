package xyz.a202132.app.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.a202132.app.data.local.LocalTrafficUsageStore
import xyz.a202132.app.network.AccountManager

object DailyTrafficReporter {
    private const val TAG = "DailyTrafficReporter"
    private const val REPORT_TIMEOUT_MS = 8_000L
    private val reportMutex = Mutex()

    suspend fun reportPending(context: Context) = reportMutex.withLock {
        val appContext = context.applicationContext
        val records = LocalTrafficUsageStore.pending(appContext)
        if (records.isEmpty()) return

        val accountManager = AccountManager(appContext)
        records.forEach { record ->
            val result = accountManager.reportTraffic(
                sessionId = "daily-${record.id}",
                subscriptionGroupName = "",
                startedAt = LocalTrafficUsageStore.dayStartMillis(record.day),
                endedAt = record.endedAt,
                uploadBytes = record.uploadBytes,
                downloadBytes = record.downloadBytes,
                timeoutMs = REPORT_TIMEOUT_MS,
            )

            if (result.isSuccess) {
                LocalTrafficUsageStore.acknowledge(
                    context = appContext,
                    recordId = record.id,
                    uploadedUploadBytes = record.uploadBytes,
                    uploadedDownloadBytes = record.downloadBytes,
                )
                Log.i(TAG, "Daily traffic report acknowledged: day=${record.day}")
            } else {
                Log.w(TAG, "Daily traffic report retained for retry: day=${record.day}", result.exceptionOrNull())
            }
        }
    }
}
