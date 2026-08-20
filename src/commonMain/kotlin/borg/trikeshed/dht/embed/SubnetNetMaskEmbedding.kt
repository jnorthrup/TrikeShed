package borg.trikeshed.dht.embed

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.NuidFanoutElement
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Nuid
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.TraitSpace
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.dht.id.NUID
import borg.trikeshed.dht.include.Address
import borg.trikeshed.dht.net.NetMask
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.α
import borg.trikeshed.util.oroboros.DhtContentGateway

/**
 * M4 — Subnet ↔ NetMask embedding.
 *
 * Two NUID systems live in this tree and never met:
 *
 *  * `borg.trikeshed.context.nuid` — the *bearer* NUID. A `Capability`
 *    (what), a `Nonce` (who, undesignated), and a concentric [Subnet]
 *    (where). Claiming is in-process: [NuidFanoutElement] walks the
 *    registry innermost-first and the first workgroup to say yes wins.
 *
 *  * `borg.trikeshed.dht.id` — the *overlay* NUID. A numeric id under a
 *    [NetMask] of `bits` width, routed by XOR distance through a Kademlia
 *    `RoutingTable`.
 *
 * They are joined by **embedding, not merger**: the bearer subnet's depth
 * is the coordinate that selects how wide an overlay you are allowed to
 * spray into. One gradient — `core` claims in-process and never leaves the
 * worker; `global.relay` fans out across the whole DHT.
 *
 * This file is pure algebra. It opens no sockets, starts no coroutines and
 * touches no transport. The only outward call is the non-suspend
 * [DhtContentGateway.lookup], which reads an already-populated routing
 * table.
 */

// ── (a) Subnet.level ↔ NetMask.bits ─────────────────────────────────────

/**
 * A monotone embedding of concentric bearer scopes into overlay id widths.
 *
 * [rungs] is the ladder, indexed by `level - 1`. It must be strictly
 * increasing: that is exactly the statement "deeper subnet ⇒ more bits",
 * and it is what makes [levelOf] a left inverse of [bitsOf].
 *
 * The embedding is a *retraction*, not a bijection: levels past the top of
 * the ladder saturate at the widest rung, so
 * `levelOf(bitsOf(l)) == min(l, depth)`. That is deliberate — past
 * `global.relay` there is no wider network to reach.
 *
 * [inProcessDepth] is the floor of the gradient: levels at or below it are
 * *in-process only* and get a fanout of zero, no matter how wide their rung
 * is. `Nuid.kt` states the rule this enforces — "authority flows inward, not
 * outward" — so a `core`-scoped action that nobody claims dies in the worker
 * rather than leaking onto the DHT. The rung still exists for those levels
 * because `bitsOf` also sizes overlay *identities*, which an in-process scope
 * may legitimately mint.
 *
 * Not a `data class`: [rungs] is a `Series<Int>` (a `Join<Int, (Int)->Int>`),
 * so a generated `equals` would compare projection lambdas by identity.
 * Equality is defined over the rung *values* instead.
 */
class SubnetEmbedding(
    val rungs: Series<Int>,
    val inProcessDepth: Int = 1,
) {
    init {
        require(rungs.size > 0) { "SubnetEmbedding needs at least one rung" }
        for (i in 1 until rungs.size) require(rungs[i] > rungs[i - 1]) {
            "SubnetEmbedding rungs must be strictly increasing (rung $i)"
        }
        require(inProcessDepth >= 0) { "inProcessDepth must be non-negative, got $inProcessDepth" }
    }

    override fun equals(other: Any?): Boolean =
        other is SubnetEmbedding && other.inProcessDepth == inProcessDepth &&
            other.rungs.size == rungs.size &&
            (0 until rungs.size).all { rungs[it] == other.rungs[it] }

    override fun hashCode(): Int =
        (0 until rungs.size).fold(inProcessDepth) { acc, i -> acc * 31 + rungs[i] }

    override fun toString(): String =
        "SubnetEmbedding(rungs=${(0 until rungs.size).map { rungs[it] }}, " +
            "inProcessDepth=$inProcessDepth)"

    /** Number of distinct scopes this ladder can name. */
    val depth: Int get() = rungs.size

    /** The widest overlay this ladder reaches. */
    val maxBits: Int get() = rungs[depth - 1]

    /** Level (1-based, = `Subnet.level`) ⇒ overlay id width in bits. */
    fun bitsOf(level: Int): Int {
        require(level >= 1) { "Subnet level is 1-based, got $level" }
        return rungs[if (level > depth) depth - 1 else level - 1]
    }

    /** Overlay id width ⇒ the shallowest level that reaches it. Left
     *  inverse of [bitsOf] over `1..depth`. */
    fun levelOf(bits: Int): Int {
        for (i in 0 until depth) if (rungs[i] >= bits) return i + 1
        return depth
    }

    /** The bearer scope's overlay width. */
    fun bitsOf(subnet: Subnet): Int = bitsOf(subnet.level)

    /**
     * An overlay identity of the narrowest primitive that holds this scope's
     * rung. `NUID.minNUID` seeds `id` to one, so this is a *sized* identity,
     * not an unassigned one — call `random()` on it to place it, not
     * `assign()`, which rejects a second write.
     */
    fun overlayIdentity(subnet: Subnet): NUID<*> = NUID.minNUID(bitsOf(subnet))

    /**
     * How many overlay peers a request at this scope is entitled to touch.
     *
     * Zero at or below [inProcessDepth] — the innermost scopes never leave the
     * worker. Above it, monotone in level and clamped to the gateway's
     * hardcoded k, so no single scope can spray the whole DHT.
     */
    fun fanoutOf(subnet: Subnet): Int {
        if (subnet.level <= inProcessDepth) return 0
        val b = bitsOf(subnet)
        return if (b > GATEWAY_K) GATEWAY_K else b
    }

    companion object {
        /** `DhtContentGateway.lookup` hardcodes k=20; mirrored here as the ceiling. */
        const val GATEWAY_K: Int = 20

        /**
         * The canonical ladder, anchored on the three [NetMask] sizes the
         * DHT already ships:
         *
         * | level | example subnet     | bits | anchor            |
         * |-------|--------------------|------|-------------------|
         * | 1     | `core`, `local`    | 2    | [NetMask.Companion.HotSz]  |
         * | 2     | `lan.localhost`    | 7    | [NetMask.Companion.WarmSz] |
         * | 3     | `mesh.worker.<id>` | 16   | —                 |
         * | 4     | `global.relay.<r>` | 32   | —                 |
         * | 5+    | anything deeper    | 64   | [NetMask.Companion.CoolSz] |
         */
        val Default: SubnetEmbedding = SubnetEmbedding(
            intArrayOf(
                NetMask.Companion.HotSz.bits,
                NetMask.Companion.WarmSz.bits,
                16,
                32,
                NetMask.Companion.CoolSz.bits,
            ) α { it }
        )
    }
}

/** Overlay id width implied by this bearer scope, under the canonical ladder. */
val Subnet.overlayBits: Int get() = SubnetEmbedding.Default.bitsOf(level)

/** Overlay peer budget implied by this bearer scope, under the canonical ladder. */
val Subnet.overlayFanout: Int get() = SubnetEmbedding.Default.fanoutOf(this)

// ── (b) Capability.category ↔ trait bits ────────────────────────────────

/**
 * The bearer side names capabilities by string family; the overlay side
 * only understands bits. [TraitBits] is the dictionary between them.
 *
 * A wildcard root (`process*`) and its leaves (`Process("spawn")`) collapse
 * to the *same* bit — the bit records the family, and family membership is
 * exactly what `Capability.matches` already tests. Anything unrecognized
 * lands on the `custom` bit rather than being dropped, so an unknown
 * capability still routes somewhere instead of silently vanishing.
 */
object TraitBits {
    /** Canonical bit order. Index = bit position; append only. */
    val categories: Series<String> = arrayOf(
        "process", "cas", "wireproto", "sctp", "modelmux",
        "blackboard", "trajectory", "custom",
    ) α { it }

    /** Bit position of the `custom` catch-all. Looked up by name, not by
     *  position, so appending to [categories] cannot silently relocate it. */
    val customBit: Int = (0 until categories.size).first { categories[it] == "custom" }

    /** Every bit this dictionary knows about. */
    val universe: Long = (1L shl categories.size) - 1L

    /** `"process*"` and `"process"` are the same family root. */
    fun rootOf(category: String): String = category.removeSuffix("*")

    /** Bit position for a category string; unknown ⇒ [customBit]. */
    fun bitOf(category: String): Int {
        val root = rootOf(category)
        for (i in 0 until categories.size) if (categories[i] == root) return i
        return customBit
    }

    /** Single-bit mask for one capability. */
    fun maskOf(capability: Capability): Long = 1L shl bitOf(capability.category)

    /** Union mask over a series of capabilities. */
    fun maskOf(capabilities: Series<Capability>): Long {
        var acc = 0L
        for (i in 0 until capabilities.size) acc = acc or maskOf(capabilities[i])
        return acc
    }

    /** The mask a workgroup advertises. */
    fun maskOf(traits: TraitSpace): Long = maskOf(traits.capabilities())

    /** Inverse: the category roots named by a mask, in canonical order. */
    fun categoriesOf(mask: Long): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until categories.size) if ((mask shr i) and 1L == 1L) out.add(categories[i])
        return out
    }

    /** Overlap test — the bitwise form of `Capability.matches` at family grain. */
    fun overlaps(offer: Long, want: Long): Boolean = (offer and want) != 0L
}

/** This capability's family bit, as a single-bit mask. */
val Capability.traitMask: Long get() = TraitBits.maskOf(this)

// ── the CBOR wire form ConfixWorker speaks ──────────────────────────────

/**
 * The decoded five-item ConfixWorker array: `[cap, nonce, subnet, verb, payload]`.
 *
 * Shaped as the `ReactorAction` join (`Nuid j (verb j payload)`) so it drops
 * straight back into the reactor algebra, with [traitMask] carried alongside
 * as the overlay-facing projection.
 */
data class ConfixWire(
    val nuid: Nuid,
    val verb: String,
    val payload: ByteArray,
) {
    val subnet: Subnet get() = nuid.b.b
    val capability: Capability get() = nuid.a
    val traitMask: Long get() = TraitBits.maskOf(capability)

    /** The reactor-shaped join. */
    val action: Join<Nuid, Join<String, ByteArray>> get() = nuid j (verb j payload)

    override fun equals(other: Any?): Boolean =
        other is ConfixWire && nuid == other.nuid && verb == other.verb &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        (nuid.hashCode() * 31 + verb.hashCode()) * 31 + payload.contentHashCode()
}

/**
 * Decode the CBOR shape `ConfixWorker.serialize` emits. Read-only mirror of
 * that private encoder — `ConfixWorker` is sibling-owned, so the wire grammar
 * is duplicated here rather than exported from there.
 */
fun decodeConfixWire(bytes: ByteArray): ConfixWire {
    val arr = Cbor.decode(bytes) as? Item.Arr
        ?: error("ConfixWire: expected a CBOR array")
    require(arr.items.size == 5) {
        "ConfixWire: expected 5 items [cap, nonce, subnet, verb, payload], got ${arr.items.size}"
    }
    val capStr = str(arr, 0, "cap")
    val nonceStr = str(arr, 1, "nonce")
    val subnetStr = str(arr, 2, "subnet")
    val verb = str(arr, 3, "verb")
    val payload = (arr.items[4] as? Item.Bin)?.value
        ?: throw IllegalArgumentException("ConfixWire: item 4 (payload) must be a CBOR byte string")
    return ConfixWire(
        nuid(decodeCapability(capStr), decodeNonce(nonceStr), Subnet.parse(subnetStr)),
        verb,
        payload,
    )
}

private fun str(arr: Item.Arr, index: Int, field: String): String =
    (arr.items[index] as? Item.Str)?.value
        ?: throw IllegalArgumentException("ConfixWire: item $index ($field) must be a CBOR text string")

/**
 * Inverse of `ConfixWorker.serializeCapability`. Single-argument families keep
 * everything after the first colon — routes and process names may themselves
 * contain colons, and truncating one silently changes the capability's
 * identity so it no longer `matches` the workgroup that registered it.
 *
 * A trailing `*` marks a wildcard family root: `ConfixWorker` emits those
 * through its `else` branch as `"process*:"`, and they must decode back to the
 * root object so the family bit survives the round trip.
 */
private fun decodeCapability(text: String): Capability {
    val cat = text.substringBefore(':')
    val arg = text.substringAfter(':', "")
    if (cat.endsWith("*")) return when (cat) {
        "process*" -> Capability.ProcessAll
        "cas*" -> Capability.CasAll
        "wireproto*" -> Capability.WireprotoAll
        "sctp*" -> Capability.SctpAll
        "modelmux*" -> Capability.ModelAll
        "blackboard*" -> Capability.BlackBoardAll
        "trajectory*" -> Capability.TrajectoryAll
        else -> Capability.CustomAll
    }
    return when (cat) {
        "process" -> Capability.Process(arg)
        "cas" -> Capability.Cas(arg)
        "wireproto" -> Capability.Wireproto(arg)
        // custom is the only two-argument family: "custom:<kind>:<token>".
        "custom" -> Capability.Custom(arg.substringBefore(':'), arg.substringAfter(':', ""))
        "sctp" -> Capability.Sctp
        "modelmux" -> Capability.Model
        "blackboard" -> Capability.BlackBoard
        "trajectory" -> Capability.Trajectory
        else -> Capability.Custom(cat, arg)
    }
}

private fun decodeNonce(text: String): Nonce {
    val body = text.substringAfter(':', "")
    require(body.isNotEmpty()) { "ConfixWire: nonce field carries no bytes ('$text')" }
    val parts = body.split(",")
    val bytes = ByteArray(parts.size) { i ->
        parts[i].trim().toByteOrNull()
            ?: throw IllegalArgumentException("ConfixWire: nonce byte ${parts[i]} is not a Byte")
    }
    return Nonce.Restored(bytes)
}

// ── (c) the seam: unclaimed in-process dispatch ⇒ overlay candidates ────

/**
 * The one gradient, made concrete.
 *
 * [NuidFanoutElement.dispatch] returns a [NuidFanoutElement.DispatchResult]
 * with `winner == null` when nobody in-process would take the work, even
 * after escalating outward through the registry. That is the moment the
 * bearer system runs out of local scope — and exactly the moment the
 * overlay takes over.
 *
 * This function is the hinge. Given the unclaimed result and the CBOR wire
 * form of the action, it:
 *
 *  1. refuses to act on a *claimed* result (returns empty — a claimed action
 *     must never be re-sprayed onto the DHT);
 *  2. decodes the bearer scope out of the wire;
 *  3. addresses the action by content — `ContentId.of(wire)` over the exact
 *     canonical bytes the sender signed;
 *  4. asks the routing table for the k=20 closest overlay peers;
 *  5. **narrows** that to [Subnet.overlayFanout] — the embedding from (a).
 *     A `core`- or `local`-scoped action that nobody claimed reaches *nobody*
 *     and dies in the worker; `lan.localhost` reaches 7 peers;
 *     `mesh.worker.<id>` reaches 16; only the deepest scopes get all 20.
 *
 * No socket is opened and no transport is wired. The caller decides what,
 * if anything, to send to the returned addresses.
 */
fun <TNum : Comparable<TNum>, Sz : NetMask<TNum>> resolveOverlayCandidates(
    result: NuidFanoutElement.DispatchResult,
    wire: ByteArray,
    gateway: DhtContentGateway<TNum, Sz>,
    embedding: SubnetEmbedding = SubnetEmbedding.Default,
): List<Join<NUID<TNum>, Address>> {
    if (result.winner != null) return emptyList()
    val decoded = decodeConfixWire(wire)
    val budget = embedding.fanoutOf(decoded.subnet)
    if (budget <= 0) return emptyList()
    val closest = gateway.lookup(ContentId.of(wire))
    return if (closest.size <= budget) closest else closest.subList(0, budget)
}
