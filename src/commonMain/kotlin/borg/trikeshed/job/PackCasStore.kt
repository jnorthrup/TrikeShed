@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package borg.trikeshed.job

import borg.trikeshed.collections.SegmentIndex
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import kotlin.concurrent.atomics.AtomicBoolean

/**
 * The CAS as one append-only pack plus a [SegmentIndex] keyed by the digest's first 8 bytes, over the
 * userspace NIO/uring [FileChannel]. A record is `kind:byte len:int digest:32 payload`: [BLOB] carries
 * the bytes; [REF] carries `size:long mtime:long path(utf8)` — a file the CAS names in place, never
 * copied. The index is not stored: opening scans record headers only (37 bytes each) and a torn tail
 * is truncated. No file per object, no rehash on read: the stored digest is compared to the asked id,
 * and a [REF] is served only while its file's size and mtime match the stamp it was put with.
 * Appends are forced once per [sync], which the commit store calls before it publishes a root.
 */
class PackCasStore(private val files: FileOperations, root: String) : CasStore() {
    private val channel: FileChannel
    private val index = SegmentIndex()
    private val header = ByteBuffer.allocate(HEADER)
    private var end = 0L
    /** Guards [header], [end] and [index]: every critical section is a few syscalls, so a spin suffices. */
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
        while (at + HEADER <= size) {
            if (!readHeader(at)) break
            val kind = header.get(0); val len = header.getInt(1)
            if ((kind != BLOB && kind != REF) || len < 0 || at + HEADER + len > size) break
            index.put(header.getLong(5), at)
            at += HEADER + len
        }
        if (at < size) channel.truncate(at)
        end = at
    }

    override fun put(bytes: ByteArray): ContentId {
        val digest = sha256(bytes)
        append(BLOB, digest, bytes)
        return idOf(digest)
    }

    /** Names [path]'s current bytes by content without copying them. */
    fun putFile(path: String): ContentId {
        val bytes = files.readAllBytes(path)
        return putRef(path, bytes, idOf(sha256(bytes)))
    }

    override fun putRef(path: String, bytes: ByteArray, cid: ContentId): ContentId {
        val hex = cid.hex
        val digest = ByteArray(32) { i -> ((HEX.indexOf(hex[2 * i]) shl 4) or HEX.indexOf(hex[2 * i + 1])).toByte() }
        val name = path.encodeToByteArray()
        val payload = ByteBuffer.allocate(16 + name.size)
        payload.putLong(bytes.size.toLong()); payload.putLong(files.lastModified(path)); payload.put(name)
        append(REF, digest, payload.array())
        return cid
    }

    private fun append(kind: Byte, digest: ByteArray, payload: ByteArray): Unit = locked {
        val key = fold(digest)
        val prior = index.get(key, -1L)
        if (prior >= 0 && readHeader(prior) && sameDigest(digest) && (kind == BLOB || header.get(0) == REF)) return@locked
        val record = ByteBuffer.allocate(HEADER + payload.size)
        record.put(kind); record.putInt(payload.size); record.put(digest); record.put(payload)
        record.flip()
        var at = end
        while (record.hasRemaining()) {
            val n = channel.write(record, at)
            check(n > 0) { "pack append stalled at $at" }
            at += n
        }
        index.put(key, end)
        end = at
    }

    override fun sync(): Unit = locked { channel.force(false) }

    override fun holds(cid: ContentId): Boolean = locked {
        val at = index.get(fold(cid.hex), -1L)
        at >= 0 && readHeader(at) && matches(cid.hex)
    }

    override fun get(cid: ContentId): ByteArray? = locked {
        val at = index.get(fold(cid.hex), -1L)
        if (at < 0 || !readHeader(at) || !matches(cid.hex)) return@locked null
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
