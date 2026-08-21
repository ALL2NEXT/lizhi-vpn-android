package xyz.a202132.windows.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.TimeUnit

sealed interface CoreDiscoveryResult {
    data class Available(
        val executable: Path,
        val version: String
    ) : CoreDiscoveryResult

    data class Missing(val searched: List<Path>) : CoreDiscoveryResult

    data class Failed(
        val executable: Path,
        val reason: String
    ) : CoreDiscoveryResult
}

class SingBoxLocator(
    private val environment: Map<String, String> = System.getenv(),
    private val workingDirectory: Path = Paths.get("").toAbsolutePath().normalize(),
    private val packagedResourcesDirectory: Path? = System.getProperty("compose.application.resources.dir")
        ?.takeIf(String::isNotBlank)
        ?.let(Paths::get)
) {
    fun locate(): CoreDiscoveryResult {
        val candidates = candidatePaths()
        var firstFailure: CoreDiscoveryResult.Failed? = null

        for (candidate in candidates) {
            if (!Files.isRegularFile(candidate)) continue

            when (val inspected = inspect(candidate)) {
                is CoreDiscoveryResult.Available -> return inspected
                is CoreDiscoveryResult.Failed -> if (firstFailure == null) firstFailure = inspected
                is CoreDiscoveryResult.Missing -> Unit
            }
        }

        return firstFailure ?: CoreDiscoveryResult.Missing(candidates)
    }

    internal fun candidatePaths(): List<Path> {
        val candidates = buildList {
            environment[FIREFLY_SING_BOX_ENV]
                ?.takeIf(String::isNotBlank)
                ?.let(::executableFromConfiguredPath)
                ?.let(::add)

            packagedResourcesDirectory
                ?.resolve(EXECUTABLE_NAME)
                ?.let(::add)

            add(workingDirectory.resolve("runtime/windows-x64/$EXECUTABLE_NAME"))

            environment["PATH"]
                .orEmpty()
                .split(System.getProperty("path.separator"))
                .filter(String::isNotBlank)
                .map(Paths::get)
                .map { it.resolve(EXECUTABLE_NAME) }
                .forEach(::add)
        }

        return candidates
            .map { it.toAbsolutePath().normalize() }
            .distinctBy { it.toString().lowercase(Locale.ROOT) }
    }

    private fun executableFromConfiguredPath(value: String): Path {
        val configured = Paths.get(value).toAbsolutePath().normalize()
        return if (configured.fileName?.toString()?.equals(EXECUTABLE_NAME, ignoreCase = true) == true) {
            configured
        } else {
            configured.resolve(EXECUTABLE_NAME)
        }
    }

    private fun inspect(executable: Path): CoreDiscoveryResult {
        return runCatching {
            val process = ProcessBuilder(executable.toString(), "version")
                .redirectErrorStream(true)
                .start()

            if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return CoreDiscoveryResult.Failed(executable, "核心版本检查超时")
            }

            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            if (process.exitValue() != 0) {
                return CoreDiscoveryResult.Failed(
                    executable,
                    output.ifBlank { "核心返回退出码 ${process.exitValue()}" }
                )
            }

            val version = parseSingBoxVersion(output)
                ?: return CoreDiscoveryResult.Failed(executable, "无法识别核心版本信息")

            CoreDiscoveryResult.Available(executable, version)
        }.getOrElse { error ->
            CoreDiscoveryResult.Failed(
                executable,
                error.message ?: error::class.simpleName.orEmpty().ifBlank { "核心检查失败" }
            )
        }
    }

    companion object {
        const val FIREFLY_SING_BOX_ENV = "FIREFLY_SING_BOX"
        private const val EXECUTABLE_NAME = "sing-box.exe"
        private const val VERSION_TIMEOUT_SECONDS = 4L

        internal fun parseSingBoxVersion(output: String): String? =
            Regex("""(?i)sing-box\s+version\s+([^\s]+)""")
                .find(output)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf(String::isNotBlank)
    }
}
