package borg.trikeshed.cas

import borg.trikeshed.ipns.IpnsCid

/**
 * Peer transport for raw blocks, and nothing else.
 *
 * Deliberately outside [borg.trikeshed.job.CasStore]: that API is synchronous and its `get` is a pure local
 * read, so hiding a network round trip inside it would change latency and failure behaviour for every existing
 * caller. A resolver that is willing to wait asks THIS interface after a local miss, verifies what came back
 * itself, and only then admits it to the local store.
 *
 * Implementations own transport only. They do not decide trust: the caller checks digest, size and scope, so a
 * peer that returns the wrong bytes (or too many) is rejected rather than believed. Identities are raw-codec
 * CIDs derived from the local sha256 [borg.trikeshed.job.ContentId]; see [borg.trikeshed.ipns.toIpnsCid].
 *
 * Cancellation must propagate: an implementation never converts a CancellationException into a result.
 */
interface ContextBlockExchange {
    /** Raw block bytes for [cid]. Reads at most [maxBytes] and fails rather than truncate. */
    suspend fun fetch(cid: IpnsCid, maxBytes: Int): ByteArray

    /** Makes [bytes] available to peers and returns the identity the peer network will serve them under. */
    suspend fun publish(bytes: ByteArray): IpnsCid

    /** Asks the node to retain [cid]. Success is its own receipt: it says nothing about any other block. */
    suspend fun pin(cid: IpnsCid)
}

/**
 * A transport stopped reading because the block is larger than the ceiling it was given. Reported to the caller as
 * [borg.trikeshed.agent.modelmux.ContextRejection.OVERSIZE], not as a transport failure: the peer is healthy, the block is refused.
 */
class BlockTooLarge(val limit: Int) : IllegalStateException("block exceeds $limit bytes")
