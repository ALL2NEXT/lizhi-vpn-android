package io.github.HanJason233.chimera.util

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallelMapTest {

    @Test
    fun preservesInputOrderAndBoundsConcurrency() = runBlocking {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)

        val result = parallelMapOrdered((0 until 40).toList(), maxConcurrency = 4) { value ->
            val current = active.incrementAndGet()
            peak.updateAndGet { previous -> maxOf(previous, current) }
            try {
                delay((value % 5).toLong())
                value * 2
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals((0 until 40).map { it * 2 }, result)
        assertTrue("peak concurrency was ${peak.get()}", peak.get() <= 4)
    }
}
