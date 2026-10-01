package io.github.HanJason233.chimera.data.model

object BandwidthTestTargets {
    const val DEFAULT_URL = "https://speed.cloudflare.com/__down?bytes=25000000"

    fun normalize(value: String?): String? = UrlTestTargets.normalize(value)
}
