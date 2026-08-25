package xyz.a202132.windows.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import xyz.a202132.windows.model.DesktopNode
import xyz.a202132.windows.model.DesktopSettings
import xyz.a202132.windows.model.ProxyMode

class SingBoxBandwidthTestManager(
    private val configGenerator: WindowsSingBoxConfigGenerator = WindowsSingBoxConfigGenerator(),
    private val stateRoot: Path = defaultStateDirectory()
) {
    fun test(
        executable: Path,
        nodes: List<DesktopNode>,
        settings: DesktopSettings,
        sizeMb: Int = 5
    ): Map<String, Float> {
        if (nodes.isEmpty()) return emptyMap()
        Files.createDirectories(stateRoot)
        val executor = Executors.newFixedThreadPool(settings.bandwidthTestConcurrency.coerceIn(1, 3))
        return try {
            executor.invokeAll(nodes.map { node ->
                Callable { node.id to testNode(executable, node, settings, sizeMb) }
            }).map { it.get() }.toMap()
        } finally {
            executor.shutdownNow()
        }
    }

    private fun testNode(
        executable: Path,
        node: DesktopNode,
        settings: DesktopSettings,
        sizeMb: Int
    ): Float {
        val directory = stateRoot.resolve(node.id)
        Files.createDirectories(directory)
        val proxyPort = ServerSocket(0).use { it.localPort }
        val apiPort = ServerSocket(0).use { it.localPort }
        val generated = configGenerator.generate(
            nodes = listOf(node),
            selectedNodeId = node.id,
            proxyMode = ProxyMode.GLOBAL,
            geoSiteCn = directory.resolve("unused-geosite.srs"),
            geoIpCn = directory.resolve("unused-geoip.srs"),
            settings = settings
        )
        val root = JsonParser.parseString(generated).asJsonObject
        root.add("inbounds", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "mixed")
                addProperty("tag", "test-in")
                addProperty("listen", "127.0.0.1")
                addProperty("listen_port", proxyPort)
            })
        })
        root.getAsJsonObject("dns").addProperty("final", "local")
        root.getAsJsonObject("experimental")
            .getAsJsonObject("clash_api")
            .addProperty("external_controller", "127.0.0.1:$apiPort")
        val configFile = directory.resolve("bandwidth-test.json")
        Files.writeString(configFile, root.toString(), Charsets.UTF_8)
        val process = processBuilder(
            executable,
            directory,
            listOf("run", "-D", directory.toString(), "-c", configFile.toString())
        ).start()
        thread(name = "sing-box-bandwidth-${node.id}", isDaemon = true) {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return try {
            Thread.sleep(650)
            if (!process.isAlive) return 0f
            downloadThroughProxy(proxyPort, settings.speedTestTimeoutMs, sizeMb)
        } finally {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun downloadThroughProxy(proxyPort: Int, timeoutMs: Int, sizeMb: Int): Float {
        val byteLimit = sizeMb.coerceAtLeast(1).toLong() * 1_000_000L
        val url = URL("https://speed.cloudflare.com/__down?bytes=$byteLimit&r=${System.nanoTime()}")
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort))
        val connection = url.openConnection(proxy) as HttpURLConnection
        val timeout = timeoutMs.coerceAtLeast(1_000)
        connection.connectTimeout = timeout
        connection.readTimeout = timeout
        connection.setRequestProperty("user-agent", "FireflyVPN/3.0.3 (Windows)")
        val started = System.nanoTime()
        return runCatching {
            connection.inputStream.use { input ->
                val buffer = ByteArray(32 * 1024)
                var received = 0L
                while (received < byteLimit) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), byteLimit - received).toInt())
                    if (count < 0) break
                    received += count
                }
                val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                if (received <= 0L || seconds <= 0.0) 0f
                else (received * 8.0 / seconds / 1_000_000.0).toFloat()
            }
        }.getOrDefault(0f).also { connection.disconnect() }
    }

    private fun processBuilder(
        executable: Path,
        directory: Path,
        arguments: List<String>
    ): ProcessBuilder {
        val builder = ProcessBuilder(listOf(executable.toString()) + arguments)
            .directory(directory.toFile())
            .redirectErrorStream(true)
        val pathKey = builder.environment().keys.firstOrNull { it.equals("PATH", true) } ?: "PATH"
        builder.environment()[pathKey] = executable.parent.toString() +
            System.getProperty("path.separator") + builder.environment()[pathKey].orEmpty()
        return builder
    }

    companion object {
        private fun defaultStateDirectory(): Path {
            val base = System.getenv("LOCALAPPDATA")
                ?.takeIf(String::isNotBlank)
                ?.let(Paths::get)
                ?: Paths.get(System.getProperty("user.home"), "AppData", "Local")
            return base.resolve("FireflyVPN").resolve("sing-box-bandwidth-test")
        }
    }
}
