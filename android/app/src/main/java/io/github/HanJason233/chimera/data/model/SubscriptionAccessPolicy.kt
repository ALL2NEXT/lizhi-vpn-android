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

/** 只有账号权限与客户端版本区间均匹配时，才允许请求该订阅。 */
internal fun SubscriptionSource.canBeRequestedBy(
    account: AccountInfo?,
    currentAppVersion: String
): Boolean {
    val isBanned = account?.accountType == ACCOUNT_TYPE_BANNED || account?.deviceBlocked == true
    val accountStatus = if (isBanned) "banned" else "normal"
    // 邮箱绑定类型独立于封禁状态，封禁的正式用户仍属于正式用户。
    val accountType = if (account?.email?.isNotBlank() == true) ACCOUNT_TYPE_FORMAL else ACCOUNT_TYPE_GUEST

    // 新版接口提供精确规则；旧版接口仍按最低账户等级兼容判断。
    if (allowedStatuses == null && allowedAccountTypes == null) {
        val legacyType = if (isBanned) ACCOUNT_TYPE_BANNED else accountType
        if (accountTypeRank(legacyType) < accountTypeRank(minAccountType)) return false
    } else {
        if (allowedStatuses?.contains(accountStatus) != true) return false
        if (allowedAccountTypes?.contains(accountType) != true) return false
    }

    val membershipType = if (account?.vipActive == true) "member" else "non_member"
    if (allowedMembershipTypes != null && !allowedMembershipTypes.contains(membershipType)) return false

    val meetsMinimum = minAppVersion.isBlank() || isVersionAtLeast(currentAppVersion, minAppVersion)
    val meetsMaximum = maxAppVersion.isBlank() || isVersionAtLeast(maxAppVersion, currentAppVersion)
    return meetsMinimum && meetsMaximum
}

/** 与后台保持一致，按点分隔的数字逐段比较客户端版本。 */
private fun isVersionAtLeast(currentVersion: String, minimumVersion: String): Boolean {
    fun parts(value: String): List<Long>? {
        val normalized = value.trim().removePrefix("v").removePrefix("V")
        if (normalized.isEmpty() || !normalized.matches(Regex("\\d+(?:\\.\\d+)*"))) return null
        return normalized.split('.').map { it.toLongOrNull() ?: return null }
    }

    val current = parts(currentVersion) ?: return false
    val minimum = parts(minimumVersion) ?: return false
    for (index in 0 until maxOf(current.size, minimum.size)) {
        val currentPart = current.getOrElse(index) { 0L }
        val minimumPart = minimum.getOrElse(index) { 0L }
        if (currentPart != minimumPart) return currentPart > minimumPart
    }
    return true
}
