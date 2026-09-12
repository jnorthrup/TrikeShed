package borg.trikeshed.userspace

import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.channels.FileChannel as UserspaceFileChannel
import borg.trikeshed.userspace.nio.channels.NonWritableChannelException
import borg.trikeshed.userspace.nio.file.StandardOpenOption as UserspaceOpenOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test fun arbitrary_file_ranges_keep_backing_ownership_and_trace_after_descriptor_close() {
        val path = Files.createTempFile("trikeshed-mmap-view-", ".bin")
        var fd = -1
        try {
            Files.write(path, ByteArray(65536) { 17 })
            fd = JvmFileTable.register(JvmChannelDescriptor(FileChannel.open(path,
                StandardOpenOption.READ, StandardOpenOption.WRITE)))
            MemoryMappingConformance.fileView(fd, 65536, {
                assertEquals(0, JvmFileTable.close(fd))
                fd = -1
            }, { Files.readAllBytes(path) })
        } finally {
            if (fd >= 0) JvmFileTable.close(fd)
            Files.deleteIfExists(path)
        }
    }

    @Test fun throwing_trace_does_not_leak_or_reopen_native_memory() {
        val trace = object : UringTrace {
            override fun channel(id: Long, availability: String, nativeCapabilities: Long) {}
            override fun submit(channel: Long, submission: UringOp.Companion.UringSubmission) {}
            override fun complete(channel: Long, submission: UringOp.Companion.UringSubmission, result: Int) {}
            override fun failed(channel: Long, submission: UringOp.Companion.UringSubmission, failure: String) {}
            override fun memory(event: MemoryTraceEvent): Unit = error("observer failure")
        }
        val mapping = mapMemory(0, 65536, 3, 2 or 0x20, -1, 0, trace)
        try {
            mapping.retain()
            mapping.release()
            mapping[0] = 47
            assertEquals(47.toByte(), mapping[0])
        } finally { mapping.close() }
        assertEquals(false, mapping.isOpen)
        assertFailsWith<IllegalStateException> { mapping[0] }
        mapping.close()
    }

    @Test fun common_file_channel_mapping_survives_channel_close() {
        val path = Files.createTempFile("trikeshed-file-channel-map-", ".bin")
        try {
            Files.write(path, ByteArray(65536) { 17 })
            val channel = borg.trikeshed.userspace.nio.channels.FileChannel.open(path.toString(),
                borg.trikeshed.userspace.nio.file.StandardOpenOption.READ,
                borg.trikeshed.userspace.nio.file.StandardOpenOption.WRITE)
            try {
                val mapping = channel.map(borg.trikeshed.userspace.nio.channels.FileChannel.MapMode.READ_WRITE, 123, 65536L - 123)
                try {
                    channel.close()
                    mapping[0] = 71
                    mapping.sync()
                    assertEquals(71.toByte(), Files.readAllBytes(path)[123])
                } finally { mapping.close() }
            } finally { channel.close() }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun common_file_mapping_extends_through_facade_and_rejects_read_only_resize(): Unit = runBlocking {
        val completions = mutableListOf<Pair<UringSubmission, Int>>()
        val order = mutableListOf<String>()
        val trace = object : UringTrace {
            override fun channel(id: Long, availability: String, nativeCapabilities: Long) {}
            override fun submit(channel: Long, submission: UringSubmission) {}
            override fun complete(channel: Long, submission: UringSubmission, result: Int) {
                completions.add(submission to result)
                order.add("complete:${submission.opcode}")
            }
            override fun failed(channel: Long, submission: UringSubmission, failure: String) {}
            override fun memory(event: MemoryTraceEvent) {
                if (event.failure == null) order.add("memory:${event.operation}")
            }
        }
        val scope = CoroutineScope(coroutineContext + trace)
        val path = Files.createTempFile("trikeshed-file-map-extent-", ".bin")
        try {
            assertEquals(0L, Files.size(path))
            val writable = UserspaceFileChannel.open(scope, path.toString(),
                setOf(UserspaceOpenOption.READ, UserspaceOpenOption.WRITE))
            try {
                val mapping = writable.map(UserspaceFileChannel.MapMode.READ_WRITE, 123, 4096)
                try {
                    assertEquals(4219L, Files.size(path))
                    val truncate = completions.single { it.first.opcode == UringOp.FTRUNCATE }
                    assertEquals(4219L, truncate.first.offset)
                    assertEquals(0, truncate.second)
                    assertTrue(order.indexOf("complete:FTRUNCATE") < order.indexOf("memory:MAP"))
                    mapping[0] = 71
                    mapping[4095] = 72
                    mapping.sync()
                } finally { mapping.close() }
            } finally { writable.close() }
            val persisted = Files.readAllBytes(path)
            assertEquals(4219, persisted.size)
            assertTrue((0 until 123).all { persisted[it] == 0.toByte() })
            assertEquals(71.toByte(), persisted[123])
            assertEquals(72.toByte(), persisted[4218])

            val readOnly = UserspaceFileChannel.open(scope, path.toString(), setOf(UserspaceOpenOption.READ))
            try {
                assertFailsWith<NonWritableChannelException> {
                    readOnly.map(UserspaceFileChannel.MapMode.READ_ONLY, 123, 4097)
                }
                assertEquals(4219L, Files.size(path))
                assertEquals(1, completions.count { it.first.opcode == UringOp.FTRUNCATE })
                assertEquals(1, order.count { it == "memory:MAP" })
            } finally { readOnly.close() }
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
