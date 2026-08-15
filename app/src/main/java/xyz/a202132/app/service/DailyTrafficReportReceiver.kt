package xyz.a202132.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.a202132.app.AppConfig

class DailyTrafficReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        AppConfig.initialize(appContext)

        when (intent?.action) {
            DailyTrafficReportScheduler.ACTION_REPORT_DAILY_TRAFFIC -> {
                // Schedule the following day's alarm before starting network I/O.
                DailyTrafficReportScheduler.schedule(appContext)
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        DailyTrafficReporter.reportPending(appContext)
                    } catch (error: Exception) {
                        Log.e("DailyTrafficReportReceiver", "Daily traffic report failed", error)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                DailyTrafficReportScheduler.schedule(appContext)
            }
        }
    }
}
