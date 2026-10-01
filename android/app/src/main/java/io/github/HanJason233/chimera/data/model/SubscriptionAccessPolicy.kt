package io.github.HanJason233.chimera.data.model

const val ACCOUNT_TYPE_BANNED = "banned"
const val ACCOUNT_TYPE_GUEST = "guest"
const val ACCOUNT_TYPE_FORMAL = "formal"

const val DEFAULT_MIN_ACCOUNT_TYPE = ACCOUNT_TYPE_GUEST

private fun accountTypeRank(accountType: String?): Int = when (accountType) {
    ACCOUNT_TYPE_BANNED -> 0
    ACCOUNT_TYPE_GUEST -> 1
    ACCOUNT_TYPE_FORMAL -> 2
    else -> 0
}

fun normalizeSubscriptionAccountType(accountType: String?): String = when (accountType) {
    ACCOUNT_TYPE_BANNED, ACCOUNT_TYPE_GUEST, ACCOUNT_TYPE_FORMAL -> accountType
    else -> DEFAULT_MIN_ACCOUNT_TYPE
}

/** 只有当前账户等级达到线路要求的最低等级时，才允许请求该订阅。 */
internal fun SubscriptionSource.canBeRequestedBy(accountType: String?): Boolean =
    accountTypeRank(accountType) >= accountTypeRank(minAccountType)
