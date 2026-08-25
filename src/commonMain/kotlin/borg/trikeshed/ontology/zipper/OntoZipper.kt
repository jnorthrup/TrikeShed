package borg.trikeshed.ontology.zipper

import borg.trikeshed.cursor.IsALattice
import borg.trikeshed.cursor.TypeToken
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.memory.IndexKind
import borg.trikeshed.memory.MemoryIndexLayer
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.DerivationReceipt
import borg.trikeshed.narsese.SemanticSignal
import kotlin.jvm.JvmInline

/**
 * The ontological-breadcrumb zipper: a focused walk over the five symbolic
 * index planes, carrying its trail as provenance. No new storage — every move
 * is a projection over an EXISTING query surface (IsALattice, BeliefBagElement,
 * MemoryIndexLayer routes, an injected causal ranking).
 *
 * AIKR is structural here: every move spends [WalkBudget]; an exhausted budget
 * returns an empty Series — refusal as data, never an exception. Plane jumps
 * require a [DerivationReceipt] by signature: you cannot cross planes without
 * saying why, and the crumb records the receipt CID.
 */

/** The closed set of walkable planes. */
enum class Plane { TAXONOMY, ISA, CAUSAL, BAG, MEMORY_FACET }

/** plane(8b) | nodeBits(56b) — packed node identity within a plane. */
@JvmInline
value class PlaneNode(val raw: Long) {
    val plane: Plane get() = Plane.entries[((raw ushr 56) and 0xFF).toInt()]
    val node: Long get() = raw and NODE_MASK

    override fun toString(): String = "${plane.name}:${node.toString(16)}"

    companion object {
        const val NODE_MASK: Long = 0x00FF_FFFF_FFFF_FFFFL
        operator fun invoke(plane: Plane, node: Long): PlaneNode =
            PlaneNode((plane.ordinal.toLong() shl 56) or (node and NODE_MASK))
    }
}

/** depth(8b) | visits(24b) — every move pays; exhaustion is a walk boundary. */
@JvmInline
value class WalkBudget(val raw: Long) {
    val depth: Int get() = ((raw ushr 24) and 0xFF).toInt()
    val visits: Int get() = (raw and 0xFFFFFF).toInt()
    val exhausted: Boolean get() = depth <= 0 || visits <= 0

    fun spend(hops: Int = 1): WalkBudget {
        val d = (depth - 1).coerceAtLeast(0)
        val v = (visits - hops).coerceAtLeast(0)
        return WalkBudget((d.toLong() shl 24) or v.toLong())
    }

    companion object {
        operator fun invoke(depth: Int, visits: Int): WalkBudget =
            WalkBudget(((depth.coerceIn(0, 255)).toLong() shl 24) or visits.coerceIn(0, 0xFFFFFF).toLong())
    }
}

/** One breadcrumb: the node stepped to ⋈ the receipt that justified the step (null intra-plane). */
typealias Crumb = Join<PlaneNode, ContentId?>

/**
 * Node identities are 56-bit; strings (taxonomy keys, causal work-ids) and full
 * 64-bit angulars resolve through this session-scoped intern atlas. Bounded:
 * at [cap] the atlas refuses new interns (walks still complete; unresolvable
 * nodes surface as null — MISSING_EVIDENCE at the caller's discretion).
 */
class NodeAtlas(private val cap: Int = 65536) {
    private val byId = HashMap<Long, Any>()

    fun intern(value: Any): Long {
        val id = fnv64(value.toString()) and PlaneNode.NODE_MASK
        if (byId.size < cap || byId.containsKey(id)) byId[id] = value
        return id
    }

    fun resolve(id: Long): Any? = byId[id]

    private fun fnv64(s: String): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (ch in s) {
            h = h xor ch.code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }
}

/** The query surfaces a walk composes over; absent planes simply yield empty moves. */
class PlaneAdapters(
    val lattice: IsALattice? = null,
    val bag: BeliefBagElement? = null,
    val index: MemoryIndexLayer? = null,
    /** Injected causal ranking: (focus work-id, k) → ranked (work-id ⋈ score). */
    val causalRank: ((String, Int) -> Series<Join<String, Double>>)? = null,
    val atlas: NodeAtlas = NodeAtlas(),
)

class OntoZipper private constructor(
    val focus: PlaneNode,
    /** Newest-first breadcrumbs — the walk's provenance artifact. */
    val trail: Series<Crumb>,
    val budget: WalkBudget,
    val planes: PlaneAdapters,
) {

    /** ISA: one hop toward supertypes. */
    fun up(): Series<OntoZipper> = isaMove { lattice, token -> lattice.directSupers(token) }

    /** ISA: one hop toward subtypes. */
    fun down(): Series<OntoZipper> = isaMove { lattice, token -> lattice.directSubs(token) }

    /** BAG: hamming-ball recall around the focus angular (AngularCodec space). */
    fun near(maxHamming: Int): Series<OntoZipper> {
        if (budget.exhausted || focus.plane != Plane.BAG) return emptySeriesOf()
        val bag = planes.bag ?: return emptySeriesOf()
        val centroid = (planes.atlas.resolve(focus.node) as? Long) ?: focus.node
        val found: Series<SemanticSignal> = bag.recallNear(centroid, maxHamming)
        val spent = budget.spend(found.size.coerceAtLeast(1))
        return found.size j { i: Int ->
            step(PlaneNode(Plane.BAG, planes.atlas.intern(found[i].angular)), spent, null)
        }
    }

    /** CAUSAL: graph-distance top-k from the focus event/work node. */
    fun causally(k: Int): Series<OntoZipper> {
        if (budget.exhausted || focus.plane != Plane.CAUSAL) return emptySeriesOf()
        val rank = planes.causalRank ?: return emptySeriesOf()
        val workId = planes.atlas.resolve(focus.node) as? String ?: return emptySeriesOf()
        val ranked = rank(workId, k)
        val spent = budget.spend(ranked.size.coerceAtLeast(1))
        return ranked.size j { i: Int ->
            step(PlaneNode(Plane.CAUSAL, planes.atlas.intern(ranked[i].a)), spent, null)
        }
    }

    /** TAXONOMY: paths under the focus key extended by [prefix]. */
    fun descend(prefix: String): Series<OntoZipper> {
        if (budget.exhausted) return emptySeriesOf()
        val index = planes.index ?: return emptySeriesOf()
        val paths = index.queryByPath(prefix)
        val spent = budget.spend(paths.size.coerceAtLeast(1))
        return paths.size j { i: Int ->
            step(PlaneNode(Plane.TAXONOMY, planes.atlas.intern(paths[i])), spent, null)
        }
    }

    /**
     * The zipper "stitch": jump planes. A receipt is MANDATORY by signature —
     * the crumb carries its CID, so every cross-plane inference is auditable.
     */
    fun crossTo(plane: Plane, node: Long, via: DerivationReceipt): OntoZipper =
        step(PlaneNode(plane, node), budget.spend(), via.canonicalCid)

    /** Replay: fold the trail newest-first back to the origin focus of each step. */
    fun replayTrail(): Series<PlaneNode> = trail.size j { i: Int -> trail[i].a }

    private inline fun isaMove(move: (IsALattice, TypeToken) -> Series<TypeToken>): Series<OntoZipper> {
        if (budget.exhausted || focus.plane != Plane.ISA) return emptySeriesOf()
        val lattice = planes.lattice ?: return emptySeriesOf()
        val found = move(lattice, TypeToken(focus.node.toInt()))
        val spent = budget.spend(found.size.coerceAtLeast(1))
        return found.size j { i: Int ->
            step(PlaneNode(Plane.ISA, found[i].poolIdx.toLong()), spent, null)
        }
    }

    private fun step(to: PlaneNode, spent: WalkBudget, receiptCid: ContentId?): OntoZipper {
        val crumb: Crumb = to j receiptCid
        val prior = trail
        val newTrail: Series<Crumb> = (prior.size + 1) j { i: Int -> if (i == 0) crumb else prior[i - 1] }
        return OntoZipper(to, newTrail, spent, planes)
    }

    companion object {
        fun seed(focus: PlaneNode, planes: PlaneAdapters, budget: WalkBudget = WalkBudget(4, 64)): OntoZipper =
            OntoZipper(focus, emptySeriesOf(), budget, planes)

        /** Convenience: seed on a bag angular. */
        fun onBag(angular: Long, planes: PlaneAdapters, budget: WalkBudget = WalkBudget(4, 64)): OntoZipper =
            seed(PlaneNode(Plane.BAG, planes.atlas.intern(angular)), planes, budget)

        /** Convenience: seed on a taxonomy path. */
        fun onPath(path: String, planes: PlaneAdapters, budget: WalkBudget = WalkBudget(4, 64)): OntoZipper =
            seed(PlaneNode(Plane.TAXONOMY, planes.atlas.intern(path)), planes, budget)
    }
}

/** MemoryIndexLayer's taxonomy route, exposed as the walkable surface [Plane.TAXONOMY] uses. */
fun MemoryIndexLayer.taxonomyKeys(): Series<String> = route(IndexKind.Taxonomy).keys
