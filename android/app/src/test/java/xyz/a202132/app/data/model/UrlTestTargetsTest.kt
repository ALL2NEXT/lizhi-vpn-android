package xyz.a202132.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlTestTargetsTest {
    @Test
    fun `contains all built in 204 targets`() {
        assertEquals(
            listOf(
                "https://cp.cloudflare.com/generate_204",
                "https://www.google.com/generate_204",
                "https://www.gstatic.com/generate_204"
            ),
            UrlTestTargets.PRESETS.map(UrlTestPreset::url)
        )
    }

    @Test
    fun `normalizes custom HTTP URLs`() {
        assertEquals(
            "https://example.com/generate_204?region=cn",
            UrlTestTargets.normalize(" example.com/generate_204?region=cn ")
        )
        assertEquals(
            "http://192.0.2.1/generate_204",
            UrlTestTargets.normalize("http://192.0.2.1/generate_204")
        )
        assertNull(UrlTestTargets.normalize("ftp://example.com/generate_204"))
        assertNull(UrlTestTargets.normalize("https://user@example.com/generate_204"))
        assertNull(UrlTestTargets.normalize("https://example.com/generate_204#fragment"))
    }
}
