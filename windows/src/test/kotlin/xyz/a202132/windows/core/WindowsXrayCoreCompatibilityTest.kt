package xyz.a202132.windows.core

import java.nio.file.Files
import java.nio.file.Paths
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import xyz.a202132.windows.model.DesktopNode
import xyz.a202132.windows.model.NodeType

class WindowsXrayCoreCompatibilityTest {
    @Test
    fun `generated dual core config passes pinned xray`() {
        val executable = Paths.get("runtime", "windows-x64", "xray.exe").toAbsolutePath()
        assumeTrue(Files.isRegularFile(executable), "Run buildXray to enable the compatibility test")

        val nodes = supportedNodes()
        val ports = nodes.mapIndexed { index, node -> node.id to (21_000 + index) }.toMap()
        val workDirectory = Files.createTempDirectory("firefly-xray-check")
        val configFile = workDirectory.resolve("config.json")
        Files.writeString(configFile, WindowsXrayConfigGenerator().generate(nodes, ports))

        val process = ProcessBuilder(
            executable.toString(),
            "run",
            "-test",
            "-c",
            configFile.toString()
        ).directory(workDirectory.toFile()).redirectErrorStream(true).start()
        assumeTrue(process.waitFor(20, TimeUnit.SECONDS), "Xray config check timed out")
        val output = process.inputStream.bufferedReader().use { it.readText() }

        assertEquals(0, process.exitValue(), output)
    }

    @Test
    fun `pinned xray starts and exposes its local bridge`() {
        val executable = Paths.get("runtime", "windows-x64", "xray.exe").toAbsolutePath()
        assumeTrue(Files.isRegularFile(executable), "Run buildXray to enable the runtime test")
        val node = supportedNodes().first()
        val port = ServerSocket(0).use { it.localPort }
        val manager = XrayProcessManager(Files.createTempDirectory("firefly-xray-runtime"))
        var unexpectedExit: String? = null

        try {
            val config = WindowsXrayConfigGenerator().generate(listOf(node), mapOf(node.id to port))
            manager.start(executable, config, listOf(port)) { unexpectedExit = it }.getOrThrow()

            assertTrue(manager.isRunning())
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 1_000)
            }
            assertEquals(null, unexpectedExit)
        } finally {
            manager.stop()
        }
        assertFalse(manager.isRunning())
    }

    private fun supportedNodes(): List<DesktopNode> {
        val vmessJson = """{"v":"2","ps":"vmess","add":"example.com","port":"443","id":"00000000-0000-0000-0000-000000000001","aid":"0","scy":"auto","net":"ws","path":"/ws","host":"example.com","tls":"tls","sni":"example.com"}"""
        val vmess = Base64.getUrlEncoder().withoutPadding().encodeToString(vmessJson.toByteArray())
        val ssCredentials = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-128-gcm:password".toByteArray())
        return listOf(
            node(
                "vless",
                NodeType.VLESS,
                "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls&sni=example.com"
            ),
            node("vmess", NodeType.VMESS, "vmess://$vmess"),
            node("trojan", NodeType.TROJAN, "trojan://password@example.com:443?sni=example.com"),
            node("ss", NodeType.SHADOWSOCKS, "ss://$ssCredentials@example.com:8388#ss")
        )
    }

    private fun node(id: String, type: NodeType, rawLink: String) = DesktopNode(
        id = id,
        name = id,
        type = type,
        server = "example.com",
        port = if (type == NodeType.SHADOWSOCKS) 8388 else 443,
        rawLink = rawLink
    )
}
