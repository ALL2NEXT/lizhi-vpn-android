package xyz.a202132.app.core.singbox

import android.content.Context
import io.nekohasekai.libbox.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.a202132.app.core.*
import xyz.a202132.app.core.hev.HevSocksTunAdapter
import xyz.a202132.app.data.model.NodeType
import xyz.a202132.app.data.model.ProxyCore
import xyz.a202132.app.data.model.TunStack
import xyz.a202132.app.platform.AndroidVpnHost
import xyz.a202132.app.service.BoxPlatformInterface
import xyz.a202132.app.service.HeadlessPlatformInterface
import xyz.a202132.app.util.RuleManager
import xyz.a202132.app.util.SingBoxConfigGenerator
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class SingBoxBackend : CoreBackend {
    override val id = ProxyCore.SING_BOX
    override val capabilities = CoreCapabilities(NodeType.entries.filterNot { it == NodeType.UNKNOWN }.toSet(), TunStack.entries.toSet())

    override suspend fun prepare(context: Context, request: CoreRequest): PreparedCore = withContext(Dispatchers.IO) {
        val generator = SingBoxConfigGenerator()
        val config = if (request.isVpn) {
            RuleManager.ensureRuleSets(context)
            val settings = request.settings!!
            generator.generateConfig(listOf(request.node), request.node.id, settings.proxyMode,
                settings.ipv6RoutingMode, internalSocksPort = request.socksPort,
                hysteriaUpMbps = request.hysteriaUpMbps, hysteriaDownMbps = request.hysteriaDownMbps,
                tunStack = settings.tunStack,
                includeTun = request.tunMode == CoreTunMode.NATIVE)
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
        host: AndroidVpnHost?, onFailure: (String) -> Unit): CoreSession = withContext(Dispatchers.IO) {
        require(prepared.core == id)
        val directory = File(context.cacheDir, "sb-${UUID.randomUUID().toString().take(8)}")
        val session = Session(prepared.request.socksPort, directory)
        try {
            synchronized(setupLock) {
                setup(context, directory)
                val platform: PlatformInterface = if (prepared.request.isVpn) {
                    BoxPlatformInterface(requireNotNull(host).vpnService, host)
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
                if (prepared.request.isVpn) session.connectStats()
            }
            if (prepared.request.isVpn && prepared.request.tunMode == CoreTunMode.HEV_SOCKS5) {
                try {
                    val adapter = HevSocksTunAdapter().start(context, requireNotNull(host),
                        prepared.request.socksPort, prepared.request.settings!!.ipv6RoutingMode, onFailure)
                    combineCoreSession(session, adapter)
                } catch (error: Throwable) {
                    session.close()
                    throw error
                }
            } else session
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    private class Session(override val socksPort: Int, private val directory: File) : CoreSession {
        override val core = ProxyCore.SING_BOX
        var server: CommandServer? = null
        private var client: CommandClient? = null
        private val stopped = AtomicBoolean()
        @Volatile private var total: CoreTraffic? = null

        fun connectStats() {
            val options = CommandClientOptions().apply {
                addCommand(Libbox.CommandStatus)
                statusInterval = 1_000_000_000L
            }
            client = CommandClient(object : CommandClientHandler {
                override fun connected() {}
                override fun disconnected(message: String?) {}
                override fun writeStatus(message: StatusMessage?) {
                    if (message?.trafficAvailable == true && !stopped.get()) {
                        total = CoreTraffic(message.uplinkTotal.coerceAtLeast(0), message.downlinkTotal.coerceAtLeast(0))
                    }
                }
                override fun writeGroups(message: OutboundGroupIterator?) {}
                override fun clearLogs() {}
                override fun setDefaultLogLevel(level: Int) {}
                override fun writeLogs(messageList: LogIterator?) {}
                override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
                override fun updateClashMode(newMode: String?) {}
                override fun writeConnectionEvents(events: ConnectionEvents?) {}
            }, options)
            client!!.connect()
        }

        override fun traffic(): CoreTraffic? = total
        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            runCatching { client?.disconnect() }
            client = null
            runCatching { server?.closeService() }
            runCatching { server?.close() }
            server = null
            // The directory is generated locally for this session; no user files are stored here.
            directory.listFiles()?.forEach { if (it.isFile) it.delete() }
            directory.delete()
        }
    }

    private companion object {
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
