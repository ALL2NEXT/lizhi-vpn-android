package xyz.a202132.windows.data

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.a202132.windows.model.ProxyMode

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
}
