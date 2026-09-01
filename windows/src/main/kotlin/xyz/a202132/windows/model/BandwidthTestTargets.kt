package xyz.a202132.windows.model

object BandwidthTestTargets {
    const val DEFAULT_URL = "https://speed.cloudflare.com/__down?bytes=5000000"

    fun normalize(value: String?): String? = UrlTestTargets.normalize(value)
}
