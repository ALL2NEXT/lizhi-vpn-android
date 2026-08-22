package xyz.a202132.windows.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import com.google.gson.JsonParser
import xyz.a202132.windows.model.DesktopNode
import xyz.a202132.windows.model.DesktopSettings
import xyz.a202132.windows.model.ProxyMode

sealed interface CoreStartResult {
    data object Started : CoreStartResult
    data class Failed(val reason: String) : CoreStartResult
}

class SingBoxProcessManager(
    private val configGenerator: WindowsSingBoxConfigGenerator = WindowsSingBoxConfigGenerator(),
    private val stateDirectory: Path = defaultStateDirectory()
) : AutoCloseable {
    private val systemProxyManager = WindowsSystemProxyManager()
    private val outputLock = Any()
    private val outputLines = ArrayDeque<String>()
    private var process: Process? = null
    private var clashApiPort: Int? = null

    fun start(
        executable: Path,
        nodes: List<DesktopNode>,
        selectedNodeId: String,
        mode: ProxyMode,
        tunEnabled: Boolean = true,
        settings: DesktopSettings = DesktopSettings()
    ): CoreStartResult {
        stop()
        return runCatching {
            Files.createDirectories(stateDirectory)
            val geoSite = copyResource("rule-sets/geosite-cn.srs")
            val geoIp = copyResource("rule-sets/geoip-cn.srs")
            val configFile = stateDirectory.resolve("config.json")
            val apiPort = ServerSocket(0).use { it.localPort }
            val proxyPort = if (tunEnabled) 0 else ServerSocket(0).use { it.localPort }
            val config = configGenerator.generate(
                nodes,
                selectedNodeId,
                mode,
                geoSite,
                geoIp,
                settings,
                apiPort,
                tunEnabled,
                proxyPort
            )
            Files.writeString(configFile, config, Charsets.UTF_8)

            val validation = executeAndCollect(
                executable = executable,
                arguments = listOf("check", "-c", configFile.toString()),
                timeoutSeconds = 15
            )
            if (validation.exitCode != 0) {
                return CoreStartResult.Failed(
                    validation.output.ifBlank { "sing-box 配置校验失败" }.lastMeaningfulLine()
                )
            }

            clearOutput()
            val runningProcess = processBuilder(
                executable,
                listOf("run", "-D", stateDirectory.toString(), "-c", configFile.toString())
            ).start()
            process = runningProcess
            clashApiPort = apiPort
            try {
                if (!tunEnabled) systemProxyManager.enable(proxyPort)
            } catch (error: Exception) {
                stop()
                return CoreStartResult.Failed(error.message ?: "设置 Windows 系统代理失败")
            }
            thread(name = "sing-box-output", isDaemon = true) {
                try {
                    runningProcess.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach(::appendOutput)
                    }
                } finally {
                    systemProxyManager.disable()
                    if (process === runningProcess) {
                        process = null
                        clashApiPort = null
                    }
                }
            }

            Thread.sleep(900)
            if (!runningProcess.isAlive) {
                val exitCode = runningProcess.exitValue()
                process = null
                clashApiPort = null
                systemProxyManager.disable()
                return CoreStartResult.Failed(
                    recentOutput().ifBlank { "sing-box 已退出（代码 $exitCode）" }.lastMeaningfulLine()
                )
            }
            CoreStartResult.Started
        }.getOrElse { error ->
            CoreStartResult.Failed(error.message ?: "sing-box 启动失败")
        }
    }

    fun stop() {
        val running = process
        process = null
        if (running != null) {
            running.destroy()
            if (!running.waitFor(3, TimeUnit.SECONDS)) {
                running.destroyForcibly()
                running.waitFor(2, TimeUnit.SECONDS)
            }
        }
        clashApiPort = null
        systemProxyManager.disable()
    }

    fun isRunning(): Boolean = process?.isAlive == true

    fun trafficTotals(): Pair<Long, Long>? {
        val port = clashApiPort ?: return null
        return runCatching {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/connections"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build()
            val response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching null
            val root = JsonParser.parseString(response.body()).asJsonObject
            val upload = root.get("uploadTotal")?.asLong ?: 0L
            val download = root.get("downloadTotal")?.asLong ?: 0L
            upload.coerceAtLeast(0L) to download.coerceAtLeast(0L)
        }.getOrNull()
    }

    fun recentOutput(): String = synchronized(outputLock) { outputLines.joinToString("\n") }

    override fun close() = stop()

    private fun executeAndCollect(
        executable: Path,
        arguments: List<String>,
        timeoutSeconds: Long
    ): ProcessResult {
        val command = processBuilder(executable, arguments).start()
        if (!command.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            command.destroyForcibly()
            return ProcessResult(-1, "sing-box 命令执行超时")
        }
        val output = command.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        return ProcessResult(command.exitValue(), output)
    }

    private fun processBuilder(executable: Path, arguments: List<String>): ProcessBuilder {
        val builder = ProcessBuilder(listOf(executable.toString()) + arguments)
            .directory(stateDirectory.toFile())
            .redirectErrorStream(true)
        val pathKey = builder.environment().keys.firstOrNull { it.equals("PATH", true) } ?: "PATH"
        val currentPath = builder.environment()[pathKey].orEmpty()
        builder.environment()[pathKey] = executable.parent.toString() +
            System.getProperty("path.separator") + currentPath
        return builder
    }

    private fun copyResource(name: String): Path {
        val target = stateDirectory.resolve(name.substringAfterLast('/'))
        Thread.currentThread().contextClassLoader.getResourceAsStream(name).use { input ->
            requireNotNull(input) { "缺少运行资源 $name" }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    private fun appendOutput(line: String) = synchronized(outputLock) {
        if (outputLines.size >= 100) outputLines.removeFirst()
        outputLines.addLast(line)
    }

    private fun clearOutput() = synchronized(outputLock) { outputLines.clear() }

    private fun String.lastMeaningfulLine(): String = lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .lastOrNull()
        ?: this

    private data class ProcessResult(val exitCode: Int, val output: String)

    companion object {
        private val HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()

        private fun defaultStateDirectory(): Path {
            val base = System.getenv("LOCALAPPDATA")
                ?.takeIf(String::isNotBlank)
                ?.let(Paths::get)
                ?: Paths.get(System.getProperty("user.home"), "AppData", "Local")
            return base.resolve("FireflyVPN").resolve("sing-box")
        }
    }
}
