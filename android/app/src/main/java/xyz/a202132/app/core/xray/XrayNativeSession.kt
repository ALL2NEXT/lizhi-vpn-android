package xyz.a202132.app.core.xray

import xyz.a202132.app.platform.isRunningCompat
import xyz.a202132.app.platform.awaitExit
import xyz.a202132.app.platform.terminateCompat

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import xyz.a202132.app.core.CoreSession
import xyz.a202132.app.core.LocalSocksEndpoint
import xyz.a202132.app.core.NativeProcessOutput
import xyz.a202132.app.data.model.ProxyCore
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A separate Go runtime for Xray; HEV owns the Android TUN data plane. */
internal class XrayNativeSession(
    private val context: Context,
    override val endpoint: LocalSocksEndpoint,
    private val onFailure: (String) -> Unit
) : CoreSession {
    override val core = ProxyCore.XRAY
    private val token = UUID.randomUUID().toString()
    private val socketName = "firefly-xray-$token"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = AtomicBoolean()
    private val ready = CompletableDeferred<Unit>()
    private val output = NativeProcessOutput()
    private val connections = Collections.synchronizedSet(mutableSetOf<LocalSocket>())
    private var server: LocalServerSocket? = null
    private var process: Process? = null
    private var configFile: File? = null

    suspend fun start(config: String): XrayNativeSession = withContext(Dispatchers.IO) {
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
            val file = File(context.cacheDir, "xray-$token.json")
            configFile = file
            file.writeText(config, Charsets.UTF_8)
            val executable = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
            check(executable.isFile) { "未找到 Xray 原生运行器" }
            val started = ProcessBuilder(executable.absolutePath, "--config", file.absolutePath,
                "--control", "@$socketName", "--token", token)
                .directory(context.filesDir).redirectErrorStream(true).start()
            process = started
            scope.launch {
                val readError = output.read(started.inputStream) { line ->
                    val event = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
                    when (event?.get("event")?.asString) {
                        "ready" -> ready.complete(Unit)
                        "error" -> ready.completeExceptionally(IllegalStateException("Xray 原生运行失败"))
                    }
                }
                // Never wait for exit here: a broken pipe can precede process
                // exit, and the failure callback is responsible for stopping it.
                val exitCode = runCatching { started.exitValue() }.getOrDefault(-1)
                ready.completeExceptionally(IllegalStateException("Xray 启动失败 ($exitCode)"))
                if (!stopped.get()) notifyFailure(if (readError == null) "Xray 意外退出 ($exitCode)"
                    else "Xray 输出通道已关闭 (${readError.javaClass.simpleName})")
            }
            withTimeout(15_000) { ready.await() }
            check(started.isRunningCompat) { "Xray 在就绪后退出" }
            file.delete()
            this@XrayNativeSession
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
                // Bound the command header before blocking on the WATCH channel.
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
                        input.read() // EOF closes the parent-liveness channel.
                    }
                    else -> Unit
                }
            }
        } catch (_: Exception) {
            // Closing the session interrupts pending handshakes. No FD survives socket.use.
        } finally {
            connections.remove(socket)
        }
    }

    private fun notifyFailure(message: String) {
        try { onFailure(message) } catch (error: Exception) {
            Log.w("XrayNativeSession", "Failure callback failed: ${error.javaClass.simpleName}")
        }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) {
            check(process?.isRunningCompat != true) { "Xray 进程仍在停止" }
            return
        }
        runCatching { server?.close() }
        server = null
        synchronized(connections) { connections.toList() }.forEach { runCatching { it.close() } }
        val running = process
        if (running != null) {
            runCatching {
                if (!running.awaitExit(500, TimeUnit.MILLISECONDS)) running.terminateCompat()
                if (!running.awaitExit(3, TimeUnit.SECONDS)) {
                    running.terminateCompat(force = true)
                    check(running.awaitExit(3, TimeUnit.SECONDS)) { "Xray 未能停止" }
                }
            }.onFailure { notifyFailure("Xray 停止失败: ${it.javaClass.simpleName}") }
            output.awaitDrain(1, TimeUnit.SECONDS)
        }
        scope.cancel()
        configFile?.delete()
        configFile = null
        check(running?.isRunningCompat != true) { "Xray 未能停止" }
        process = null
    }
}
