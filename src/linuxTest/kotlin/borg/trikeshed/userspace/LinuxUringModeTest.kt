package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.lib.get
import borg.trikeshed.lib.toSeries
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxUringModeTest {
    @Test fun capabilityVocabularyAndFileAdviceSurviveModeSwitch() = runBlocking {
        val previous = getenv("TRIKESHED_URING_MODE")?.toKString()
        try {
            check(setenv("TRIKESHED_URING_MODE", "emulated", 1) == 0)
            val emulated = openUserspaceChannelBackend(2)
            val capabilities = try {
                assertEquals(0L, emulated.nativeCapabilities)
                assertTrue(emulated.capabilities and UringOp.FADVISE.mask != 0L)
                val request = UringSubmission(UringOp.FADVISE, -1, 0, 0, 0, userData = 1)
                assertEquals(UringCompletion(1, -9, 0), emulated.batchEnqueue(listOf(request).toSeries())[0])
                emulated.capabilities
            } finally { emulated.close() }

            check(setenv("TRIKESHED_URING_MODE", "auto", 1) == 0)
            val selected = openUserspaceChannelBackend(2)
            try {
                assertEquals(capabilities, selected.capabilities)
                assertEquals(0L, selected.nativeCapabilities and selected.capabilities.inv())
                val request = UringSubmission(UringOp.FADVISE, -1, 0, 0, 0, userData = 2)
                assertEquals(UringCompletion(2, -9, 0), selected.batchEnqueue(listOf(request).toSeries())[0])
            } finally { selected.close() }
        } finally {
            if (previous == null) unsetenv("TRIKESHED_URING_MODE")
            else setenv("TRIKESHED_URING_MODE", previous, 1)
        }
    }
}
