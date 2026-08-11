package borg.trikeshed.util.oroboros

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * File-backed CAS store using sharded paths (sha256/<first-two>/<rest>).
 */
class FileCasStore(
    private val fileOps: FileOperations,
    private val casRoot: String
) : CasStore() {

    private fun getShardedPath(cid: ContentId): String {
        val hex = cid.hex
        require(hex.length == 64) { "Invalid ContentId hex length" }
        val dir = hex.substring(0, 2)
        val file = hex.substring(2)
        return fileOps.resolvePath(casRoot, "sha256", dir, file)
    }

    override fun put(bytes: ByteArray): ContentId {
        val cid = ContentId.of(bytes)
        val path = getShardedPath(cid)

        if (!fileOps.exists(path)) {
            val dirPath = fileOps.resolvePath(casRoot, "sha256", cid.hex.substring(0, 2))
            if (!fileOps.exists(dirPath)) {
                fileOps.mkdirs(dirPath)
            }
            fileOps.write(path, bytes)
        }

        // verification
        val reread = get(cid)
        if (reread == null || !reread.contentEquals(bytes)) {
            throw IllegalStateException("digest mismatch: failed to verify stored blob for CID $cid")
        }

        return cid
    }

    override fun get(cid: ContentId): ByteArray? {
        val path = getShardedPath(cid)
        if (!fileOps.exists(path)) return null

        val bytes = fileOps.readAllBytes(path)
        val actualCid = ContentId.of(bytes)
        if (actualCid != cid) {
            throw IllegalStateException("digest mismatch: stored blob does not match CID $cid")
        }
        return bytes
    }
}

/**
 * Sha2CasBus orchestrates durable-before-visible events and CAS interactions.
 * Features bounded backpressure and strictly finite ingress.
 */
class Sha2CasBus(
    private val fileCasStore: FileCasStore,
    capacity: Int = 64
) {
    // Channel is finite and suspending. bounded backpressure.
    private val eventChannel = Channel<ByteArray>(capacity = capacity)

    suspend fun put(bytes: ByteArray): ContentId {
        // durable put
        val cid = fileCasStore.put(bytes)

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
