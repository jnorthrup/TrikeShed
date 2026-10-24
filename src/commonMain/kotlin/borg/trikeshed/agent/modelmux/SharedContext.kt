package borg.trikeshed.agent.modelmux

import borg.trikeshed.cas.BlockTooLarge
import borg.trikeshed.cas.ContextBlockExchange
import borg.trikeshed.ipns.toIpnsCid
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import borg.trikeshed.lib.*

/** Where a block came from. [LOCAL] never touched the network. */
enum class BlockSource { LOCAL, PEER }

/** [LOCAL] answers from the local store only and never calls the exchange; [PEER] may fetch a verified miss. */
enum class SharedContextMode { LOCAL, PEER }

enum class ContextRejection {
    /** Not in the local store, and peer reads are off. */
    ABSENT,

    /** Larger than the policy ceiling, whether held locally or offered by a peer. */
    OVERSIZE,

    /** The bytes do not hash to the identity that was asked for. */
    DIGEST_MISMATCH,

    /** The transport failed. Cancellation is never reported this way. */
    EXCHANGE_FAILED,

    /** The requested scope is not one this reader is configured to serve. */
    FORBIDDEN_SCOPE,

    /** The manifest is pinned for a different scope than the one requested. */
    SCOPE_MISMATCH,

    /** The manifest bytes are not a canonical manifest. */
    MANIFEST_INVALID,

    /** A document's normalized spine is not the one the manifest recorded for it. */
    SPINE_MISMATCH,

    /** A pinned document is not valid UTF-8 text, so no line range could name its exact bytes. */
    NOT_TEXT,

    /** The closure needs more blocks or bytes than the policy allows. */
    BUDGET_EXCEEDED,
}

class ContextBlockException(
    val cid: ContentId,
    val reason: ContextRejection,
    detail: String,
    cause: Throwable? = null,
) : IllegalStateException("$reason ${cid.value}: $detail", cause)

data class ContextReadPolicy(
    val mode: SharedContextMode = SharedContextMode.LOCAL,
    val maxBlockBytes: Int = 1_048_576,
    val maxBlocks: Int = 128,
    val maxTotalBytes: Long = 8_388_608,
    /**
     * Scopes this reader serves. Empty by default: nothing is readable until a root names what it will serve. A wildcard
     * entry (a prefix ending in `/`, then `*`) grants every scope below that prefix, one grant per kind of run and not per
     * run; see [serves].
     */
    val allowedScopes: Set<String> = emptySet(),
) {
    init {
        require(maxBlockBytes > 0) { "maxBlockBytes must be positive" }
        require(maxBlocks > 0 && maxTotalBytes > 0) { "closure budgets must be positive" }
    }

    /**
     * Whether this reader serves [scope]: an exact entry, or a wildcard entry (a prefix ending in `/`, then `*`) with
     * something after the prefix. The grant is the operator's and is stateless; nothing a manifest says can add to it.
     * A bare `*` is not a universal grant, and the wildcard for `reel/` does not serve `reel/` itself or a sibling such
     * as `reelx/1`.
     */
    fun serves(scope: String): Boolean =
        scope in allowedScopes || allowedScopes.any { it.length > 2 && it.endsWith("/*") && scope.length >= it.length && scope.startsWith(it.substring(0, it.length - 1)) }
}

/** Verified bytes for one identity, and how they were obtained. */
class ContextBlock(val cid: ContentId, val bytes: ByteArray, val source: BlockSource)

/**
 * Work this reader did: local reads, and peer transfers it verified and admitted. An exchange that coalesces concurrent
 * requests reports its own physical count; see [SharedContextElement.counters].
 */
data class ContextReadCounters(val localReads: Int, val peerFetches: Int, val peerBytes: Long)

/**
 * Block-level shared-context reads: the local store first, an injected peer exchange on an allowed miss.
 *
 * Every byte that enters the local store from a peer has been checked against the identity that was asked for,
 * and is size-bounded, before it is admitted. A block that fails either check is never stored, so a hostile or
 * confused peer cannot pollute the cache. The resolver borrows its [cas] and [exchange] and owns no lifecycle.
 *
 * The lock guards the (non-thread-safe) store and the counters only; it is never held across a network fetch.
 * Two concurrent misses for the same block therefore both fetch here; collapsing those into one physical fetch
 * is the job of the owning element, which has the scope to do it without cancelling the other waiter.
 */
class SharedContext(
    private val cas: CasStore,
    private val exchange: ContextBlockExchange? = null,
    val policy: ContextReadPolicy = ContextReadPolicy(),
    /**
     * Rebuildable memory of what projections over this context's evidence have already computed. Projections made through
     * it ([BreadcrumbProjector.project]'s `facets`) cost less after the first; they never differ.
     */
    val facets: ProjectionFacets = ProjectionFacets(),
) {
    init {
        require(policy.mode == SharedContextMode.LOCAL || exchange != null) { "PEER mode needs a ContextBlockExchange" }
    }

    private val gate = Mutex()
    private var localReads = 0
    private var peerFetches = 0
    private var peerBytes = 0L

    /**
     * Admits locally authored bytes (a document of a new revision, or the manifest itself) under their own identity.
     * The ceiling that governs reads governs writes too, so nothing can be stored that could not be read back.
     */
    suspend fun admit(bytes: ByteArray): ContentId {
        if (bytes.size > policy.maxBlockBytes) {
            throw ContextBlockException(ContentId.of(bytes), ContextRejection.OVERSIZE, "${bytes.size} bytes exceeds ${policy.maxBlockBytes}")
        }
        return gate.withLock { cas.put(bytes) }
    }

    /**
     * Makes everything admitted so far durable ([borg.trikeshed.job.CasStore.sync]). A revision is named to anyone else only
     * after this returns, because a name for blocks that vanish in a crash is worse than no name.
     */
    suspend fun sync() {
        gate.withLock { cas.sync() }
    }

    suspend fun counters(): ContextReadCounters = gate.withLock { ContextReadCounters(localReads, peerFetches, peerBytes) }

    /** Whether this context reads and writes [other] itself, so a holder can tell a shared store from a look-alike. */
    internal fun usesStore(other: CasStore): Boolean = cas === other

    suspend fun block(cid: ContentId): ContextBlock {
        gate.withLock { readLocal(cid) }?.let { return it }

        val peer = exchange?.takeIf { policy.mode == SharedContextMode.PEER }
            ?: throw ContextBlockException(cid, ContextRejection.ABSENT, "not in the local store and peer reads are off")

        val fetched = try {
            peer.fetch(cid.toIpnsCid(RAW_CODEC), policy.maxBlockBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (tooLarge: BlockTooLarge) {
            throw ContextBlockException(cid, ContextRejection.OVERSIZE, tooLarge.message ?: "block exceeds the ceiling", tooLarge)
        } catch (failure: Throwable) {
            throw ContextBlockException(cid, ContextRejection.EXCHANGE_FAILED, failure.message ?: "peer fetch failed", failure)
        }
        if (fetched.size > policy.maxBlockBytes) {
            throw ContextBlockException(cid, ContextRejection.OVERSIZE, "${fetched.size} bytes exceeds ${policy.maxBlockBytes}")
        }
        if (ContentId.of(fetched) != cid) {
            throw ContextBlockException(cid, ContextRejection.DIGEST_MISMATCH, "peer bytes hash to ${ContentId.of(fetched).value}")
        }
        return gate.withLock {
            check(cas.put(fetched) == cid) { "store admitted ${cid.value} under a different identity" }
            peerFetches++
            peerBytes += fetched.size
            ContextBlock(cid, fetched, BlockSource.PEER)
        }
    }

    /**
     * Resolves one pinned revision to a fully verified [ContextSnapshot], or fails and returns nothing.
     *
     * Checked in this order, each failure typed and none partially visible: the requested [scope] is one this
     * reader serves (the manifest's own claim about its scope is untrusted metadata, so it can only be compared,
     * never believed); the manifest is canonical and pinned for that scope; every named document arrives within
     * the block, byte and size budgets; and each document's normalized spine is the one the manifest recorded.
     * Documents with identical bytes are fetched and counted once. Blocks fetched before a failure stay in the
     * cache (they are verified and content-addressed) but no snapshot, index entry or projection is produced.
     *
     * Manifests are hash-linked, so a revision cannot name itself or its descendants: there is no cycle to guard
     * and lineage is recorded, not traversed. Only the pinned revision's own documents are required.
     */
    suspend fun acquire(manifest: ContentId, scope: String): ContextSnapshot {
        if (!policy.serves(scope)) {
            throw ContextBlockException(manifest, ContextRejection.FORBIDDEN_SCOPE, "scope '$scope' is not served here")
        }
        val head = block(manifest)
        val pinned = try {
            BreadcrumbContextManifest.decode(head.bytes)
        } catch (malformed: ManifestFormatException) {
            throw ContextBlockException(manifest, ContextRejection.MANIFEST_INVALID, malformed.message ?: "malformed manifest", malformed)
        }
        if (pinned.scope != scope) {
            throw ContextBlockException(manifest, ContextRejection.SCOPE_MISMATCH, "pinned for '${pinned.scope}', requested '$scope'")
        }

        var blocks = 1
        var totalBytes = head.bytes.size.toLong()
        val fetchedBytes = LinkedHashMap<ContentId, ByteArray>()
        val derivedSpine = HashMap<ContentId, ContentId>()
        val documents = ArrayList<ContextDocument>(pinned.documents.size)
        for (i in 0 until pinned.documents.size) {
            val doc = pinned.documents[i]
            val bytes = fetchedBytes[doc.content] ?: run {
                if (blocks + 1 > policy.maxBlocks) {
                    throw ContextBlockException(doc.content, ContextRejection.BUDGET_EXCEEDED, "more than ${policy.maxBlocks} blocks")
                }
                val fetched = block(doc.content).bytes
                if (!ManifestDocument.isExactText(fetched)) {
                    throw ContextBlockException(doc.content, ContextRejection.NOT_TEXT, "'${doc.name}' is not valid UTF-8 text")
                }
                blocks++
                totalBytes += fetched.size
                if (totalBytes > policy.maxTotalBytes) {
                    throw ContextBlockException(doc.content, ContextRejection.BUDGET_EXCEEDED, "more than ${policy.maxTotalBytes} bytes")
                }
                fetchedBytes[doc.content] = fetched
                // Memoized by the digest-verified content id; the comparison below is still made per manifest entry.
                derivedSpine[doc.content] = facets.spineOf(doc.content, fetched)
                fetched
            }
            // Checked per ENTRY, not per block: two names for the same bytes must each carry the true spine.
            if (derivedSpine.getValue(doc.content) != doc.spine) {
                throw ContextBlockException(doc.content, ContextRejection.SPINE_MISMATCH, "'${doc.name}' normalizes to a different spine than recorded")
            }
            documents.add(ContextDocument(doc.name, doc.content, bytes))
        }
        return ContextSnapshot(manifest, pinned, documents.toSeries())
    }

    private fun readLocal(cid: ContentId): ContextBlock? {
        val held = try {
            cas.get(cid)
        } catch (corrupt: IllegalStateException) {
            throw ContextBlockException(cid, ContextRejection.DIGEST_MISMATCH, "the local copy is corrupt", corrupt)
        } ?: return null
        if (held.size > policy.maxBlockBytes) {
            throw ContextBlockException(cid, ContextRejection.OVERSIZE, "${held.size} bytes exceeds ${policy.maxBlockBytes}")
        }
        localReads++
        return ContextBlock(cid, held, BlockSource.LOCAL)
    }

    private companion object {
        /** IPFS "raw" codec: the block's bytes are exactly the content, so the sha256 identities coincide. */
        const val RAW_CODEC: ULong = 0x55u
    }
}
