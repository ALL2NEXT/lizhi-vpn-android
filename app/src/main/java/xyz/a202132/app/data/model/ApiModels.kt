package xyz.a202132.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * 版本更新响应
 */
data class UpdateInfo(
    @SerializedName("version")
    val version: String,
    
    @SerializedName("versionCode")
    val versionCode: Int,
    
    @SerializedName("downloadUrl")
    val downloadUrl: String,
    
    @SerializedName("changelog") val changelog: String,
    @SerializedName("is_force") val isForce: Int = 0
)

/**
 * 通知公告响应
 */
data class NoticeInfo(
    @SerializedName("hasNotice")
    val hasNotice: Boolean,
    
    @SerializedName("title")
    val title: String = "",
    
    @SerializedName("content")
    val content: String = "",
    
    @SerializedName("noticeId")
    val noticeId: String = "",
    
    @SerializedName("showOnce")
    val showOnce: Boolean = true,
    
    @SerializedName("scheduledNodeUpdate")
    val scheduledNodeUpdate: ScheduledNodeUpdateInfo? = null,

    // 兼容旧 notice 配置；新配置推荐放到 scheduledNodeUpdate.nodeAutoReconnect。
    @SerializedName("nodeAutoReconnect")
    val nodeAutoReconnect: Boolean? = null
)

/** App-facing settings delivered by the unified Worker backend. */
data class BackendAppSettings(
    @SerializedName("websiteUrl")
    val websiteUrl: String = "",

    @SerializedName("feedbackEmail")
    val feedbackEmail: String = "",

    @SerializedName("feedbackUrl")
    val feedbackUrl: String = "",

    @SerializedName("githubUrl")
    val githubUrl: String = "",

    @SerializedName("nodeRequestTimeoutMs")
    val nodeRequestTimeoutMs: Long = 25000L
)

/**
 * 服务端下发的定时节点更新配置。
 * 字段为空时不覆盖本地设置，整个对象存在时优先级高于本地定时更新配置。
 */
data class ScheduledNodeUpdateInfo(
    @SerializedName("enabled")
    val enabled: Boolean? = null,

    @SerializedName("hours")
    val hours: Int? = null,

    @SerializedName("minutes")
    val minutes: Int? = null,

    @SerializedName("nodeAutoReconnect")
    val nodeAutoReconnect: Boolean? = null,

    @SerializedName("toastEnabled")
    val toastEnabled: Boolean? = null
)

/**
 * VPN连接状态
 */
enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

/**
 * 代理模式
 */
enum class ProxyMode {
    GLOBAL,     // 全局代理
    SMART       // 智能分流（国内直连，国外代理）
}
