package borg.trikeshed.userspace

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The JVM emulation's native transfer memory: the thread window, and malloc'd blocks past its cap, neither cleared. */
class JvmNativeWindowTest {
    private inline fun withFile(bytes: ByteArray, action: (Int) -> Unit) {
        val path = java.nio.file.Files.createTempFile("trikeshed-window-", ".bin")
        try {
            java.nio.file.Files.write(path, bytes)
            val fd = JvmFileSyscalls.openat(UringOp.AT_FDCWD, path.toString(), UringOp.O_RDWR, 0)
            assertTrue(fd >= 0, "openat errno ${-fd}")
            try { action(fd) } finally { JvmFileSyscalls.close(fd) }
        } finally {
            java.nio.file.Files.deleteIfExists(path)
        }
    }

    @Test fun transfersPastTheWindowCapRoundTripThroughMallocdMemory() = withFile(ByteArray(0)) { fd ->
        val size = JvmFileSyscalls.WINDOW_CAP + 4097
        val out = ByteArray(size) { (it % 251).toByte() }
        assertEquals(size, JvmFileSyscalls.transfer(fd, out, 0, size, 0, false))
        val back = ByteArray(size + 2) { 91 }
        assertEquals(size, JvmFileSyscalls.transfer(fd, back, 1, size, 0, true))
        assertContentEquals(out, back.copyOfRange(1, size + 1))
        assertEquals(91.toByte(), back[0])
        assertEquals(91.toByte(), back[size + 1])
    }

    @Test fun aShortReadStoresOnlyItsBytesWhateverTheWindowHeld() = withFile(ByteArray(4096) { 0x41 }) { full ->
        assertEquals(4096, JvmFileSyscalls.transfer(full, ByteArray(4096), 0, 4096, 0, true))
        withFile(byteArrayOf(1, 2, 3)) { short ->
            val destination = ByteArray(16) { 91 }
            assertEquals(3, JvmFileSyscalls.transfer(short, destination, 4, 8, 0, true))
            assertContentEquals(byteArrayOf(91, 91, 91, 91, 1, 2, 3, 91, 91, 91, 91, 91, 91, 91, 91, 91), destination,
                "past its completion the window keeps the caller's bytes, not the thread window's")
        }
    }
}
