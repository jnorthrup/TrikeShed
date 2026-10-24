package borg.trikeshed.agent.modelmux

import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.ipns.IpnsCrypto
import borg.trikeshed.ipns.IpnsName
import borg.trikeshed.ipns.IpnsRecord
import borg.trikeshed.job.ContentId
import borg.trikeshed.util.toLowerHex
import kotlin.time.Clock
import kotlin.time.Instant

/** Why an offered head was or was not taken. Refusals never change what is held. */
enum class HeadDecision { ACCEPTED_FIRST, ACCEPTED_NEWER, UNCHANGED, REFUSED_OLDER, REFUSED_CONFLICT, REFUSED_INVALID }

/** One author's current head: the revision it names, under that author's own signed name. */
class AuthorHead(val author: IpnsName, val manifest: ContentId, val sequence: ULong, val validUntil: Instant, val record: IpnsRecord)

/**
 * Independent, authenticated heads, one per author, never merged.
 *
 * Merging two authors' work is a revision (a new manifest with both as parents) that someone accepted, not something a
 * mutable name can do. So there is no shared head here and no race over one: each author's name moves only forward
 * under that author's own signature, and an older record, however valid, cannot move it back.
 *
 * Signature, name binding and expiry are [IpnsRecord.verify]'s; nothing here adds cryptography. A task that pinned a
 * manifest keeps it; it hears of a moved head only by asking [updateFor], and adopts it by choosing to.
 */
class AuthorHeads(private val crypto: IpnsCrypto, private val now: () -> Instant = { Clock.System.now() }) {
    private val heads = LinkedHashMap<String, AuthorHead>()

    fun offer(author: IpnsName, wire: ByteArray): HeadDecision {
        val record = try {
            IpnsRecord.verify(author, wire, now(), crypto)
        } catch (invalid: RuntimeException) {
            return HeadDecision.REFUSED_INVALID
        }
        val manifest = revisionOf(record.value) ?: return HeadDecision.REFUSED_INVALID
        val key = author.toString()
        val held = heads[key]
        val decision = when {
            held == null -> HeadDecision.ACCEPTED_FIRST
            record.sequence > held.sequence -> HeadDecision.ACCEPTED_NEWER
            record.sequence < held.sequence -> return HeadDecision.REFUSED_OLDER
            held.manifest == manifest -> return HeadDecision.UNCHANGED
            else -> return HeadDecision.REFUSED_CONFLICT          // two values at one sequence: equivocation, keep what was held
        }
        heads[key] = AuthorHead(author, manifest, record.sequence, record.validUntil, record)
        return decision
    }

    fun head(author: IpnsName): AuthorHead? = heads[author.toString()]

    fun all(): List<AuthorHead> = heads.values.toList()

    /** The author's head if it is no longer the revision [pinned], else null. A pinned task is never moved by this. */
    fun updateFor(author: IpnsName, pinned: ContentId): AuthorHead? = head(author)?.takeIf { it.manifest != pinned }

    companion object {
        /** `/ipfs/<raw sha256 CID>` is the only head value this layer understands; anything else is not a revision. */
        fun revisionOf(value: String): ContentId? {
            if (!value.startsWith("/ipfs/")) return null
            val cid = try { IpnsCid.parse(value.removePrefix("/ipfs/")) } catch (bad: RuntimeException) { return null }
            if (cid.codec != 0x55uL) return null
            val multihash = cid.multihash
            if (multihash.size != 34 || multihash[0].toInt() != 0x12 || multihash[1].toInt() != 0x20) return null
            return ContentId("sha256:" + multihash.copyOfRange(2, 34).toLowerHex())
        }
    }
}
