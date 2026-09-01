package xyz.a202132.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandwidthTestTargetsTest {
    @Test
    fun `contains only Cloudflare 5 and 25 MB presets`() {
        assertEquals(
            listOf(
                BandwidthTestTargets.DEFAULT_URL,
                "https://speed.cloudflare.com/__down?bytes=25000000"
            ),
            BandwidthTestTargets.PRESETS.map(BandwidthTestPreset::url)
        )
        assertTrue(
            BandwidthTestTargets.PRESETS.all { preset ->
                BandwidthTestTargets.normalize(preset.url) == preset.url
            }
        )
    }
}
