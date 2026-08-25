package xyz.a202132.app.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** 管理供 sing-box 桥接出站使用的独立 Xray 进程。 */
internal class XrayProcessManager(
    context: Context,
    private val scope: CoroutineScope,
    runtimeDirectoryName: String = DEFAULT_RUNTIME_DIRECTORY,
    private val onUnexpectedExit: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val workDir = File(
        appContext.filesDir,
        runtimeDirectoryName.takeIf(::isSafeRuntimeDirectoryName)
            ?: error("Xray 运行目录名称无效")
    )
    private val binary = File(appContext.applicationInfo.nativeLibraryDir, "libxray.so")
    private val configFile = File(workDir, "config.json")
    private val testConfigFile = File(workDir, "config-test.json")

    @Volatile
    private var process: Process? = null

    @Volatile
    private var expectedStop = false

    private var outputJob: Job? = null

    suspend fun checkConfig(config: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureRuntime()
            testConfigFile.writeText(config, Charsets.UTF_8)
            val checkProcess = newProcess("run", "-test", "-c", testConfigFile.absolutePath).start()
            val outputReader = scope.async(Dispatchers.IO) {
                checkProcess.inputStream.bufferedReader().use { it.readText() }
            }
            if (!checkProcess.waitFor(CONFIG_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                checkProcess.destroyForcibly()
                error("Xray 配置检查超时")
            }
            val output = outputReader.await().trim()
            if (checkProcess.exitValue() != 0) {
                error(output.takeLast(MAX_ERROR_LENGTH).ifBlank { "Xray 配置无效" })
            }
        }.also { runCatching { testConfigFile.delete() } }
    }

    suspend fun start(config: String, bridgePorts: Collection<Int>) = withContext(Dispatchers.IO) {
        require(bridgePorts.isNotEmpty()) { "Xray 没有可启动的桥接端口" }
        stop()
        ensureRuntime()
        writeConfigAtomically(config)
        expectedStop = false

        val started = newProcess("run", "-c", configFile.absolutePath).start()
        process = started
        val recentOutput = ArrayDeque<String>(MAX_RECENT_LOG_LINES)
        outputJob = scope.launch(Dispatchers.IO) {
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (recentOutput.size == MAX_RECENT_LOG_LINES) recentOutput.removeFirst()
                        recentOutput.addLast(line)
                        Log.d(TAG, line)
                    }
                }
            }
            val exitCode = runCatching { started.waitFor() }.getOrDefault(-1)
            val wasCurrentProcess = process === started
            if (wasCurrentProcess) process = null
            if (wasCurrentProcess && !expectedStop) {
                val detail = recentOutput.joinToString("\n").takeLast(MAX_ERROR_LENGTH)
                onUnexpectedExit("Xray 异常退出（$exitCode）${detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }
        }

        waitUntilReady(started, bridgePorts)
        if (configFile.exists() && !configFile.delete()) {
            Log.w(TAG, "Unable to remove Xray config after startup")
        }
        Log.i(TAG, "Xray started with ${bridgePorts.size} protocol bridge(s)")
    }

    fun stop() {
        expectedStop = true
        val running = process
        process = null
        if (running != null) {
            runCatching {
                running.destroy()
                if (!running.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    running.destroyForcibly()
                    running.waitFor(1, TimeUnit.SECONDS)
                }
            }.onFailure { Log.w(TAG, "Failed to stop Xray cleanly", it) }
        }
        outputJob?.cancel()
        outputJob = null
        runCatching { configFile.delete() }
        runCatching { testConfigFile.delete() }
    }

    private suspend fun waitUntilReady(started: Process, bridgePorts: Collection<Int>) {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        val remaining = bridgePorts.toMutableSet()
        while (remaining.isNotEmpty() && System.currentTimeMillis() < deadline) {
            if (!started.isAlive) error("Xray 在桥接端口就绪前退出")
            remaining.removeAll { port ->
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), PORT_PROBE_TIMEOUT_MS)
                    }
                }.isSuccess
            }
            if (remaining.isNotEmpty()) delay(PORT_PROBE_INTERVAL_MS)
        }
        if (remaining.isNotEmpty()) {
            stop()
            error("Xray 桥接端口启动超时: ${remaining.joinToString()}")
        }
    }

    private fun ensureRuntime() {
        check(binary.isFile) { "未找到内置 Xray 核心: ${binary.absolutePath}" }
        if (!workDir.exists()) check(workDir.mkdirs()) { "无法创建 Xray 工作目录" }
        if (!binary.canExecute()) {
            check(binary.setExecutable(true, true) || binary.canExecute()) { "Xray 核心不可执行" }
        }
    }

    private fun writeConfigAtomically(config: String) {
        val pending = File(workDir, "config.json.new")
        pending.writeText(config, Charsets.UTF_8)
        if (configFile.exists() && !configFile.delete()) error("无法替换 Xray 配置")
        check(pending.renameTo(configFile)) { "无法提交 Xray 配置" }
    }

    private fun newProcess(vararg args: String): ProcessBuilder = ProcessBuilder(
        buildList {
            add(binary.absolutePath)
            addAll(args)
        }
    ).directory(workDir).redirectErrorStream(true).apply {
        environment()["XRAY_LOCATION_ASSET"] = workDir.absolutePath
        environment()["XRAY_LOCATION_CERT"] = workDir.absolutePath
    }

    private companion object {
        const val DEFAULT_RUNTIME_DIRECTORY = "xray"
        const val TAG = "XrayProcessManager"
        const val CONFIG_CHECK_TIMEOUT_SECONDS = 15L
        const val STOP_TIMEOUT_SECONDS = 3L
        const val START_TIMEOUT_MS = 6_000L
        const val PORT_PROBE_TIMEOUT_MS = 150
        const val PORT_PROBE_INTERVAL_MS = 80L
        const val MAX_RECENT_LOG_LINES = 20
        const val MAX_ERROR_LENGTH = 1_500

        fun isSafeRuntimeDirectoryName(value: String): Boolean =
            value.isNotBlank() && value.length <= 80 && value.all { character ->
                character.isLetterOrDigit() || character == '-' || character == '_'
            }
    }
}
