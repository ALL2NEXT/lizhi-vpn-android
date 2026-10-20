package io.github.HanJason233.chimera.core.singbox

import android.content.Context
import android.util.Log
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.HanJason233.chimera.core.CoreBackend
import io.github.HanJason233.chimera.core.CoreCapabilities
import io.github.HanJason233.chimera.core.CoreCompatibility
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
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class SingBoxBackend : CoreBackend {
    override val id = ProxyCore.SING_BOX
    override val capabilities = CoreCapabilities(
        NodeType.entries.filterNot { it == NodeType.UNKNOWN || it == NodeType.MASQUE }.toSet()
    )

    override fun supports(node: io.github.HanJason233.chimera.data.model.Node): Boolean =
        node.type in capabilities.protocols &&
            !CoreCompatibility.requiresXray(node) &&
            !CoreCompatibility.hasUnsupportedSpecialFeatures(node)

    override suspend fun prepare(context: Context, request: CoreRequest): PreparedCore = withContext(Dispatchers.IO) {
        val config = SingBoxConfigGenerator().let { generator ->
            if (request.isVpn) {
                RuleManager.ensureRuleSets(context)
                val settings = request.settings!!
                generator.generateConfig(
                    request.outboundNodes.ifEmpty { listOf(request.node) },
                    request.node.id,
                    settings.proxyMode,
                    settings.ipv6RoutingMode, internalSocksEndpoint = request.endpoint,
                    lanInbound = settings.lanInbound,
                    dnsSettings = settings.dnsSettings,
                    smartRoutingSettings = settings.smartRoutingSettings,
                    hysteriaUpMbps = request.hysteriaUpMbps,
                    hysteriaDownMbps = request.hysteriaDownMbps,
                    ruleSetDirectory = RuleManager.ruleSetDirectory(context),
                    ruleSetUrls = RuleManager.ruleSetUrls(),
                    failoverProbeUrl = request.failoverProbeUrl
                )
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
        val outputFile = File(context.cacheDir, "sing-box-check-${UUID.randomUUID()}.log")
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
            ).directory(context.filesDir)
                .redirectErrorStream(true)
                // 校验失败时保留核心输出，便于定位具体配置字段。
                .redirectOutput(outputFile)
                .start()
            val finished = process.awaitExit(15, TimeUnit.SECONDS)
            val exitCode = if (finished) process.exitValue() else null
            val output = outputFile.takeIf(File::isFile)
                ?.readText(Charsets.UTF_8)
                ?.trim()
                .orEmpty()
            if (!finished || exitCode != 0) {
                // 核心优先输出 JSON 日志，提取 message 后展示关键失败原因。
                val detail = output.lineSequence().mapNotNull { line ->
                    runCatching {
                        JSONObject(line).optString("message").takeIf(String::isNotBlank)
                    }.getOrNull()
                }.firstOrNull() ?: output.takeLast(2000).ifBlank { "<no runner output>" }
                Log.e(TAG, "sing-box config validation failed: finished=$finished exit=$exitCode output=$detail")
                error("sing-box 配置校验失败：${detail.take(240)}")
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
        const val TAG = "SingBoxBackend"
    }
}
