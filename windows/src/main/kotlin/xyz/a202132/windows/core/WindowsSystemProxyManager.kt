package xyz.a202132.windows.core

import java.util.concurrent.TimeUnit

/**
 * 管理当前 Windows 用户的系统代理设置。
 *
 * 使用 HKCU 不需要管理员权限；启用前保存原值，停止代理后恢复，避免影响用户原有代理配置。
 */
internal class WindowsSystemProxyManager {
    private var activeProxy: ActiveProxy? = null

    @Synchronized
    fun enable(port: Int) {
        require(port in 1..65_535) { "系统代理端口无效：$port" }
        if (activeProxy?.port == port) return

        disable()
        val previous = readSnapshot()
        try {
            writeValue("ProxyEnable", "REG_DWORD", "1")
            writeValue("ProxyServer", "REG_SZ", "127.0.0.1:$port")
            writeValue("ProxyOverride", "REG_SZ", ACTIVE_PROXY_OVERRIDE)
            refreshSystemProxy()
            activeProxy = ActiveProxy(port, previous)
        } catch (error: Exception) {
            runCatching { restore(previous) }
            refreshSystemProxy()
            throw IllegalStateException("设置 Windows 系统代理失败：${error.message}", error)
        }
    }

    @Synchronized
    fun disable() {
        val active = activeProxy ?: return
        try {
            // 用户在运行期间手动改过代理时，保留用户的新设置，不强行覆盖。
            if (isManagedState(readSnapshot(), active.port)) {
                restore(active.previous)
                refreshSystemProxy()
            }
        } finally {
            activeProxy = null
        }
    }

    private fun isManagedState(snapshot: RegistrySnapshot, port: Int): Boolean =
        snapshot.proxyEnable?.data?.let(::isEnabled) == true &&
            snapshot.proxyServer?.data == "127.0.0.1:$port" &&
            snapshot.proxyOverride?.data == ACTIVE_PROXY_OVERRIDE

    private fun isEnabled(value: String): Boolean =
        value.removePrefix("0x").toIntOrNull(16)?.let { it != 0 }
            ?: (value == "1")

    private fun restore(snapshot: RegistrySnapshot) {
        restoreValue("ProxyEnable", snapshot.proxyEnable)
        restoreValue("ProxyServer", snapshot.proxyServer)
        restoreValue("ProxyOverride", snapshot.proxyOverride)
    }

    private fun restoreValue(name: String, value: RegistryValue?) {
        if (value == null) {
            runRegistry("delete", REGISTRY_KEY, "/v", name, "/f")
        } else {
            writeValue(name, value.type, value.data)
        }
    }

    private fun readSnapshot() = RegistrySnapshot(
        proxyEnable = readValue("ProxyEnable"),
        proxyServer = readValue("ProxyServer"),
        proxyOverride = readValue("ProxyOverride")
    )

    private fun readValue(name: String): RegistryValue? {
        val output = runRegistry("query", REGISTRY_KEY, "/v", name, allowFailure = true)
        if (output == null) return null
        val line = output.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("$name ") || it.startsWith("$name\t") }
            ?: return null
        val fields = line.split(Regex("\\s+"), limit = 3)
        if (fields.size < 3) return null
        return RegistryValue(fields[1], fields[2])
    }

    private fun writeValue(name: String, type: String, data: String) {
        runRegistry(
            "add",
            REGISTRY_KEY,
            "/v",
            name,
            "/t",
            type,
            "/d",
            data,
            "/f"
        )
    }

    private fun runRegistry(
        vararg arguments: String,
        allowFailure: Boolean = false
    ): String? {
        val process = ProcessBuilder(listOf("reg.exe") + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            if (allowFailure) return null
            error("reg.exe 执行超时")
        }
        if (process.exitValue() != 0) {
            if (allowFailure) return null
            error(output.trim().ifBlank { "reg.exe 执行失败" })
        }
        return output
    }

    private fun refreshSystemProxy() {
        runCatching {
            ProcessBuilder("rundll32.exe", "user32.dll,UpdatePerUserSystemParameters")
                .start()
                .waitFor(2, TimeUnit.SECONDS)
        }
    }

    private data class ActiveProxy(val port: Int, val previous: RegistrySnapshot)

    private data class RegistrySnapshot(
        val proxyEnable: RegistryValue?,
        val proxyServer: RegistryValue?,
        val proxyOverride: RegistryValue?
    )

    private data class RegistryValue(val type: String, val data: String)

    private companion object {
        const val REGISTRY_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
        const val ACTIVE_PROXY_OVERRIDE = "<local>;localhost;127.*;[::1]"
    }
}
