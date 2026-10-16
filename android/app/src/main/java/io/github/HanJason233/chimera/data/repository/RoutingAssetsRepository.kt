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

/** 管理 Xray 使用的 geo 数据库，与 v2rayNG 的 assets 目录及配置格式保持一致。 */
internal class RoutingAssetsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    val directory: File = appContext.getDir(ASSET_DIRECTORY, Context.MODE_PRIVATE).apply { mkdirs() }

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
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(asset.url).openConnection() as HttpURLConnection
            connection.connectTimeout = DOWNLOAD_TIMEOUT_MS
            connection.readTimeout = DOWNLOAD_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.connect()
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            connection.inputStream.use { input -> temporary.outputStream().use(input::copyTo) }
            check(temporary.length() > 0L) { "下载文件为空" }
            if (target.exists() && !target.delete()) error("无法替换旧资源")
            check(temporary.renameTo(target)) { "无法保存下载文件" }
            true
        } catch (_: Exception) {
            temporary.delete()
            false
        } finally {
            connection?.disconnect()
        }
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
        private const val DOWNLOAD_TIMEOUT_MS = 15_000
        private val BUILTIN_FILES = listOf("geosite.dat", "geoip.dat", GEOIP_CN_PRIVATE)
    }
}
