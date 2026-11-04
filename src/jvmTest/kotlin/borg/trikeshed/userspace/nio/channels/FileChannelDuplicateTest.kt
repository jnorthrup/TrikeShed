package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.file.spi.DescriptorDuplication
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class FileChannelDuplicateTest {
    @TempDir lateinit var directory: Path

    @Test fun duplicateRetainsOriginalFileAfterReplacementAndClose() {
        assumeTrue(System.getProperty("os.name").lowercase().let { "mac" in it || "linux" in it })
        val path = directory.resolve("pack")
        val replacement = directory.resolve("replacement")
        Files.writeString(path, "original")
        Files.writeString(replacement, "replaced")
        val original = FileChannel.open(path.toString(), StandardOpenOption.READ)
        val copy = try { original.pin(JvmFileOperations()).open() } catch (failure: Throwable) { original.close(); throw failure }
        try {
            Files.move(replacement, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            original.close()
            val bytes = ByteBuffer.allocate(8)
            assertEquals(8, copy.read(bytes, 0))
            assertContentEquals("original".encodeToByteArray(), bytes.array())
            assertEquals("replaced", Files.readString(path))
        } finally {
            copy.close()
            original.close()
        }
    }

    @Test fun writeOnlySourceCannotCreateAReadableDuplicate() {
        val path = directory.resolve("write-only")
        Files.writeString(path, "content")
        FileChannel.open(path.toString(), StandardOpenOption.WRITE).use { channel ->
            assertFailsWith<NonReadableChannelException> { channel.pin(JvmFileOperations()) }
        }
    }

    @Test fun closeWaitsUntilDescriptorDuplicationFinishes() {
        assumeTrue(System.getProperty("os.name").lowercase().let { "mac" in it || "linux" in it })
        val path = directory.resolve("concurrent")
        Files.writeString(path, "original")
        val original = FileChannel.open(path.toString(), StandardOpenOption.READ)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val outcome = AtomicReference<Result<FileChannel>>()
        val delegate = JvmFileOperations()
        val operations = object : DescriptorDuplication {
            override fun duplicateDescriptor(id: Int): Int {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return delegate.duplicateDescriptor(id)
            }
        }
        val duplicate = Thread { outcome.set(runCatching { original.pin(operations).open() }) }
        val closer = Thread { original.close() }
        var checked = false
        duplicate.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            closer.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (closer.isAlive && closer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, closer.state, "close must wait at the descriptor ownership gate")
            checked = true
        } finally {
            release.countDown()
            duplicate.join(5_000)
            closer.join(5_000)
            if (!duplicate.isAlive && !closer.isAlive) original.close()
            if (!checked) outcome.get()?.getOrNull()?.close()
        }
        assertFalse(duplicate.isAlive)
        assertFalse(closer.isAlive)
        val copy = requireNotNull(outcome.get()).getOrThrow()
        try {
            val buffer = ByteBuffer.allocate(8)
            assertEquals(8, copy.read(buffer, 0))
            assertContentEquals("original".encodeToByteArray(), buffer.array())
        } finally { copy.close(); original.close() }
    }
}
