package io.github.HanJason233.chimera.data.model

const val ACCOUNT_TYPE_BANNED = "banned"
const val ACCOUNT_TYPE_GUEST = "guest"
const val ACCOUNT_TYPE_FORMAL = "formal"

const val DEFAULT_MIN_ACCOUNT_TYPE = ACCOUNT_TYPE_GUEST

fun normalizeSubscriptionAccountType(accountType: String?): String = when (accountType) {
    ACCOUNT_TYPE_BANNED, ACCOUNT_TYPE_GUEST, ACCOUNT_TYPE_FORMAL -> accountType
    else -> DEFAULT_MIN_ACCOUNT_TYPE
}
