package xyz.a202132.windows.core

import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 管理供 sing-box 桥接出站使用的独立 Windows Xray 进程。 */
class XrayProcessManager(
    private val stateDirectory: Path = defaultStateDirectory()
) : AutoCloseable {
    private val processLock = Any()
    private val outputLock = Any()
    private val outputLines = ArrayDeque<String>()
    private var process: Process? = null

    fun checkConfig(executable: Path, config: String): Result<Unit> = runCatching {
        ensureRuntime(executable)
        val testConfig = stateDirectory.resolve("config-test.json")
        Files.writeString(testConfig, config, Charsets.UTF_8)
        try {
            val check = executeAndCollect(
                executable,
                listOf("run", "-test", "-c", testConfig.toString()),
                CONFIG_CHECK_TIMEOUT_SECONDS
            )
            check(check.exitCode == 0) {
                check.output.takeLast(MAX_ERROR_LENGTH).ifBlank { "Xray 配置无效" }
            }
        } finally {
            Files.deleteIfExists(testConfig)
        }
    }

    fun start(
        executable: Path,
        config: String,
        bridgePorts: Collection<Int>,
        onUnexpectedExit: (String) -> Unit
    ): Result<Unit> = runCatching {
        require(bridgePorts.isNotEmpty()) { "Xray 没有可启动的桥接端口" }
        stop()
        ensureRuntime(executable)
        val configFile = stateDirectory.resolve("config.json")
        writeAtomically(configFile, config)
        clearOutput()

        val running = processBuilder(
            executable,
            listOf("run", "-c", configFile.toString())
        ).start()
        synchronized(processLock) { process = running }
        thread(name = "xray-output", isDaemon = true) {
            running.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach(::appendOutput)
            }
            val exitCode = runCatching { running.waitFor() }.getOrDefault(-1)
            val unexpected = synchronized(processLock) {
                if (process === running) {
                    process = null
                    true
                } else {
                    false
                }
            }
            if (unexpected) {
                val detail = recentOutput().takeLast(MAX_ERROR_LENGTH)
                onUnexpectedExit(
                    "Xray 异常退出（$exitCode）" +
                        detail.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()
                )
            }
        }

        waitUntilReady(running, bridgePorts)
        // Xray 已完整加载配置，尽力删除含节点凭据的临时文件；删除失败不影响已启动连接。
        runCatching { Files.deleteIfExists(configFile) }
        Unit
    }.onFailure {
        stop()
    }

    fun stop() {
        val running = synchronized(processLock) {
            process.also { process = null }
        }
        if (running != null) {
            running.destroy()
            if (!running.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                running.destroyForcibly()
                running.waitFor(1, TimeUnit.SECONDS)
            }
        }
        runCatching { Files.deleteIfExists(stateDirectory.resolve("config.json")) }
        runCatching { Files.deleteIfExists(stateDirectory.resolve("config-test.json")) }
    }

    fun isRunning(): Boolean = synchronized(processLock) { process?.isAlive == true }

    fun recentOutput(): String = synchronized(outputLock) { outputLines.joinToString("\n") }

    override fun close() = stop()

    private fun waitUntilReady(running: Process, bridgePorts: Collection<Int>) {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        val remaining = bridgePorts.toMutableSet()
        while (remaining.isNotEmpty() && System.currentTimeMillis() < deadline) {
            check(running.isAlive) {
                recentOutput().takeLast(MAX_ERROR_LENGTH).ifBlank { "Xray 在桥接端口就绪前退出" }
            }
            remaining.removeAll { port ->
                runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), PORT_PROBE_TIMEOUT_MS)
                    }
                }.isSuccess
            }
            if (remaining.isNotEmpty()) Thread.sleep(PORT_PROBE_INTERVAL_MS)
        }
        check(remaining.isEmpty()) { "Xray 桥接端口启动超时：${remaining.joinToString()}" }
    }

    private fun ensureRuntime(executable: Path) {
        check(Files.isRegularFile(executable)) { "未找到 Xray 核心：$executable" }
        Files.createDirectories(stateDirectory)
    }

    private fun executeAndCollect(
        executable: Path,
        arguments: List<String>,
        timeoutSeconds: Long
    ): ProcessResult {
        val command = processBuilder(executable, arguments).start()
        if (!command.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            command.destroyForcibly()
            return ProcessResult(-1, "Xray 命令执行超时")
        }
        val output = command.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        return ProcessResult(command.exitValue(), output)
    }

    private fun processBuilder(executable: Path, arguments: List<String>): ProcessBuilder =
        ProcessBuilder(listOf(executable.toString()) + arguments)
            .directory(stateDirectory.toFile())
            .redirectErrorStream(true)
            .apply {
                environment()["XRAY_LOCATION_ASSET"] = stateDirectory.toString()
                environment()["XRAY_LOCATION_CERT"] = stateDirectory.toString()
            }

    private fun writeAtomically(target: Path, content: String) {
        val pending = target.resolveSibling("${target.fileName}.new")
        Files.writeString(pending, content, Charsets.UTF_8)
        runCatching {
            Files.move(
                pending,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.getOrElse {
            Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun appendOutput(line: String) = synchronized(outputLock) {
        if (outputLines.size >= MAX_RECENT_LOG_LINES) outputLines.removeFirst()
        outputLines.addLast(line)
    }

    private fun clearOutput() = synchronized(outputLock) { outputLines.clear() }

    private data class ProcessResult(val exitCode: Int, val output: String)

    companion object {
        private const val CONFIG_CHECK_TIMEOUT_SECONDS = 15L
        private const val STOP_TIMEOUT_SECONDS = 3L
        private const val START_TIMEOUT_MS = 6_000L
        private const val PORT_PROBE_TIMEOUT_MS = 150
        private const val PORT_PROBE_INTERVAL_MS = 80L
        private const val MAX_RECENT_LOG_LINES = 20
        private const val MAX_ERROR_LENGTH = 1_500

        private fun defaultStateDirectory(): Path {
            val base = System.getenv("LOCALAPPDATA")
                ?.takeIf(String::isNotBlank)
                ?.let(Paths::get)
                ?: Paths.get(System.getProperty("user.home"), "AppData", "Local")
            return base.resolve("Firefly").resolve("xray")
        }
    }
}
