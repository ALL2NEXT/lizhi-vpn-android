package io.github.HanJason233.chimera.core.hev

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import io.github.HanJason233.chimera.core.*
import io.github.HanJason233.chimera.data.model.IpFamilyRoutingSettings
import io.github.HanJason233.chimera.platform.TunProvider
import io.github.HanJason233.chimera.platform.awaitExit
import io.github.HanJason233.chimera.platform.isRunningCompat
import io.github.HanJason233.chimera.platform.terminateCompat
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HEV is the Android VPN data plane, not a proxy core. It turns the Android
 * TUN into SOCKS5 requests to the selected core's loopback inbound.
 */
internal class HevSocksTunAdapter : TunAdapter {
    override suspend fun start(
        context: Context,
        tunProvider: TunProvider,
        endpoint: LocalSocksEndpoint,
        ipFamilySettings: IpFamilyRoutingSettings,
        onFailure: (String) -> Unit
    ): TunAdapterSession {
        tunProvider.openTun(HevSocksTunConfig.tunnel(ipFamilySettings))
        return try {
            HevNativeSession(context, tunProvider, onFailure)
                .start(HevSocksTunConfig.render(endpoint))
        } catch (error: Throwable) {
            tunProvider.closeTun()
            throw error
        }
    }
}

private class HevNativeSession(
    private val context: Context,
    private val tunProvider: TunProvider,
    private val onFailure: (String) -> Unit
) : TunAdapterSession {
    private val token = UUID.randomUUID().toString()
    private val socketName = "chimera-hev-$token"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = AtomicBoolean()
    private val ready = CompletableDeferred<Unit>()
    private val output = NativeProcessOutput()
    private val connections = Collections.synchronizedSet(mutableSetOf<LocalSocket>())
    private var server: LocalServerSocket? = null
    private var process: Process? = null
    private var configFile: File? = null
    @Volatile private var total: CoreTraffic? = null

    suspend fun start(config: String): HevNativeSession = withContext(Dispatchers.IO) {
        try {
            server = LocalServerSocket(socketName)
            scope.launch {
                while (isActive && !stopped.get()) {
                    val socket = try { server?.accept() ?: break } catch (_: Exception) { break }
                    synchronized(connections) {
                        if (stopped.get()) runCatching { socket.close() } else connections.add(socket)
                    }
                    if (stopped.get()) break
                    launch { serve(socket) }
                }
            }
            val file = File(context.cacheDir, "hev-$token.yml")
            configFile = file
            file.writeText(config, Charsets.UTF_8)
            val executable = File(context.applicationInfo.nativeLibraryDir, "libhev.so")
            check(executable.isFile) { "未找到 HEV 原生运行器" }
            val started = ProcessBuilder(executable.absolutePath,
                "--config", file.absolutePath,
                "--control", "@$socketName",
                "--token", token)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
            process = started
            scope.launch {
                val readError = output.read(started.inputStream) { line ->
                    val event = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
                    when (event?.get("event")?.asString) {
                        "ready" -> ready.complete(Unit)
                        "traffic" -> total = CoreTraffic(
                            event.get("upload").asLong.coerceAtLeast(0),
                            event.get("download").asLong.coerceAtLeast(0)
                        )
                        "error" -> ready.completeExceptionally(
                            IllegalStateException("HEV 原生运行失败")
                        )
                    }
                }
                val exitCode = runCatching { started.exitValue() }.getOrDefault(-1)
                ready.completeExceptionally(IllegalStateException("HEV 启动失败 ($exitCode)"))
                if (!stopped.get()) notifyFailure(
                    if (readError == null) "HEV 意外退出 ($exitCode)"
                    else "HEV 输出通道已关闭 (${readError.javaClass.simpleName})"
                )
            }
            withTimeout(15_000) { ready.await() }
            check(started.isRunningCompat) { "HEV 在就绪后退出" }
            file.delete()
            this@HevNativeSession
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun serve(socket: LocalSocket) {
        try {
            socket.use {
                socket.soTimeout = 5_000
                if (socket.peerCredentials.uid != android.os.Process.myUid()) return
                val input = socket.inputStream
                val header = StringBuilder()
                while (header.length <= 96) {
                    val value = input.read()
                    if (value < 0) return
                    if (value == 10) break
                    header.append(value.toChar())
                }
                when (header.toString()) {
                    "WATCH $token" -> {
                        socket.soTimeout = 0
                        input.read()
                    }
                    "TUN $token" -> {
                        socket.setFileDescriptorsForSend(arrayOf(tunProvider.tunDescriptor()))
                        socket.outputStream.write(1)
                        socket.outputStream.flush()
                        socket.setFileDescriptorsForSend(null)
                    }
                    else -> Unit
                }
            }
        } catch (_: Exception) {
            // Closing the session interrupts pending handshakes.
        } finally {
            connections.remove(socket)
        }
    }

    override fun traffic(): CoreTraffic? = total

    private fun notifyFailure(message: String) {
        try { onFailure(message) } catch (error: Exception) {
            Log.w("HevNativeSession", "Failure callback failed: ${error.javaClass.simpleName}")
        }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) {
            check(process?.isRunningCompat != true) { "HEV 进程仍在停止" }
            return
        }
        runCatching { server?.close() }
        server = null
        synchronized(connections) { connections.toList() }.forEach { runCatching { it.close() } }
        val running = process
        if (running != null) {
            runCatching {
                // Closing WATCH requests an immediate graceful HEV stop. TERM
                // is only a watchdog after the native cleanup window expires.
                if (!running.awaitExit(3, TimeUnit.SECONDS)) running.terminateCompat()
                if (!running.awaitExit(2, TimeUnit.SECONDS)) {
                    running.terminateCompat(force = true)
                    check(running.awaitExit(3, TimeUnit.SECONDS)) { "HEV 未能停止" }
                }
            }.onFailure { notifyFailure("HEV 停止失败: ${it.javaClass.simpleName}") }
            output.awaitDrain(1, TimeUnit.SECONDS)
        }
        runCatching { tunProvider.closeTun() }
        scope.cancel()
        configFile?.delete()
        configFile = null
        check(running?.isRunningCompat != true) { "HEV 未能停止" }
        process = null
    }
}
