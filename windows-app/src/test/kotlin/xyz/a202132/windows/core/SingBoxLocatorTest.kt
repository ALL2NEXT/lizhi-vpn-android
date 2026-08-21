package xyz.a202132.windows.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SingBoxLocatorTest {
    @Test
    fun parsesStableVersionOutput() {
        assertEquals(
            "1.13.0",
            SingBoxLocator.parseSingBoxVersion("sing-box version 1.13.0\nEnvironment: go1.25 windows/amd64")
        )
    }

    @Test
    fun parsesVersionCaseInsensitively() {
        assertEquals(
            "1.14.0-alpha.31",
            SingBoxLocator.parseSingBoxVersion("Sing-Box Version 1.14.0-alpha.31")
        )
    }

    @Test
    fun rejectsUnknownOutput() {
        assertNull(SingBoxLocator.parseSingBoxVersion("not a sing-box executable"))
    }
}
