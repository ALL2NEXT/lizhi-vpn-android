package xyz.a202132.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinSettingsTest {
    @Test
    fun defaultSchemeEnablesMonetWithBlueGreyFallback() {
        val scheme = SkinScheme.default()

        assertTrue(scheme.monetEnabled)
        assertEquals(0xFF37474FL, scheme.baseColorArgb)
        assertEquals(SkinSettings.DEFAULT_BASE_COLOR_ARGB, scheme.baseColorArgb)
    }

    @Test
    fun customSchemeKeepsBaseColorEnabledByDefault() {
        val scheme = SkinScheme.create("自定义")

        assertFalse(scheme.monetEnabled)
        assertEquals(SkinSettings.DEFAULT_BASE_COLOR_ARGB, scheme.baseColorArgb)
    }

    @Test
    fun initialSettingsMatchTheDefaultScheme() {
        val settings = SkinSettings()

        assertTrue(settings.monetEnabled)
        assertEquals(SkinScheme.DEFAULT_SCHEME_ID, settings.activeSchemeId)
        assertEquals(settings.activeScheme.monetEnabled, settings.monetEnabled)
        assertEquals(settings.activeScheme.baseColorArgb, settings.baseColorArgb)
    }
}
