package xyz.a202132.app.core

import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A child-process pipe can throw when another thread destroys the process. */
internal class NativeProcessOutput {
    private val drained = CountDownLatch(1)

    // Return failures to the session owner. Throwing from an IO launch under
    // SupervisorJob would still invoke Android's uncaught exception handler.
    fun read(input: InputStream, onLine: (String) -> Unit): Exception? = try {
        input.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        null
    } catch (error: Exception) {
        error
    } finally {
        drained.countDown()
    }

    fun awaitDrain(timeout: Long, unit: TimeUnit): Boolean = drained.await(timeout, unit)
}
