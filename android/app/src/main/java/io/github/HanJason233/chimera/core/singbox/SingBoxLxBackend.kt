package io.github.HanJason233.chimera.core.singbox

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import io.github.HanJason233.chimera.core.CoreBackend
import io.github.HanJason233.chimera.core.CoreCapabilities
import io.github.HanJason233.chimera.core.CoreRequest
import io.github.HanJason233.chimera.core.CoreSession
import io.github.HanJason233.chimera.core.PreparedCore
import io.github.HanJason233.chimera.data.model.NodeType
import io.github.HanJason233.chimera.data.model.ProxyCore
import io.github.HanJason233.chimera.platform.UnderlayController
import io.github.HanJason233.chimera.platform.awaitExit
import io.github.HanJason233.chimera.platform.isRunningCompat
import io.github.HanJason233.chimera.platform.terminateCompat
import io.github.HanJason233.chimera.util.RuleManager
import io.github.HanJason233.chimera.util.SingBoxConfigGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** sing-box-lx 后端适配层：复用 sing-box 的 SOCKS/HEV 接口，并启用 LX 扩展配置。 */
internal class SingBoxLxBackend : CoreBackend {
    override val id = ProxyCore.SING_BOX_LX
    override val capabilities = CoreCapabilities(
        NodeType.entries.filterNot { it == NodeType.UNKNOWN }.toSet()
    )

    override suspend fun prepare(context: Context, request: CoreRequest): PreparedCore = withContext(Dispatchers.IO) {
        val generator = SingBoxConfigGenerator(allowLxExtensions = true)
        val config = if (request.isVpn) {
            RuleManager.ensureRuleSets(context)
            val settings = request.settings!!
            generator.generateConfig(
                listOf(request.node), request.node.id, settings.proxyMode,
                settings.ipv6RoutingMode, internalSocksEndpoint = request.endpoint,
                lanInbound = settings.lanInbound,
                dnsSettings = settings.dnsSettings,
                smartRoutingSettings = settings.smartRoutingSettings,
                hysteriaUpMbps = request.hysteriaUpMbps,
                hysteriaDownMbps = request.hysteriaDownMbps
            )
                .replace("\"geosite-cn.srs\"", "\"${File(context.filesDir, "sing-box/geosite-cn.srs").absolutePath}\"")
                .replace("\"geoip-cn.srs\"", "\"${File(context.filesDir, "sing-box/geoip-cn.srs").absolutePath}\"")
        } else {
            generator.generateTestConfig(
                request.node, request.socksPort,
                request.hysteriaUpMbps, request.hysteriaDownMbps
            )
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
        SingBoxLxNativeSession(context, prepared.request.endpoint, onFailure).start(prepared.config)
    }

    private fun validate(context: Context, config: String) {
        val checkId = UUID.randomUUID()
        val file = File(context.cacheDir, "sing-box-lx-check-$checkId.json")
        val outputFile = File(context.cacheDir, "sing-box-lx-check-$checkId.log")
        var process: Process? = null
        try {
            file.writeText(config, Charsets.UTF_8)
            val executable = File(context.applicationInfo.nativeLibraryDir, "libsingboxlx.so")
            check(executable.isFile) { "未找到 sing-box-lx 原生运行器" }
            process = ProcessBuilder(
                executable.absolutePath,
                "--check",
                "--config", file.absolutePath,
                "--directory", context.filesDir.absolutePath
            ).directory(context.filesDir)
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .start()
            val finished = process.awaitExit(15, TimeUnit.SECONDS)
            val exitCode = if (finished) process.exitValue() else null
            if (!finished || exitCode != 0) {
                val output = outputFile.takeIf { it.isFile }
                    ?.readText(Charsets.UTF_8)
                    .orEmpty()
                // 运行器以 JSON 事件输出校验错误，提取原因后写入 Android 日志便于设备排查。
                val coreMessage = output.lineSequence().mapNotNull { line ->
                    runCatching {
                        JsonParser.parseString(line)
                            .takeIf { it.isJsonObject }
                            ?.asJsonObject
                            ?.takeIf { it.get("event")?.asString == "error" }
                            ?.get("message")
                            ?.asString
                    }.getOrNull()
                }.lastOrNull()
                val detail = (coreMessage ?: output.lineSequence().lastOrNull(String::isNotBlank))
                    ?.takeLast(600)
                    ?.replace(Regex("(?i)(password|private[_-]?key|secret|token|uuid|short[_-]?id)(\\s*[=:]\\s*)[^,\\s]+")) {
                        "${it.groupValues[1]}${it.groupValues[2]}<redacted>"
                    }
                val reason = when {
                    !finished -> "校验进程超时"
                    !detail.isNullOrBlank() -> detail
                    else -> "退出码 $exitCode（没有错误详情）"
                }
                Log.println(
                    Log.ERROR,
                    TAG,
                    "sing-box-lx config validation failed: finished=$finished exit=$exitCode output=$reason"
                )
                error("sing-box-lx 配置校验失败：$reason")
            }
        } finally {
            process?.let {
                if (it.isRunningCompat) {
                    it.terminateCompat(force = true)
                    it.awaitExit(3, TimeUnit.SECONDS)
                }
            }
            file.delete()
            outputFile.delete()
        }
    }

    private companion object {
        const val TAG = "SingBoxLxBackend"
    }
}
