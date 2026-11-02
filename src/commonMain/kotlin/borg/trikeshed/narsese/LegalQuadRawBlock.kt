package borg.trikeshed.narsese

import borg.trikeshed.htx.client.ipfs.BlockStore
import borg.trikeshed.htx.client.ipfs.CID
import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.ipns.IpnsEncoding
import borg.trikeshed.job.ContentId
import borg.trikeshed.util.toLowerHex

/**
 * Candidate snapshots in one CIDv1 raw/sha2-256 block. The [BlockStore] is supplied by the
 * host; this adapter neither discovers a node nor transfers data over a network. It does not
 * import quads or validate their cited sources, quotations, authority, or review status.
 *
 * A raw block is opaque bytes, not a DAG-CBOR graph. The `sha256:` identifiers in a snapshot
 * are claims, not IPFS links; retaining this block does not retain its cited documents.
 *
 * The block cap matches KuboContextBlockExchange's single-block limit. [BlockStore.get] has no
 * bounded-read contract, so a malicious backend may allocate or return an oversized value
 * before this adapter can reject it. Copying and digest verification protect the decoder, not
 * the retrieval allocation, backend durability, or future mutation of stored bytes.
 */
object LegalQuadRawBlock {
    const val MAX_BYTES: Int = 2_097_152
    private const val RAW: ULong = 0x55u
    private const val SHA2_256: ULong = 0x12u

    /** Return the CID of the bytes offered to [store]; backend durability is not established. */
    suspend fun retain(store: BlockStore, candidates: List<LegalQuadCandidate>): IpnsCid {
        val bytes = LegalQuadSnapshot.encode(candidates)
        require(bytes.size <= MAX_BYTES) { "Candidate snapshot exceeds the raw-block limit" }
        val cid = IpnsCid.raw(bytes)
        store.put(CID(cid.bytes), bytes.copyOf())
        return cid
    }

    /** Decode only a size-bounded copy that hashes to this exact raw/sha2-256 CID. */
    suspend fun read(store: BlockStore, cid: IpnsCid): List<LegalQuadCandidate> {
        require(cid.codec == RAW) { "Candidate snapshot must use the raw codec" }
        val (algorithm, digest) = IpnsEncoding.multihashParts(cid.multihash)
        require(algorithm == SHA2_256 && digest.size == 32) { "Candidate snapshot must use sha2-256" }
        val stored = requireNotNull(store.get(CID(cid.bytes))) { "Candidate block is absent" }
        require(stored.size <= MAX_BYTES) { "Candidate block exceeds the raw-block limit" }
        val frozen = stored.copyOf()
        val expected = ContentId("sha256:" + digest.toLowerHex())
        return LegalQuadSnapshot.decode(frozen, expected)
    }
}
