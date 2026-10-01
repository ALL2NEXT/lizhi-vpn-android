package io.github.HanJason233.chimera.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BandwidthTestTargetsTest {
    @Test
    fun `uses Cloudflare 25 MB as the only built in default`() {
        assertEquals(
            "https://speed.cloudflare.com/__down?bytes=25000000",
            BandwidthTestTargets.DEFAULT_URL
        )
        assertEquals(BandwidthTestTargets.DEFAULT_URL, BandwidthTestTargets.normalize(BandwidthTestTargets.DEFAULT_URL))
    }
}
