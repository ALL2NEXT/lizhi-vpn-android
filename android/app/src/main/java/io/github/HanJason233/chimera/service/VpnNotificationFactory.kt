package io.github.HanJason233.chimera.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.MainActivity
import io.github.HanJason233.chimera.R
import io.github.HanJason233.chimera.data.model.ProxyMode

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
        nodeGroupName: String?,
        proxyMode: ProxyMode,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val targetProxyMode = proxyMode.opposite()
        val switchModeIntent = serviceAction(3, BoxVpnService.ACTION_SET_PROXY_MODE) {
            putExtra(BoxVpnService.EXTRA_PROXY_MODE, targetProxyMode.name)
        }
        val stopIntent = serviceAction(1, BoxVpnService.ACTION_STOP)
        val restartIntent = serviceAction(2, BoxVpnService.ACTION_RESTART)
        val detailLines = listOfNotNull(
            nodeGroupName?.trim()?.takeIf(String::isNotEmpty)?.let { "订阅组：$it" },
            "分流规则：${proxyMode.notificationLabel()}"
        )
        return NotificationCompat.Builder(service, AppConfig.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(nodeName)
            .setContentText(detailLines.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailLines.joinToString("\n")))
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_vpn_key,
                "切换到${targetProxyMode.notificationLabel()}",
                switchModeIntent
            )
            .addAction(R.drawable.ic_vpn_key, "断开连接", stopIntent)
            .addAction(R.drawable.ic_vpn_key, "重置连接", restartIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** VPN 内部重载完成后刷新同一个常驻通知，避免继续显示旧分流规则。 */
    fun update(nodeName: String, nodeGroupName: String?, proxyMode: ProxyMode) {
        service.getSystemService(NotificationManager::class.java)
            .notify(AppConfig.NOTIFICATION_ID, build(nodeName, nodeGroupName, proxyMode))
    }

    private fun serviceAction(
        requestCode: Int,
        action: String,
        configure: Intent.() -> Unit = {}
    ): PendingIntent =
        PendingIntent.getService(
            service,
            requestCode,
            Intent(service, BoxVpnService::class.java).apply {
                this.action = action
                configure()
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun ProxyMode.opposite(): ProxyMode = when (this) {
        ProxyMode.GLOBAL -> ProxyMode.SMART
        ProxyMode.SMART -> ProxyMode.GLOBAL
    }

    private fun ProxyMode.notificationLabel(): String = when (this) {
        ProxyMode.GLOBAL -> "全局"
        ProxyMode.SMART -> "智能"
    }
}
