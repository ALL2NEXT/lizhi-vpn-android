package xyz.a202132.app.core.xray

import xyz.a202132.app.platform.isRunningCompat
import xyz.a202132.app.platform.awaitExit
import xyz.a202132.app.platform.terminateCompat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.a202132.app.core.*
import xyz.a202132.app.core.route.CoreRuleRepository
import xyz.a202132.app.core.hev.HevSocksTunAdapter
import xyz.a202132.app.data.model.ProtocolCoreSettings
import xyz.a202132.app.data.model.ProxyCore
import xyz.a202132.app.data.model.ProxyMode
import xyz.a202132.app.platform.AndroidVpnHost
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class XrayBackend : CoreBackend {
    override val id = ProxyCore.XRAY
    override val capabilities = CoreCapabilities(ProtocolCoreSettings.XRAY_CAPABLE_PROTOCOLS.toSet())

    override suspend fun prepare(context: Context, request: CoreRequest): PreparedCore = withContext(Dispatchers.IO) {
        val rules = if (request.settings?.proxyMode == ProxyMode.SMART) {
            CoreRuleRepository.load(context)
        } else null
        val config = XrayVpnConfig.generate(request, rules)
        val temporary = File(context.cacheDir, "xray-check-${UUID.randomUUID()}.json")
        var process: Process? = null
        try {
            temporary.writeText(config)
            val executable = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
            process = ProcessBuilder(executable.absolutePath, "--check", "--config", temporary.absolutePath)
                .redirectErrorStream(true).start()
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
        host: AndroidVpnHost?, onFailure: (String) -> Unit): CoreSession {
        require(prepared.core == id)
        val core = XrayNativeSession(context, prepared.request.socksPort, host, onFailure)
            .start(prepared.config)
        if (prepared.request.isVpn) {
            return try {
                val adapter = HevSocksTunAdapter().start(context, requireNotNull(host),
                    prepared.request.socksPort, prepared.request.settings!!.ipv6RoutingMode, onFailure)
                combineCoreSession(core, adapter)
            } catch (error: Throwable) {
                core.close()
                throw error
            }
        }
        return core
    }
}
