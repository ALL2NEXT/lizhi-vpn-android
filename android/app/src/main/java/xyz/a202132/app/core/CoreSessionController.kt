package xyz.a202132.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicLong

/** Serializes native ownership and invalidates work as soon as stop is requested. */
internal class CoreSessionController {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    @Volatile private var running: CoreSession? = null

    val current: CoreSession? get() = running

    fun invalidate(): Long = generation.incrementAndGet()
    fun isCurrent(ticket: Long): Boolean = generation.get() == ticket

    suspend fun replace(ticket: Long, start: suspend () -> CoreSession): CoreSession = mutex.withLock {
        checkCurrent(ticket)
        closeCurrent()
        // Keep ownership observable if cancellation races the return from a native start.
        val session = withContext(NonCancellable) { start() }
        try { currentCoroutineContext().ensureActive() } catch (cancelled: CancellationException) {
            session.close()
            throw cancelled
        }
        if (!isCurrent(ticket)) {
            withContext(NonCancellable) { session.close() }
            throw CancellationException("连接请求已被替换")
        }
        running = session
        session
    }

    suspend fun stop() = mutex.withLock { closeCurrent() }

    private fun closeCurrent() {
        val previous = running
        previous?.close()
        running = null
    }

    private fun checkCurrent(ticket: Long) {
        if (!isCurrent(ticket)) throw CancellationException("连接请求已取消")
    }
}
