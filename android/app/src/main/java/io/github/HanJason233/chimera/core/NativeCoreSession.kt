package io.github.HanJason233.chimera.core

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import io.github.HanJason233.chimera.data.model.ProxyCore
import io.github.HanJason233.chimera.data.repository.RoutingAssetsRepository
import io.github.HanJason233.chimera.platform.awaitExit
import io.github.HanJason233.chimera.platform.isRunningCompat
import io.github.HanJason233.chimera.platform.terminateCompat
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Common lifecycle for a core that is hosted by an Android native process. */
internal class NativeCoreSession(
    private val context: Context,
    override val core: ProxyCore,
    override val endpoint: LocalSocksEndpoint,
    private val executableName: String,
    private val processLabel: String,
    private val onFailure: (String) -> Unit,
    private val extraArguments: List<String> = emptyList()
) : CoreSession {
    private val token = UUID.randomUUID().toString()
    private val socketName = "chimera-$processLabel-$token"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = AtomicBoolean()
    private val ready = CompletableDeferred<Unit>()
    private val output = NativeProcessOutput()
    private val lanCounter = LanTrafficCounter()
    private val connections = Collections.synchronizedSet(mutableSetOf<LocalSocket>())
    private val controlWriteLock = Any()
    private var server: LocalServerSocket? = null
    @Volatile private var controlSocket: LocalSocket? = null
    private var process: Process? = null
    private var configFile: File? = null
    @Volatile private var selectedOutboundTag: String? = null

    suspend fun start(config: String): NativeCoreSession = withContext(Dispatchers.IO) {
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

            val file = File(context.cacheDir, "$processLabel-$token.json")
            configFile = file
            file.writeText(config, Charsets.UTF_8)
            val executable = File(context.applicationInfo.nativeLibraryDir, executableName)
            check(executable.isFile) { "未找到 $processLabel 原生运行器" }
            val arguments = mutableListOf(
                executable.absolutePath,
                "--config", file.absolutePath,
                "--control", "@$socketName",
                "--token", token
            )
            arguments += extraArguments
            val processBuilder = ProcessBuilder(arguments)
                .directory(context.filesDir)
                .redirectErrorStream(true)
            if (core == ProxyCore.XRAY) {
                // Xray 与 v2rayNG 一样从应用 files/assets 目录读取 Geo 数据库。
                processBuilder.environment()["XRAY_LOCATION_ASSET"] =
                    RoutingAssetsRepository.assetDirectory(context).absolutePath
            }
            val started = processBuilder.start()
            process = started
            scope.launch {
                val readError = output.read(started.inputStream) { line ->
                    val event = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
                    when (event?.get("event")?.asString) {
                        "ready" -> ready.complete(Unit)
                        "lan-traffic" -> lanCounter.record(event)
                        "outbound" -> selectedOutboundTag = event.get("tag")?.asString
                        "error" -> {
                            val message = event.get("message")?.asString.orEmpty()
                            Log.e(TAG, "$processLabel runner startup failed: $message")
                            ready.completeExceptionally(
                                IllegalStateException("$processLabel 原生运行器启动失败")
                            )
                        }
                    }
                }
                val exitCode = runCatching { started.exitValue() }.getOrDefault(-1)
                ready.completeExceptionally(
                    IllegalStateException("$processLabel 原生运行器启动失败 ($exitCode)")
                )
                if (!stopped.get()) notifyFailure(
                    if (readError == null) "$processLabel 意外退出 ($exitCode)"
                    else "$processLabel 输出通道已关闭 (${readError.javaClass.simpleName})"
                )
            }
            withTimeout(15_000) { ready.await() }
            check(started.isRunningCompat) { "$processLabel 在就绪后退出" }
            file.delete()
            this@NativeCoreSession
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
                if (header.toString() == "WATCH $token") {
                    controlSocket = socket
                    socket.soTimeout = 0
                    input.read()
                }
            }
        } catch (_: Exception) {
            // Closing the session interrupts the pending liveness channel.
        } finally {
            if (controlSocket === socket) controlSocket = null
            connections.remove(socket)
        }
    }

    fun sendControlCommand(command: String): Boolean {
        if (command.isBlank() || command.any { it == '\n' || it == '\r' }) return false
        return synchronized(controlWriteLock) {
            val socket = controlSocket ?: return@synchronized false
            runCatching {
                socket.outputStream.apply {
                    write((command + "\n").toByteArray(Charsets.UTF_8))
                    flush()
                }
            }.isSuccess
        }
    }

    private fun notifyFailure(message: String) {
        try { onFailure(message) } catch (error: Exception) {
            Log.w("NativeCoreSession", "Failure callback failed: ${error.javaClass.simpleName}")
        }
    }

    override fun lanTraffic(): CoreTraffic? = lanCounter.total

    override fun selectedOutboundTag(): String? = selectedOutboundTag

    override fun close() {
        if (!stopped.compareAndSet(false, true)) {
            check(process?.isRunningCompat != true) { "$processLabel 进程仍在停止" }
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
                    check(running.awaitExit(3, TimeUnit.SECONDS)) { "$processLabel 进程无法停止" }
                }
            }.onFailure { notifyFailure("$processLabel 停止失败: ${it.javaClass.simpleName}") }
            output.awaitDrain(1, TimeUnit.SECONDS)
        }
        scope.cancel()
        configFile?.delete()
        configFile = null
        check(running?.isRunningCompat != true) { "$processLabel 进程无法停止" }
        process = null
    }

    private companion object {
        const val TAG = "NativeCoreSession"
    }
}
