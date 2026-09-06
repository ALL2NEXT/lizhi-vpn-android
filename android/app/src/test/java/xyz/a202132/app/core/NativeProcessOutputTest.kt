package xyz.a202132.app.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.TimeUnit

class NativeProcessOutputTest {
    @Test fun closedPipeReturnsFailureAndReleasesDrainWaiter() {
        val expected = IOException("Stream closed")
        val bytes = "ready\ntraffic\n".byteInputStream()
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = if (bytes.available() > 0) bytes.read() else throw expected
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                if (bytes.available() > 0) bytes.read(buffer, offset, length) else throw expected
            override fun close() { closed = true }
        }
        val lines = mutableListOf<String>()
        val output = NativeProcessOutput()
        assertSame(expected, output.read(input, lines::add))
        assertEquals(listOf("ready", "traffic"), lines)
        assertTrue(closed)
        assertTrue(output.awaitDrain(1, TimeUnit.SECONDS))
    }

    @Test fun pipeCloseFailureDoesNotEscapeAtNormalEof() {
        val expected = IOException("Pipe closed while releasing stream")
        val input = object : ByteArrayInputStream("ready\n".toByteArray()) {
            override fun close() { throw expected }
        }
        val output = NativeProcessOutput()
        assertSame(expected, output.read(input) {})
        assertTrue(output.awaitDrain(1, TimeUnit.SECONDS))
    }

    @Test fun malformedEventCallbackBecomesSessionFailure() {
        val expected = IllegalStateException("Malformed event")
        val output = NativeProcessOutput()
        assertSame(expected, output.read("bad-event\n".byteInputStream()) { throw expected })
        assertTrue(output.awaitDrain(1, TimeUnit.SECONDS))
    }

    @Test fun brokenProcessDoesNotReachUncaughtCoroutineHandlerOrCancelPeers() = runBlocking {
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            uncaught.add(error)
        })
        try {
            val failure = CompletableDeferred<Exception?>()
            val completed = CompletableDeferred<String>()
            val broken = scope.launch {
                failure.complete(NativeProcessOutput().read(object : InputStream() {
                    override fun read(): Int = throw IOException("process exited")
                }) {})
            }
            val healthy = scope.launch {
                NativeProcessOutput().read("ready\n".byteInputStream()) { completed.complete(it) }
            }
            joinAll(broken, healthy)
            assertTrue(failure.await() is IOException)
            assertEquals("ready", completed.await())
            assertTrue(uncaught.isEmpty())
        } finally { scope.cancel() }
    }
}
