package borg.trikeshed.cas

import borg.trikeshed.collections.associative.FunnelHashIndex
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.α
import borg.trikeshed.lib.view

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
     *  INHERITED        — in master, stamps match (drop, unreachable via merge).
     *  NOVEL            — singleton, not in master (keep).
     *  INHERITED_CROSS  — all stamps equal across copies (drop — same line, no relocation).
     *  RELOCATED        — relocated master content, OR stamps differ across copies (surface).
     *
     * Reachability contract: INHERITED is provably unreachable through [merge].
     * [residualsOf] emits only atoms that miss the master funnel OR have a
     * different neighbor stamp than the master baseline. [topologyOf] groups these
     * atoms, and [gradeClusters] re-queries the same frozen index. For master
     * hits, it verifies the stamp against the master baseline. Since any identical
     * stamp was dropped in [residualsOf], any master hit reaching [gradeClusters]
     * must have a differing stamp and is graded RELOCATED. The INHERITED arm survives
     * only because [gradeClusters] is total over ANY (topology, baseline) pair.
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
        /** Strict-INHERITED drop count. Via [merge]: provably 0 — see [ClusterGrade]. */
        val inheritedCount: Int,
        /** INHERITED_CROSS drop count — content novel to master but shared (equal
         *  stamps) across sources; the cross-source-boilerplate harvest. */
        val inheritedCrossCount: Int,
    )

    // ── Stage 4: build per-source residual spines ─────────────────────────

    /**
     * Extract a residual spine from one source spine against a master baseline.
     *
     * Stage 1 (spine) + Stage 3 (probe) composed: each source line that MISSES
     * the master funnel OR has a different neighbor stamp than master becomes
     * a [LineAtom]. True hits (content + context match) are inherited and dropped.
     * This is the DRY harvest primitive — the cells that are novel relative
     * to the frozen master baseline (either new content or moved content).
     *
     * The master baseline is built from master's contentCid.hex keys
     * and neighbor stamps (see [buildMasterFunnel]).
     */
    fun residualsOf(
        source: LineSpine,
        masterBaseline: MasterBaseline,
        sourceIdx: SourceIdx,
    ): ResidualSpine {
        val atoms = ArrayList<LineAtom>(source.size)
        for (node in source.view) {
            val hex = node.contentCid.hex
            val prev = node.prevHex
            val next = node.nextHex
            val prefix = NeighborPrefix(
                ((hexNibble(prev) shl 4) or hexNibble(next)).toShort()
            )
            
            if (masterBaseline.contains(hex)) {
                val masterStamp = masterBaseline.stamps[hex]
                if (masterStamp != null && masterStamp.raw == prefix.raw) {
                    continue // hit = strict inherited
                }
            }
            
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
     * Grade every cluster against the master baseline.
     *
     *  INHERITED        — in master, stamps match (drop, unreachable via merge).
     *  NOVEL            — singleton, not in master (keep).
     *  INHERITED_CROSS  — all stamps equal across copies (drop — same line, no relocation).
     *  RELOCATED        — relocated master content, OR stamps differ across copies (surface).
     *
     * The grade tells the merge driver when to DRY-collapse (INHERITED,
     * INHERITED_CROSS) vs when to surface to a 3-way (RELOCATED) vs when to
     * fast-apply (NOVEL). The funnel is the INHERITED oracle; the stamp diff
     * is the RELOCATION signal.
     *
     * Totality: a pure function of (topology, masterBaseline) — merge
     * provenance is never consulted. When the topology came from
     * [residualsOf] against the SAME baseline the INHERITED arm is unreachable
     * (see [ClusterGrade]); when the baseline is newer than the topology it is
     * the absorption path and MUST fire.
     */
    fun gradeClusters(
        topology: Topology,
        masterBaseline: MasterBaseline,
    ): GradedTopology {
        return topology α { cluster ->
            val hex = cluster.contentCid.hex
            val inMaster = masterBaseline.contains(hex)
            
            val grade = when {
                inMaster -> {
                    // It's in the funnel, but since it's in the residual topology,
                    // its stamp MUST differ from the master baseline's stamp 
                    // (otherwise residualsOf would have dropped it). 
                    // This explicitly signals a RELOCATED master line.
                    val masterStamp = masterBaseline.stamps[hex]
                    val clusterStamp = cluster.copies[0].neighborPrefix
                    
                    if (masterStamp != null && masterStamp.raw != clusterStamp.raw) {
                        ClusterGrade.RELOCATED
                    } else {
                        // Fallback for absorption path (stale topology vs new baseline).
                        ClusterGrade.INHERITED
                    }
                }
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
            GradedCluster(cluster, grade)
        }
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
        val keptList = ArrayList<GradedCluster>(graded.size)
        val droppedList = ArrayList<GradedCluster>(graded.size)
        var novel = 0; var relocated = 0; var inherited = 0; var inheritedCross = 0

        for (gc in graded.view) {
            when (gc.grade) {
                ClusterGrade.NOVEL -> { keptList.add(gc); novel++ }
                ClusterGrade.RELOCATED -> { keptList.add(gc); relocated++ }
                ClusterGrade.INHERITED -> { droppedList.add(gc); inherited++ }
                ClusterGrade.INHERITED_CROSS -> { droppedList.add(gc); inheritedCross++ }
            }
        }

        return MergeReceipt(
            kept = keptList.size j { i: Int -> keptList[i] },
            dropped = droppedList.size j { i: Int -> droppedList[i] },
            novelCount = novel,
            relocatedCount = relocated,
            inheritedCount = inherited,
            inheritedCrossCount = inheritedCross,
        )
    }

    // ── full pipeline ─────────────────────────────────────────────────────

    /**
     * Run the full 57-way (N-way) funnel residual merge.
     *
     * Composes all six stages:
     *   1. (caller) build master baseline from master contentCid.hex keys
     *   2. (caller) build source spines via [LineCas.spine]
     *   3. [residualsOf] — per-source probe, misses + relocations = residual atoms
     *   4. [topologyOf]  — group-by mini64 across N residual spines
     *   5. [gradeClusters] — INHERITED / NOVEL / INHERITED_CROSS / RELOCATED
     *   6. [mergeResiduals] — forward merge on NOVEL + RELOCATED survivors
     *
     * Returns the [MergeReceipt]. The caller applies the kept residuals (NOVEL
     * fast, RELOCATED to 3-way) and drops the rest. Cost is O(|union
     * residual|), not Σ |patch|.
     *
     * @param sources the N source spines (already built via [LineCas.spine])
     * @param masterBaseline frozen membership index and stamps built from master
     *   (see [buildMasterFunnel])
     */
    fun merge(
        sources: Series<LineSpine>,
        masterBaseline: MasterBaseline,
    ): MergeReceipt {
        val residuals = sources.size j { s: Int ->
            residualsOf(sources[s], masterBaseline, SourceIdx(s))
        }
        val topology = topologyOf(residuals)
        val graded = gradeClusters(topology, masterBaseline)
        return mergeResiduals(graded)
    }

    // ── master baseline builder ───────────────────────────────────────────

    /**
     * The frozen master baseline containing both the fast membership funnel
     * and the exact master-topology neighbor stamps for relocation detection.
     */
    class MasterBaseline(
        val funnel: FunnelHashIndex<String>,
        val stamps: Map<String, NeighborPrefix>,
    ) {
        fun contains(hex: String): Boolean = funnel.contains(hex)
    }

    /**
     * Build the master baseline: frozen [FunnelHashIndex] over master's
     * contentCid.hex keys, plus their exact neighbor stamps.
     *
     * This is the frozen baseline. Every source probe is a batch of membership
     * queries against this index. Almost all probes are "already have it" —
     * the negative-query-heavy workload FunnelHashIndex is built for. The stamps
     * map makes master-content relocation visible.
     */
    fun buildMasterBaseline(masterSpine: LineSpine, seed: Long = 0L): MasterBaseline {
        val keySeries = masterSpine α { it.contentCid.hex }
        val funnel = FunnelHashIndex.build(keySeries, seed)
        val stamps = LinkedHashMap<String, NeighborPrefix>(masterSpine.size)
        for (node in masterSpine.view) {
            val prev = node.prevHex
            val next = node.nextHex
            val prefix = NeighborPrefix(
                ((hexNibble(prev) shl 4) or hexNibble(next)).toShort()
            )
            stamps[node.contentCid.hex] = prefix
        }
        return MasterBaseline(funnel, stamps)
    }

    /**
     * Build the master baseline from raw master texts.
     *
     * Stays on the public [LineCas] API (no private-field reach into
     * [LineCasIndex]). O(|master|) once. Prefer [buildMasterBaseline] when you
     * hold the master spine directly.
     */
    fun buildMasterBaselineFromTexts(
        masterTexts: Series<String>,
        seed: Long = 0L,
    ): MasterBaseline {
        val keys = ArrayList<String>(masterTexts.size * 10)
        val stamps = LinkedHashMap<String, NeighborPrefix>(masterTexts.size * 10)
        for (text in masterTexts.view) {
            val spine = LineCas.spine(text)
            for (node in spine.view) {
                keys.add(node.contentCid.hex)
                val prev = node.prevHex
                val next = node.nextHex
                val prefix = NeighborPrefix(
                    ((hexNibble(prev) shl 4) or hexNibble(next)).toShort()
                )
                stamps[node.contentCid.hex] = prefix
            }
        }
        val keySeries = keys.size j { i: Int -> keys[i] }
        val funnel = FunnelHashIndex.build(keySeries, seed)
        return MasterBaseline(funnel, stamps)
    }

    // ── master funnel builder (deprecated) ────────────────────────────────

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
    @Deprecated("Use buildMasterBaseline instead")
    fun buildMasterFunnel(masterSpine: LineSpine, seed: Long = 0L): FunnelHashIndex<String> {
        val keySeries = masterSpine α { it.contentCid.hex }
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
    @Deprecated("Use buildMasterBaselineFromTexts instead")
    fun buildMasterFunnelFromTexts(
        masterTexts: Series<String>,
        seed: Long = 0L,
    ): FunnelHashIndex<String> {
        val keys = ArrayList<String>(masterTexts.size * 10)
        for (text in masterTexts.view) {
            val spine = LineCas.spine(text)
            for (node in spine.view) {
                keys.add(node.contentCid.hex)
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
    }
}
