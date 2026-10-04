package io.github.HanJason233.chimera.data.repository

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.HanJason233.chimera.data.model.NodeGroupKey
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.data.model.SpeedTestScheme
import io.github.HanJason233.chimera.data.model.SpeedTestSelectionMode
import io.github.HanJason233.chimera.data.model.SkinScheme
import io.github.HanJason233.chimera.feature.nodegrouping.model.NodeGroupingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class SelectedNodeGroupSettingsTest {
    private lateinit var repository: SettingsRepository
    private lateinit var store: TestPreferencesStore

    @Before
    fun resetSelection() = runBlocking {
        // DataStore 1.0 的文件替换依赖 POSIX rename；Windows JVM 使用内存存储。
        store = TestPreferencesStore()
        repository = SettingsRepository(RuntimeEnvironment.getApplication(), store)
        repository.setSelectedNodeGroupId(null)
        repository.setSelectedNodeId(null)
        repository.setSelectedCountryCode(null)
        repository.setSelectedSubscriptionSourceId(null)
    }

    @Test
    fun legacyBuiltInDefaultsUpdateWithoutChangingCustomPresets() = runBlocking {
        seedLegacyPresets()
        val speedSchemes = repository.speedTestSchemes.first()
        val defaultSpeed = speedSchemes.first()
        assertEquals(SpeedTestSelectionMode.COUNT, defaultSpeed.urlTestSelectionMode)
        assertEquals(10, defaultSpeed.urlTestSelectionValue)
        assertEquals(true, defaultSpeed.linkTypeTestEnabled)
        assertEquals(2_000L, defaultSpeed.tcpingTimeoutMs)
        val customSpeed = speedSchemes.first { it.id == "custom-speed" }
        assertEquals(4, customSpeed.urlTestSelectionValue)
        assertEquals(false, customSpeed.linkTypeTestEnabled)

        val themes = repository.skinSettings.first().schemes
        assertEquals(false, themes.first { it.isDefault }.homeIconEffectEnabled)
        assertEquals(false, themes.first { it.isDefault }.monetEnabled)
        assertEquals(true, themes.first { it.id == "custom-theme" }.homeIconEffectEnabled)
    }

    @Test
    fun manualChangesToUpdatedBuiltInDefaultsSurviveReload() = runBlocking {
        seedLegacyPresets()
        repository.upsertSpeedTestScheme(repository.speedTestSchemes.first().first().copy(
            urlTestSelectionValue = 3, linkTypeTestEnabled = false
        ))
        repository.upsertSkinScheme(repository.skinSettings.first().schemes.first { it.isDefault }.copy(
            homeIconEffectEnabled = true
        ))
        val reloaded = SettingsRepository(RuntimeEnvironment.getApplication(), store)
        val scheme = reloaded.speedTestSchemes.first().first()
        assertEquals(SpeedTestSelectionMode.COUNT, scheme.urlTestSelectionMode)
        assertEquals(3, scheme.urlTestSelectionValue)
        assertEquals(false, scheme.linkTypeTestEnabled)
        assertEquals(true, reloaded.skinSettings.first().schemes.first { it.isDefault }.homeIconEffectEnabled)
    }

    @Test
    fun linkTypeToggleSurvivesReloadWithoutChangingUnlockTests() = runBlocking {
        val scheme = SpeedTestScheme.default().copy(
            linkTypeTestEnabled = true, unlockTestsEnabled = true, unlockTestIds = setOf("netflix")
        )
        repository.upsertSpeedTestScheme(scheme)
        val reloaded = SettingsRepository(RuntimeEnvironment.getApplication(), store)
        val restored = reloaded.speedTestSchemes.first().first()
        assertEquals(true, restored.linkTypeTestEnabled)
        assertEquals(true, restored.unlockTestsEnabled)
        assertEquals(setOf("netflix"), restored.unlockTestIds)
        repository.upsertSpeedTestScheme(restored.copy(linkTypeTestEnabled = false))
        assertEquals(false, reloaded.speedTestSchemes.first().first().linkTypeTestEnabled)
    }

    @Test
    fun personalGroupSurvivesUnrelatedPreferenceChangesAndRepositoryReload() = runBlocking {
        repository.setSelectedNodeGroup(NodeGroupKey.Subscription("official-first"))
        repository.setSelectedNodeGroup(NodeGroupKey.Imported("personal-selected"))
        repository.setProxyMode(ProxyMode.GLOBAL)

        val reloaded = SettingsRepository(RuntimeEnvironment.getApplication(), store)
        assertEquals("imported:personal-selected", reloaded.selectedNodeGroupId.first())
        assertNull(reloaded.selectedSubscriptionSourceId.first())
        assertNull(reloaded.selectedCountryCode.first())
        assertEquals(NodeGroupingMode.WAREHOUSE, reloaded.nodeGroupingMode.first())
    }

    @Test
    fun countrySelectionSurvivesAStoredOfficialSource() = runBlocking {
        repository.setSelectedNodeGroup(NodeGroupKey.Subscription("official-first"))
        repository.setSelectedNodeGroup(NodeGroupKey.Country("JP"))
        repository.setProxyMode(ProxyMode.SMART)

        assertEquals("country:JP", repository.selectedNodeGroupId.first())
        assertEquals("JP", repository.selectedCountryCode.first())
        assertEquals(NodeGroupingMode.REGION, repository.nodeGroupingMode.first())
    }

    @Test
    fun selectingTheSameGroupKeepsTheChosenNode() = runBlocking {
        val group = NodeGroupKey.Imported("personal-selected")
        repository.setSelectedNodeGroup(group)
        repository.setSelectedNodeId("chosen-node")
        repository.setSelectedNodeGroup(group)

        assertEquals("chosen-node", repository.selectedNodeId.first())
    }

    @Test
    fun changingCountryClearsThePreviousGroupsNode() = runBlocking {
        repository.setSelectedNodeGroup(NodeGroupKey.Country("US"))
        repository.setSelectedNodeId("us-node")
        repository.setSelectedNodeGroup(NodeGroupKey.Country("JP"))

        assertEquals("country:JP", repository.selectedNodeGroupId.first())
        assertEquals("JP", repository.selectedCountryCode.first())
        assertNull(repository.selectedNodeId.first())
    }

    @Test
    fun explicitlySelectingAnOfficialGroupSynchronizesItsFilters() = runBlocking {
        repository.setSelectedNodeGroup(NodeGroupKey.Country("JP"))
        repository.setSelectedNodeId("jp-node")
        repository.setSelectedNodeGroup(NodeGroupKey.Subscription("official-second"))

        assertEquals("subscription:official-second", repository.selectedNodeGroupId.first())
        assertEquals("official-second", repository.selectedSubscriptionSourceId.first())
        assertEquals(NodeGroupingMode.WAREHOUSE, repository.nodeGroupingMode.first())
        assertNull(repository.selectedCountryCode.first())
        assertNull(repository.selectedNodeId.first())
    }

    private suspend fun seedLegacyPresets() {
        store.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[stringPreferencesKey("speed_test_schemes_json")] = """
                    [{"id":"${SpeedTestScheme.DEFAULT_ID}","name":"默认测速","builtIn":true,
                    "urlTestSelectionMode":"PERCENT","urlTestSelectionValue":50,
                    "linkTypeTestEnabled":false,"tcpingTimeoutMs":2000},
                    {"id":"custom-speed","name":"自定义","urlTestSelectionMode":"COUNT",
                    "urlTestSelectionValue":4,"linkTypeTestEnabled":false}]
                """.trimIndent()
                this[stringPreferencesKey("skin_schemes_json")] = """
                    [{"id":"${SkinScheme.DEFAULT_SCHEME_ID}","name":"默认主题",
                    "homeIconEffectEnabled":true,"monetEnabled":false},
                    {"id":"custom-theme","name":"自定义主题","homeIconEffectEnabled":true}]
                """.trimIndent()
            }
        }
    }

    private class TestPreferencesStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        private val mutex = Mutex()

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }
}
