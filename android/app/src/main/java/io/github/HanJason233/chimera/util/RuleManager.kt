package io.github.HanJason233.chimera.util

import android.content.Context
import android.util.Log
import io.github.HanJason233.chimera.data.repository.RoutingAssetFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import io.github.HanJason233.chimera.network.NetworkClient
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class SingBoxRuleSetUpdateResult(
    val updated: List<String>,
    val failed: List<String>,
    val skipped: Boolean = false
)

/**
 * 规则集管理器
 * 管理 sing-box rule_set 所需的 .srs 规则集文件
 * 
 * 流程：
 * 1. APP 启动时后台尝试下载最新规则集
 * 2. VPN 启动前调用 ensureRuleSets() 确保文件存在（从 assets 拷贝兜底）
 */
object RuleManager {
    private const val TAG = "RuleManager"
    private const val PREFS_NAME = "rule_manager"
    private const val LAST_SUCCESSFUL_UPDATE = "last_successful_update"
    private const val UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val INSTALLED_RULE_SET_TAGS = "installed_rule_set_tags"
    private const val SELECTED_PROVIDER = "selected_rule_set_provider"
    private val downloadClient by lazy {
        NetworkClient.withUserAgent(OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // .srs 规则集下载地址 (jsDelivr CDN 加速)
    private const val GEOIP_CN_URL = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geoip@rule-set/geoip-cn.srs"
    private const val GEOSITE_CN_URL = "https://testingcf.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-cn.srs"

    // 文件名常量
    const val GEOIP_CN_FILE = "geoip-cn.srs"
    const val GEOSITE_CN_FILE = "geosite-cn.srs"
    private const val AUTO_UPDATE_ENABLED = "auto_update_enabled"
    val RULE_SET_FILES = listOf(GEOIP_CN_FILE, GEOSITE_CN_FILE)

    internal fun selectedProvider(context: Context): SingBoxRuleSetProvider {
        val id = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SELECTED_PROVIDER, SingBoxRuleSetProvider.SAGER_NET.id)
        return SingBoxRuleSetCatalog.providers.firstOrNull { it.id == id }
            ?: SingBoxRuleSetProvider.SAGER_NET
    }

    internal fun setSelectedProvider(context: Context, provider: SingBoxRuleSetProvider) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SELECTED_PROVIDER, provider.id)
            .apply()
    }

    internal fun ruleSetDirectory(context: Context): File = File(context.filesDir, "sing-box")

    /** 所有目录资源的 URL；未下载的目录规则也可由 sing-box 按需拉取。 */
    internal fun ruleSetUrls(): Map<String, String> =
        SingBoxRuleSetCatalog.entries.associate { it.tag to it.url }

    internal fun isAutoUpdateEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(AUTO_UPDATE_ENABLED, true)

    internal fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AUTO_UPDATE_ENABLED, enabled)
            .apply()
    }

    internal fun ruleSetFileInfo(context: Context): Map<String, RoutingAssetFileInfo> {
        val workDir = ruleSetDirectory(context)
        val fileNames = (RULE_SET_FILES + SingBoxRuleSetCatalog.entries.map { it.fileName }).distinct()
        return fileNames.mapNotNull { name ->
            File(workDir, name).takeIf(File::isFile)?.let { file ->
                name to RoutingAssetFileInfo(file.length(), file.lastModified())
            }
        }.toMap()
    }

    /**
     * 确保规则集文件存在于 sing-box 工作目录中
     * 如果文件不存在或大小异常，从 assets 拷贝默认版本
     * 应在 VPN 启动前调用（同步，快速）
     */
    suspend fun ensureRuleSets(context: Context) {
        withContext(Dispatchers.IO) {
            val workDir = ruleSetDirectory(context)
            if (!workDir.exists()) {
                workDir.mkdirs()
            }

            val geoipFile = File(workDir, GEOIP_CN_FILE)
            val geositeFile = File(workDir, GEOSITE_CN_FILE)

            if (!geoipFile.exists() || geoipFile.length() < 1024) {
                val ok = copyFromAssets(context, "rule-sets/$GEOIP_CN_FILE", geoipFile)
                if (!ok || !geoipFile.exists() || geoipFile.length() < 1024) {
                    throw IllegalStateException("Missing required rule set: $GEOIP_CN_FILE")
                }
            }

            if (!geositeFile.exists() || geositeFile.length() < 1024) {
                val ok = copyFromAssets(context, "rule-sets/$GEOSITE_CN_FILE", geositeFile)
                if (!ok || !geositeFile.exists() || geositeFile.length() < 1024) {
                    throw IllegalStateException("Missing required rule set: $GEOSITE_CN_FILE")
                }
            }
        }
    }

    /**
     * 后台下载/更新规则集文件
     * 下载成功则替换现有文件，失败则保留现有文件不影响使用
     * 应在 APP 启动时后台调用（异步，不阻塞）
     */
    internal suspend fun updateRuleSets(
        context: Context,
        force: Boolean = false
    ): SingBoxRuleSetUpdateResult {
        return withContext(Dispatchers.IO) {
            try {
                val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val lastUpdate = preferences.getLong(LAST_SUCCESSFUL_UPDATE, 0L)
                if (!force && !preferences.getBoolean(AUTO_UPDATE_ENABLED, true)) {
                    Log.i(TAG, "Automatic rule set updates are disabled")
                    return@withContext SingBoxRuleSetUpdateResult(emptyList(), emptyList(), skipped = true)
                }
                if (!force && now - lastUpdate in 0 until UPDATE_INTERVAL_MS) {
                    Log.d(TAG, "Rule sets checked recently; skipping startup download")
                    return@withContext SingBoxRuleSetUpdateResult(emptyList(), emptyList(), skipped = true)
                }

                val workDir = ruleSetDirectory(context).apply { mkdirs() }
                val targets = updateTargets(context).map { (name, url) ->
                    Triple(name, url, File(workDir, name))
                }
                val updated = mutableListOf<String>()
                val failed = mutableListOf<String>()
                targets.forEach { (name, url, file) ->
                    Log.i(TAG, "Updating $name...")
                    if (downloadToFile(url, file)) {
                        updated += name
                    } else {
                        Log.w(TAG, "Failed to update $name, keeping existing file")
                        failed += name
                    }
                }

                if (failed.isEmpty()) {
                    preferences.edit().putLong(LAST_SUCCESSFUL_UPDATE, now).apply()
                    Log.i(TAG, "All rule sets updated successfully")
                }
                SingBoxRuleSetUpdateResult(updated, failed)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update rule sets", e)
                SingBoxRuleSetUpdateResult(emptyList(), updateTargets(context).map { it.first })
            }
        }
    }

    /** 下载并登记一条来源目录规则，供自动更新和 sing-box 分流配置使用。 */
    internal fun installRuleSet(context: Context, entry: SingBoxRuleSetCatalogEntry): Boolean {
        if (SingBoxRuleSetCatalog.find(entry.tag) != entry) return false
        val directory = ruleSetDirectory(context).apply { mkdirs() }
        val target = File(directory, entry.fileName)
        if (!downloadToFile(entry.url, target)) return false
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val installed = preferences.getStringSet(INSTALLED_RULE_SET_TAGS, emptySet()).orEmpty().toMutableSet()
        installed += entry.tag
        preferences.edit().putStringSet(INSTALLED_RULE_SET_TAGS, installed).apply()
        return true
    }

    /** 更新单条已登记规则。 */
    internal fun updateRuleSet(context: Context, entry: SingBoxRuleSetCatalogEntry): Boolean =
        SingBoxRuleSetCatalog.find(entry.tag) == entry &&
            downloadToFile(entry.url, File(ruleSetDirectory(context).apply { mkdirs() }, entry.fileName))

    /** 删除单条目录规则及其自动更新登记。 */
    internal fun removeRuleSet(context: Context, entry: SingBoxRuleSetCatalogEntry) {
        if (SingBoxRuleSetCatalog.find(entry.tag) != entry) return
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val installed = preferences.getStringSet(INSTALLED_RULE_SET_TAGS, emptySet()).orEmpty()
            .toMutableSet()
        installed -= entry.tag
        preferences.edit().putStringSet(INSTALLED_RULE_SET_TAGS, installed).apply()
        File(ruleSetDirectory(context), entry.fileName).delete()
    }

    internal fun removeBuiltInRuleSet(context: Context, fileName: String) {
        if (fileName !in RULE_SET_FILES) return
        File(ruleSetDirectory(context), fileName).delete()
    }

    private fun updateTargets(context: Context): List<Pair<String, String>> {
        val installedTags = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(INSTALLED_RULE_SET_TAGS, emptySet()).orEmpty().toSet()
        val directory = ruleSetDirectory(context)
        val managed = SingBoxRuleSetCatalog.entries
            .filter { it.tag in installedTags || File(directory, it.fileName).isFile }
            .map { it.fileName to it.url }
        return listOf(
            GEOIP_CN_FILE to GEOIP_CN_URL,
            GEOSITE_CN_FILE to GEOSITE_CN_URL
        ) + managed
    }

    /**
     * 从 assets 拷贝默认规则集文件
     */
    private fun copyFromAssets(context: Context, assetPath: String, targetFile: File): Boolean {
        return try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Copied default ${targetFile.name} from assets (${targetFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy ${targetFile.name} from assets", e)
            false
        }
    }

    /**
     * 下载文件到目标位置
     * 先下载到临时文件，成功后再替换，避免下载失败导致文件损坏
     */
    private fun downloadToFile(urlStr: String, targetFile: File): Boolean {
        val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")
        try {
            downloadClient.newCall(Request.Builder().url(urlStr).build()).execute().use { response ->
                if (response.isSuccessful) {
                    checkNotNull(response.body).byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output, bufferSize = 8192)
                        }
                    }

                    // 校验下载文件大小
                    if (tempFile.length() < 64) {
                        Log.e(TAG, "Downloaded file too small: ${tempFile.length()} bytes")
                        tempFile.delete()
                        return false
                    }

                    // 原子替换
                    android.system.Os.rename(tempFile.absolutePath, targetFile.absolutePath)
                    Log.i(TAG, "Downloaded ${targetFile.name} successfully (${targetFile.length()} bytes)")
                    return true
                } else {
                    Log.e(TAG, "Download failed: HTTP " + response.code)
                    tempFile.delete()
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error for $urlStr", e)
            tempFile.delete()
            return false
        }
    }
}
