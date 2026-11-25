package io.github.HanJason233.chimera.network.subscription

import io.github.HanJason233.chimera.data.model.AccountInfo
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubscriptionRequestsTest {
    @Test
    fun listRequestIncludesEncodedDeviceIdAndRefreshFlag() {
        val url = backendClientUrl("https://worker.example/", "device +/?", true).toHttpUrl()
        assertEquals("/api/client", url.encodedPath)
        assertEquals("device +/?", url.queryParameter("deviceId"))
        assertEquals("1", url.queryParameter("refresh"))
        assertEquals(listOf("deviceId"), backendClientUrl("https://worker.example", "device").toHttpUrl().queryParameterNames.toList())
    }

    @Test
    fun downloadRequestUsesCurrentDeviceAndPreservesOtherParameters() {
        val url = subscriptionRequestUrl(
            "https://worker.example/api/subscription/43?deviceId=old&refresh=1",
            "https://worker.example/", "current"
        ).toHttpUrl()
        assertEquals(listOf("current"), url.queryParameterValues("deviceId"))
        assertEquals("1", url.queryParameter("refresh"))
        val external = "https://external.example/api/subscription/1"
        assertEquals(external, subscriptionRequestUrl(external, "https://worker.example", "current"))
    }

    @Test
    fun cacheScopeChangesWithBackendDeviceAccountVersionAndPermissions() {
        val account = AccountInfo(uid = "123")
        fun scope(
            backend: String = "https://worker.example",
            device: String = "device",
            user: AccountInfo = account,
            version: String = "1.0.0"
        ) = subscriptionCacheScope(backend, device, user, version)
        val original = scope()
        assertEquals(original, scope(backend = "https://worker.example/"))
        assertNotEquals(original, scope(backend = "https://other.example"))
        assertNotEquals(original, scope(device = "other"))
        assertNotEquals(original, scope(user = account.copy(uid = "456")))
        assertNotEquals(original, scope(version = "1.1.0"))
        assertNotEquals(original, scope(user = account.copy(email = "user@example.com", accountType = "formal")))
        assertNotEquals(original, scope(user = account.copy(deviceBlocked = true)))
        assertNotEquals(original, scope(user = account.copy(vipActive = true, vipExpiresAt = "2030-01-01T00:00:00Z")))
    }
}
