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
        ),
        BandwidthTestPreset(
            "Cloudflare 100 MB",
            "https://speed.cloudflare.com/__down?bytes=100000000"
        ),
        BandwidthTestPreset("OVH 10 MB", "https://proof.ovh.net/files/10Mb.dat"),
        BandwidthTestPreset("OVH 100 MB", "https://proof.ovh.net/files/100Mb.dat"),
        BandwidthTestPreset(
            "ThinkBroadband 5 MB",
            "https://ipv4.download.thinkbroadband.com/5MB.zip"
        ),
        BandwidthTestPreset("Hetzner 10 MB", "https://ash-speed.hetzner.com/10MB.bin")
    )

    fun normalize(value: String?): String? = UrlTestTargets.normalize(value)
}
