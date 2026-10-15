package io.github.HanJason233.chimera.data.model

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

    @SerializedName("startAt")
    val startAt: String = "",

    @SerializedName("endAt")
    val endAt: String = ""
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

    @SerializedName("telegramUrl")
    val telegramUrl: String = "",

    @SerializedName("registrationEnabled")
    val registrationEnabled: Boolean = true,

    @SerializedName("requireAdminRegistrationCode")
    val requireAdminRegistrationCode: Boolean = false,

)

data class AccountRegisterRequest(
    @SerializedName("deviceId")
    val deviceId: String,

    @SerializedName("appVersion")
    val appVersion: String,

    @SerializedName("brand")
    val brand: String,

    @SerializedName("model")
    val model: String,

    @SerializedName("androidVersion")
    val androidVersion: String,

    @SerializedName("androidSdk")
    val androidSdk: Int
)

data class AccountRegisterResponse(
    @SerializedName("ok")
    val ok: Boolean = false,

    @SerializedName("account")
    val account: AccountInfo? = null,

    @SerializedName("error")
    val error: String? = null
)

data class AccountBindingRequest(
    @SerializedName("deviceId")
    val deviceId: String,

    @SerializedName("uid")
    val uid: String,

    @SerializedName("email")
    val email: String,

    @SerializedName("password")
    val password: String,

    @SerializedName("registrationCode")
    val registrationCode: String = ""
)

data class AccountLoginRequest(
    @SerializedName("deviceId")
    val deviceId: String,

    @SerializedName("email")
    val email: String,

    @SerializedName("password")
    val password: String,

    @SerializedName("appVersion")
    val appVersion: String,

    @SerializedName("brand")
    val brand: String,

    @SerializedName("model")
    val model: String,

    @SerializedName("androidVersion")
    val androidVersion: String,

    @SerializedName("androidSdk")
    val androidSdk: Int
)

data class AccountVipActivationRequest(
    @SerializedName("deviceId")
    val deviceId: String,

    @SerializedName("code")
    val code: String
)

data class AccountDeleteRequest(
    @SerializedName("deviceId")
    val deviceId: String,

    @SerializedName("uid")
    val uid: String,

    @SerializedName("password")
    val password: String = ""
)

data class AccountActionResponse(
    @SerializedName("ok")
    val ok: Boolean = false,

    @SerializedName("error")
    val error: String? = null
)

data class AccountInfo(
    @SerializedName("uid")
    val uid: String,

    @SerializedName("nickname")
    val nickname: String = "用户",

    @SerializedName("accountType")
    val accountType: String = "guest",

    @SerializedName("email")
    val email: String = "",

    @SerializedName("brand")
    val brand: String = "",

    @SerializedName("model")
    val model: String = "",

    @SerializedName("androidVersion")
    val androidVersion: String = "",

    @SerializedName("androidSdk")
    val androidSdk: Int = 0,

    @SerializedName("appVersion")
    val appVersion: String = "",

    @SerializedName("deviceBlocked")
    val deviceBlocked: Boolean = false,

    @SerializedName("vipActive")
    val vipActive: Boolean = false,

    @SerializedName("vipExpiresAt")
    val vipExpiresAt: String? = null,

    @SerializedName("traffic")
    val traffic: AccountTrafficInfo? = null
)

data class AccountTrafficInfo(
    @SerializedName("today")
    val today: AccountTrafficPeriod = AccountTrafficPeriod(),

    @SerializedName("month")
    val month: AccountTrafficPeriod = AccountTrafficPeriod(),

    @SerializedName("total")
    val total: AccountTrafficPeriod = AccountTrafficPeriod(),

    @SerializedName("lastReportAt")
    val lastReportAt: String? = null
)

data class AccountTrafficPeriod(
    @SerializedName("uploadBytes")
    val uploadBytes: Long = 0L,

    @SerializedName("downloadBytes")
    val downloadBytes: Long = 0L,

    @SerializedName("totalBytes")
    val totalBytes: Long = 0L
)

data class AccountProfileUpdateRequest(
    @SerializedName("deviceId")
    val deviceId: String,

    @SerializedName("nickname")
    val nickname: String
)

data class TrafficReportRequest(
    @SerializedName("deviceId")
    val deviceId: String,

    @SerializedName("sessionId")
    val sessionId: String,

    @SerializedName("subscriptionGroupName")
    val subscriptionGroupName: String,

    @SerializedName("startedAt")
    val startedAt: Long,

    @SerializedName("endedAt")
    val endedAt: Long,

    @SerializedName("uploadBytes")
    val uploadBytes: Long,

    @SerializedName("downloadBytes")
    val downloadBytes: Long
)

data class TrafficReportResponse(
    @SerializedName("ok")
    val ok: Boolean = false,

    @SerializedName("error")
    val error: String? = null
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
