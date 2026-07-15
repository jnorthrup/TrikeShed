package borg.trikeshed.job

/**
 * CasStore — content-addressable store.
 * In-memory implementation: SHA-256 keyed blob map with digest verification on read.
 */
open class CasStore private constructor(
    private val blobs: MutableMap<ContentId, ByteArray> = mutableMapOf(),
) {
    open fun put(bytes: ByteArray): ContentId {
        val cid = ContentId.of(bytes)
        blobs[cid] = bytes.copyOf()
        return cid
    }

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

    fun contains(cid: ContentId): Boolean = get(cid) != null

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
