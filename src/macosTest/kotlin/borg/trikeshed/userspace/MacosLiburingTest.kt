package borg.trikeshed.userspace

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MacosLiburingTest {

    @Test
    fun liburingFailsExplicitlyOnMacos() {
        val result = Liburing.open(entries = 2, flags = 0)

        assertTrue(result.isFailure)
        val failure = assertIs<UnsupportedOperationException>(result.exceptionOrNull())
        assertContains(failure.message.orEmpty(), "only available on linux")
    }
}
