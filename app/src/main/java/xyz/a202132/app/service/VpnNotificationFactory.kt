package xyz.a202132.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import xyz.a202132.app.AppConfig
import xyz.a202132.app.MainActivity
import xyz.a202132.app.R

internal class VpnNotificationFactory(private val service: BoxVpnService) {
    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            AppConfig.NOTIFICATION_CHANNEL_ID,
            service.getString(R.string.vpn_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VPN服务通知"
            setShowBadge(false)
        }
        service.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(
        nodeName: String,
        uploadSpeed: Long,
        downloadSpeed: Long,
        uploadTotal: Long,
        downloadTotal: Long
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = serviceAction(1, BoxVpnService.ACTION_STOP)
        val restartIntent = serviceAction(2, BoxVpnService.ACTION_RESTART)
        val upSpeedText = ServiceManager.formatSpeed(uploadSpeed)
        val downSpeedText = ServiceManager.formatSpeed(downloadSpeed)

        return NotificationCompat.Builder(service, AppConfig.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(nodeName)
            .setContentText("上传 $upSpeedText  下载 $downSpeedText")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "上传 $upSpeedText   累计 ${ServiceManager.formatTraffic(uploadTotal)}\n" +
                        "下载 $downSpeedText   累计 ${ServiceManager.formatTraffic(downloadTotal)}"
                )
            )
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_vpn_key, "断开连接", stopIntent)
            .addAction(R.drawable.ic_vpn_key, "重置连接", restartIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun serviceAction(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            service,
            requestCode,
            Intent(service, BoxVpnService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
