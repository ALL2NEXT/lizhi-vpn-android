package io.github.HanJason233.chimera.core.xray

import com.google.gson.JsonParser
import io.github.HanJason233.chimera.platform.isRunningCompat
import io.github.HanJason233.chimera.platform.awaitExit
import io.github.HanJason233.chimera.platform.terminateCompat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.HanJason233.chimera.core.*
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.ProtocolCoreSettings
import io.github.HanJason233.chimera.data.model.ProxyCore
import io.github.HanJason233.chimera.data.repository.RoutingAssetsRepository
import io.github.HanJason233.chimera.platform.UnderlayController
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class XrayBackend : CoreBackend {
    override val id = ProxyCore.XRAY
    override val capabilities = CoreCapabilities(ProtocolCoreSettings.XRAY_CAPABLE_PROTOCOLS.toSet())

    override fun supports(node: Node): Boolean =
        node.type in capabilities.protocols && CoreCompatibility.xraySupports(node)

    override suspend fun prepare(context: Context, request: CoreRequest): PreparedCore = withContext(Dispatchers.IO) {
        val config = XrayVpnConfig.generate(request)
        val assetRepository = RoutingAssetsRepository(context)
        assetRepository.initializeBundledAssets()
        val assetDirectory = assetRepository.directory
        val requiredAssets = buildList {
            if (config.contains("geosite:")) add("geosite.dat")
            if (config.contains("geoip:")) add("geoip.dat")
            if (config.contains("ext:geoip-only-cn-private.dat:")) {
                add(RoutingAssetsRepository.GEOIP_CN_PRIVATE)
            }
        }
        val missingAssets = requiredAssets.distinct().filterNot { name ->
            File(assetDirectory, name).let { it.isFile && it.length() > 0L }
        }
        check(missingAssets.isEmpty()) {
            "Xray 路由规则缺少 Geo 数据库：${missingAssets.joinToString()}，请到设置中的路由资源文件下载或导入"
        }
        val temporary = File(context.cacheDir, "xray-check-${UUID.randomUUID()}.json")
        val outputFile = File(context.cacheDir, "xray-check-${UUID.randomUUID()}.log")
        var process: Process? = null
        try {
            temporary.writeText(config, Charsets.UTF_8)
            val executable = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
            check(executable.isFile) { "未找到 Xray 原生运行器" }
            val processBuilder = ProcessBuilder(
                executable.absolutePath,
                "--check",
                "--config",
                temporary.absolutePath
            ).redirectErrorStream(true)
            processBuilder.environment()["XRAY_LOCATION_ASSET"] =
                assetDirectory.absolutePath
            process = processBuilder.redirectOutput(outputFile).start()
            val finished = process.awaitExit(15, TimeUnit.SECONDS)
            val exitCode = if (finished) process.exitValue() else null
            if (!finished || exitCode != 0) {
                // 保留 Xray 核心原始校验信息，直接指出不兼容的字段或缺失的 Geo 数据库。
                val output = outputFile.takeIf(File::isFile)
                    ?.readText(Charsets.UTF_8)
                    ?.trim()
                    .orEmpty()
                // Xray runner 会把核心输出封装成 JSON 事件，优先展示其中真正的错误消息。
                val detail = output.lineSequence().mapNotNull { line ->
                    runCatching {
                        JsonParser.parseString(line).asJsonObject
                            .get("message")?.takeIf { it.isJsonPrimitive }?.asString
                            ?.takeIf(String::isNotBlank)
                    }.getOrNull()
                }.firstOrNull() ?: output.ifBlank { "Xray 未提供错误详情" }
                error("Xray 配置校验失败：${detail.takeLast(1200)}")
            }
        } finally {
            process?.let { if (it.isRunningCompat) { it.terminateCompat(force = true); it.awaitExit(3, TimeUnit.SECONDS) } }
            temporary.delete()
            outputFile.delete()
        }
        PreparedCore(id, request, config)
    }

    override suspend fun start(context: Context, scope: CoroutineScope, prepared: PreparedCore,
        underlay: UnderlayController?, onFailure: (String) -> Unit): CoreSession {
        require(prepared.core == id)
        return XrayNativeSession(context, prepared.request.endpoint, onFailure).start(prepared.config)
    }
}
