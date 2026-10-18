package io.github.HanJason233.chimera.data.repository

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal data class RoutingAssetRecord(
    val id: String,
    val name: String,
    val url: String,
    val locked: Boolean = false,
    val addedTime: Long = System.currentTimeMillis()
)

internal data class RoutingAssetFileInfo(val length: Long, val lastModified: Long)

internal data class RoutingAssetsSnapshot(
    val source: String,
    val assets: List<RoutingAssetRecord>,
    val files: Map<String, RoutingAssetFileInfo>
)

internal data class RoutingAssetDownloadResult(
    val downloaded: Int,
    val failed: List<String>
)

internal data class RoutingAssetInitializationResult(
    val copied: List<String>,
    val failed: List<String>
)

/** 管理 Xray 使用的 geo 数据库，与 v2rayNG 的 assets 目录及配置格式保持一致。 */
internal class RoutingAssetsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val legacyDirectory = appContext.getDir(ASSET_DIRECTORY, Context.MODE_PRIVATE)
    val directory: File = assetDirectory(appContext).apply { mkdirs() }

    init {
        // 迁移早期版本保存在应用私有目录中的 Geo 文件，路径对齐 v2rayNG 的 files/assets。
        if (directory.canonicalFile != legacyDirectory.canonicalFile) {
            legacyDirectory.listFiles().orEmpty().filter(File::isFile).forEach { oldFile ->
                val newFile = File(directory, oldFile.name)
                if (!newFile.exists()) runCatching { oldFile.copyTo(newFile) }
            }
        }
    }

    /** 首次启动时从 APK assets 补齐内置 Geo 文件，不覆盖已下载或导入的版本。 */
    fun initializeBundledAssets(): RoutingAssetInitializationResult = synchronized(BUNDLED_ASSETS_LOCK) {
        val copied = mutableListOf<String>()
        val failed = mutableListOf<String>()
        BUILTIN_FILES.forEach { filename ->
            val target = File(directory, filename)
            if (target.isFile && target.length() > 0L) return@forEach

            val temporary = File(directory, "$filename.initializing")
            val result = runCatching {
                appContext.assets.open("$BUNDLED_ASSET_DIRECTORY/$filename").use { input ->
                    temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                check(temporary.isFile && temporary.length() > 0L) { "内置资源文件为空" }
                if (target.exists() && !target.delete()) error("无法替换空资源文件")
                check(temporary.renameTo(target)) { "无法保存内置资源文件" }
            }
            if (result.isSuccess) {
                copied += filename
            } else {
                temporary.delete()
                failed += "$filename: ${result.exceptionOrNull()?.message ?: "初始化失败"}"
            }
        }
        RoutingAssetInitializationResult(copied, failed)
    }

    fun load(): RoutingAssetsSnapshot {
        val source = preferences.getString(KEY_GEO_SOURCE, GEO_SOURCES.first())
            ?.takeIf(String::isNotBlank) ?: GEO_SOURCES.first()
        val saved = readSavedAssets()
        val defaultItems = BUILTIN_FILES
            .filterNot { filename -> saved.any { it.name == filename } }
            .map { filename ->
                RoutingAssetRecord(
                    id = "builtin:$filename",
                    name = filename,
                    url = defaultUrl(filename, source),
                    locked = true
                )
            }
        // 该专用数据库不随镜像仓库切换，URL 与 v2rayNG 保持一致。
        val assets = (defaultItems + saved).map { item ->
            if (item.name == GEOIP_CN_PRIVATE) item.copy(url = GEOIP_CN_PRIVATE_URL) else item
        }
        val files = assets.mapNotNull { item ->
            File(directory, item.name).takeIf(File::isFile)?.let { file ->
                item.id to RoutingAssetFileInfo(file.length(), file.lastModified())
            }
        }.toMap()
        return RoutingAssetsSnapshot(source, assets, files)
    }

    fun saveSource(source: String) {
        if (source in GEO_SOURCES) preferences.edit().putString(KEY_GEO_SOURCE, source).apply()
    }

    /** 返回 null 表示保存成功，否则返回可直接显示给用户的原因。 */
    fun saveAsset(id: String?, nameInput: String, urlInput: String): String? {
        val name = File(nameInput.trim()).name
        val url = urlInput.trim()
        if (name.isBlank() || name == "." || name == "..") return "请填写有效的文件名"
        if (url.isBlank()) return "请填写资源地址"
        if (url != LOCAL_FILE_URL && runCatching { Uri.parse(url).scheme?.lowercase() }
                .getOrNull() !in setOf("http", "https")
        ) return "资源地址必须使用 HTTP 或 HTTPS"

        val existing = readSavedAssets()
        val old = id?.let { assetId -> existing.firstOrNull { it.id == assetId } }
        if (old?.locked == true) return "内置资源不能编辑"
        if (existing.any { it.id != id && it.name == name }) return "该文件名已存在"

        val record = RoutingAssetRecord(
            id = id ?: UUID.randomUUID().toString(),
            name = name,
            url = url,
            locked = false,
            addedTime = old?.addedTime ?: System.currentTimeMillis()
        )
        val updated = existing.filterNot { it.id == record.id } + record
        writeSavedAssets(updated)
        if (old != null && (old.name != name || old.url != url)) File(directory, old.name).delete()
        return null
    }

    /** 复制用户选取的本地文件，并将其登记为不可远程更新的资源。 */
    fun importLocalFile(uri: Uri): String? {
        val displayName = appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (column >= 0) cursor.getString(column) else null
            } else null
        }?.takeIf(String::isNotBlank) ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: return "无法读取文件名"
        val name = File(displayName).name
        if (name.isBlank() || name == "." || name == "..") return "无效的文件名"
        if (readSavedAssets().any { it.name == name }) return "该文件名已存在"

        val target = File(directory, name)
        val temporary = File(directory, "$name.importing")
        return runCatching {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: error("无法打开所选文件")
            input.use { source -> temporary.outputStream().use(source::copyTo) }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            val saved = readSavedAssets() + RoutingAssetRecord(
                id = UUID.randomUUID().toString(),
                name = name,
                url = LOCAL_FILE_URL
            )
            writeSavedAssets(saved)
            null
        }.getOrElse { error ->
            temporary.delete()
            error.message ?: "导入本地文件失败"
        }
    }

    fun removeAsset(id: String, name: String) {
        writeSavedAssets(readSavedAssets().filterNot { it.id == id })
        File(directory, File(name).name).delete()
    }

    fun downloadAll(): RoutingAssetDownloadResult {
        val assets = load().assets.filter { it.url != LOCAL_FILE_URL }
        var downloaded = 0
        val failures = mutableListOf<String>()
        assets.forEach { asset ->
            if (download(asset)) downloaded++ else failures += asset.name
        }
        return RoutingAssetDownloadResult(downloaded, failures)
    }

    private fun download(asset: RoutingAssetRecord): Boolean {
        val name = File(asset.name).name
        if (name.isBlank()) return false
        val target = File(directory, name)
        val temporary = File(directory, "$name.download")
        val urls = buildList {
            if (asset.locked) {
                val mirror = when (name) {
                    "geosite.dat", "geoip.dat" ->
                        "https://cdn.jsdelivr.net/gh/${load().source}@release/$name"
                    GEOIP_CN_PRIVATE ->
                        "https://cdn.jsdelivr.net/gh/Loyalsoldier/geoip@release/$name"
                    else -> null
                }
                // v2rayNG README 将 jsDelivr 列为 GitHub 不可达时的镜像，优先使用该镜像减少连接等待。
                mirror?.let(::add)
            }
            add(asset.url)
        }.distinct()
        for (url in urls) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = DOWNLOAD_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.connect()
                check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                connection.inputStream.use { input -> temporary.outputStream().use(input::copyTo) }
                check(temporary.length() > 0L) { "下载文件为空" }
                if (target.exists() && !target.delete()) error("无法替换旧资源")
                check(temporary.renameTo(target)) { "无法保存下载文件" }
                return true
            } catch (_: Exception) {
                temporary.delete()
            } finally {
                connection?.disconnect()
            }
        }
        return false
    }

    private fun readSavedAssets(): List<RoutingAssetRecord> = runCatching {
        val array = JSONArray(preferences.getString(KEY_ASSETS, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val url = item.optString("url").trim()
                if (name.isBlank() || url.isBlank()) continue
                add(
                    RoutingAssetRecord(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = File(name).name,
                        url = url,
                        locked = item.optBoolean("locked", false),
                        addedTime = item.optLong("addedTime", System.currentTimeMillis())
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun writeSavedAssets(assets: List<RoutingAssetRecord>) {
        val json = JSONArray().apply {
            assets.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("url", item.url)
                    put("locked", item.locked)
                    put("addedTime", item.addedTime)
                })
            }
        }
        preferences.edit().putString(KEY_ASSETS, json.toString()).apply()
    }

    private fun defaultUrl(filename: String, source: String): String =
        "https://github.com/$source/releases/latest/download/$filename"

    companion object {
        const val LOCAL_FILE_URL = "file"
        const val ASSET_DIRECTORY = "assets"
        const val BUNDLED_ASSET_DIRECTORY = "routing-geo"
        const val GEOIP_CN_PRIVATE = "geoip-only-cn-private.dat"
        const val GEOIP_CN_PRIVATE_URL =
            "https://raw.githubusercontent.com/Loyalsoldier/geoip/release/$GEOIP_CN_PRIVATE"
        val GEO_SOURCES = listOf(
            "Loyalsoldier/v2ray-rules-dat",
            "runetfreedom/russia-v2ray-rules-dat",
            "Chocolate4U/Iran-v2ray-rules"
        )

        private const val PREFERENCES_NAME = "routing_assets"
        private const val KEY_ASSETS = "assets_json"
        private const val KEY_GEO_SOURCE = "geo_source"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val DOWNLOAD_TIMEOUT_MS = 90_000
        private val BUILTIN_FILES = listOf("geosite.dat", "geoip.dat", GEOIP_CN_PRIVATE)
        private val BUNDLED_ASSETS_LOCK = Any()

        fun assetDirectory(context: Context): File =
            context.applicationContext.getExternalFilesDir(ASSET_DIRECTORY)
                ?: context.applicationContext.getDir(ASSET_DIRECTORY, Context.MODE_PRIVATE)
    }
}
