package xyz.a202132.windows.core

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import xyz.a202132.windows.model.DesktopNode
import xyz.a202132.windows.model.DesktopSettings
import xyz.a202132.windows.model.ProxyMode
import xyz.a202132.windows.model.UrlTestTargets

class SingBoxUrlTestManager(
    private val configGenerator: WindowsSingBoxConfigGenerator = WindowsSingBoxConfigGenerator(),
    private val stateDirectory: Path = defaultStateDirectory()
) {
    fun test(
        executable: Path,
        nodes: List<DesktopNode>,
        settings: DesktopSettings
    ): Map<String, Int> {
        if (nodes.isEmpty()) return emptyMap()
        Files.createDirectories(stateDirectory)
        val port = ServerSocket(0).use { it.localPort }
        val generated = configGenerator.generate(
            nodes = nodes,
            selectedNodeId = nodes.first().id,
            proxyMode = ProxyMode.GLOBAL,
            geoSiteCn = stateDirectory.resolve("unused-geosite.srs"),
            geoIpCn = stateDirectory.resolve("unused-geoip.srs"),
            settings = settings
        )
        val root = JsonParser.parseString(generated).asJsonObject
        root.add("inbounds", JsonArray())
        root.getAsJsonObject("dns").addProperty("final", "local")
        root.getAsJsonObject("experimental")
            .getAsJsonObject("clash_api")
            .addProperty("external_controller", "127.0.0.1:$port")
        val configFile = stateDirectory.resolve("url-test.json")
        Files.writeString(configFile, root.toString(), Charsets.UTF_8)

        val process = processBuilder(
            executable,
            listOf("run", "-D", stateDirectory.toString(), "-c", configFile.toString())
        ).start()
        thread(name = "sing-box-url-test-output", isDaemon = true) {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return try {
            waitUntilReady(port, process)
            val executor = Executors.newFixedThreadPool(settings.urlTestConcurrency.coerceIn(1, 128))
            try {
                val tasks = nodes.map { node ->
                    Callable {
                        node.id to testNode(
                            port,
                            node,
                            settings.urlTestUrl,
                            settings.urlTestTimeoutMs
                        )
                    }
                }
                executor.invokeAll(tasks).map { it.get() }.toMap()
            } finally {
                executor.shutdownNow()
            }
        } finally {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun waitUntilReady(port: Int, process: Process) {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build()
        repeat(20) {
            check(process.isAlive) { "URL Test 核心启动失败" }
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/version"))
                .timeout(Duration.ofMillis(500))
                .GET()
                .build()
            if (runCatching { client.send(request, HttpResponse.BodyHandlers.discarding()) }
                    .getOrNull()?.statusCode() in 200..299
            ) return
            Thread.sleep(100)
        }
        throw IllegalStateException("URL Test 核心启动超时")
    }

    private fun testNode(
        port: Int,
        node: DesktopNode,
        targetUrl: String?,
        timeoutMs: Int
    ): Int {
        val encodedTag = URLEncoder.encode(node.id, StandardCharsets.UTF_8)
        val normalizedUrl = UrlTestTargets.normalize(targetUrl) ?: UrlTestTargets.DEFAULT_URL
        val encodedUrl = URLEncoder.encode(normalizedUrl, StandardCharsets.UTF_8)
        val timeout = timeoutMs.coerceAtLeast(500)
        val request = HttpRequest.newBuilder(
            URI("http://127.0.0.1:$port/proxies/$encodedTag/delay?timeout=$timeout&url=$encodedUrl")
        ).timeout(Duration.ofMillis(timeout.toLong() + 1_500L)).GET().build()
        return runCatching {
            val response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) return@runCatching DesktopNode.FAILED
            JsonParser.parseString(response.body()).asJsonObject.get("delay")?.asInt
                ?.takeIf { it > 0 }
                ?: DesktopNode.FAILED
        }.getOrDefault(DesktopNode.FAILED)
    }

    private fun processBuilder(executable: Path, arguments: List<String>): ProcessBuilder {
        val builder = ProcessBuilder(listOf(executable.toString()) + arguments)
            .directory(stateDirectory.toFile())
            .redirectErrorStream(true)
        val pathKey = builder.environment().keys.firstOrNull { it.equals("PATH", true) } ?: "PATH"
        builder.environment()[pathKey] = executable.parent.toString() +
            System.getProperty("path.separator") + builder.environment()[pathKey].orEmpty()
        return builder
    }

    companion object {
        private val HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build()

        private fun defaultStateDirectory(): Path {
            val base = System.getenv("LOCALAPPDATA")
                ?.takeIf(String::isNotBlank)
                ?.let(Paths::get)
                ?: Paths.get(System.getProperty("user.home"), "AppData", "Local")
            return base.resolve("Firefly").resolve("sing-box-url-test")
        }
    }
}
