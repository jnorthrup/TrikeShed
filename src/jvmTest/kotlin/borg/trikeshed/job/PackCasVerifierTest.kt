package borg.trikeshed.job

import borg.trikeshed.userspace.nio.file.spi.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.ByteBuffer as JvmBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel as JvmChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import borg.trikeshed.userspace.JvmChannelDescriptor
import borg.trikeshed.userspace.JvmFileTable
import kotlin.test.*

class PackCasVerifierTest {
    @TempDir lateinit var directory: Path

    private class ProbeChannel(
        private val source: JvmChannel,
        private val beforeRead: (Long) -> Unit,
        private val afterClose: () -> Unit = {},
    ) : JvmChannel() {
        override fun read(dst: JvmBuffer): Int = source.read(dst)
        override fun read(dsts: Array<out JvmBuffer>, offset: Int, length: Int): Long = source.read(dsts, offset, length)
        override fun write(src: JvmBuffer): Int = source.write(src)
        override fun write(srcs: Array<out JvmBuffer>, offset: Int, length: Int): Long = source.write(srcs, offset, length)
        override fun position(): Long = source.position()
        override fun position(newPosition: Long): JvmChannel { source.position(newPosition); return this }
        override fun size(): Long = source.size()
        override fun truncate(size: Long): JvmChannel { source.truncate(size); return this }
        override fun force(metaData: Boolean) = source.force(metaData)
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = source.transferTo(position, count, target)
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = source.transferFrom(src, position, count)
        override fun read(dst: JvmBuffer, position: Long): Int { beforeRead(position); return source.read(dst, position) }
        override fun write(src: JvmBuffer, position: Long): Int = source.write(src, position)
        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = source.map(mode, position, size)
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = source.lock(position, size, shared)
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = source.tryLock(position, size, shared)
        override fun implCloseChannel() { try { source.close() } finally { afterClose() } }
    }

    @Test fun blobAndReferenceAreVerifiedWithoutMaterializing(): Unit = runBlocking {
        val files = JvmFileOperations()
        PackCasStore(files, directory.resolve("cas").toString()).use { cas ->
            val bytes = ByteArray(180_001) { (it * 17).toByte() }
            val blob = cas.put(bytes)
            assertTrue(cas.verify(blob, bytes.size.toLong()))
            assertFalse(cas.verify(blob, bytes.size.toLong() - 1))
            assertFalse(cas.verify(ContentId.of("absent".encodeToByteArray()), 6))
            val differentSuffix = ContentId(blob.value.dropLast(1) + if (blob.value.last() == '0') "1" else "0")
            assertFalse(cas.verify(differentSuffix, bytes.size.toLong()))
            val file = directory.resolve("reference")
            val refBytes = ByteArray(180_002) { (it * 23).toByte() }
            Files.write(file, refBytes)
            val ref = ContentId.of(refBytes)
            cas.putRef(file.toString(), refBytes, ref)
            assertTrue(cas.verify(ref, refBytes.size.toLong()))
            val mtime = Files.getLastModifiedTime(file)
            Files.write(file, ByteArray(refBytes.size) { 42 })
            Files.setLastModifiedTime(file, mtime)
            assertFailsWith<IllegalStateException> { cas.verify(ref, refBytes.size.toLong()) }
        }
    }

    @Test fun blobCorruptionIsDetected(): Unit = runBlocking {
        val root = directory.resolve("corrupt")
        PackCasStore(JvmFileOperations(), root.toString()).use { cas ->
            val bytes = "known bytes".encodeToByteArray()
            val cid = cas.put(bytes)
            RandomAccessFile(root.resolve("pack").toFile(), "rw").use { f ->
                f.seek(PackCasStore.HEADER.toLong())
                f.write(0)
            }
            assertFailsWith<IllegalStateException> { cas.verify(cid, bytes.size.toLong()) }
        }
    }

    @Test fun stalledPinnedBlobReadDoesNotHoldPackGate(): Unit = runBlocking {
        val root = directory.resolve("stalled-blob")
        val delegate = JvmFileOperations()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val files = object : FileOperations by delegate, DescriptorDuplication {
            override fun duplicateDescriptor(id: Int): Int {
                val source = JvmChannel.open(root.resolve("pack"), java.nio.file.StandardOpenOption.READ)
                return JvmFileTable.register(JvmChannelDescriptor(ProbeChannel(source, { position ->
                    if (position >= PackCasStore.HEADER) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                })))
            }
        }
        PackCasStore(files, root.toString()).use { cas ->
            val bytes = ByteArray(100_000) { it.toByte() }
            val cid = cas.put(bytes)
            val verifying = async(Dispatchers.IO) { cas.verify(cid, bytes.size.toLong()) }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                withTimeout(5_000) { withContext(Dispatchers.IO) { cas.put("other".encodeToByteArray()) } }
            } finally { release.countDown() }
            assertTrue(withTimeout(5_000) { verifying.await() })
        }
    }

    @Test fun readerCloseFailureDoesNotReplaceReadFailure(): Unit = runBlocking {
        val root = directory.resolve("failed-reader")
        val delegate = JvmFileOperations()
        val files = object : FileOperations by delegate, DescriptorDuplication {
            override fun duplicateDescriptor(id: Int): Int {
                val source = JvmChannel.open(root.resolve("pack"), java.nio.file.StandardOpenOption.READ)
                return JvmFileTable.register(JvmChannelDescriptor(ProbeChannel(source,
                    { throw java.io.IOException("probe read failure") },
                    { throw java.io.IOException("probe close failure") })))
            }
        }
        PackCasStore(files, root.toString()).use { cas ->
            val bytes = "source".encodeToByteArray()
            val cid = cas.put(bytes)
            val failure = assertFailsWith<borg.trikeshed.userspace.nio.UringIOException> { cas.verify(cid, bytes.size.toLong()) }
            assertEquals(borg.trikeshed.userspace.UringOp.READ, failure.operation)
            assertTrue(failure.suppressedExceptions.isNotEmpty(), "the close error must be suppressed")
        }
    }

    @Test fun replacedOrTruncatedPackIsRejected(): Unit = runBlocking {
        val root = directory.resolve("replaced")
        PackCasStore(JvmFileOperations(), root.toString()).use { cas ->
            val bytes = "stored content".encodeToByteArray()
            val cid = cas.put(bytes)
            val pack = root.resolve("pack")
            val replacement = root.resolve("other")
            Files.copy(pack, replacement)
            RandomAccessFile(pack.toFile(), "rw").use { f ->
                f.seek(PackCasStore.HEADER.toLong())
                f.write(0)
            }
            Files.move(replacement, pack, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertFailsWith<IllegalStateException> { cas.verify(cid, bytes.size.toLong()) }
        }
        val truncated = directory.resolve("truncated")
        PackCasStore(JvmFileOperations(), truncated.toString()).use { cas ->
            val bytes = "stored content".encodeToByteArray()
            val cid = cas.put(bytes)
            RandomAccessFile(truncated.resolve("pack").toFile(), "rw").use { it.setLength(PackCasStore.HEADER.toLong()) }
            assertFailsWith<IllegalStateException> { cas.verify(cid, bytes.size.toLong()) }
        }
        val header = directory.resolve("truncated-header")
        PackCasStore(JvmFileOperations(), header.toString()).use { cas ->
            val bytes = "stored content".encodeToByteArray()
            val cid = cas.put(bytes)
            RandomAccessFile(header.resolve("pack").toFile(), "rw").use { it.setLength(10) }
            assertFailsWith<IllegalStateException> { cas.verify(cid, bytes.size.toLong()) }
        }
    }

    @Test fun verificationRequiresOwnedReaderCapability(): Unit = runBlocking {
        val delegate = JvmFileOperations()
        val files = object : FileOperations by delegate, ChunkedFileReader by delegate {}
        PackCasStore(files, directory.resolve("unpinned").toString()).use { cas ->
            val bytes = "stored content".encodeToByteArray()
            val cid = cas.put(bytes)
            val failure = assertFailsWith<UnsupportedOperationException> { cas.verify(cid, bytes.size.toLong()) }
            assertContains(failure.message.orEmpty(), "Owned pack readers")
        }
    }

    @Test fun referenceRequiresStreamingAdapter(): Unit = runBlocking {
        val delegate = JvmFileOperations()
        val files = object : FileOperations by delegate, DescriptorDuplication by delegate {}
        PackCasStore(files, directory.resolve("unsupported").toString()).use { cas ->
            val file = directory.resolve("file")
            val bytes = "source".encodeToByteArray()
            Files.write(file, bytes)
            val cid = ContentId.of(bytes)
            cas.putRef(file.toString(), bytes, cid)
            assertFailsWith<UnsupportedOperationException> { cas.verify(cid, bytes.size.toLong()) }
        }
    }

    @Test fun referenceVerificationDoesNotHoldPackLockAndClosesAfterCancellation(): Unit = runBlocking {
        val delegate = JvmFileOperations()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val files = object : FileOperations by delegate, DescriptorDuplication by delegate, ChunkedFileReader {
            override fun readChunks(path: String, limit: Long, consume: (ByteArray, Int) -> Unit): Long =
                delegate.readChunks(path, limit) { bytes, count ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    consume(bytes, count)
                }
        }
        PackCasStore(files, directory.resolve("concurrency").toString()).use { cas ->
            val file = directory.resolve("file")
            val bytes = ByteArray(100_000) { it.toByte() }
            Files.write(file, bytes)
            val cid = ContentId.of(bytes)
            cas.putRef(file.toString(), bytes, cid)
            val verification = async(Dispatchers.IO) { cas.verify(cid, bytes.size.toLong()) }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                withTimeout(5_000) { withContext(Dispatchers.IO) { cas.put("other".encodeToByteArray()) } }
                verification.cancel()
            } finally { release.countDown() }
            verification.cancelAndJoin()
            assertTrue(cas.verify(ContentId.of("other".encodeToByteArray()), 5))
        }
    }

    @Test fun referenceReplacementInvalidatesAnInFlightVerification(): Unit = runBlocking {
        val delegate = JvmFileOperations()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val files = object : FileOperations by delegate, DescriptorDuplication by delegate, ChunkedFileReader {
            override fun readChunks(path: String, limit: Long, consume: (ByteArray, Int) -> Unit): Long =
                delegate.readChunks(path, limit) { bytes, count ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    consume(bytes, count)
                }
        }
        PackCasStore(files, directory.resolve("replacement-race").toString()).use { cas ->
            val bytes = ByteArray(100_000) { it.toByte() }
            val first = directory.resolve("first")
            val second = directory.resolve("second")
            Files.write(first, bytes)
            Files.write(second, ByteArray(bytes.size) { 42 })
            val cid = ContentId.of(bytes)
            cas.putRef(first.toString(), bytes, cid)
            val verification = async(Dispatchers.IO) { cas.verify(cid, bytes.size.toLong()) }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                cas.putRef(second.toString(), bytes, cid)
            } finally { release.countDown() }
            assertFalse(withTimeout(5_000) { verification.await() })
            assertFailsWith<IllegalStateException> { cas.verify(cid, bytes.size.toLong()) }
        }
    }

    @Test fun referencePathReplacementDoesNotAuthenticateAnotherInode(): Unit = runBlocking {
        val delegate = JvmFileOperations()
        val source = directory.resolve("source")
        val replacement = directory.resolve("swap")
        val bytes = ByteArray(100_000) { it.toByte() }
        Files.write(source, bytes)
        Files.write(replacement, ByteArray(bytes.size) { 42 })
        Files.setLastModifiedTime(replacement, Files.getLastModifiedTime(source))
        val files = object : FileOperations by delegate, DescriptorDuplication by delegate, ChunkedFileReader {
            var swapped = false
            override fun readChunks(path: String, limit: Long, consume: (ByteArray, Int) -> Unit): Long =
                delegate.readChunks(path, limit) { chunk, count ->
                    if (!swapped) {
                        swapped = true
                        Files.move(replacement, source, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                    consume(chunk, count)
                }
        }
        PackCasStore(files, directory.resolve("path-swap").toString()).use { cas ->
            val cid = ContentId.of(bytes)
            cas.putRef(source.toString(), bytes, cid)
            assertFalse(cas.verify(cid, bytes.size.toLong()), "A replaced path cannot authenticate its old descriptor")
        }
    }

    @Test fun referencePayloadMutationInvalidatesAnInFlightRead(): Unit = runBlocking {
        val delegate = JvmFileOperations()
        val root = directory.resolve("payload-race")
        val source = directory.resolve("source-a")
        val replacement = directory.resolve("source-b")
        val bytes = ByteArray(100_000) { it.toByte() }
        Files.write(source, bytes)
        Files.write(replacement, ByteArray(bytes.size) { 42 })
        Files.setLastModifiedTime(replacement, Files.getLastModifiedTime(source))
        assertEquals(source.toString().encodeToByteArray().size, replacement.toString().encodeToByteArray().size)
        val files = object : FileOperations by delegate, DescriptorDuplication by delegate, ChunkedFileReader {
            var mutated = false
            override fun readChunks(path: String, limit: Long, consume: (ByteArray, Int) -> Unit): Long =
                delegate.readChunks(path, limit) { chunk, count ->
                    if (!mutated) {
                        mutated = true
                        RandomAccessFile(root.resolve("pack").toFile(), "rw").use { pack ->
                            pack.seek(PackCasStore.HEADER.toLong() + 16)
                            pack.write(replacement.toString().encodeToByteArray())
                        }
                    }
                    consume(chunk, count)
                }
        }
        PackCasStore(files, root.toString()).use { cas ->
            val cid = ContentId.of(bytes)
            cas.putRef(source.toString(), bytes, cid)
            assertFalse(cas.verify(cid, bytes.size.toLong()))
        }
    }

    @Test fun referenceMutationCannotSynthesizeANonexistentSnapshot(): Unit = runBlocking {
        val delegate = JvmFileOperations()
        val source = directory.resolve("mutating")
        val expected = ByteArray(100_000) { (it * 7).toByte() }
        val initial = expected.copyOf().also { it.fill(42, 65_536, it.size) }
        val changed = expected.copyOf().also { it.fill(42, 0, 65_536) }
        Files.write(source, initial)
        val mtime = Files.getLastModifiedTime(source)
        val files = object : FileOperations by delegate, DescriptorDuplication by delegate, ChunkedFileReader {
            var mutated = false
            override fun readChunks(path: String, limit: Long, consume: (ByteArray, Int) -> Unit): Long =
                delegate.readChunks(path, limit) { chunk, count ->
                    if (!mutated) {
                        mutated = true
                        Files.write(source, changed)
                        Files.setLastModifiedTime(source, mtime)
                    }
                    consume(chunk, count)
                }
        }
        PackCasStore(files, directory.resolve("mutation").toString()).use { cas ->
            val cid = ContentId.of(expected)
            cas.putRef(source.toString(), expected, cid)
            assertFalse(cas.verify(cid, expected.size.toLong()), "The expected bytes never existed as one source snapshot")
        }
    }

    @Test fun fifoReplacementDoesNotBlockReferenceOpen(): Unit = runBlocking {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/mkfifo")))
        val delegate = JvmFileOperations()
        val source = directory.resolve("source-fifo")
        val bytes = "source".encodeToByteArray()
        Files.write(source, bytes)
        val mtime = Files.getLastModifiedTime(source)
        val timestamp = directory.resolve("fifo-timestamp")
        Files.write(timestamp, bytes)
        Files.setLastModifiedTime(timestamp, mtime)
        val files = object : FileOperations by delegate, DescriptorDuplication by delegate, ChunkedFileReader by delegate {
            var replaced = false
            override fun isFile(path: String): Boolean {
                val result = delegate.isFile(path)
                if (!replaced && path == source.toString()) {
                    replaced = true
                    Files.delete(source)
                    check(ProcessBuilder("/usr/bin/mkfifo", path).start().waitFor() == 0)
                    check(ProcessBuilder("/usr/bin/touch", "-r", timestamp.toString(), path).start().waitFor() == 0)
                }
                return result
            }
        }
        PackCasStore(files, directory.resolve("fifo").toString()).use { cas ->
            val cid = ContentId.of(bytes)
            cas.putRef(source.toString(), bytes, cid)
            val pending = async(Dispatchers.IO) { runCatching { cas.verify(cid, bytes.size.toLong()) } }
            try {
                val result = withTimeout(5_000) { pending.await() }
                val failure = assertIs<IllegalStateException>(result.exceptionOrNull())
                assertContains(failure.message.orEmpty(), "not a regular file")
            } finally {
                if (!pending.isCompleted) {
                    val writer = async(Dispatchers.IO) { Files.newOutputStream(source).close() }
                    pending.cancelAndJoin()
                    writer.await()
                }
            }
        }
    }

    @Test fun invalidStreamChunkIsRejected(): Unit = runBlocking {
        val delegate = JvmFileOperations()
        val files = object : FileOperations by delegate, DescriptorDuplication by delegate, ChunkedFileReader {
            override fun readChunks(path: String, limit: Long, consume: (ByteArray, Int) -> Unit): Long {
                consume(ByteArray(1), 2)
                return limit
            }
        }
        PackCasStore(files, directory.resolve("bad-reader").toString()).use { cas ->
            val bytes = "source".encodeToByteArray()
            val source = directory.resolve("source")
            Files.write(source, bytes)
            val cid = ContentId.of(bytes)
            cas.putRef(source.toString(), bytes, cid)
            val failure = assertFailsWith<IllegalStateException> { cas.verify(cid, bytes.size.toLong()) }
            assertContains(failure.message.orEmpty(), "Invalid stream chunk")
        }
        val misreported = object : FileOperations by delegate, DescriptorDuplication by delegate, ChunkedFileReader {
            override fun readChunks(path: String, limit: Long, consume: (ByteArray, Int) -> Unit): Long {
                val bytes = "source".encodeToByteArray()
                consume(bytes, bytes.size)
                return limit + 1
            }
        }
        PackCasStore(misreported, directory.resolve("misreported-reader").toString()).use { cas ->
            val bytes = "source".encodeToByteArray()
            val source = directory.resolve("source")
            val cid = ContentId.of(bytes)
            cas.putRef(source.toString(), bytes, cid)
            val failure = assertFailsWith<IllegalStateException> { cas.verify(cid, bytes.size.toLong()) }
            assertContains(failure.message.orEmpty(), "Stream length differs from consumed bytes")
        }
        var calls = 0
        val overlong = object : FileOperations by delegate, DescriptorDuplication by delegate, ChunkedFileReader {
            override fun readChunks(path: String, limit: Long, consume: (ByteArray, Int) -> Unit): Long {
                while (true) {
                    calls++
                    consume(ByteArray(1), 1)
                }
            }
        }
        PackCasStore(overlong, directory.resolve("overlong-reader").toString()).use { cas ->
            val bytes = "source".encodeToByteArray()
            val source = directory.resolve("source")
            val cid = ContentId.of(bytes)
            cas.putRef(source.toString(), bytes, cid)
            assertFalse(cas.verify(cid, bytes.size.toLong()))
            assertEquals(bytes.size + 1, calls)
        }
    }

    @Test fun verificationFailsWhenStoreClosesDuringReferenceRead(): Unit = runBlocking {
        val delegate = JvmFileOperations()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val files = object : FileOperations by delegate, DescriptorDuplication by delegate, ChunkedFileReader {
            override fun readChunks(path: String, limit: Long, consume: (ByteArray, Int) -> Unit): Long =
                delegate.readChunks(path, limit) { bytes, count ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    consume(bytes, count)
                }
        }
        val cas = PackCasStore(files, directory.resolve("close").toString())
        val file = directory.resolve("file")
        val bytes = "source".encodeToByteArray()
        Files.write(file, bytes)
        val cid = ContentId.of(bytes)
        cas.putRef(file.toString(), bytes, cid)
        val verification = async(Dispatchers.IO) { runCatching { cas.verify(cid, bytes.size.toLong()) } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            cas.close()
        } finally { release.countDown() }
        assertIs<IllegalStateException>(withTimeout(5_000) { verification.await() }.exceptionOrNull())
    }
}
