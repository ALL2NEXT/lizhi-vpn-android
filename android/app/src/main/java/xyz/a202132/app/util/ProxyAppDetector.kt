package xyz.a202132.app.util

/**
 * 根据应用标识判断是否属于常见的国际网络应用。
 *
 * Android 在应用真正发起连接前无法可靠判断它是否需要代理，因此这里采用
 * 与 v2rayN 进程规则相近的“应用标识匹配”方式，并且只匹配较明确的候选。
 */
object ProxyAppDetector {
    private val knownPackages = setOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.brave.browser",
        "com.duckduckgo.mobile.android",
        "com.google.android.gm",
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.google.android.apps.docs",
        "com.google.android.apps.photos",
        "com.google.android.apps.maps",
        "com.google.android.apps.translate",
        "com.google.android.apps.authenticator2",
        "com.google.android.apps.tachyon",
        "com.google.android.play.games",
        "com.twitter.android",
        "com.x.android",
        "com.instagram.android",
        "org.telegram.messenger",
        "com.discord",
        "com.reddit.frontpage",
        "com.linkedin.android",
        "com.github.android",
        "com.openai.chatgpt",
        "com.spotify.music",
        "tv.twitch.android.app",
        "com.netflix.mediaclient",
        "com.cloudflare.onedotonedotonedotone"
    )

    private val knownPackagePrefixes = listOf(
        "com.google.android.apps.",
        "com.microsoft.",
        "com.facebook.",
        "com.reddit.",
        "org.telegram.",
        "com.discord.",
        "com.linkedin.",
        "com.spotify.",
        "com.netflix.",
        "tv.twitch.",
        "com.openai."
    )

    private val knownAppNameKeywords = listOf(
        "google",
        "youtube",
        "twitter",
        "instagram",
        "facebook",
        "telegram",
        "discord",
        "reddit",
        "linkedin",
        "github",
        "openai",
        "chatgpt",
        "spotify",
        "netflix",
        "twitch",
        "microsoft",
        "bing",
        "onedrive",
        "whatsapp",
        "谷歌",
        "油管",
        "推特",
        "脸书",
        "电报",
        "领英",
        "微软"
    )

    fun isProxyCandidate(
        packageName: String,
        appName: String,
        isSystemApp: Boolean
    ): Boolean {
        if (isSystemApp) return false

        val normalizedPackageName = packageName.lowercase()
        val normalizedAppName = appName.lowercase()

        return normalizedPackageName in knownPackages ||
            knownPackagePrefixes.any(normalizedPackageName::startsWith) ||
            knownAppNameKeywords.any(normalizedAppName::contains)
    }
}
