package io.github.HanJason233.chimera.network.account

import android.content.Context
import io.github.HanJason233.chimera.data.model.AccountInfo
import java.lang.reflect.InvocationTargetException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccountManagerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("account_identity", Context.MODE_PRIVATE)

    @Before
    @After
    fun clearPreferences() {
        preferences.edit().clear().commit()
    }

    @Test
    fun legacyGuestSessionIsClearedWithoutChangingDeviceOrOfflineSettings() {
        preferences.edit()
            .putString("account_id", "legacy-uid")
            .putString("account_type", "guest")
            .putString("account_email", "")
            .putLong("traffic_total_total_bytes", 123L)
            .putString("install_id", "existing-install")
            .putBoolean("offline_mode_enabled", true)
            .commit()

        assertNull(AccountManager(context).currentAccount())
        assertFalse(preferences.contains("account_id"))
        assertFalse(preferences.contains("traffic_total_total_bytes"))
        assertEquals("existing-install", preferences.getString("install_id", null))
        assertEquals(true, preferences.getBoolean("offline_mode_enabled", false))
    }

    @Test
    fun legacyGuestResponseCannotCreateAnAuthenticatedSession() {
        val manager = AccountManager(context)
        val persist = AccountManager::class.java.getDeclaredMethod("persistAccount", AccountInfo::class.java)
        persist.isAccessible = true

        for (email in listOf("", "user@example.com")) {
            val error = assertThrows(InvocationTargetException::class.java) {
                persist.invoke(manager, AccountInfo(uid = "legacy-uid", accountType = "guest", email = email))
            }
            assertEquals("account_not_registered", error.cause?.message)
        }
        assertNull(manager.currentAccount())
        assertFalse(preferences.contains("account_id"))
    }

    @Test
    fun registeredSessionRemainsAvailable() {
        preferences.edit()
            .putString("account_id", "registered-uid")
            .putString("account_type", "formal")
            .putString("account_email", "user@example.com")
            .commit()

        assertEquals("registered-uid", AccountManager(context).currentAccount()?.uid)
    }
}
