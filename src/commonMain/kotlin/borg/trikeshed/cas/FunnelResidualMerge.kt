package borg.trikeshed.cas

import borg.trikeshed.collections.associative.FunnelHashIndex
import borg.trikeshed.job.ContentId
<<<<<<< HEAD
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/**
 * Funnel residual merge — the N-way topology pipeline.
 *
 * Stages 1–3 (spine, funnel build, per-source probe) live in [LineCas] /
 * [LineCasIndex] / [FunnelHashIndex]. This object composes stages 4–6 on top
 * of the already-landed primitives:
 *
 *   4. Cross-source topology — group-by mini64 across N residual spines
 *   5. Grade clusters         — INHERITED / NOVEL / RELOCATED
 *   6. Residual merge         — forward merge on survivors only
 *
 * The algebra stays PRELOAD-native: everything is `Join` / `Series`, no `List`
 * demotion until a stdlib boundary, value classes for the cheap identities,
 * and the funnel is the negative-query accelerator (miss = residual, not a
 * full-tree walk).
 *
 * Cost shape: O(|union residual|), not Σ |patch|. With N mostly-overlapping
 * agent patches the residual set is the DRY harvest — the novel cells that
 * survive the master funnel — and the merge cost tracks that, not N trees.
 *
 * Honest scope: this wires the topology + grading + survivor merge on top of
 * the landed pre-filter/residual primitives. It does NOT claim to replace git
 * conflict markers on true content conflicts at the same locus; the funnel
 * collapses duplicates, it does not pick winners.
 */
object FunnelResidualMerge {

    // ── value-class identities (zero boxing) ──────────────────────────────

    /** Cheap per-line identity. ContentId.hex is sha-256; mini64 folds it to a
     *  Long for fast group-by without holding full hex strings in hot paths. */
    @JvmInline
    value class Mini64(val raw: Long)

    /** Index of a source spine within the N-way set. */
    @JvmInline
    value class SourceIdx(val raw: Int)

    /** Ordinal of a line within its source spine. */
    @JvmInline
    value class Ordinal(val raw: Int)

    /** Neighbor prefix bytes: prev ‖ next, one byte each side. */
    @JvmInline
    value class NeighborPrefix(val raw: Short)

    // ── atoms ─────────────────────────────────────────────────────────────

    /** A residual line atom: identity + neighbor context + source address. */
    data class LineAtom(
        val mini64: Mini64,
        val neighborPrefix: NeighborPrefix,
        val sourceIdx: SourceIdx,
        val ordinal: Ordinal,
        val contentCid: ContentId,
    )

    /** A residual spine = Series of LineAtom for one source. */
    typealias ResidualSpine = Series<LineAtom>

    /** Address of one copy of a content line across the N sources. */
    data class CopyAddress(
        val sourceIdx: SourceIdx,
        val ordinal: Ordinal,
        val neighborPrefix: NeighborPrefix,
    )

    /** A cluster: one mini64 identity and all its copy addresses across sources. */
    data class Cluster(
        val mini64: Mini64,
        val contentCid: ContentId,
        val copies: Series<CopyAddress>,
    )

    /** The cross-source topology: all clusters, one per distinct mini64. */
    typealias Topology = Series<Cluster>

    // ── grades ────────────────────────────────────────────────────────────

    /**
     * Cluster grade — what the topology says about a cluster of copies.
     *
     *  INHERITED        — master has it (drop).
     *  NOVEL            — singleton, not in master (keep).
     *  INHERITED_CROSS  — all stamps equal across copies (drop — same line, no relocation).
     *  RELOCATED        — stamps differ across copies (surface — context moved).
     */
    enum class ClusterGrade { INHERITED, NOVEL, INHERITED_CROSS, RELOCATED }

    /** A graded cluster: the cluster + its grade. */
    data class GradedCluster(
        val cluster: Cluster,
        val grade: ClusterGrade,
    )

    /** Graded topology = Series of GradedCluster. */
    typealias GradedTopology = Series<GradedCluster>

    // ── merge receipt ─────────────────────────────────────────────────────

    /** Outcome of a residual merge: which clusters survived and which were dropped. */
    data class MergeReceipt(
        val kept: Series<GradedCluster>,
        val dropped: Series<GradedCluster>,
        val novelCount: Int,
        val relocatedCount: Int,
        val inheritedCount: Int,
    )

    // ── Stage 4: build per-source residual spines ─────────────────────────

    /**
     * Extract a residual spine from one source spine against a master funnel.
     *
     * Stage 1 (spine) + Stage 3 (probe) composed: each source line that MISSES
     * the master funnel becomes a [LineAtom]. Hits are inherited and dropped.
     * This is the DRY harvest primitive — the cells that are novel relative
     * to the frozen master baseline.
     *
     * The master funnel is the [FunnelHashIndex] built from master's
     * contentCid.hex keys (see [buildMasterFunnel]). A miss = novel residual.
     */
    fun residualsOf(
        source: LineSpine,
        masterFunnel: FunnelHashIndex<String>,
        sourceIdx: SourceIdx,
    ): ResidualSpine {
        val atoms = ArrayList<LineAtom>(source.size)
        for (i in 0 until source.size) {
            val node = source[i]
            if (masterFunnel.contains(node.contentCid.hex)) continue // hit = inherited
            val prev = node.prevHex
            val next = node.nextHex
            val prefix = NeighborPrefix(
                ((hexNibble(prev) shl 4) or hexNibble(next)).toShort()
            )
            atoms.add(LineAtom(
                mini64 = mini64Of(node.contentCid),
                neighborPrefix = prefix,
                sourceIdx = sourceIdx,
                ordinal = Ordinal(node.ordinal),
                contentCid = node.contentCid,
            ))
        }
        return atoms.size j { i: Int -> atoms[i] }
    }

    // ── Stage 4: cross-source topology ────────────────────────────────────

    /**
     * Group N residual spines by mini64 to reveal the cross-source topology.
     *
     * Each distinct mini64 becomes a [Cluster] holding every [CopyAddress]
     * (sourceIdx + ordinal + neighborPrefix) where that content line appears.
     * This is the DRY-detection step: lines shared across patches collapse to
     * one cluster; lines unique to one patch stay singletons.
     *
     * Cost: O(Σ |residual|) single pass with a per-mini64 bucket map. No
     * materialization of the full N trees.
     */
    fun topologyOf(residuals: Series<ResidualSpine>): Topology {
        val buckets = LinkedHashMap<Long, MutableList<CopyAddress>>()
        val cidByMini = LinkedHashMap<Long, ContentId>()

        for (s in 0 until residuals.size) {
            val spine = residuals[s]
            for (i in 0 until spine.size) {
                val atom = spine[i]
                val key = atom.mini64.raw
                cidByMini.getOrPut(key) { atom.contentCid }
                val list = buckets.getOrPut(key) { mutableListOf() }
                list.add(CopyAddress(
                    sourceIdx = atom.sourceIdx,
                    ordinal = atom.ordinal,
                    neighborPrefix = atom.neighborPrefix,
                ))
            }
        }

        val entries = buckets.entries.toList()
        return entries.size j { i: Int ->
            val e = entries[i]
            val copies = e.value
            Cluster(
                mini64 = Mini64(e.key),
                contentCid = cidByMini[e.key]!!,
                copies = copies.size j { j: Int -> copies[j] },
            )
        }
    }

    // ── Stage 5: grade clusters ───────────────────────────────────────────

    /**
     * Grade every cluster against the master funnel.
     *
     *  INHERITED        — master funnel has the contentCid (drop, regardless of copies).
     *  NOVEL            — not in master, single copy (keep).
     *  INHERITED_CROSS  — not in master, multiple copies, all neighborPrefix equal (drop — shared boilerplate).
     *  RELOCATED        — not in master, multiple copies, neighborPrefix differs (surface — context moved).
     *
     * The grade tells the merge driver when to DRY-collapse (INHERITED,
     * INHERITED_CROSS) vs when to surface to a 3-way (RELOCATED) vs when to
     * fast-apply (NOVEL). The funnel is the INHERITED oracle; the stamp diff
     * is the RELOCATION signal.
     */
    fun gradeClusters(
        topology: Topology,
        masterFunnel: FunnelHashIndex<String>,
    ): GradedTopology {
        val graded = ArrayList<GradedCluster>(topology.size)
        for (i in 0 until topology.size) {
            val cluster = topology[i]
            val inMaster = masterFunnel.contains(cluster.contentCid.hex)
            val grade = when {
                inMaster -> ClusterGrade.INHERITED
                cluster.copies.size == 1 -> ClusterGrade.NOVEL
                else -> {
                    val first = cluster.copies[0].neighborPrefix.raw
                    var allEqual = true
                    for (j in 1 until cluster.copies.size) {
                        if (cluster.copies[j].neighborPrefix.raw != first) {
                            allEqual = false
                            break
                        }
                    }
                    if (allEqual) ClusterGrade.INHERITED_CROSS else ClusterGrade.RELOCATED
                }
            }
            graded.add(GradedCluster(cluster, grade))
        }
        return graded.size j { i: Int -> graded[i] }
    }

    // ── Stage 6: residual merge on survivors ─────────────────────────────

    /**
     * Forward merge on survivors only: NOVEL + RELOCATED clusters.
     *
     * INHERITED and INHERITED_CROSS are dropped (master or shared boilerplate).
     * NOVEL clusters are fast-applied (single source, no conflict possible).
     * RELOCATED clusters are surfaced for a 3-way (context moved — the merge
     * driver must pick the winner, not the funnel).
     *
     * Returns a [MergeReceipt] with the kept and dropped sets plus counts.
     * This is the O(|residual|) settlement, not O(N × |tree|).
     */
    fun mergeResiduals(graded: GradedTopology): MergeReceipt {
        val kept = ArrayList<GradedCluster>(graded.size)
        val dropped = ArrayList<GradedCluster>(graded.size)
        var novel = 0; var relocated = 0; var inherited = 0

        for (i in 0 until graded.size) {
            val gc = graded[i]
            when (gc.grade) {
                ClusterGrade.NOVEL -> { kept.add(gc); novel++ }
                ClusterGrade.RELOCATED -> { kept.add(gc); relocated++ }
                ClusterGrade.INHERITED -> { dropped.add(gc); inherited++ }
                ClusterGrade.INHERITED_CROSS -> { dropped.add(gc); inherited++ }
            }
        }

        return MergeReceipt(
            kept = kept.size j { i: Int -> kept[i] },
            dropped = dropped.size j { i: Int -> dropped[i] },
            novelCount = novel,
            relocatedCount = relocated,
            inheritedCount = inherited,
        )
    }

    // ── full pipeline ─────────────────────────────────────────────────────

    /**
     * Run the full 57-way (N-way) funnel residual merge.
     *
     * Composes all six stages:
     *   1. (caller) build master funnel from master contentCid.hex keys
     *   2. (caller) build source spines via [LineCas.spine]
     *   3. [residualsOf] — per-source probe, misses = residual atoms
     *   4. [topologyOf]  — group-by mini64 across N residual spines
     *   5. [gradeClusters] — INHERITED / NOVEL / INHERITED_CROSS / RELOCATED
     *   6. [mergeResiduals] — forward merge on NOVEL + RELOCATED survivors
     *
     * Returns the [MergeReceipt]. The caller applies the kept residuals (NOVEL
     * fast, RELOCATED to 3-way) and drops the rest. Cost is O(|union
     * residual|), not Σ |patch|.
     *
     * @param sources the N source spines (already built via [LineCas.spine])
     * @param masterFunnel frozen membership index built from master's
     *   contentCid.hex keys (see [buildMasterFunnel])
     */
    fun merge(
        sources: Series<LineSpine>,
        masterFunnel: FunnelHashIndex<String>,
    ): MergeReceipt {
        val residuals = sources.size j { s: Int ->
            residualsOf(sources[s], masterFunnel, SourceIdx(s))
        }
=======
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get
import borg.trikeshed.lib.α
import kotlin.jvm.JvmInline

/**
 * FunnelMergeReceipt captures the kept/dropped Series + counts
 * generated from merging a clustered topology of residuals.
 */
data class MergeReceipt(
    val kept: Series<GradedCluster>,
    val dropped: Series<GradedCluster>,
    val keptCount: Int,
    val droppedCount: Int
)

@JvmInline
value class Mini64(val value: Long)

data class CopyAddress(
    val sourceIdx: Int,
    val ordinal: Int,
    val neighborPrefix: String
)

data class Cluster(
    val mini64: Mini64,
    val contentCid: ContentId,
    val copies: Series<CopyAddress>
)

enum class Grade {
    INHERITED,
    NOVEL,
    INHERITED_CROSS,
    RELOCATED
}

data class GradedCluster(
    val cluster: Cluster,
    val grade: Grade
)

typealias ResidualSpine = Join<Int, Series<LineNode>>

/**
 * The 57-way funnel residual merge pipeline.
 *
 * Implements the full six-stage pipeline composable over LineSpines:
 * Stage 1: LineCas.spine
 * Stage 2: FunnelHashIndex.build
 * Stage 3: [LineCasIndex.residualsOf], [LineCasIndex.linkMatch], [LineCasIndex.residualDensity]
 * Stage 4: topologyOf
 * Stage 5: gradeClusters
 * Stage 6: mergeResiduals
 */
object FunnelResidualMerge {

    fun mini64Of(cid: ContentId): Mini64 {
        var h = -0x349b101655b38cbL // FNV offset basis
        val hex = cid.hex
        for (i in 0 until hex.length) {
            h = h xor hex[i].code.toLong()
            h = h * 0x100000001b3L // FNV prime
        }
        return Mini64(h)
    }

    /**
     * Stage 4: topologyOf
     */
    fun topologyOf(residuals: Series<ResidualSpine>): Series<Cluster> {
        val map = linkedMapOf<Long, MutableList<Pair<ContentId, CopyAddress>>>()

        for (i in 0 until residuals.size) {
            val sourceIdx = residuals[i].a
            val nodes = residuals[i].b
            for (j in 0 until nodes.size) {
                val node = nodes[j]
                val m64 = mini64Of(node.contentCid).value
                val copy = CopyAddress(
                    sourceIdx = sourceIdx,
                    ordinal = node.ordinal,
                    neighborPrefix = node.stamp.hex
                )
                map.getOrPut(m64) { mutableListOf() }.add(Pair(node.contentCid, copy))
            }
        }

        val entries = map.entries.toList()
        return entries.size j { i: Int ->
            val entry = entries[i]
            val copiesList = entry.value
            val firstCid = copiesList.first().first
            val copiesSeries = copiesList.size j { j: Int -> copiesList[j].second }
            Cluster(Mini64(entry.key), firstCid, copiesSeries)
        }
    }

    /**
     * Stage 5: gradeClusters
     */
    fun gradeClusters(topology: Series<Cluster>, masterFunnel: FunnelHashIndex<String>): Series<GradedCluster> {
        return topology.size j { i: Int ->
            val cluster = topology[i]
            val cidHex = cluster.contentCid.hex

            val grade = if (masterFunnel.contains(cidHex)) {
                Grade.INHERITED
            } else if (cluster.copies.size <= 1) {
                Grade.NOVEL
            } else {
                val firstPrefix = cluster.copies[0].neighborPrefix
                var allSame = true
                for (j in 1 until cluster.copies.size) {
                    if (cluster.copies[j].neighborPrefix != firstPrefix) {
                        allSame = false
                        break
                    }
                }

                if (allSame) {
                    Grade.INHERITED_CROSS
                } else {
                    Grade.RELOCATED
                }
            }

            GradedCluster(cluster, grade)
        }
    }

    /**
     * Stage 6: mergeResiduals
     */
    fun mergeResiduals(graded: Series<GradedCluster>): MergeReceipt {
        val keptList = mutableListOf<GradedCluster>()
        val droppedList = mutableListOf<GradedCluster>()

        for (i in 0 until graded.size) {
            val g = graded[i]
            if (g.grade == Grade.NOVEL || g.grade == Grade.RELOCATED) {
                keptList.add(g)
            } else {
                droppedList.add(g)
            }
        }

        val keptSeries = keptList.size j { i: Int -> keptList[i] }
        val droppedSeries = droppedList.size j { i: Int -> droppedList[i] }

        return MergeReceipt(keptSeries, droppedSeries, keptList.size, droppedList.size)
    }

    /**
     * The full six-stage pipeline composable.
     * Stage 1: LineCas.spine
     * Stage 2: FunnelHashIndex.build
     * Stage 3: [LineCasIndex.residualsOf], [LineCasIndex.linkMatch], [LineCasIndex.residualDensity]
     * Stage 4: [topologyOf]
     * Stage 5: [gradeClusters]
     * Stage 6: [mergeResiduals]
     */
    fun merge(sources: Series<LineSpine>, masterFunnel: FunnelHashIndex<String>): MergeReceipt {
        val index = LineCasIndex()
        index.funnel = masterFunnel

        val residuals = sources.size j { i: Int ->
            val spine = sources[i]
            val resNodes = index.residualsOf(spine)
            i j resNodes
        }

>>>>>>> origin/jules-16549645140847297621-da6d01af
        val topology = topologyOf(residuals)
        val graded = gradeClusters(topology, masterFunnel)
        return mergeResiduals(graded)
    }

<<<<<<< HEAD
    // ── master funnel builder ─────────────────────────────────────────────

    /**
     * Build the master funnel: frozen [FunnelHashIndex] over master's
     * contentCid.hex keys.
     *
     * This is the frozen baseline. Every source probe is a batch of membership
     * queries against this index. Almost all probes are "already have it" —
     * the negative-query-heavy workload FunnelHashIndex is built for.
     *
     * The index is rebuilt on each new master ingest (paper FunnelHashIndex is
     * frozen after build). For a static master baseline, build once.
     */
    fun buildMasterFunnel(masterSpine: LineSpine, seed: Long = 0L): FunnelHashIndex<String> {
        val keys = ArrayList<String>(masterSpine.size)
        for (i in 0 until masterSpine.size) {
            keys.add(masterSpine[i].contentCid.hex)
        }
        val keySeries = keys.size j { i: Int -> keys[i] }
        return FunnelHashIndex.build(keySeries, seed)
    }

    /**
     * Build the master funnel from raw master texts.
     *
     * Stays on the public [LineCas] API (no private-field reach into
     * [LineCasIndex]). O(|master|) once. Prefer [buildMasterFunnel] when you
     * hold the master spine directly; this overload is for callers that have
     * the texts but not the spines.
     */
    fun buildMasterFunnelFromTexts(
        masterTexts: Series<String>,
        seed: Long = 0L,
    ): FunnelHashIndex<String> {
        val keys = ArrayList<String>(masterTexts.size * 10)
        for (i in 0 until masterTexts.size) {
            val spine = LineCas.spine(masterTexts[i])
            for (j in 0 until spine.size) {
                keys.add(spine[j].contentCid.hex)
            }
        }
        val keySeries = keys.size j { i: Int -> keys[i] }
        return FunnelHashIndex.build(keySeries, seed)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Fold a ContentId.hex into a 64-bit identity for fast group-by.
     *
     * mix64-style fold over the hex chars — not cryptographic, just a stable
     * cheap identity. Two lines with the same content get the same mini64;
     * collision rate is negligible for group-by purposes (the full contentCid
     * is retained on the atom for disambiguation if ever needed).
     */
    private fun mini64Of(cid: ContentId): Mini64 {
        val hex = cid.hex
        // Fold 64 hex chars into 8 bytes / 64 bits. Take every 8th char and
        // pack into a Long. Cheap, stable, collision-resistant enough for
        // group-by topology.
        var h = 0L
        for (i in hex.indices step 8) {
            h = h * 31L + hex[i].code
        }
        return Mini64(h)
    }

    /** Hex char → nibble (0–15). Returns 0 for non-hex (defensive). */
    private fun hexNibble(hex: String): Int {
        if (hex.isEmpty()) return 0
        return when (val c = hex[0]) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> 0
        }
=======
    fun buildMasterFunnel(keys: Series<String>, seed: Long): FunnelHashIndex<String> {
        return FunnelHashIndex.build(keys, seed)
    }

    fun buildMasterFunnelFromTexts(texts: Series<String>, seed: Long): FunnelHashIndex<String> {
        val flatKeysList = mutableListOf<String>()
        for (i in 0 until texts.size) {
            val spine = LineCas.spine(texts[i])
            for (j in 0 until spine.size) {
                flatKeysList.add(spine[j].contentCid.hex)
            }
        }
        val flatKeys = flatKeysList.size j { i: Int -> flatKeysList[i] }
        return FunnelHashIndex.build(flatKeys, seed)
>>>>>>> origin/jules-16549645140847297621-da6d01af
    }
}
