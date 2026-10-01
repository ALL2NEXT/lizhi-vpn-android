package io.github.HanJason233.chimera.data.model

/**
 * A node subscription source supplied by the remote subscription list.
 */
data class SubscriptionSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val encrypted: Boolean = false,
    /** 允许访问这条线路的最低账户等级：被封禁账户、游客、正式用户。 */
    val minAccountType: String = DEFAULT_MIN_ACCOUNT_TYPE
)

data class SubscriptionList(
    val sources: List<SubscriptionSource>
)
