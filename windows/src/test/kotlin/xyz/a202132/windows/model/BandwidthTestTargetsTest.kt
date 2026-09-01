package xyz.a202132.windows.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BandwidthTestTargetsTest {
    @Test
    fun `contains bandwidth presets from multiple providers`() {
        assertEquals(BandwidthTestTargets.DEFAULT_URL, BandwidthTestTargets.PRESETS.first().url)
        assertTrue(BandwidthTestTargets.PRESETS.size >= 4)
        assertEquals(
            BandwidthTestTargets.PRESETS.size,
            BandwidthTestTargets.PRESETS.map(BandwidthTestPreset::url).toSet().size
        )
        assertTrue(
            BandwidthTestTargets.PRESETS.all { preset ->
                BandwidthTestTargets.normalize(preset.url) == preset.url
            }
        )
    }
}
