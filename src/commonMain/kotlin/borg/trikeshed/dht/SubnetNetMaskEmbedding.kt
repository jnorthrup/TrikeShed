package borg.trikeshed.dht

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.NuidFanoutElement
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.dht.id.NUID
import borg.trikeshed.dht.include.Address
import borg.trikeshed.dht.net.NetMask
import borg.trikeshed.dht.routing.RoutingTable
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.util.oroboros.DhtContentGateway

/**
 * M4 — Subnet↔NetMask embedding (midpoint-map seam).
 *
 * Joins the two NUID systems by *embedding*, not merger:
 *
 *   - the concentric bearer subnets ([Subnet], `context.nuid`) carry
 *     authorization scope as a dotted path whose `level` counts segments;
 *   - the numeric Kademlia overlay ([NUID] + [NetMask], `dht.*`) carries
 *     routing identity as a bit-width.
 *
 * The embedding is pure algebra: `Subnet.level ↦ NetMask.bits` is a strictly
 * monotone linear map (deeper subnet ⇒ wider overlay id space), with the
 * floor retraction `bits ↦ level` as its one-sided inverse, so the round
 * trip `levelFor(bitsFor(s)) == s.level` holds for every subnet.
 *
 * No transport is wired here — the seam function at the bottom only
 * *resolves* overlay candidates for an unclaimed in-process dispatch; it
 * never opens sockets.
 */
object SubnetNetMaskEmbedding {

    /**
     * Bit budget per concentric level. One octet per segment mirrors the
     * dotted-path grammar (each `a.b.c` segment gets a byte-wide overlay
     * stratum) and lands `minNUID` on natural primitive widths:
     * level 1 → UByte(8), level 2 → UShort(16), level 4 → UInt(32), …
     */
    const val BITS_PER_LEVEL: Int = 8

    /** ι : Subnet.level → NetMask.bits. Strictly monotone — deeper ⇒ more bits. */
    fun bitsFor(subnet: Subnet): Int = subnet.level * BITS_PER_LEVEL

    /** Retraction ρ : NetMask.bits → Subnet.level (floor). ρ ∘ ι = id on levels. */
    fun levelFor(bits: Int): Int {
        require(bits >= 0) { "NetMask.bits must be non-negative (was $bits)" }
        return bits / BITS_PER_LEVEL
    }

    /**
     * The subnet's image in the numeric overlay: an unassigned [NUID] whose
     * [NetMask.bits] is exactly `ι(subnet.level)`. Pure — no id is assigned.
     */
    fun overlayNuidFor(subnet: Subnet): NUID<*> = NUID.minNUID(bitsFor(subnet))

    /** The embedding as a Join, for lattice/series composition: subnet ⨝ bits. */
    fun embed(subnet: Subnet): Join<Subnet, Int> = subnet j bitsFor(subnet)
}

/**
 * Capability ↔ trait-bits mapping over [Capability.category] strings.
 *
 * Each capability *family* owns one bit; the wildcard root (`process*`, …)
 * shares the bit with every leaf of its family, mirroring the `matches`
 * family semantics in `context.nuid`. Unknown categories fold into the
 * `custom` bit — the open-namespace escape hatch stays a single bit wide.
 */
object CapabilityTraitBits {

    /** Stable bit order — append-only; never reorder (wire compatibility). */
    private val order: List<String> = listOf(
        "process", "cas", "wireproto", "sctp", "modelmux", "blackboard", "custom", "trajectory",
    )

    private val customBit: Int = order.indexOf("custom")

    /** Bit index for a category string; wildcard `*` suffix strips to the family root. */
    fun bitIndexOf(category: String): Int {
        val root = category.removeSuffix("*")
        val idx = order.indexOf(root)
        return if (idx >= 0) idx else customBit
    }

    /** One-hot trait bits for a single capability. */
    fun traitBits(cap: Capability): Long = 1L shl bitIndexOf(cap.category)

    /** Union of trait bits over an advertised capability series. */
    fun traitBitsOf(caps: Series<Capability>): Long {
        var acc = 0L
        for (i in 0 until caps.a) acc = acc or traitBits(caps.b(i))
        return acc
    }

    /** Retraction: the family categories present in a trait-bit word, as a lazy Series. */
    fun categoriesOf(bits: Long): Series<String> {
        val hit = order.filterIndexed { i, _ -> bits and (1L shl i) != 0L }
        return hit.size j { i -> hit[i] }
    }
}

/**
 * M4 seam — bearer dispatch fell through; fan the action out to the overlay.
 *
 * Takes an *unclaimed* [NuidFanoutElement.DispatchResult] (`winner == null`:
 * no in-process workgroup claimed the action) plus the CBOR wire form of the
 * action — the `ConfixWorker` serialize shape, a five-item CBOR array of
 * `[cap, nonce, subnet, verb, payload]` — and resolves overlay targets by
 * content address through [DhtContentGateway] (`getClosest`, k = 20).
 *
 * Returns the candidate routes only. No sockets are opened, no transport is
 * wired — the caller owns delivery.
 *
 * A *claimed* dispatch (`winner != null`) needs no overlay fanout and yields
 * the empty candidate list.
 */
fun <TNum : Comparable<TNum>, Sz : NetMask<TNum>> resolveUnclaimedOverlay(
    dispatch: NuidFanoutElement.DispatchResult,
    wire: ByteArray,
    routingTable: RoutingTable<TNum, Sz>,
    toTNum: (ContentId) -> TNum,
): List<Join<NUID<TNum>, Address>> {
    if (dispatch.winner != null) return emptyList()

    // Validate the ConfixWorker wire shape before content-addressing it.
    val arr = Cbor.decode(wire) as? Item.Arr
        ?: error("overlay seam: wire form must be the ConfixWorker five-item CBOR array")
    check(arr.size == 5) { "overlay seam: expected 5 CBOR items (cap, nonce, subnet, verb, payload), got ${arr.size}" }
    val capStr = (arr[0] as? Item.Str)?.value
        ?: error("overlay seam: item 0 (capability) must be a CBOR text string")
    val subnetStr = (arr[2] as? Item.Str)?.value
        ?: error("overlay seam: item 2 (subnet) must be a CBOR text string")
    check(arr[4] is Item.Bin) { "overlay seam: item 4 (payload) must be a CBOR byte string" }

    // Both embeddings apply at the seam: the bearer subnet lands in the
    // overlay at ι(level) bits, and the capability folds to its family bit.
    val subnet = Subnet.parse(subnetStr)
    check(SubnetNetMaskEmbedding.bitsFor(subnet) >= SubnetNetMaskEmbedding.BITS_PER_LEVEL) {
        "overlay seam: subnet '$subnetStr' embeds below the innermost overlay stratum"
    }
    CapabilityTraitBits.bitIndexOf(capStr.substringBefore(':'))

    // Content-address the whole wire form; the overlay looks up by ContentId.
    val contentId = ContentId.of(wire)
    return DhtContentGateway(routingTable, toTNum).lookup(contentId)
}
