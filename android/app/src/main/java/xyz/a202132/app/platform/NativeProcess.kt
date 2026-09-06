package xyz.a202132.app.platform

import android.os.Build
import java.util.concurrent.TimeUnit

/** java.lang.Process timed wait/isAlive are API 26; the app also supports API 24/25. */
internal val Process.isRunningCompat: Boolean
    get() = try { exitValue(); false } catch (_: IllegalThreadStateException) { true }

internal fun Process.awaitExit(timeout: Long, unit: TimeUnit): Boolean {
    val deadline = System.nanoTime() + unit.toNanos(timeout)
    while (isRunningCompat) {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) return false
        Thread.sleep(TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1, 20))
    }
    return true
}

internal fun Process.terminateCompat(force: Boolean = false) {
    if (force && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) destroyForcibly() else destroy()
}
