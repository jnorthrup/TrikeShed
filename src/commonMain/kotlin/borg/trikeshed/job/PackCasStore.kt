@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package borg.trikeshed.job

import borg.trikeshed.collections.SegmentIndex
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import borg.trikeshed.userspace.nio.file.spi.BoundedFileReader
import borg.trikeshed.userspace.nio.file.spi.BoundedReadLimitException
import borg.trikeshed.userspace.nio.file.spi.ChunkedFileReader
import borg.trikeshed.userspace.nio.file.spi.FileChangedException
import borg.trikeshed.userspace.nio.file.spi.DescriptorDuplication
import borg.trikeshed.util.toLowerHex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlin.concurrent.atomics.AtomicBoolean

/**
 * The CAS as one append-only pack plus a [SegmentIndex] keyed by the digest's first 8 bytes, over the
 * userspace NIO/uring [FileChannel]. A record is `kind:byte len:int digest:32 payload`: [BLOB] carries
 * the bytes; [REF] carries `size:long mtime:long path(utf8)` — a file the CAS names in place, never
 * copied. The index is not stored: opening scans record headers only (37 bytes each) and a torn tail
 * is truncated. No file per object, no rehash on read: the stored digest is compared to the asked id,
 * and a [REF] is served only while its file's size and mtime match the stamp it was put with.
 * Legacy [get] does not verify content. [getBounded] is a separate bounded verified read.
 * [putRef] validates the supplied bytes against the CID, not the current source path.
 * Appends are forced once per [sync], which the commit store calls before it publishes a root.
 * On recovery, any repeated prefix (including repeated references to one digest) triggers a second
 * header scan. Actual full-digest collisions trigger a third scan to build sparse collision buckets.
 * Recovery performance for repeated-prefix packs must be measured for the deployed workload.
 */
class PackCasStore(private val files: FileOperations, root: String) : CasStore(), BoundedCasReader, CasVerifier, AutoCloseable {
    private val channel: FileChannel
    private val index = SegmentIndex()
    /** Full-digest offsets exist only for prefixes known to collide. */
    private val collisions = HashMap<Long, MutableMap<String, IndexedRecord>>()
    private data class IndexedRecord(val offset: Long, val kind: Byte)
    private val header = ByteBuffer.allocate(HEADER)
    private var end = 0L
    /** Guards [header], [end], and [index]. Materialized verified reads hold this gate across I/O;
     * streaming verification holds it only for descriptor pinning and index revalidation. */
    private val held = AtomicBoolean(false)

    private inline fun <T> locked(block: () -> T): T {
        while (!held.compareAndSet(false, true)) Unit
        try { return block() } finally { held.store(false) }
    }

    override val durability: StorageDurability get() = StorageDurability.DURABLE

    init {
        files.mkdirs(root)
        channel = FileChannel.open(files.resolvePath(root, "pack"), StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
        val size = channel.size()
        var at = 0L
        var records = 0L
        while (at + HEADER <= size) {
            if (!readHeader(at)) break
            val kind = header.get(0); val len = header.getInt(1)
            if ((kind != BLOB && kind != REF) || len < 0 || at + HEADER + len > size) break
            index.put(header.getLong(5), at)
            records++
            at += HEADER + len
        }
        if (at < size) channel.truncate(at)
        end = at
        // Recovery is followed by millions of reads: settle the scanned runs
        // once instead of rescanning the heap tail and every run for each CID.
        index.compact()
        // SegmentIndex's tail is linear to search: detect collisions only after compaction.
        // When every prefix is unique the original single header scan remains sufficient.
        if (records != index.size.toLong()) recoverCollisions()
    }

    private fun recoverCollisions() {
        var at = 0L
        while (at < end) {
            check(readHeader(at)) { "pack header vanished during recovery" }
            val len = header.getInt(1)
            val key = header.getLong(5)
            val latest = index.get(key, -1L)
            if (at != latest && key !in collisions) {
                val digest = ByteArray(32) { header.get(5 + it) }
                check(latest >= 0 && readHeader(latest)) { "pack index points to a missing header" }
                if (!sameDigest(digest)) collisions[key] = HashMap()
            }
            at += HEADER + len
        }
        if (collisions.isEmpty()) return
        // A third sequential scan visits only actual collision buckets. Prefer a BLOB over
        // later REF records for the same digest, including histories produced by the old bug.
        at = 0L
        while (at < end) {
            check(readHeader(at)) { "pack header vanished during collision recovery" }
            val len = header.getInt(1)
            collisions[header.getLong(5)]?.let { bucket ->
                val kind = header.get(0)
                val hex = headerHex()
                val prior = bucket[hex]
                if (prior == null || prior.kind != BLOB || kind == BLOB)
                    bucket[hex] = IndexedRecord(at, kind)
            }
            at += HEADER + len
        }
    }

    /** Leaves the matching header loaded for the caller. */
    private fun find(cid: ContentId): Long {
        val key = fold(cid.hex)
        val at = collisions[key]?.get(cid.hex)?.offset ?: if (key in collisions) return -1L else index.get(key, -1L)
        return if (at >= 0 && readHeader(at) && matches(cid.hex)) at else -1L
    }

    private fun headerHex(): String {
        val out = CharArray(64)
        for (i in 0 until 32) {
            val b = header.get(5 + i).toInt() and 0xff
            out[2 * i] = HEX[b ushr 4]; out[2 * i + 1] = HEX[b and 15]
        }
        return out.concatToString()
    }

    override fun put(bytes: ByteArray): ContentId {
        val digest = sha256(bytes)
        val cid = idOf(digest)
        append(BLOB, digest, bytes, cid)
        return cid
    }

    /** Names [path]'s current bytes by content without copying them. */
    fun putFile(path: String): ContentId {
        val bytes = files.readAllBytes(path)
        return putRef(path, bytes, idOf(sha256(bytes)))
    }

    override fun putRef(path: String, bytes: ByteArray, cid: ContentId): ContentId {
        val snapshot = bytes.copyOf()
        require(ContentId.of(snapshot) == cid) { "Reference bytes do not match content ID" }
        val hex = cid.hex
        val digest = ByteArray(32) { i -> ((HEX.indexOf(hex[2 * i]) shl 4) or HEX.indexOf(hex[2 * i + 1])).toByte() }
        val name = path.encodeToByteArray()
        val payload = ByteBuffer.allocate(16 + name.size)
        payload.putLong(snapshot.size.toLong()); payload.putLong(files.lastModified(path)); payload.put(name)
        append(REF, digest, payload.array(), cid)
        return cid
    }

    private fun append(kind: Byte, digest: ByteArray, payload: ByteArray, cid: ContentId): Unit = locked {
        val key = fold(digest)
        val bucket = collisions[key]
        val prior = if (bucket == null) index.get(key, -1L) else bucket[cid.hex]?.offset ?: -1L
        // In the usual no-collision case this retains the original single index lookup.
        if (prior >= 0 && readHeader(prior)) {
            if (sameDigest(digest)) {
                // A BLOB owns immutable bytes; a REF may need a new path/stamp or promotion.
                if (header.get(0) == BLOB) return@locked
            } else {
                check(bucket == null) { "collision bucket points to a different digest" }
                collisions[key] = hashMapOf(headerHex() to IndexedRecord(prior, header.get(0)))
            }
        }
        val record = ByteBuffer.allocate(HEADER + payload.size)
        record.put(kind); record.putInt(payload.size); record.put(digest); record.put(payload)
        record.flip()
        var at = end
        while (record.hasRemaining()) {
            val n = channel.write(record, at)
            check(n > 0) { "pack append stalled at $at" }
            at += n
        }
        collisions[key]?.set(cid.hex, IndexedRecord(end, kind))
        index.put(key, end)
        end = at
    }

    override fun sync(): Unit = locked { channel.force(false) }

    override fun close(): Unit = locked { channel.close() }

    override fun holds(cid: ContentId): Boolean = locked {
        find(cid) >= 0
    }

    override fun get(cid: ContentId): ByteArray? = locked {
        val at = find(cid)
        if (at < 0) return@locked null
        val kind = header.get(0); val len = header.getInt(1)
        val payload = ByteBuffer.allocate(len)
        var read = 0
        while (read < len) {
            val n = channel.read(payload, at + HEADER + read)
            if (n <= 0) return@locked null
            read += n
        }
        val bytes = payload.array()
        if (kind == BLOB) return@locked bytes
        val stamp = ByteBuffer.wrap(bytes)
        val size = stamp.getLong(0); val mtime = stamp.getLong(8)
        val path = bytes.decodeToString(16, bytes.size)
        if (!files.isFile(path) || files.lastModified(path) != mtime) return@locked null
        val current = files.readAllBytes(path)
        if (current.size.toLong() != size) null else current
    }

    /** Bounded verified read; reference paths need a bounded filesystem adapter. Reference metadata is
     * separately capped at [MAX_REF_RECORD] bytes. This holds the store lock across source I/O and hashing. */
    override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? = locked {
        require(maxBytes >= 0 && maxBytes < Int.MAX_VALUE) { "Invalid CAS read limit" }
        val at = find(cid)
        if (at < 0) return@locked null
        val kind = header.get(0)
        val len = header.getInt(1)
        check(kind == BLOB || kind == REF) { "Unknown pack record kind" }
        check(len >= 0 && at + HEADER.toLong() + len <= end) { "Invalid pack record length" }
        if (kind == BLOB && len > maxBytes) throw BoundedReadLimitException(maxBytes)
        if (kind == REF) check(len in 16..MAX_REF_RECORD) { "Invalid reference record length" }
        val payload = ByteBuffer.allocate(len)
        var read = 0
        while (read < len) {
            val n = channel.read(payload, at + HEADER + read)
            check(n > 0) { "Pack record ended while reading" }
            read += n
        }
        val bytes = payload.array()
        if (kind == BLOB) {
            check(ContentId.of(bytes) == cid) { "Stored blob does not match its content ID" }
            return@locked bytes
        }
        val stamp = ByteBuffer.wrap(bytes)
        val size = stamp.getLong(0)
        val mtime = stamp.getLong(8)
        check(size >= 0) { "Invalid reference length" }
        val path = bytes.decodeToString(16, bytes.size)
        if (!files.isFile(path) || files.lastModified(path) != mtime) return@locked null
        if (size > maxBytes) throw BoundedReadLimitException(maxBytes)
        val reader = files as? BoundedFileReader
            ?: throw UnsupportedOperationException("Bounded source-backed reads are unavailable")
        val current = reader.readBounded(path, maxBytes)
        if (current.size > maxBytes) throw BoundedReadLimitException(maxBytes)
        if (current.size.toLong() != size) return@locked null
        check(ContentId.of(current) == cid) { "Referenced bytes do not match their content ID" }
        current
    }

    /** Verify observed bytes through a pinned pack reader without materializing the object. */
    override suspend fun verify(cid: ContentId, length: Long): Boolean {
        require(length >= 0) { "Invalid CAS length" }
        val context = currentCoroutineContext()
        suspend fun <T> access(block: () -> T): T {
            while (!held.compareAndSet(false, true)) {
                context.ensureActive()
                yield()
            }
            try { context.ensureActive(); return block() } finally { held.store(false) }
        }
        val duplicator = files as? DescriptorDuplication
            ?: throw UnsupportedOperationException("Owned pack readers are unavailable")
        data class Pin(val offset: Long, val end: Long, val collision: Boolean,
                       val descriptor: borg.trikeshed.userspace.nio.channels.PinnedFile)
        val key = fold(cid.hex)
        val pin = access {
            check(channel.isOpen()) { "CAS store is closed" }
            val bucket = collisions[key]
            val at = bucket?.get(cid.hex)?.offset ?: if (bucket == null) index.get(key, -1L) else -1L
            if (at < 0) return@access null
            Pin(at, end, bucket != null, channel.pin(duplicator))
        } ?: return false
        // The gate covers only index lookup and descriptor pinning, not backend creation or I/O.
        val reader = pin.descriptor.open()
        var failure: Throwable? = null
        try {
            fun readAt(at: Long, size: Int): ByteArray {
                val buffer = ByteBuffer.allocate(size)
                var read = 0
                while (read < size) {
                    context.ensureActive()
                    val count = reader.read(buffer, at + read)
                    check(count > 0) { "Indexed pack record ended while reading" }
                    read += count
                }
                return buffer.array()
            }
            fun headerMatches(bytes: ByteArray): Boolean {
                val hex = cid.hex
                for (i in 0 until 32) {
                    val value = bytes[5 + i].toInt() and 255
                    if (HEX[value ushr 4] != hex[2 * i] || HEX[value and 15] != hex[2 * i + 1]) return false
                }
                return true
            }
            val first = readAt(pin.offset, HEADER)
            val firstBuffer = ByteBuffer.wrap(first)
            check(firstBuffer.getLong(5) == key) { "Indexed pack header prefix changed" }
            if (!headerMatches(first)) {
                check(!pin.collision) { "Indexed pack digest changed" }
                // A prefix-only index cannot distinguish an absent collision from a changed suffix.
                return false
            }
            val kind = firstBuffer.get(0)
            val size = firstBuffer.getInt(1)
            check(kind == BLOB || kind == REF) { "Unknown pack record kind" }
            check(size >= 0 && pin.offset >= 0 && pin.offset <= pin.end - HEADER &&
                size.toLong() <= pin.end - pin.offset - HEADER) { "Invalid pack record length" }
            val reference = if (kind == REF) {
                check(size in 16..MAX_REF_RECORD) { "Invalid reference record length" }
                readAt(pin.offset + HEADER, size)
            } else null
            val digest = Sha256()
            if (reference == null) {
                if (size.toLong() != length) return false
                val buffer = ByteBuffer.allocate(64 * 1024)
                var offset = 0
                while (offset < size) {
                    context.ensureActive()
                    buffer.clear()
                    buffer.limit(minOf(buffer.array().size, size - offset))
                    val count = reader.read(buffer, pin.offset + HEADER + offset)
                    check(count > 0) { "Indexed pack record ended while reading" }
                    digest.update(buffer.array(), 0, count)
                    offset += count
                }
            } else {
                val stamp = ByteBuffer.wrap(reference)
                val declared = stamp.getLong(0)
                val mtime = stamp.getLong(8)
                check(declared >= 0) { "Invalid reference length" }
                if (declared != length) return false
                val path = reference.decodeToString(16, reference.size)
                if (!files.isFile(path) || files.lastModified(path) != mtime) return false
                val source = files as? ChunkedFileReader
                    ?: throw UnsupportedOperationException("Streaming source-backed verification is unavailable")
                var hashed = 0L
                val total = try { source.readChunks(path, declared) { bytes, count ->
                    context.ensureActive()
                    check(count in 1..bytes.size && hashed <= Long.MAX_VALUE - count) { "Invalid stream chunk" }
                    if (count.toLong() > declared - hashed) throw FileChangedException()
                    digest.update(bytes, 0, count)
                    hashed += count
                } } catch (_: FileChangedException) { return false }
                context.ensureActive()
                check(total == hashed) { "Stream length differs from consumed bytes" }
                if (total != declared || !files.isFile(path) || files.lastModified(path) != mtime) return false
            }
            context.ensureActive()
            check("sha256:" + digest.digest().toLowerHex() == cid.value) { "Stored bytes do not match their content ID" }
            if (!readAt(pin.offset, HEADER).contentEquals(first)) error("Indexed pack header changed")
            if (reference != null && !readAt(pin.offset + HEADER, size).contentEquals(reference)) return false
            return access {
                check(channel.isOpen()) { "CAS store is closed" }
                val bucket = collisions[key]
                val current = bucket?.get(cid.hex)?.offset ?: if (bucket == null) index.get(key, -1L) else -1L
                current == pin.offset
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            val primary = failure
            if (primary == null) reader.close()
            else try { reader.close() } catch (cleanup: Throwable) {
                if (cleanup !== primary) primary.addSuppressed(cleanup)
            }
        }
    }

    /** Distribution drilldown of the index: per segment, first key, last key, rows. */
    fun segments(): List<LongArray> = locked { index.segmentStats() }

    private fun readHeader(at: Long): Boolean {
        header.clear()
        var read = 0
        while (read < HEADER) {
            val n = channel.read(header, at + read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun sameDigest(digest: ByteArray): Boolean {
        for (i in 0 until 32) if (header.get(5 + i) != digest[i]) return false
        return true
    }

    private fun matches(hex: String): Boolean {
        for (i in 0 until 32) {
            val v = header.get(5 + i).toInt() and 0xff
            if (HEX[v ushr 4] != hex[2 * i] || HEX[v and 15] != hex[2 * i + 1]) return false
        }
        return true
    }

    companion object {
        const val BLOB: Byte = 1
        const val REF: Byte = 2
        const val HEADER = 1 + 4 + 32
        /** Fixed metadata bound independent of a caller's content allocation budget. */
        private const val MAX_REF_RECORD = 16 + 65_536
        private const val HEX = "0123456789abcdef"

        /** The index key: the digest's first 8 bytes, big-endian. */
        fun fold(digest: ByteArray): Long {
            var k = 0L
            for (i in 0 until 8) k = (k shl 8) or (digest[i].toLong() and 0xff)
            return k
        }

        fun fold(hex: String): Long {
            var k = 0L
            for (i in 0 until 16) k = (k shl 4) or HEX.indexOf(hex[i]).toLong()
            return k
        }

        /** The id of a digest already computed; ContentId.of would hash the digest again. */
        private fun idOf(digest: ByteArray): ContentId {
            val c = CharArray(64)
            for (i in 0 until 32) { val v = digest[i].toInt() and 0xff; c[2 * i] = HEX[v ushr 4]; c[2 * i + 1] = HEX[v and 15] }
            return ContentId("sha256:" + c.concatToString())
        }
    }
}
