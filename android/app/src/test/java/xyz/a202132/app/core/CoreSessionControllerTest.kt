package xyz.a202132.app.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import xyz.a202132.app.data.model.ProxyCore

class CoreSessionControllerTest {
    private class Session(private val events: MutableList<String>, private val name: String) : CoreSession {
        override val core = ProxyCore.SING_BOX
        override val socksPort = 1
        var closes = 0
        override fun traffic(): CoreTraffic? = null
        override fun close() { closes++; events += "close:$name" }
    }

    @Test fun closesOldReaderBeforeStartingAnotherCore() = runBlocking {
        val events = mutableListOf<String>()
        val controller = CoreSessionController()
        val first = Session(events, "one")
        controller.replace(controller.invalidate()) { events += "start:one"; first }
        controller.replace(controller.invalidate()) { events += "start:two"; Session(events, "two") }
        controller.stop()
        controller.stop()
        assertEquals(listOf("start:one", "close:one", "start:two", "close:two"), events)
        assertEquals(1, first.closes)
        assertNull(controller.current)
    }

    @Test fun stopDuringNativeStartReclaimsLateSession() = runBlocking {
        val controller = CoreSessionController()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = Session(mutableListOf(), "late")
        val ticket = controller.invalidate()
        val starting = launch {
            controller.replace(ticket) { entered.complete(Unit); release.await(); session }
        }
        entered.await()
        controller.invalidate()
        starting.cancel()
        release.complete(Unit)
        starting.join()
        controller.stop()
        assertEquals(1, session.closes)
        assertNull(controller.current)
    }

    @Test fun obsoleteRequestNeverStartsNativeCode() = runBlocking {
        val controller = CoreSessionController()
        val old = controller.invalidate()
        controller.invalidate()
        var started = false
        try {
            controller.replace(old) { started = true; Session(mutableListOf(), "invalid") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertFalse(started)
    }

    @Test fun failedStopPreventsStartingAnotherReader() = runBlocking {
        val controller = CoreSessionController()
        val broken = object : CoreSession {
            override val core = ProxyCore.XRAY
            override val socksPort = 1
            override fun traffic(): CoreTraffic? = null
            override fun close(): Unit = error("native reader still alive")
        }
        controller.replace(controller.invalidate()) { broken }
        var started = false
        try {
            controller.replace(controller.invalidate()) { started = true; Session(mutableListOf(), "second") }
            fail("A live native reader must retain ownership")
        } catch (_: IllegalStateException) { }
        assertFalse(started)
        assertSame(broken, controller.current)
    }
}
