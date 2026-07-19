package borg.trikeshed.userspace

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class LinuxLiburingStateTest {

    @Test
    fun unopenedRingRejectsSubmissionWithoutTouchingTheKernel() {
        Liburing.close()

        val submit = Liburing.submit()
        assertTrue(submit.isFailure)
        assertContains(submit.exceptionOrNull()?.message.orEmpty(), "ring is not open")

        val peek = Liburing.peekCqe()
        assertTrue(peek.isFailure)
        assertContains(peek.exceptionOrNull()?.message.orEmpty(), "ring is not open")
    }
}
