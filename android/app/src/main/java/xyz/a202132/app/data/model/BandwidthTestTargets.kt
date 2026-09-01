package xyz.a202132.app.data.model

data class BandwidthTestPreset(
    val name: String,
    val url: String
)

object BandwidthTestTargets {
    const val DEFAULT_URL = "https://speed.cloudflare.com/__down?bytes=5000000"

    val PRESETS = listOf(
        BandwidthTestPreset("Cloudflare 5 MB", DEFAULT_URL),
        BandwidthTestPreset(
            "Cloudflare 25 MB",
            "https://speed.cloudflare.com/__down?bytes=25000000"
        )
    )

    fun normalize(value: String?): String? = UrlTestTargets.normalize(value)
}
