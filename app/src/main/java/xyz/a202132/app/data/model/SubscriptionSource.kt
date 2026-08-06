package xyz.a202132.app.data.model

/**
 * A node subscription source supplied by the remote subscription list.
 */
data class SubscriptionSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true
)

data class SubscriptionList(
    val autoUpdate: Boolean,
    val sources: List<SubscriptionSource>
)
