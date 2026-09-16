package xyz.a202132.app.core.singbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.a202132.app.core.CoreBackend
import xyz.a202132.app.core.CoreCapabilities
import xyz.a202132.app.core.CoreRequest
import xyz.a202132.app.core.CoreSession
import xyz.a202132.app.core.PreparedCore
import xyz.a202132.app.data.model.NodeType
import xyz.a202132.app.data.model.ProxyCore
import xyz.a202132.app.platform.UnderlayController
import xyz.a202132.app.platform.awaitExit
import xyz.a202132.app.platform.isRunningCompat
import xyz.a202132.app.platform.terminateCompat
import xyz.a202132.app.util.RuleManager
import xyz.a202132.app.util.SingBoxConfigGenerator
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class SingBoxBackend : CoreBackend {
    override val id = ProxyCore.SING_BOX
    override val capabilities = CoreCapabilities(NodeType.entries.filterNot { it == NodeType.UNKNOWN }.toSet())

    override suspend fun prepare(context: Context, request: CoreRequest): PreparedCore = withContext(Dispatchers.IO) {
        val config = SingBoxConfigGenerator().let { generator ->
            if (request.isVpn) {
                RuleManager.ensureRuleSets(context)
                val settings = request.settings!!
                generator.generateConfig(
                    listOf(request.node), request.node.id, settings.proxyMode,
                    settings.ipv6RoutingMode, internalSocksEndpoint = request.endpoint,
                    lanInbound = settings.lanInbound,
                    dnsSettings = settings.dnsSettings,
                    smartRoutingSettings = settings.smartRoutingSettings,
                    hysteriaUpMbps = request.hysteriaUpMbps, hysteriaDownMbps = request.hysteriaDownMbps,
                )
                    .replace("\"geosite-cn.srs\"", "\"${File(context.filesDir, "sing-box/geosite-cn.srs").absolutePath}\"")
                    .replace("\"geoip-cn.srs\"", "\"${File(context.filesDir, "sing-box/geoip-cn.srs").absolutePath}\"")
            } else {
                generator.generateTestConfig(
                    request.node, request.socksPort,
                    request.hysteriaUpMbps, request.hysteriaDownMbps
                )
            }
        }
        validate(context, config)
        PreparedCore(id, request, config)
    }

    override suspend fun start(
        context: Context,
        scope: CoroutineScope,
        prepared: PreparedCore,
        underlay: UnderlayController?,
        onFailure: (String) -> Unit
    ): CoreSession = withContext(Dispatchers.IO) {
        require(prepared.core == id)
        SingBoxNativeSession(context, prepared.request.endpoint, onFailure).start(prepared.config)
    }

    private fun validate(context: Context, config: String) {
        val file = File(context.cacheDir, "sing-box-check-${UUID.randomUUID()}.json")
        var process: Process? = null
        try {
            file.writeText(config, Charsets.UTF_8)
            val executable = File(context.applicationInfo.nativeLibraryDir, "libsingbox.so")
            check(executable.isFile) { "未找到 sing-box 原生运行器" }
            process = ProcessBuilder(
                executable.absolutePath,
                "--check",
                "--config", file.absolutePath,
                "--directory", context.filesDir.absolutePath
            ).directory(context.filesDir).redirectErrorStream(true).start()
            val finished = process.awaitExit(15, TimeUnit.SECONDS)
            val exitCode = if (finished) process.exitValue() else null
            val output = if (finished) {
                process.inputStream.bufferedReader().use { it.readText() }.trim()
            } else {
                ""
            }
            if (!finished || exitCode != 0) {
                val detail = output.takeLast(2000).ifBlank { "<no runner output>" }
                Log.e(TAG, "sing-box config validation failed: finished=$finished exit=$exitCode output=$detail")
                error("sing-box 配置校验失败，请检查节点协议与传输参数")
            }
        } finally {
            process?.let {
                if (it.isRunningCompat) {
                    it.terminateCompat(force = true)
                    it.awaitExit(3, TimeUnit.SECONDS)
                }
            }
            file.delete()
        }
    }

    private companion object {
        const val TAG = "SingBoxBackend"
    }
}
