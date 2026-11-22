package io.github.HanJason233.chimera.data.model

/**
 * A node subscription source supplied by the remote subscription list.
 */
data class SubscriptionSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    /** 新版后台按封禁状态控制访问；为空时表示旧版接口没有返回该规则。 */
    val allowedStatuses: List<String>? = null,
    /** Group membership eligibility; null keeps compatibility with older backends. */
    val allowedMembershipTypes: List<String>? = null,
    /** 分组要求的最低客户端版本。 */
    val minAppVersion: String = "",
    /** 分组允许访问的最高客户端版本；空值表示不设上限。 */
    val maxAppVersion: String = "",
)

data class SubscriptionList(
    val sources: List<SubscriptionSource>,
    /** 后台按客户端可见的订阅 ID 返回各自版本号。 */
    val subscriptionVersions: Map<String, Long> = emptyMap()
)
