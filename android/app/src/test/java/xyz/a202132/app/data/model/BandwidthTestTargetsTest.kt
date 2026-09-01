package xyz.a202132.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
