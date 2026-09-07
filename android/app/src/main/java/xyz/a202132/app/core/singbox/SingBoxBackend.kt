package xyz.a202132.app.core.singbox

import android.content.Context
import io.nekohasekai.libbox.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.a202132.app.core.*
import xyz.a202132.app.data.model.NodeType
import xyz.a202132.app.data.model.ProxyCore
import xyz.a202132.app.platform.UnderlayController
import xyz.a202132.app.service.BoxPlatformInterface
import xyz.a202132.app.service.HeadlessPlatformInterface
import xyz.a202132.app.util.RuleManager
import xyz.a202132.app.util.SingBoxConfigGenerator
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class SingBoxBackend : CoreBackend {
    override val id = ProxyCore.SING_BOX
    override val capabilities = CoreCapabilities(NodeType.entries.filterNot { it == NodeType.UNKNOWN }.toSet())

    override suspend fun prepare(context: Context, request: CoreRequest): PreparedCore = withContext(Dispatchers.IO) {
        val generator = SingBoxConfigGenerator()
        val config = if (request.isVpn) {
            RuleManager.ensureRuleSets(context)
            val settings = request.settings!!
            generator.generateConfig(listOf(request.node), request.node.id, settings.proxyMode,
                settings.ipv6RoutingMode, internalSocksEndpoint = request.endpoint,
                hysteriaUpMbps = request.hysteriaUpMbps, hysteriaDownMbps = request.hysteriaDownMbps,
            )
                .replace("\"geosite-cn.srs\"", "\"${File(context.filesDir, "sing-box/geosite-cn.srs").absolutePath}\"")
                .replace("\"geoip-cn.srs\"", "\"${File(context.filesDir, "sing-box/geoip-cn.srs").absolutePath}\"")
        } else generator.generateTestConfig(request.node, request.socksPort, request.hysteriaUpMbps, request.hysteriaDownMbps)
        synchronized(setupLock) {
            setup(context, File(context.filesDir, "sing-box"))
            Libbox.checkConfig(config)
        }
        PreparedCore(id, request, config)
    }

    override suspend fun start(context: Context, scope: CoroutineScope, prepared: PreparedCore,
        underlay: UnderlayController?, onFailure: (String) -> Unit): CoreSession = withContext(Dispatchers.IO) {
        require(prepared.core == id)
        val directory = File(context.cacheDir, "sb-${UUID.randomUUID().toString().take(8)}")
        val session = Session(prepared.request.endpoint, directory)
        try {
            synchronized(setupLock) {
                setup(context, directory)
                val platform: PlatformInterface = if (prepared.request.isVpn) {
                    BoxPlatformInterface(requireNotNull(underlay))
                } else HeadlessPlatformInterface(context)
                session.server = Libbox.newCommandServer(object : CommandServerHandler {
                    override fun serviceStop() { onFailure("sing-box 请求停止") }
                    override fun serviceReload() { onFailure("sing-box 请求重新连接") }
                    override fun getSystemProxyStatus() = SystemProxyStatus()
                    override fun setSystemProxyEnabled(enabled: Boolean) {}
                    override fun writeDebugMessage(message: String?) {}
                }, platform)
                session.server!!.start()
                session.server!!.startOrReloadService(prepared.config, OverrideOptions())
            }
            session
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    private class Session(override val endpoint: LocalSocksEndpoint, private val directory: File) : CoreSession {
        override val core = ProxyCore.SING_BOX
        var server: CommandServer? = null
        private val stopped = AtomicBoolean()
        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            runCatching { server?.closeService() }
            runCatching { server?.close() }
            server = null
            // 该目录仅供当前会话临时使用，不包含用户文件。
            directory.listFiles()?.forEach { if (it.isFile) it.delete() }
            directory.delete()
        }
    }

    // VPN 会话与批量测速必须共用同一把初始化锁，避免并发改写 libbox 全局工作目录。
    internal companion object {
        val setupLock = Any()
        fun setup(context: Context, directory: File) {
            check(directory.isDirectory || directory.mkdirs())
            Libbox.setup(SetupOptions().apply {
                basePath = directory.absolutePath
                workingPath = directory.absolutePath
                tempPath = context.cacheDir.absolutePath
            })
        }
    }
}
