package xyz.a202132.windows.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.a202132.windows.model.ProxyMode
import xyz.a202132.windows.model.NodeType
import xyz.a202132.windows.model.UrlTestTargets

class DesktopPreferencesStoreTest {
    @Test
    fun preservesInstallIdentityAndSettings() {
        val directory = Files.createTempDirectory("firefly-preferences-test")
        val file = directory.resolve("preferences.json")
        val first = DesktopPreferencesStore(file)
        val installId = first.snapshot().installId
        first.update { it.copy(proxyMode = ProxyMode.GLOBAL, favoriteNodeIds = setOf("node-1")) }

        val restored = DesktopPreferencesStore(file).snapshot()
        assertEquals(installId, restored.installId)
        assertEquals(ProxyMode.GLOBAL, restored.proxyMode)
        assertTrue("node-1" in restored.favoriteNodeIds)
    }

    @Test
    fun `normalizes backend address like Android`() {
        assertEquals("https://example.com", DesktopPreferencesStore.normalizeBackendUrl("example.com/"))
        assertEquals("http://127.0.0.1:8080", DesktopPreferencesStore.normalizeBackendUrl("http://127.0.0.1:8080/"))
        assertEquals(null, DesktopPreferencesStore.normalizeBackendUrl("http://example.com"))
        assertEquals(null, DesktopPreferencesStore.normalizeBackendUrl("https://user@example.com"))
    }

    @Test
    fun `persists custom URL test target`() {
        val directory = Files.createTempDirectory("firefly-url-test-preferences")
        val file = directory.resolve("preferences.json")
        val store = DesktopPreferencesStore(file)
        val customUrl = "https://example.com/generate_204"

        store.update { preferences ->
            preferences.copy(settings = preferences.settings.copy(urlTestUrl = customUrl))
        }

        assertEquals(customUrl, DesktopPreferencesStore(file).snapshot().settings.urlTestUrl)
    }

    @Test
    fun `uses default URL test target when older preferences omit it`() {
        val directory = Files.createTempDirectory("firefly-old-preferences")
        val file = directory.resolve("preferences.json")
        DesktopPreferencesStore(file).update { it }
        val oldJson = Files.readString(file).replace(
            Regex("""\s*"urlTestUrl"\s*:\s*"[^"]*",?"""),
            ""
        )
        Files.writeString(file, oldJson)

        assertEquals(
            UrlTestTargets.DEFAULT_URL,
            DesktopPreferencesStore(file).snapshot().settings.urlTestUrl
        )
    }

    @Test
    fun `preserves an explicit empty xray protocol selection`() {
        val directory = Files.createTempDirectory("firefly-core-preferences")
        val file = directory.resolve("preferences.json")
        val store = DesktopPreferencesStore(file)

        store.update { it.copy(xrayProtocols = emptySet()) }

        assertEquals(emptySet<NodeType>(), DesktopPreferencesStore(file).snapshot().xrayProtocols)
    }
}
