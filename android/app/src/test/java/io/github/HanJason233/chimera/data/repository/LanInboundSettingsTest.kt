package io.github.HanJason233.chimera.data.repository

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.HanJason233.chimera.data.model.ProxyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LanInboundSettingsTest {
    @Test
    fun legacySharedModeIsPreservedWhenPhoneModeChanges() = runBlocking {
        val store = MemoryStore()
        store.updateData { it.toMutablePreferences().apply {
            this[stringPreferencesKey("proxy_mode")] = ProxyMode.GLOBAL.name
        } }
        val repository = SettingsRepository(RuntimeEnvironment.getApplication(), store)
        assertEquals(ProxyMode.GLOBAL, repository.lanInboundSettings.first().proxyMode)

        repository.setProxyMode(ProxyMode.SMART)
        val reloaded = SettingsRepository(RuntimeEnvironment.getApplication(), store)
        assertEquals(ProxyMode.SMART, reloaded.proxyMode.first())
        assertEquals(ProxyMode.GLOBAL, reloaded.lanInboundSettings.first().proxyMode)
        assertEquals(ProxyMode.GLOBAL, reloaded.getVpnRuntimeSettings().lanInbound.proxyMode)
    }

    @Test
    fun lanModePersistsIndependentlyAcrossOtherSettingChanges() = runBlocking {
        val store = MemoryStore()
        val repository = SettingsRepository(RuntimeEnvironment.getApplication(), store)
        repository.setLanInboundProxyMode(ProxyMode.GLOBAL)
        repository.setLanInboundEnabled(true)
        repository.setLanInboundDetails(12345, "shared", "secret")
        repository.setProxyMode(ProxyMode.GLOBAL)
        repository.setProxyMode(ProxyMode.SMART)

        val reloaded = SettingsRepository(RuntimeEnvironment.getApplication(), store)
        val lan = reloaded.lanInboundSettings.first()
        assertEquals(ProxyMode.SMART, reloaded.proxyMode.first())
        assertEquals(ProxyMode.GLOBAL, lan.proxyMode)
        assertEquals(true, lan.enabled)
        assertEquals(12345, lan.port)
        assertEquals("shared", lan.username)
        assertEquals("secret", lan.password)
    }

    @Test
    fun newInstallKeepsLanSmartWhenPhoneSwitchesToGlobal() = runBlocking {
        val repository = SettingsRepository(RuntimeEnvironment.getApplication(), MemoryStore())
        repository.setProxyMode(ProxyMode.GLOBAL)
        assertEquals(ProxyMode.SMART, repository.lanInboundSettings.first().proxyMode)
        repository.setLanInboundProxyMode(ProxyMode.GLOBAL)
        assertEquals(ProxyMode.GLOBAL, repository.proxyMode.first())
        assertEquals(ProxyMode.GLOBAL, repository.lanInboundSettings.first().proxyMode)
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }
}
