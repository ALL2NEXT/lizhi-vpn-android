package xyz.a202132.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar
import java.util.TimeZone

object DailyTrafficReportScheduler {
    const val ACTION_REPORT_DAILY_TRAFFIC = "xyz.a202132.app.REPORT_DAILY_TRAFFIC"

    private const val REQUEST_CODE = 2300
    private const val BEIJING_TIME_ZONE = "Asia/Shanghai"

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = pendingIntent(appContext)
        val triggerAtMillis = nextTriggerAtMillis(System.currentTimeMillis())

        alarmManager.cancel(pendingIntent)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pendingIntent,
        )
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DailyTrafficReportReceiver::class.java).apply {
            action = ACTION_REPORT_DAILY_TRAFFIC
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun nextTriggerAtMillis(now: Long): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(BEIJING_TIME_ZONE)).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return calendar.timeInMillis
    }
}
