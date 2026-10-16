package io.github.HanJason233.chimera.core.xray

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
        val temporary = File(context.cacheDir, "xray-check-${UUID.randomUUID()}.json")
        var process: Process? = null
        try {
            temporary.writeText(config)
            val executable = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
            val processBuilder = ProcessBuilder(
                executable.absolutePath,
                "--check",
                "--config",
                temporary.absolutePath
            ).redirectErrorStream(true)
            processBuilder.environment()["XRAY_LOCATION_ASSET"] =
                context.getDir("assets", Context.MODE_PRIVATE).absolutePath
            process = processBuilder.start()
            check(process.awaitExit(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
                "Xray 配置校验失败，请检查节点协议与传输参数"
            }
        } finally {
            process?.let { if (it.isRunningCompat) { it.terminateCompat(force = true); it.awaitExit(3, TimeUnit.SECONDS) } }
            temporary.delete()
        }
        PreparedCore(id, request, config)
    }

    override suspend fun start(context: Context, scope: CoroutineScope, prepared: PreparedCore,
        underlay: UnderlayController?, onFailure: (String) -> Unit): CoreSession {
        require(prepared.core == id)
        return XrayNativeSession(context, prepared.request.endpoint, onFailure).start(prepared.config)
    }
}
