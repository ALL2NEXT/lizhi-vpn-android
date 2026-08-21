package xyz.a202132.windows.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.a202132.windows.data.DesktopPreferencesStore

class BackendClientTest {
    @Test
    fun registersInstallationAndLoadsManagedSubscription() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        var registeredDeviceId = ""
        var subscriptionDeviceId = ""
        var reportReceived = false
        server.createContext("/api/account/register") { exchange ->
            registeredDeviceId = Regex("\"deviceId\":\"([^\"]+)\"")
                .find(exchange.requestBody.bufferedReader().readText())?.groupValues?.get(1).orEmpty()
            exchange.json(
                """{"ok":true,"account":{"uid":"guest-1","nickname":"流萤测试","accountType":"guest"}}"""
            )
        }
        server.createContext("/api/client") { exchange ->
            exchange.json(
                """{"subscriptions":[{"id":"7","name":"测试线路","enabled":true,"encrypted":true,"minAccountType":"guest","url":"$baseUrl/api/subscription/7"}],"settings":{"nodeRequestTimeoutMs":5000}}"""
            )
        }
        server.createContext("/api/subscription/7") { exchange ->
            subscriptionDeviceId = exchange.requestURI.rawQuery
                .substringAfter("deviceId=", "")
            val plaintext = "vless://00000000-0000-0000-0000-000000000000@example.com:443?security=tls#测试节点"
            exchange.responseHeaders.add("X-Firefly-Client-IP", "1.2.3.4")
            exchange.text(protect(plaintext, 4))
        }
        server.createContext("/api/account/profile") { exchange ->
            exchange.json(accountJson("新昵称", "guest", ""))
        }
        server.createContext("/api/account/bind") { exchange ->
            exchange.json(accountJson("新昵称", "formal", "user@example.com"))
        }
        server.createContext("/api/account/login") { exchange ->
            exchange.json(accountJson("已登录", "formal", "user@example.com"))
        }
        server.createContext("/api/account/report") { exchange ->
            reportReceived = exchange.requestBody.bufferedReader().readText().contains("session-1")
            exchange.json("""{"ok":true}""")
        }
        server.createContext("/api/account/delete") { exchange ->
            exchange.json("""{"ok":true}""")
        }
        server.start()

        try {
            val file = Files.createTempDirectory("firefly-backend-test").resolve("preferences.json")
            val store = DesktopPreferencesStore(file)
            store.update { it.copy(backendUrl = baseUrl) }
            val client = BackendClient(store)
            val result = client.load()

            assertEquals("流萤测试", result.account.nickname)
            assertEquals(1, result.sourceCount)
            assertEquals("测试节点", result.nodes.single().displayName())
            assertEquals("7", result.nodes.single().subscriptionSourceId)
            assertTrue(registeredDeviceId.isNotBlank())
            assertTrue(subscriptionDeviceId.isNotBlank())

            assertEquals("新昵称", client.updateNickname("新昵称").nickname)
            assertEquals(
                "formal",
                client.bindAccount("guest-1", "user@example.com", "password1", "").accountType
            )
            assertEquals("已登录", client.loginAccount("user@example.com", "password1").nickname)
            client.reportTraffic("session-1", "测试线路", 1000, 2000, 10, 20)
            assertTrue(reportReceived)
            client.deleteAccount("guest-1", "")
        } finally {
            server.stop(0)
        }
    }

    private fun accountJson(nickname: String, type: String, email: String): String =
        """{"ok":true,"account":{"uid":"guest-1","nickname":"$nickname","accountType":"$type","email":"$email"}}"""

    private fun protect(value: String, shift: Int): String {
        val standard = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val privateAlphabet =
            "iWm124UFyO7KG9NBPhCj5kS8IYbHRvtselQcTA3ugVEo6and-Lprq_DJMw0ZzfxX"
        return Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
            .map { character ->
                if (character == '=') character else {
                    val index = standard.indexOf(character)
                    privateAlphabet[(index + shift) % 64]
                }
            }.joinToString("")
    }

    private fun HttpExchange.json(value: String) {
        responseHeaders.add("content-type", "application/json; charset=utf-8")
        text(value)
    }

    private fun HttpExchange.text(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
