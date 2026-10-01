package xyz.a202132.app.data.model

import java.net.URI

data class UrlTestPreset(
    val name: String,
    val url: String
)

object UrlTestTargets {
    const val DEFAULT_URL = "https://www.google.com/generate_204"
    const val DEFAULT_BACKUP_URL = "https://www.gstatic.com/generate_204"

    /** Empty input disables the optional backup target. */
    fun normalizeOptional(value: String?): String? =
        if (value.isNullOrBlank()) "" else normalize(value)

    val PRESETS = listOf(
        UrlTestPreset("Cloudflare", "https://cp.cloudflare.com/generate_204"),
        UrlTestPreset("Google", DEFAULT_URL),
        UrlTestPreset("Google Static", DEFAULT_BACKUP_URL)
    )

    /** Accepts an HTTP(S) URL, adding HTTPS when the scheme is omitted. */
    fun normalize(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank() || raw.length > 2048) return null
        if ("://" in raw &&
            !raw.startsWith("https://", ignoreCase = true) &&
            !raw.startsWith("http://", ignoreCase = true)
        ) return null
        val candidate = if (
            raw.startsWith("https://", ignoreCase = true) ||
            raw.startsWith("http://", ignoreCase = true)
        ) raw else "https://$raw"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) return null
        return candidate
    }
}
