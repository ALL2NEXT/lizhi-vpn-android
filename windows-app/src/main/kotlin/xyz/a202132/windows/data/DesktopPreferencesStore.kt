package xyz.a202132.windows.data

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.net.URI
import java.util.UUID
import xyz.a202132.windows.model.DesktopSettings
import xyz.a202132.windows.model.ProxyMode

data class DesktopPreferences(
    val installId: String = UUID.randomUUID().toString(),
    val backendUrl: String = DesktopPreferencesStore.DEFAULT_BACKEND_URL,
    val proxyMode: ProxyMode = ProxyMode.SMART,
    val selectedNodeId: String? = null,
    val favoriteNodeIds: Set<String> = emptySet(),
    val settings: DesktopSettings = DesktopSettings()
)

class DesktopPreferencesStore(
    private val file: Path = defaultFile()
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val lock = Any()
    private var preferences = read()

    fun snapshot(): DesktopPreferences = synchronized(lock) { preferences }

    fun update(transform: (DesktopPreferences) -> DesktopPreferences): DesktopPreferences =
        synchronized(lock) {
            preferences = transform(preferences)
            write(preferences)
            preferences
        }

    private fun read(): DesktopPreferences = runCatching {
        if (!Files.isRegularFile(file)) return@runCatching DesktopPreferences()
        gson.fromJson(Files.readString(file, Charsets.UTF_8), DesktopPreferences::class.java)
            ?: DesktopPreferences()
    }.getOrDefault(DesktopPreferences())

    private fun write(value: DesktopPreferences) {
        Files.createDirectories(file.parent)
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, gson.toJson(value), Charsets.UTF_8)
        runCatching {
            Files.move(
                temporary,
                file,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.getOrElse {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val DEFAULT_BACKEND_URL = "https://liuying.11145149.xyz"

        fun normalizeBackendUrl(value: String?): String? {
            val raw = value?.trim().orEmpty()
            if (raw.isBlank() || raw.length > 2048) return null
            val candidate = if (
                raw.startsWith("https://", ignoreCase = true) ||
                raw.startsWith("http://", ignoreCase = true)
            ) raw else "https://$raw"
            val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme !in setOf("http", "https")) return null
            if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
                return null
            }
            if (scheme == "http" && uri.host != "127.0.0.1") return null
            return candidate.trimEnd('/')
        }

        private fun defaultFile(): Path {
            val base = System.getenv("LOCALAPPDATA")
                ?.takeIf(String::isNotBlank)
                ?.let(Paths::get)
                ?: Paths.get(System.getProperty("user.home"), "AppData", "Local")
            return base.resolve("FireflyVPN").resolve("preferences.json")
        }
    }
}
