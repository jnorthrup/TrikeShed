package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.file.Files as UserFiles
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JvmUserspaceChannelBackendTest {

    @Test
    fun opened_file_fd_is_visible_to_common_ring_backend() {
        val path = java.nio.file.Files.createTempFile("trikeshed-uring-", ".bin")
        java.nio.file.Files.write(path, ByteArray(8))
        try {
            val file = UserFiles.open(path.toString(), readOnly = false)
            val channel = Channels.open(entries = 8)
            val writeBuffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))

            channel.write(file, writeBuffer, offset = 2L, userData = 11L)
            assertEquals(1, channel.submit())
            assertEquals(listOf(SelectionResult(4, 11L)), channel.wait(minComplete = 1))

            val readBuffer = ByteBuffer.allocate(4)
            channel.read(file, readBuffer, offset = 2L, userData = 12L)
            assertEquals(1, channel.submit())
            assertEquals(listOf(SelectionResult(4, 12L)), channel.wait(minComplete = 1))
            assertContentEquals(byteArrayOf(1, 2, 3, 4), readBuffer.array())

            file.close()
            assertFalse(file.isOpen())
        } finally {
            java.nio.file.Files.deleteIfExists(path)
        }
    }
}
