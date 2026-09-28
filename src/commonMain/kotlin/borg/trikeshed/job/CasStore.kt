package borg.trikeshed.job

import borg.trikeshed.collections.associative.LinearHashMap as CasHashMap

/**
 * CasStore — content-addressable store.
 * In-memory implementation: SHA-256 keyed blob map with digest verification on read.
 */
open class CasStore protected constructor(
    private val blobs: CasHashMap<ContentId, ByteArray> = CasHashMap(),
) {
    /** Whether acknowledged publication survives a process restart; durable backends opt in. */
    open val durability: borg.trikeshed.userspace.nio.file.spi.StorageDurability
        get() = borg.trikeshed.userspace.nio.file.spi.StorageDurability.VOLATILE

    open fun put(bytes: ByteArray): ContentId {
        val cid = ContentId.of(bytes)
        blobs[cid] = bytes.copyOf()
        return cid
    }

    /** Makes every put so far durable; a root that names them is published only after this returns. */
    open fun sync() {}

    /**
     * Names [bytes], already read from the file at [path] and digested as [cid], by reference where
     * the store can: the file stays where it is and nothing is copied. Stores that hold bytes only
     * fall back to [put].
     */
    open fun putRef(path: String, bytes: ByteArray, cid: ContentId): ContentId = put(bytes)

    /** Whether [cid] was put here — named, whether or not its bytes are still servable; no bytes are read. */
    open fun holds(cid: ContentId): Boolean = get(cid) != null

    fun put(doc: borg.trikeshed.parse.confix.ConfixDoc): ContentId {
        val canonical = CanonicalCbor.encode(doc)
        return put(canonical)
    }

    open fun get(cid: ContentId): ByteArray? {
        val bytes = blobs[cid] ?: return null
        val actual = ContentId.of(bytes)
        if (actual != cid) {
            throw IllegalStateException("digest mismatch: stored blob does not match CID $cid")
        }
        return bytes.copyOf()
    }

    fun project(cid: ContentId): Lens {
        return ProjectionRegistry(this).project(cid)
    }

    fun corrupt(cid: ContentId) {
        blobs[cid]?.let { original ->
            val corrupted = original.copyOf()
            if (corrupted.isNotEmpty()) {
                corrupted[0] = (corrupted[0].toInt() xor 0xFF).toByte()
            }
            blobs[cid] = corrupted
        }
    }

    companion object {
        fun inMemory(): CasStore = CasStore()
    }
}