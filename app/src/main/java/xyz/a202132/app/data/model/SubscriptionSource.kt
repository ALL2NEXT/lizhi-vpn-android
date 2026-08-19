package xyz.a202132.app.data.model

/**
 * A node subscription source supplied by the remote subscription list.
 */
data class SubscriptionSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val encrypted: Boolean = false,
    /** 是否在应用启动时主动请求这条线路的最新节点。 */
    val refreshOnAppOpen: Boolean = true
)

data class SubscriptionList(
    val sources: List<SubscriptionSource>
)
