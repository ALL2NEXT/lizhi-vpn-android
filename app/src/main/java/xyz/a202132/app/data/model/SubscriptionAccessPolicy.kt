package xyz.a202132.app.data.model

/** 仅当本地账户等级达到线路要求的最低等级时，才允许请求该订阅。 */
internal fun SubscriptionSource.canBeRequestedBy(accountLevel: Int): Boolean =
    accountLevel >= minLevel
