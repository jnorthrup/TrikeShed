package borg.trikeshed.util.oroboros

import borg.trikeshed.cas.CasReplicationElement
import borg.trikeshed.cas.CasPaths
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.BoundedCasReader
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.BoundedFileReader
import borg.trikeshed.userspace.nio.file.spi.BoundedReadLimitException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * File-backed CAS store using sha256/<hex[0]>/<hex[1]>/<hex[2]>/<hex[3]>/<hex[4:]>.
 */
class FileCasStore(
    private val fileOps: FileOperations,
    private val casRoot: String
) : CasStore(), BoundedCasReader {

    override val durability get() = fileOps.durability

    private fun getShardedPath(cid: ContentId): String =
        fileOps.resolvePath(casRoot, CasPaths.blob(cid))

    override fun put(bytes: ByteArray): ContentId {
        val cid = ContentId.of(bytes)
        val path = getShardedPath(cid)

        if (fileOps.exists(path)) {
            val existing = fileOps.readAllBytes(path)
            if (ContentId.of(existing) == cid) return cid
        }

        val dirPath = fileOps.resolvePath(casRoot, CasPaths.shard(cid))
        if (!fileOps.exists(dirPath)) fileOps.mkdirs(dirPath)
        // A corrupt object at the expected CID path is repaired from the
        // caller-provided bytes without ever exposing a partial object.
        fileOps.writeAtomically(path, bytes)

        // Publication is not visible until the exact bytes are re-read and
        // checked against both the requested payload and its address.
        val reread = fileOps.readAllBytes(path)
        if (!reread.contentEquals(bytes) || ContentId.of(reread) != cid) {
            throw IllegalStateException("digest mismatch: failed to verify stored blob for CID $cid")
        }

        return cid
    }

    override fun get(cid: ContentId): ByteArray? {
        val shardedPath = getShardedPath(cid)
        val path = if (fileOps.exists(shardedPath)) shardedPath
            else fileOps.resolvePath(casRoot, CasPaths.legacyBlob(cid))
        if (!fileOps.exists(path)) return null

        val bytes = fileOps.readAllBytes(path)
        val actualCid = ContentId.of(bytes)
        if (actualCid != cid) {
            throw IllegalStateException("digest mismatch: stored blob does not match CID $cid")
        }
        return bytes
    }

    override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? {
        require(maxBytes >= 0 && maxBytes < Int.MAX_VALUE) { "Invalid CAS read limit" }
        // Never fall back to readAllBytes: an implementation must explicitly enforce the
        // bound on the opened file, including when the file changes during the read.
        val reader = fileOps as? BoundedFileReader
            ?: throw UnsupportedOperationException("Bounded file reads are unavailable")
        val shardedPath = getShardedPath(cid)
        val path = if (fileOps.exists(shardedPath)) shardedPath
            else fileOps.resolvePath(casRoot, CasPaths.legacyBlob(cid))
        if (!fileOps.exists(path)) return null
        val bytes = reader.readBounded(path, maxBytes)
        // The backing implementation is trusted to bound allocation, but a broken
        // implementation must not be able to return an oversized successful read.
        if (bytes.size > maxBytes) throw BoundedReadLimitException(maxBytes)
        check(ContentId.of(bytes) == cid) { "digest mismatch: stored blob does not match CID $cid" }
        return bytes
    }
}

/**
 * Sha2CasBus orchestrates durable-before-visible events and CAS interactions.
 * Features bounded backpressure and strictly finite ingress.
 */
class Sha2CasBus(
    private val fileCasStore: FileCasStore,
    capacity: Int = 64,
    private val replicationElement: CasReplicationElement? = null
) {
    // Channel is finite and suspending. bounded backpressure.
    private val eventChannel = Channel<ByteArray>(capacity = capacity)

    suspend fun put(bytes: ByteArray): ContentId {
        // durable put
        val cid = fileCasStore.put(bytes)

        // replicate if wired
        replicationElement?.replicate(cid, bytes)

        // visible event
        // channel is finite, send will suspend if full
        eventChannel.send(bytes)

        return cid
    }

    fun subscribe(): ReceiveChannel<ByteArray> = eventChannel

    fun close() {
        eventChannel.close()
    }
}
