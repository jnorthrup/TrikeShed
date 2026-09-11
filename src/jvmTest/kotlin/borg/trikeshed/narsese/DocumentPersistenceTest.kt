package borg.trikeshed.narsese

import borg.trikeshed.couch.isam.WalFrame
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.channels.FileLock
import borg.trikeshed.userspace.nio.channels.ReadableByteChannel
import borg.trikeshed.userspace.nio.channels.WritableByteChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real userspace FileChannel persistence; controlled channel faults are explicitly injected. */
class DocumentPersistenceTest {
    private val parent = "/private/tmp"
    private fun path() = "$parent/document-persistence-${Random.nextLong().toULong()}.bin"

    @Test
    fun casReopensExactBytesAndDuplicatePutsDoNotAppend() {
        val path = path()
        val bytes = "source \uD83D\uDE80\n".encodeToByteArray()
        val expected = ContentId.of(bytes)
        DocumentCasStore.open(path, parent).use { cas ->
            assertEquals(expected, cas.put(bytes))
            val size = size(path)
            assertEquals(expected, cas.put(bytes.copyOf()))
            assertEquals(size, size(path))
            val returned = cas.get(expected)!!
            returned[0] = 0
            assertContentEquals(bytes, cas.get(expected))
        }
        DocumentCasStore.open(path, parent).use { cas ->
            assertContentEquals(bytes, cas.get(expected))
            assertNull(cas.get(ContentId.of("absent".encodeToByteArray())))
            val size = size(path)
            assertEquals(expected, cas.put(bytes))
            assertEquals(size, size(path))
            val empty = cas.put(byteArrayOf())
            assertContentEquals(byteArrayOf(), cas.get(empty))
        }
    }

    @Test
    fun ledgerPreservesMonotonicSequencesAndPayloadsOnReopen() = runBlocking {
        val path = path()
        DocumentAppendLog.open(path, parent).use { log ->
            assertEquals(1L, log.append(1, "reservation".encodeToByteArray()))
            assertEquals(3L, log.append(3, "submitted".encodeToByteArray()))
            assertFails { log.append(3, byteArrayOf()) }
            assertFails { log.append(2, byteArrayOf()) }
            log.flush()
        }
        DocumentAppendLog.open(path, parent).use { log ->
            val frames = mutableListOf<Pair<Long, String>>()
            assertEquals(3L, log.replay { sequence, payload -> frames.add(sequence to payload.decodeToString()) })
            assertEquals(listOf(1L to "reservation", 3L to "submitted"), frames)
            log.append(4, byteArrayOf())
            log.flush()
        }
    }

    @Test
    fun shortTrailingFrameRecoversOnlyValidatedPrefix() = runBlocking {
        val path = path()
        DocumentAppendLog.open(path, parent).use { it.append(1, "reserved".encodeToByteArray()); it.flush() }
        val validEnd = size(path)
        val torn = WalFrame.encode(2, "uncommitted tail".encodeToByteArray()).copyOf(WalFrame.HEADER_SIZE + 3)
        write(path, validEnd, torn)
        DocumentAppendLog.open(path, parent).use { log ->
            assertEquals(validEnd, size(path))
            assertEquals(1L, log.replay { sequence, payload ->
                assertEquals(1L, sequence); assertEquals("reserved", payload.decodeToString())
            })
            log.append(2, "retry".encodeToByteArray())
            log.flush()
        }
        DocumentAppendLog.open(path, parent).use { assertEquals(2L, it.replay { _, _ -> }) }
    }

    @Test
    fun completeChecksumCorruptionIsRefusedWithoutTruncation() {
        val path = path()
        DocumentAppendLog.open(path, parent).use {
            it.append(1, "reservation".encodeToByteArray())
            it.append(2, "submitted".encodeToByteArray())
            it.flush()
            it.injectCorruptionAfter(1)
            assertFails { it.append(3, byteArrayOf()) }
        }
        val corruptSize = size(path)
        val failure = assertFails { DocumentAppendLog.open(path, parent) }
        assertTrue("checksum" in failure.message.orEmpty())
        assertEquals(corruptSize, size(path), "A complete corrupt frame must not erase reservations")
    }

    @Test
    fun fileKindsVersionsAndOversizedFrameLengthsAreRejected() {
        val path = path()
        DocumentCasStore.open(path, parent).use { it.put("blob".encodeToByteArray()) }
        val casSize = size(path)
        assertFails { DocumentAppendLog.open(path, parent) }
        assertEquals(casSize, size(path))
        write(path, 7, byteArrayOf('2'.code.toByte()))
        assertFails { DocumentCasStore.open(path, parent) }

        val oversized = path()
        DocumentAppendLog.open(oversized, parent).close()
        val header = WalFrame.encode(1, byteArrayOf()).copyOf(WalFrame.HEADER_SIZE)
        ByteBuffer.wrap(header).putInt(14, Int.MAX_VALUE)
        write(oversized, DocumentFrameFile.HEADER_SIZE.toLong(), header)
        val size = size(oversized)
        val failure = assertFails { DocumentAppendLog.open(oversized, parent) }
        assertTrue("length invalid" in failure.message.orEmpty())
        assertEquals(size, size(oversized))
    }

    @Test
    fun partialIoLoopsAndPayloadBoundsUseTheSameFrameImplementation() {
        val path = path()
        val channel = FaultChannel(open(path), 3)
        var directorySyncs = 0
        DocumentFrameFile.initialize(channel, DocumentFileKind.LOG, maxPayload = 8,
            syncParent = { directorySyncs++; syncParent() }).use { file ->
            assertFails { file.append(1, ByteArray(9)) }
            val payload = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
            val frame = file.append(1, payload)
            file.flush()
            assertContentEquals(payload, file.read(frame))
            assertTrue(channel.reads > 1 && channel.writes > 1)
            assertEquals(1, directorySyncs)
        }
        assertFalse(channel.isOpen())
        DocumentFrameFile.open(path, parent, DocumentFileKind.LOG).use { assertEquals(1L, it.sequence) }
    }

    @Test
    fun uncertainWriteAndForceRequireReopen() {
        val path = path()
        val channel = FaultChannel(open(path), 3)
        DocumentFrameFile.initialize(channel, DocumentFileKind.LOG, syncParent = ::syncParent).use { file ->
            channel.writesBeforeFailure = 1
            assertFails { file.append(1, ByteArray(16)) }
            assertFails { file.append(1, byteArrayOf()) }
            assertFails { file.flush() }
        }
        DocumentFrameFile.open(path, parent, DocumentFileKind.LOG).use { file ->
            assertEquals(0L, file.sequence)
            assertEquals(DocumentFrameFile.HEADER_SIZE.toLong(), size(path))
        }

        val forcePath = path()
        val forceChannel = FaultChannel(open(forcePath), 3)
        DocumentFrameFile.initialize(forceChannel, DocumentFileKind.LOG, syncParent = ::syncParent).use { file ->
            file.append(1, "uncertain reservation".encodeToByteArray())
            forceChannel.failForce = true
            assertFails { file.flush() }
            assertFails { file.append(2, byteArrayOf()) }
        }
        DocumentFrameFile.open(forcePath, parent, DocumentFileKind.LOG).use { file ->
            assertEquals(1L, file.sequence, "A complete uncertain reservation survives recovery")
        }
    }

    @Test
    fun directorySyncFailureIsReportedAndClosesTheFile() {
        val path = path()
        val channel = FaultChannel(open(path), 3)
        assertFails {
            DocumentFrameFile.initialize(channel, DocumentFileKind.CAS) { error("injected directory fsync failure") }
        }
        assertFalse(channel.isOpen())
        DocumentCasStore.open(path, parent).use { assertEquals(ContentId.of(byteArrayOf()), it.put(byteArrayOf())) }
    }

    @Test
    fun concurrentCasPutsKeepIndexAndDuplicateAppendAtomic() = runBlocking {
        val path = path()
        val payloads = Array(8) { "source-$it".encodeToByteArray() }
        DocumentCasStore.open(path, parent).use { cas ->
            coroutineScope {
                repeat(64) { task -> launch(Dispatchers.Default) {
                    val payload = payloads[task % payloads.size]
                    val cid = cas.put(payload)
                    assertContentEquals(payload, cas.get(cid))
                } }
            }
            val expectedSize = DocumentFrameFile.HEADER_SIZE.toLong() +
                payloads.sumOf { WalFrame.HEADER_SIZE + it.size + 4 }
            assertEquals(expectedSize, size(path), "Each distinct CID appends once across worker threads")
        }
        DocumentCasStore.open(path, parent).use { cas ->
            for (payload in payloads) assertContentEquals(payload, cas.get(ContentId.of(payload)))
        }
    }

    @Test
    fun replayReleasesFileLockBeforeSuspendingCallback() = runBlocking {
        val path = path()
        DocumentAppendLog.open(path, parent).use { log ->
            log.append(1, "reserved".encodeToByteArray())
            log.flush()
            assertEquals(1L, log.replay { _, _ ->
                withContext(Dispatchers.Default) { log.append(2, "submitted".encodeToByteArray()); log.flush() }
            })
            assertEquals(2L, log.replay { _, _ -> })
        }
    }

    private fun open(path: String) = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
    private fun size(path: String) = FileChannel.open(path, StandardOpenOption.READ).use { it.size() }
    private fun syncParent() = FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
    private fun write(path: String, offset: Long, bytes: ByteArray) = open(path).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) check(channel.write(buffer, offset + buffer.position()) > 0)
        channel.force(true)
    }

    /** Real positional IO with deterministic short completions and explicit failure injection. */
    private class FaultChannel(private val delegate: FileChannel, private val chunk: Int) : FileChannel() {
        var writesBeforeFailure = Int.MAX_VALUE
        var failForce = false
        var reads = 0
        var writes = 0
        override fun read(dst: ByteBuffer, position: Long): Int = limited(dst) { reads++; delegate.read(dst, position) }
        override fun write(src: ByteBuffer, position: Long): Int = limited(src) {
            check(writesBeforeFailure-- > 0) { "injected partial write failure" }
            writes++; delegate.write(src, position)
        }
        private fun limited(buffer: ByteBuffer, operation: () -> Int): Int {
            val limit = buffer.limit()
            buffer.limit(minOf(limit, buffer.position() + chunk))
            try { return operation() } finally { buffer.limit(limit) }
        }
        override fun force(metaData: Boolean) { check(!failForce) { "injected fsync failure" }; delegate.force(metaData) }
        override fun close() = delegate.close()
        override fun isOpen() = delegate.isOpen()
        override fun implCloseChannel() = close()
        override fun begin() {}
        override fun end(completed: Boolean) {}
        override fun size() = delegate.size()
        override fun truncate(size: Long): FileChannel { delegate.truncate(size); return this }
        override fun position() = delegate.position()
        override fun position(newPosition: Long): FileChannel { delegate.position(newPosition); return this }
        override fun read(dst: ByteBuffer) = error("positional read required")
        override fun write(src: ByteBuffer) = error("positional write required")
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = error("positional read required")
        override fun read(dsts: Array<out ByteBuffer>): Long = error("positional read required")
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = error("positional write required")
        override fun write(srcs: Array<out ByteBuffer>): Long = error("positional write required")
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = error("unused")
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = error("unused")
        override fun map(mode: MapMode, position: Long, size: Long): borg.trikeshed.userspace.MemoryMapping = error("unused")
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = error("unused")
        override fun lock(): FileLock = error("unused")
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = error("unused")
        override fun tryLock(): FileLock? = error("unused")
    }
}
