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
    /** Automatic refresh interval in minutes; 0 disables automatic refresh. */
    val updateIntervalMinutes: Int = 0
)

data class SubscriptionList(
    val sources: List<SubscriptionSource>
)
