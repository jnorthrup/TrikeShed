package borg.trikeshed.agent.modelmux

import borg.trikeshed.cas.ContextBlockExchange
import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.ipns.IpnsPublisher
import borg.trikeshed.ipns.toIpnsCid
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlinx.coroutines.CancellationException

/** Who may see what leaves this process. */
enum class PublicationAudience {
    /** Nothing leaves. The default. */
    LOCAL,

    /** Operator-named private peers or nodes. */
    PRIVATE_PEERS,

    /** Anyone. Content published here cannot be reliably retracted, so it needs an explicit approval on top of the audience. */
    PUBLIC,
}

/**
 * The operator's decision about what may leave. Content never decides this: a manifest cannot grant itself export,
 * name its own scope as approved, or vouch for its ancestors.
 */
class PublicationPolicy(
    val audience: PublicationAudience = PublicationAudience.LOCAL,
    val approvedScopes: Set<String> = emptySet(),
    /** Parent revisions approved for export together with their children. Any other ancestor stays private. */
    val approvedAncestry: Set<ContentId> = emptySet(),
    val publicApproved: Boolean = false,
    val maxBlocks: Int = 8_192,
    val maxBlockBytes: Int = 1_048_576,
    val maxTotalBytes: Long = 67_108_864,
    val maxLineageDepth: Int = 8,
) {
    init {
        require(maxBlocks > 0 && maxBlockBytes > 0 && maxTotalBytes > 0 && maxLineageDepth >= 0) { "publication budgets must be positive" }
    }
}

/** Why nothing was published. Every refusal happens before any block is sent. */
enum class PublicationRefusal {
    LOCAL_MODE, PUBLIC_NOT_APPROVED, SCOPE_NOT_APPROVED, PRIVATE_LINEAGE, CLOSURE_INCOMPLETE, MANIFEST_INVALID, BUDGET_EXCEEDED,
}

class PublicationRefused(val reason: PublicationRefusal, detail: String) : IllegalStateException("$reason: $detail")

/**
 * What happened to one block, with each fact kept separate: the node named it, retained it, and served it back. A
 * node that accepted a block and cannot serve it has not made it available.
 */
data class BlockReceipt(
    val cid: ContentId,
    val bytes: Int,
    val published: Boolean,
    val pinned: Boolean,
    val verified: Boolean,
    val failure: String?,
)

data class HeadReceipt(val name: String, val sequence: ULong, val value: String, val replicated: Boolean)

enum class PublicationStatus { COMPLETE, INCOMPLETE }

class PublicationReceipt(
    val root: ContentId,
    val blocks: List<BlockReceipt>,
    val status: PublicationStatus,
    /** Null when no head was announced: either none is configured, the closure is incomplete, or [headFailure] says why. */
    val head: HeadReceipt?,
    val headFailure: String?,
)

/** Announces a revision as an author's current head. Only ever called after every required block is live. */
fun interface HeadAnnouncer {
    suspend fun announce(root: ContentId): HeadReceipt
}

/**
 * The existing IPNS publisher as a [HeadAnnouncer]. It adds no cryptography and no sequencing of its own: the record,
 * its signature and its sequence number are the publisher's.
 */
class IpnsHeadAnnouncer(private val publisher: IpnsPublisher) : HeadAnnouncer {
    override suspend fun announce(root: ContentId): HeadReceipt {
        val report = publisher.publish(root)
        return HeadReceipt(publisher.name.toString(), report.record.sequence, report.record.value, report.complete)
    }
}

/**
 * Publishes one revision and its approved closure to a peer exchange, in this order and no other:
 *
 *  1. Policy gate, before anything is read: LOCAL never publishes; PUBLIC needs explicit approval.
 *  2. Closure plan from the LOCAL store only, with nothing sent: the manifest, every document it pins, and every parent
 *     the operator approved (recursively, bounded). An unapproved parent is refused, never traversed and never left as a
 *     dangling reference. A missing or out-of-scope piece refuses the whole publication.
 *  3. For each distinct block: publish (the node's answer must equal the identity computed locally), pin, then read it
 *     back and compare digests. Every block gets a receipt even if an earlier one failed.
 *  4. The head is announced only if every block is published, pinned and verified. A valid signed name over a block
 *     nobody can fetch is a broken pointer, so it is never made.
 */
class SharedContextPublication(
    private val cas: CasStore,
    private val exchange: ContextBlockExchange?,
    private val announcer: HeadAnnouncer?,
    private val policy: PublicationPolicy,
) {
    private fun refuse(reason: PublicationRefusal, detail: String) = PublicationRefused(reason, detail)

    suspend fun publish(root: ContentId): PublicationReceipt {
        if (policy.audience == PublicationAudience.LOCAL) throw refuse(PublicationRefusal.LOCAL_MODE, "audience is LOCAL; nothing leaves this process")
        if (policy.audience == PublicationAudience.PUBLIC && !policy.publicApproved) {
            throw refuse(PublicationRefusal.PUBLIC_NOT_APPROVED, "PUBLIC export needs explicit approval")
        }
        val peer = exchange ?: throw refuse(PublicationRefusal.LOCAL_MODE, "no exchange is configured")

        val blocks = plan(root)
        val receipts = ArrayList<BlockReceipt>(blocks.size)
        for ((cid, bytes) in blocks) receipts += send(peer, cid, bytes)

        val whole = receipts.all { it.verified }
        var head: HeadReceipt? = null
        var headFailure: String? = null
        if (whole && announcer != null) {
            try {
                head = announcer.announce(root)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                headFailure = describe(failure)
            }
        }
        return PublicationReceipt(root, receipts, if (whole) PublicationStatus.COMPLETE else PublicationStatus.INCOMPLETE, head, headFailure)
    }

    private fun plan(root: ContentId): List<Pair<ContentId, ByteArray>> {
        val planned = LinkedHashMap<ContentId, ByteArray>()
        var total = 0L

        fun held(cid: ContentId, what: String): ByteArray {
            val bytes = try { cas.get(cid) } catch (corrupt: IllegalStateException) { throw refuse(PublicationRefusal.CLOSURE_INCOMPLETE, "$what ${cid.value}: the local copy is corrupt") }
            return bytes ?: throw refuse(PublicationRefusal.CLOSURE_INCOMPLETE, "$what ${cid.value} is not held locally")
        }

        fun take(cid: ContentId, bytes: ByteArray) {
            if (cid in planned) return
            if (bytes.size > policy.maxBlockBytes) throw refuse(PublicationRefusal.BUDGET_EXCEEDED, "block ${cid.value} is ${bytes.size} bytes")
            total += bytes.size
            if (planned.size + 1 > policy.maxBlocks || total > policy.maxTotalBytes) throw refuse(PublicationRefusal.BUDGET_EXCEEDED, "closure exceeds ${policy.maxBlocks} blocks or ${policy.maxTotalBytes} bytes")
            planned[cid] = bytes
        }

        fun revision(cid: ContentId, depth: Int) {
            if (cid in planned) return
            if (depth > policy.maxLineageDepth) throw refuse(PublicationRefusal.BUDGET_EXCEEDED, "lineage is deeper than ${policy.maxLineageDepth}")
            val bytes = held(cid, "manifest")
            val manifest = try {
                BreadcrumbContextManifest.decode(bytes)
            } catch (bad: ManifestFormatException) {
                throw refuse(PublicationRefusal.MANIFEST_INVALID, "${cid.value}: ${bad.message}")
            }
            if (manifest.scope !in policy.approvedScopes) throw refuse(PublicationRefusal.SCOPE_NOT_APPROVED, "scope '${manifest.scope}' is not approved for export")
            take(cid, bytes)
            for (i in 0 until manifest.documents.size) {
                val pinned = manifest.documents[i]
                take(pinned.content, held(pinned.content, "document '${pinned.name}'"))
            }
            for (i in 0 until manifest.parents.size) {
                val parent = manifest.parents[i]
                if (parent !in policy.approvedAncestry) throw refuse(PublicationRefusal.PRIVATE_LINEAGE, "parent ${parent.value} is not approved for export")
                revision(parent, depth + 1)
            }
        }

        revision(root, 0)
        return planned.map { it.key to it.value }
    }

    private suspend fun send(peer: ContextBlockExchange, cid: ContentId, bytes: ByteArray): BlockReceipt {
        val expected = cid.toIpnsCid(RAW)
        var published = false
        var pinned = false
        var verified = false
        var failure: String? = null
        try {
            val named = peer.publish(bytes)
            if (named != expected) {
                failure = "the node named this block $named, not $expected"
            } else {
                published = true
                peer.pin(expected)
                pinned = true
                val back = peer.fetch(expected, maxOf(1, bytes.size))
                if (ContentId.of(back) == cid) verified = true else failure = "the bytes read back do not match the block"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (broken: Throwable) {
            failure = describe(broken)
        }
        return BlockReceipt(cid, bytes.size, published, pinned, verified, failure)
    }

    private fun describe(failure: Throwable): String = "${failure::class.simpleName}: ${failure.message.orEmpty().take(120)}"

    private companion object {
        const val RAW = 0x55uL
    }
}
