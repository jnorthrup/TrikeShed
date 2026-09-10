package borg.trikeshed.userspace

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemoryMappingTest {
    @Test fun anonymous_memory_has_owned_lifetime_and_real_byte_access() =
        MemoryMappingConformance.anonymous(65536)

    @Test fun shared_and_private_mappings_outlive_their_descriptor() {
        val path = Files.createTempFile("trikeshed-mmap-", ".bin")
        var fd = -1
        try {
            Files.write(path, ByteArray(65536) { 17 })
            fd = JvmFileTable.register(JvmChannelDescriptor(FileChannel.open(path,
                StandardOpenOption.READ, StandardOpenOption.WRITE)))
            MemoryMappingConformance.file(fd, 65536, {
                assertEquals(0, JvmFileTable.close(fd))
                fd = -1
            }, { Files.readAllBytes(path) })
        } finally {
            if (fd >= 0) JvmFileTable.close(fd)
            Files.deleteIfExists(path)
        }
    }

    @Test fun java_file_mapping_does_not_silently_extend_the_file() {
        val path = Files.createTempFile("trikeshed-mmap-size-", ".bin")
        var fd = -1
        try {
            Files.write(path, ByteArray(4096))
            fd = JvmFileTable.register(JvmChannelDescriptor(FileChannel.open(path,
                StandardOpenOption.READ, StandardOpenOption.WRITE)))
            assertFailsWith<UnsupportedOperationException> { mapMemory(0, 8192, 3, 1, fd, 0) }
            assertEquals(4096L, Files.size(path))
        } finally {
            if (fd >= 0) JvmFileTable.close(fd)
            Files.deleteIfExists(path)
        }
    }

    @Test fun common_file_channel_mapping_survives_channel_close() {
        val path = Files.createTempFile("trikeshed-file-channel-map-", ".bin")
        try {
            Files.write(path, ByteArray(65536) { 17 })
            val channel = borg.trikeshed.userspace.nio.channels.FileChannel.open(path.toString(),
                borg.trikeshed.userspace.nio.file.StandardOpenOption.READ,
                borg.trikeshed.userspace.nio.file.StandardOpenOption.WRITE)
            try {
                val mapping = channel.map(borg.trikeshed.userspace.nio.channels.FileChannel.MapMode.READ_WRITE, 0, 65536)
                try {
                    channel.close()
                    mapping[0] = 71
                    mapping.sync()
                    assertEquals(71.toByte(), Files.readAllBytes(path)[0])
                } finally { mapping.close() }
            } finally { channel.close() }
        } finally { Files.deleteIfExists(path) }
    }


    @Test fun completion_releases_its_borrow_while_byte_access_is_active() {
        val physical = mapMemory(0, 65536, 3, 2 or 0x20, -1, 0)
        val entered = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val readerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val mapping = MemoryMapping(object : MemoryMappingAccess {
            override val address: Long get() = physical.address
            override val length: Long get() = physical.length
            override fun get(offset: Long): Byte {
                entered.countDown()
                check(proceed.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return physical[offset]
            }
            override fun set(offset: Long, value: Byte) { physical[offset] = value }
            override fun read(offset: Long, dst: ByteArray, start: Int, length: Int) = physical.read(offset, dst, start, length)
            override fun write(offset: Long, src: ByteArray, start: Int, length: Int) = physical.write(offset, src, start, length)
            override fun sync(offset: Long, length: Long, flags: Int) = physical.sync(offset, length, flags)
            override fun close() = physical.close()
        }, 3)
        mapping.retain()
        val reader = Thread {
            try { mapping[0] } catch (failure: Throwable) { readerFailure.set(failure) }
        }
        reader.start()
        try {
            check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            mapping.release()
            assertEquals(true, mapping.isOpen)
        } finally {
            proceed.countDown()
            reader.join(5000)
            physical.close()
        }
        assertEquals(false, reader.isAlive)
        assertEquals(null, readerFailure.get())
        mapping.close()
        assertEquals(false, mapping.isOpen)
    }

}
