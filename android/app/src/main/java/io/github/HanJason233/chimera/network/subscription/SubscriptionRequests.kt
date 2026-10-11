package io.github.HanJason233.chimera.network.subscription

import io.github.HanJason233.chimera.data.model.AccountInfo
import okhttp3.HttpUrl.Companion.toHttpUrl

internal fun backendClientUrl(
    backendUrl: String,
    deviceId: String,
    forceRefresh: Boolean = false
): String = "${backendUrl.trimEnd('/')}/api/client".toHttpUrl().newBuilder()
    .addQueryParameter("deviceId", deviceId)
    .apply { if (forceRefresh) addQueryParameter("refresh", "1") }
    .build().toString()

internal fun subscriptionRequestUrl(url: String, backendUrl: String, deviceId: String): String {
    val managedPrefix = "${backendUrl.trimEnd('/')}/api/subscription/"
    if (!url.startsWith(managedPrefix, ignoreCase = true)) return url
    return url.toHttpUrl().newBuilder()
        .setQueryParameter("deviceId", deviceId)
        .build().toString()
}

/** 旧版公共列表及其他后台、账号或权限状态的缓存不能作为当前账号的线路缓存。 */
internal fun subscriptionCacheScope(
    backendUrl: String,
    deviceId: String,
    account: AccountInfo?,
    appVersion: String
): String = listOf(
    backendUrl.trimEnd('/'), deviceId, account?.uid.orEmpty(), appVersion,
    account?.accountType.orEmpty(), account?.email.orEmpty(),
    account?.deviceBlocked.toString(), account?.vipActive.toString(), account?.vipExpiresAt.orEmpty()
).joinToString("\n")
