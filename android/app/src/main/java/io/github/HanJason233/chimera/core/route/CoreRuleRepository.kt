package io.github.HanJason233.chimera.core.route

import io.github.HanJason233.chimera.platform.isRunningCompat
import io.github.HanJason233.chimera.platform.awaitExit
import io.github.HanJason233.chimera.platform.terminateCompat

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.HanJason233.chimera.util.RuleManager
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Shared positive domain/IP policy, derived from the same updated SRS files as sing-box. */
internal object CoreRuleRepository {
    private val mutex = Mutex()

    suspend fun load(context: Context): JsonObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            RuleManager.ensureRuleSets(context)
            val source = File(context.filesDir, "sing-box")
            val site = File(source, RuleManager.GEOSITE_CN_FILE).readBytes()
            val ip = File(source, RuleManager.GEOIP_CN_FILE).readBytes()
            val digest = MessageDigest.getInstance("SHA-256").apply { update(site); update(ip) }
                .digest().joinToString("") { "%02x".format(it) }
            val directory = File(context.filesDir, "core-rules").apply { mkdirs() }
            val cached = File(directory, "routes-$digest.json")
            if (!cached.isFile) {
                // Snapshot inputs so an asynchronous rule update cannot change this conversion.
                val siteFile = File(directory, "geosite.tmp")
                val ipFile = File(directory, "geoip.tmp")
                val output = File(directory, "routes.tmp")
                var process: Process? = null
                try {
                    siteFile.writeBytes(site)
                    ipFile.writeBytes(ip)
                    val executable = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
                    process = ProcessBuilder(executable.absolutePath,
                        "--export-rules", output.absolutePath, "--geosite", siteFile.absolutePath,
                        "--geoip", ipFile.absolutePath).redirectErrorStream(true).start()
                    check(process.awaitExit(20, TimeUnit.SECONDS) && process.exitValue() == 0) {
                        "公共分流规则转换失败，请更新规则后重试"
                    }
                    check(output.renameTo(cached)) { "无法保存公共分流规则" }
                    directory.listFiles()?.filter { it.name.startsWith("routes-") && it != cached }
                        ?.forEach { it.delete() }
                } finally {
                    process?.let { if (it.isRunningCompat) { it.terminateCompat(force = true); it.awaitExit(3, TimeUnit.SECONDS) } }
                    siteFile.delete(); ipFile.delete(); output.delete()
                }
            }
            cached.reader().use { JsonParser.parseReader(it).asJsonObject }
        }
    }
}
