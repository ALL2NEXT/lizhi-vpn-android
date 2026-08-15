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

    @SerializedName("nodeRequestTimeoutMs")
    val nodeRequestTimeoutMs: Long = 25000L,

    @SerializedName("experienceThresholds")
    val experienceThresholds: List<Long> = listOf(100L, 200L, 400L, 800L, 1600L),

    @SerializedName("accountBindingExperience")
    val accountBindingExperience: Long = 50L,

    @SerializedName("telegramGroupUrl")
    val telegramGroupUrl: String = "",

    @SerializedName("telegramTaskExperience")
    val telegramTaskExperience: Long = 50L,

    @SerializedName("speedShareSmartExperience")
    val speedShareSmartExperience: Long = 5L
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
    val password: String
)

data class AccountInviteRequest(
    @SerializedName("deviceId")
    val deviceId: String,

    @SerializedName("code")
    val code: String
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
    val nickname: String = "流萤用户",

    @SerializedName("level")
    val level: Int = 1,

    @SerializedName("experience")
    val experience: Long = 0L,

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

    @SerializedName("inviteCode")
    val inviteCode: String = "",

    @SerializedName("invitedByCode")
    val invitedByCode: String = "",

    @SerializedName("inviteeCount")
    val inviteeCount: Int = 0,

    @SerializedName("deviceBlocked")
    val deviceBlocked: Boolean = false
)

data class AccountProfileUpdateRequest(
    @SerializedName("deviceId")
    val deviceId: String,

    @SerializedName("nickname")
    val nickname: String
)

data class TelegramTaskSubmitRequest(
    @SerializedName("deviceId")
    val deviceId: String,

    @SerializedName("code")
    val code: String
)

enum class SpeedShareMode {
    OFF,
    SMART
}

data class SpeedShareNodeResult(
    @SerializedName("nodeId")
    val nodeId: String,

    @SerializedName("serverHost")
    val serverHost: String = "",

    @SerializedName("subscriptionGroupName")
    val subscriptionGroupName: String = "",

    @SerializedName("tcpingLatencyMs")
    val tcpingLatencyMs: Int = 0,

    @SerializedName("urlTestLatencyMs")
    val urlTestLatencyMs: Int = 0,

    @SerializedName("downloadMbps")
    val downloadMbps: Float = 0f,

    @SerializedName("uploadMbps")
    val uploadMbps: Float = 0f,

    @SerializedName("province")
    val province: String = "",

    @SerializedName("carrier")
    val carrier: String = ""
)

data class SpeedShareRequest(
    @SerializedName("deviceId")
    val deviceId: String,

    @SerializedName("results")
    val results: List<SpeedShareNodeResult>
)

data class SpeedShareStatus(
    @SerializedName("enabled")
    val enabled: Boolean = true,

    @SerializedName("smartReportedThisHour")
    val smartReportedThisHour: Boolean = false,

    @SerializedName("smartReportedGroupCount")
    val smartReportedGroupCount: Int = 0,

    @SerializedName("smartReportedAt")
    val smartReportedAt: String = "",

    @SerializedName("smartRewardExperience")
    val smartRewardExperience: Long = 5L,

    @SerializedName("lastReportedAt")
    val lastReportedAt: String = "",

    @SerializedName("retainedResultCount")
    val retainedResultCount: Int = 0
)

data class SpeedShareResponse(
    @SerializedName("ok")
    val ok: Boolean = false,

    @SerializedName("acceptedResults")
    val acceptedResults: Int = 0,

    @SerializedName("account")
    val account: AccountInfo? = null,

    @SerializedName("experienceAwarded")
    val experienceAwarded: Long = 0L,

    @SerializedName("speedShare")
    val speedShare: SpeedShareStatus? = null,

    @SerializedName("error")
    val error: String? = null
)

data class SpeedShareResultsResponse(
    @SerializedName("ok")
    val ok: Boolean = false,

    @SerializedName("clientProvince")
    val clientProvince: String = "",

    @SerializedName("clientCarrier")
    val clientCarrier: String = "",

    @SerializedName("results")
    val results: List<SpeedShareNodeResult> = emptyList(),

    @SerializedName("error")
    val error: String? = null
)

data class TelegramTaskStatus(
    @SerializedName("enabled")
    val enabled: Boolean = false,

    @SerializedName("groupUrl")
    val groupUrl: String = "",

    @SerializedName("rewardExperience")
    val rewardExperience: Long = 50L,

    @SerializedName("status")
    val status: String = "not_claimed",

    @SerializedName("claimedAt")
    val claimedAt: String = "",

    @SerializedName("experienceAwarded")
    val experienceAwarded: Long = 0L
)

data class TelegramTaskResponse(
    @SerializedName("ok")
    val ok: Boolean = false,

    @SerializedName("account")
    val account: AccountInfo? = null,

    @SerializedName("speedShare")
    val speedShare: SpeedShareStatus? = null,

    @SerializedName("telegram")
    val telegram: TelegramTaskStatus? = null,

    @SerializedName("error")
    val error: String? = null
)

data class TaskCenterStatus(
    val speedShare: SpeedShareStatus = SpeedShareStatus(),
    val telegram: TelegramTaskStatus
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
