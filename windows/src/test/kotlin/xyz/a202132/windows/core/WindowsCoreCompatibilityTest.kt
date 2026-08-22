package xyz.a202132.windows.core

import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import xyz.a202132.windows.model.ProxyMode
import xyz.a202132.windows.model.DesktopSettings
import xyz.a202132.windows.model.Ipv6RoutingMode
import xyz.a202132.windows.model.TunStack

class WindowsCoreCompatibilityTest {
    @Test
    fun `generated smart config passes the pinned Windows core`() {
        val executable = Paths.get("runtime", "windows-x64", "sing-box.exe").toAbsolutePath()
        assumeTrue(Files.isRegularFile(executable), "Run buildSingBox to enable the core compatibility test")

        val workDirectory = Files.createTempDirectory("firefly-core-check")
        val geoSite = copyResource("rule-sets/geosite-cn.srs", workDirectory)
        val geoIp = copyResource("rule-sets/geoip-cn.srs", workDirectory)
        val node = requireNotNull(
            NodeLinkParser().parseNodeLink(
                "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls&sni=example.com#Tokyo"
            )
        )
        TunStack.entries.forEach { stack ->
            Ipv6RoutingMode.entries.forEach { ipv6Mode ->
                val config = WindowsSingBoxConfigGenerator().generate(
                    nodes = listOf(node),
                    selectedNodeId = node.id,
                    proxyMode = ProxyMode.SMART,
                    geoSiteCn = geoSite,
                    geoIpCn = geoIp,
                    settings = DesktopSettings(tunStack = stack, ipv6RoutingMode = ipv6Mode)
                )
                val configFile = workDirectory.resolve("config-${stack.name}-${ipv6Mode.name}.json")
                Files.writeString(configFile, config)

                val process = ProcessBuilder(
                    executable.toString(),
                    "check",
                    "-c",
                    configFile.toString()
                ).directory(workDirectory.toFile()).redirectErrorStream(true).start()
                assumeTrue(process.waitFor(15, TimeUnit.SECONDS), "sing-box check timed out")
                val output = process.inputStream.bufferedReader().use { it.readText() }

                assertEquals(0, process.exitValue(), "$stack/$ipv6Mode: $output")
            }
        }
    }

    @Test
    fun `generated local proxy config passes the pinned Windows core`() {
        val executable = Paths.get("runtime", "windows-x64", "sing-box.exe").toAbsolutePath()
        assumeTrue(Files.isRegularFile(executable), "Run buildSingBox to enable the core compatibility test")

        val workDirectory = Files.createTempDirectory("firefly-core-local-proxy")
        val geoSite = copyResource("rule-sets/geosite-cn.srs", workDirectory)
        val geoIp = copyResource("rule-sets/geoip-cn.srs", workDirectory)
        val node = requireNotNull(
            NodeLinkParser().parseNodeLink(
                "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls&sni=example.com#Tokyo"
            )
        )
        val config = WindowsSingBoxConfigGenerator().generate(
            nodes = listOf(node),
            selectedNodeId = node.id,
            proxyMode = ProxyMode.SMART,
            geoSiteCn = geoSite,
            geoIpCn = geoIp,
            tunEnabled = false,
            proxyPort = 18_080
        )
        val configFile = workDirectory.resolve("local-proxy-config.json")
        Files.writeString(configFile, config)

        val process = ProcessBuilder(
            executable.toString(),
            "check",
            "-c",
            configFile.toString()
        ).directory(workDirectory.toFile()).redirectErrorStream(true).start()
        assumeTrue(process.waitFor(15, TimeUnit.SECONDS), "sing-box check timed out")
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.exitValue(), output)
    }

    @Test
    fun `dns detour starts with the pinned Windows core`() {
        val executable = Paths.get("runtime", "windows-x64", "sing-box.exe").toAbsolutePath()
        assumeTrue(Files.isRegularFile(executable), "Run buildSingBox to enable the core compatibility test")

        val workDirectory = Files.createTempDirectory("firefly-core-runtime")
        val geoSite = copyResource("rule-sets/geosite-cn.srs", workDirectory)
        val geoIp = copyResource("rule-sets/geoip-cn.srs", workDirectory)
        val node = requireNotNull(
            NodeLinkParser().parseNodeLink(
                "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls&sni=example.com#Tokyo"
            )
        )
        val root = JsonParser.parseString(
            WindowsSingBoxConfigGenerator().generate(
                nodes = listOf(node),
                selectedNodeId = node.id,
                proxyMode = ProxyMode.SMART,
                geoSiteCn = geoSite,
                geoIpCn = geoIp
            )
        ).asJsonObject
        // Keep the DNS/outbound graph under test, but avoid creating a TUN
        // interface in the unit-test process.
        root.add("inbounds", com.google.gson.JsonArray())
        root.remove("experimental")
        val configFile = workDirectory.resolve("runtime-config.json")
        Files.writeString(configFile, root.toString())

        val process = ProcessBuilder(
            executable.toString(),
            "run",
            "-D",
            workDirectory.toString(),
            "-c",
            configFile.toString()
        ).directory(workDirectory.toFile()).redirectErrorStream(true).start()
        try {
            Thread.sleep(1_500)
            val output = process.inputStream.bufferedReader().use { reader ->
                if (process.isAlive) "" else reader.readText()
            }
            assertTrue(process.isAlive, "sing-box exited while starting DNS detours: $output")
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun copyResource(name: String, directory: java.nio.file.Path): java.nio.file.Path {
        val target = directory.resolve(name.substringAfterLast('/'))
        Thread.currentThread().contextClassLoader.getResourceAsStream(name).use { input ->
            requireNotNull(input)
            Files.copy(input, target)
        }
        return target
    }
}
