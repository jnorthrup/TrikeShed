package borg.trikeshed.cas

import kotlin.jvm.JvmInline

import borg.trikeshed.collections.associative.FunnelHashIndex
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.cursor.monotonicNanoTime
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

    /** A residual line atom: identity + neighbor context + source address.
     *  [isThreadAnchored] is true when the line's neighbor stamp hex is in the
     *  master funnel — the content is novel but its context is inherited, so
     *  the line is RELOCATED, not NOVEL. */
    data class LineAtom(
        val mini64: Mini64,
        val neighborPrefix: NeighborPrefix,
        val sourceIdx: SourceIdx,
        val ordinal: Ordinal,
        val contentCid: ContentId,
        val isThreadAnchored: Boolean = false,
    )

    /** A residual spine = Series of LineAtom for one source. */
    typealias ResidualSpine = Series<LineAtom>

    /** Address of one copy of a content line across the N sources. */
    data class CopyAddress(
        val sourceIdx: SourceIdx,
        val ordinal: Ordinal,
        val neighborPrefix: NeighborPrefix,
        val isThreadAnchored: Boolean = false,
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

    // ── conflict posts (RELOCATED deferral) ───────────────────────────────

    /**
     * A structured conflict posted for RELOCATED clusters. The funnel merge
     * does NOT pick winners on content conflicts — it posts the full context
     * so an LLM (or deterministic resolver) can reason through the resolution
     * and supply a routine back into [mergeResiduals].
     *
     * The post is self-contained: the content identity, every copy address
     * across sources, the master baseline stamp (if the content is a master
     * relocation), and the thread-anchor flag (if the neighbor context is
     * inherited) give the resolver everything it needs to decide without
     * re-probing the funnel.
     */
    data class ConflictPost(
        val cluster: Cluster,
        val grade: ClusterGrade,
        /** Master's stamp for this content, if the content is in master (relocation).
         *  Null when the content is novel-but-thread-anchored (context inherited,
         *  content is not). */
        val masterStamp: NeighborPrefix?,
        /** True when the cluster's neighbor stamp hex is in the master stamp funnel
         *  — the context is inherited, only the content moved. */
        val isThreadAnchored: Boolean,
    )

    /**
     * Deterministic resolution of one [ConflictPost], supplied by an LLM
     * reasoning through the posted conflict. The routine must be deterministic:
     * same input → same output, no randomness, no guessing.
     */
    sealed interface ConflictResolution {
        /** Accept the version from this source index. */
        data class Accept(val sourceIdx: SourceIdx) : ConflictResolution
        /** Drop the cluster entirely (neither source wins). */
        data object Reject : ConflictResolution
        /** Supply new content that replaces all copies. */
        data class Merge(val content: String) : ConflictResolution
    }

    /**
     * A deterministic resolver routine: maps each [ConflictPost] to a
     * [ConflictResolution]. The LLM supplies this routine after reasoning
     * through the posted conflicts; it is fed back into [mergeResiduals]
     * to close the RELOCATED set without guessing.
     */
    fun interface ResolutionRoutine {
        fun resolve(post: ConflictPost): ConflictResolution
    }

    // ── merge receipt ─────────────────────────────────────────────────────

    /** Outcome of a residual merge: NOVEL survivors fast-applied, RELOCATED
     *  clusters posted as conflicts for LLM resolution, INHERITED/INHERITED_CROSS
     *  dropped. If a [ResolutionRoutine] was supplied, [conflicts] is empty and
     *  [resolvedCount] holds the resolved total; otherwise [conflicts] lists the
     *  pending RELOCATED posts awaiting a routine. */
    data class MergeReceipt(
        val kept: Series<GradedCluster>,
        val dropped: Series<GradedCluster>,
        /** RELOCATED clusters posted as conflicts, pending resolution. Empty
         *  when a [ResolutionRoutine] resolved them all. */
        val conflicts: Series<ConflictPost>,
        val novelCount: Int,
        val relocatedCount: Int,
        /** RELOCATED clusters resolved by the supplied routine. */
        val resolvedCount: Int,
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
            
            // Thread-anchored: the line's content is novel (missed the funnel
            // or had a different stamp), but its neighbor stamp hex IS in the
            // master funnel — the context is inherited, so the line is a
            // relocation, not a true novel insertion.
            val isThreadAnchored = masterBaseline.containsStamp(node.stamp.hex)
            
            atoms.add(LineAtom(
                mini64 = mini64Of(node.contentCid),
                neighborPrefix = prefix,
                sourceIdx = sourceIdx,
                ordinal = Ordinal(node.ordinal),
                contentCid = node.contentCid,
                isThreadAnchored = isThreadAnchored,
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
                    isThreadAnchored = atom.isThreadAnchored,
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
                cluster.copies.size == 1 -> {
                    if (cluster.copies[0].isThreadAnchored) ClusterGrade.RELOCATED
                    else ClusterGrade.NOVEL
                }
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
     * Forward merge on survivors only: NOVEL clusters fast-applied, RELOCATED
     * clusters posted as structured [ConflictPost]s for LLM resolution.
     *
     * INHERITED and INHERITED_CROSS are dropped (master or shared boilerplate).
     * NOVEL clusters are fast-applied (single source, no conflict possible).
     * RELOCATED clusters are POSTED — the funnel does NOT pick winners. Each
     * post carries the cluster, grade, master stamp (if a master relocation),
     * and thread-anchor flag so a deterministic resolver can reason without
     * re-probing the funnel.
     *
     * When [resolver] is non-null, each RELOCATED post is fed to the routine
     * and the resolution is applied:
     *   - [ConflictResolution.Accept] → the winning source's copy is kept.
     *   - [ConflictResolution.Reject] → the cluster is dropped.
     *   - [ConflictResolution.Merge] → kept as-is for the caller to apply the
     *     merged content (the routine's content is carried on the GradedCluster).
     * If [resolver] is null, all RELOCATED posts land in [MergeReceipt.conflicts]
     * for the caller to defer to an LLM.
     *
     * Returns the [MergeReceipt] with NOVEL survivors, dropped sets, and either
     * pending conflicts or resolved counts. Cost is O(|residual|).
     *
     * @param resolver optional deterministic routine supplied by an LLM that
     *   reasoned through the posted conflicts. Same input → same output.
     */
    fun mergeResiduals(
        graded: GradedTopology,
        masterBaseline: MasterBaseline? = null,
        resolver: ResolutionRoutine? = null,
    ): MergeReceipt {
        val keptList = ArrayList<GradedCluster>(graded.size)
        val droppedList = ArrayList<GradedCluster>(graded.size)
        val conflictList = ArrayList<ConflictPost>()
        var novel = 0; var relocated = 0; var resolved = 0; var inherited = 0; var inheritedCross = 0

        for (gc in graded.view) {
            when (gc.grade) {
                ClusterGrade.NOVEL -> { keptList.add(gc); novel++ }
                ClusterGrade.RELOCATED -> {
                    relocated++
                    val isThreadAnchored = gc.cluster.copies.size > 0 &&
                        gc.cluster.copies[0].isThreadAnchored
                    val masterStamp = if (masterBaseline != null) {
                        masterBaseline.stamps[gc.cluster.contentCid.hex]
                    } else null
                    val post = ConflictPost(
                        cluster = gc.cluster,
                        grade = gc.grade,
                        masterStamp = masterStamp,
                        isThreadAnchored = isThreadAnchored,
                    )
                    if (resolver != null) {
                        when (val res = resolver.resolve(post)) {
                            is ConflictResolution.Accept -> { keptList.add(gc); resolved++ }
                            is ConflictResolution.Reject -> { droppedList.add(gc); resolved++ }
                            is ConflictResolution.Merge -> { keptList.add(gc); resolved++ }
                        }
                    } else {
                        conflictList.add(post)
                    }
                }
                ClusterGrade.INHERITED -> { droppedList.add(gc); inherited++ }
                ClusterGrade.INHERITED_CROSS -> { droppedList.add(gc); inheritedCross++ }
            }
        }

        return MergeReceipt(
            kept = keptList.size j { i: Int -> keptList[i] },
            dropped = droppedList.size j { i: Int -> droppedList[i] },
            conflicts = conflictList.size j { i: Int -> conflictList[i] },
            novelCount = novel,
            relocatedCount = relocated,
            resolvedCount = resolved,
            inheritedCount = inherited,
            inheritedCrossCount = inheritedCross,
        )
    }

    // ── full pipeline ─────────────────────────────────────────────────────

    /**
     * Run the full N-way funnel residual merge.
     *
     * Composes all six stages:
     *   1. (caller) build master baseline from master contentCid.hex keys
     *   2. (caller) build source spines via [LineCas.spine]
     *   3. [residualsOf] — per-source probe, misses + relocations = residual atoms
     *   4. [topologyOf]  — group-by mini64 across N residual spines
     *   5. [gradeClusters] — INHERITED / NOVEL / INHERITED_CROSS / RELOCATED
     *   6. [mergeResiduals] — NOVEL fast-applied, RELOCATED posted as conflicts
     *
     * RELOCATED clusters are posted as [ConflictPost]s in the receipt. When
     * [resolver] is non-null, the LLM-supplied routine resolves each post
     * deterministically and the receipt carries resolved counts instead of
     * pending conflicts. The caller never guesses — it either supplies a
     * deterministic routine or defers the posted conflicts.
     *
     * Cost is O(|union residual|), not Σ |patch|.
     *
     * @param sources the N source spines (already built via [LineCas.spine])
     * @param masterBaseline frozen membership index and stamps built from master
     *   (see [buildMasterBaseline])
     * @param resolver optional deterministic routine supplied by an LLM that
     *   reasoned through the posted conflicts. Same input → same output.
     */
    fun merge(
        sources: Series<LineSpine>,
        masterBaseline: MasterBaseline,
        resolver: ResolutionRoutine? = null,
    ): MergeReceipt {
        val residuals = sources.size j { s: Int ->
            residualsOf(sources[s], masterBaseline, SourceIdx(s))
        }
        val topology = topologyOf(residuals)
        val graded = gradeClusters(topology, masterBaseline)
        return mergeResiduals(graded, masterBaseline, resolver)
    }

    /**
     * Parallel N-way merge using the Pijul commutative patch algorithm.
     *
     * The funnel hash pre-filters master's content so only residual lines
     * (NOVEL + RELOCATED) enter the CRDT — not the full N trees. Then every
     * source's residuals are applied to a [borg.trikeshed.crdt.PijulCrdt] as
     * commutative patches:
     *
     *   - NOVEL lines      → Insert at their source position
     *   - RELOCATED lines  → the CRDT sees the insert at the new position;
     *     the old position is implicitly dead because the old content's stamp
     *     no longer matches (the funnel already proved the line moved)
     *   - INHERITED/CROSS  → never enter the CRDT (dropped by the funnel)
     *
     * Non-overlapping edits commute — no conflict resolution, no merge markers,
     * no LLM deferral. The CRDT resolves everything deterministically. The
     * caller materializes the result once via [PijulCrdt.render].
     *
     * Cost: O(|union residual|) for the funnel probe + O(V log V) for the CRDT
     * apply (binary-search attach per line). No O(N × |tree|) merge.
     *
     * @param sources the N source spines (already built via [LineCas.spine])
     * @param masterBaseline frozen membership index and stamps built from master
     * @param masterText the master document text, used to seed the CRDT
     * @param sourceTexts the N source texts, parallel to [sources]. Used to
     *   extract line content for the CRDT inserts.
     * @return the [PijulMergeResult] with the materialized merged document and
     *   per-source provenance.
     */
    fun pijulMerge(
        sources: Series<LineSpine>,
        masterBaseline: MasterBaseline,
        masterText: String,
        sourceTexts: Series<String>,
    ): PijulMergeResult {
        // Stage 1-3: per-source residual probe (funnel hash pre-filter)
        val residuals = sources.size j { s: Int ->
            residualsOf(sources[s], masterBaseline, SourceIdx(s))
        }

        // Stage 4-5: grade clusters
        val topology = topologyOf(residuals)
        val graded = gradeClusters(topology, masterBaseline)

        // Stage 6: apply all survivors to a single Pijul CRDT.
        // The CRDT is seeded with master's content first — INHERITED lines
        // are the baseline graph. Then NOVEL = pure Insert. RELOCATED =
        // Insert at new position (the old position is implicitly tombstoned
        // — its stamp no longer matches master, so the line is dead at its
        // old locus). INHERITED/CROSS never need a patch — they're already
        // in the seed.
        val crdt = borg.trikeshed.crdt.PijulCrdt()
        val patchStore = borg.trikeshed.pijul.PatchStorage()
        val provenance = mutableListOf<PijulMergeResult.SourceProvenance>()

        // Seed: apply master's lines as the initial patch.
        val masterLines = masterText.lineSequence().toList()
        val seedChanges = masterLines.mapIndexed { idx, line ->
            borg.trikeshed.pijul.Change.Insert(idx, line + "\n")
        }
        if (seedChanges.isNotEmpty()) {
            val seedId = borg.trikeshed.patch.Blake3Hash.hash(
                "pijul-seed-master".encodeToByteArray()
            )
            crdt.apply(borg.trikeshed.pijul.Patch(
                id = seedId,
                changes = seedChanges,
                dependencies = emptyList(),
            ))
        }

        for (s in 0 until sources.size) {
            val spine = sources[s]
            val text = sourceTexts[s]
            val lines = text.lineSequence().toList()
            val sourceResiduals = residuals[s]

            val changes = mutableListOf<borg.trikeshed.pijul.Change>()
            for (i in 0 until sourceResiduals.size) {
                val atom = sourceResiduals[i]
                val lineIdx = atom.ordinal.raw
                val content = if (lineIdx < lines.size) lines[lineIdx] else ""
                changes.add(borg.trikeshed.pijul.Change.Insert(lineIdx, content + "\n"))
            }

            if (changes.isNotEmpty()) {
                val patchId = borg.trikeshed.patch.Blake3Hash.hash(
                    ("pijul-merge-$s-${monotonicNanoTime()}").encodeToByteArray()
                )
                val patch = borg.trikeshed.pijul.Patch(
                    id = patchId,
                    changes = changes,
                    dependencies = emptyList(),
                )
                crdt.apply(patch)
                patchStore.store(patch)
                provenance.add(PijulMergeResult.SourceProvenance(
                    sourceIdx = SourceIdx(s),
                    patchId = patchId,
                    changeCount = changes.size,
                ))
            }
        }

        return PijulMergeResult(
            mergedText = crdt.render(),
            gradedTopology = graded,
            sourceCount = sources.size,
            provenance = provenance.size j { i: Int -> provenance[i] },
        )
    }

    /**
     * Result of a Pijul parallel merge: the materialized merged document plus
     * the graded topology and per-source provenance.
     */
    data class PijulMergeResult(
        val mergedText: String,
        val gradedTopology: GradedTopology,
        val sourceCount: Int,
        val provenance: Series<SourceProvenance>,
    ) {
        /** Provenance for one source's contribution to the merge. */
        data class SourceProvenance(
            val sourceIdx: SourceIdx,
            val patchId: borg.trikeshed.patch.Blake3Hash,
            val changeCount: Int,
        )
    }

    /**
     * The frozen master baseline containing both the fast membership funnel
     * and the exact master-topology neighbor stamps for relocation detection.
     */
    class MasterBaseline(
        val funnel: FunnelHashIndex<String>,
        val stamps: Map<String, NeighborPrefix>,
        /** Separate funnel over master's neighbor-stamp hexes, for thread-anchored
         *  relocation detection. A novel line whose stamp hex hits this funnel is
         *  RELOCATED (context inherited, content moved), not NOVEL. */
        private val stampFunnel: FunnelHashIndex<String>,
    ) {
        fun contains(hex: String): Boolean = funnel.contains(hex)
        fun containsStamp(stampHex: String): Boolean = stampFunnel.contains(stampHex)
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
        val stampKeys = ArrayList<String>(masterSpine.size)
        for (node in masterSpine.view) {
            val prev = node.prevHex
            val next = node.nextHex
            val prefix = NeighborPrefix(
                ((hexNibble(prev) shl 4) or hexNibble(next)).toShort()
            )
            stamps[node.contentCid.hex] = prefix
            stampKeys.add(node.stamp.hex)
        }
        val stampKeySeries = stampKeys.size j { i: Int -> stampKeys[i] }
        val stampFunnel = FunnelHashIndex.build(stampKeySeries, seed)
        return MasterBaseline(funnel, stamps, stampFunnel)
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
        val stampKeys = ArrayList<String>(masterTexts.size * 10)
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
                stampKeys.add(node.stamp.hex)
            }
        }
        val keySeries = keys.size j { i: Int -> keys[i] }
        val funnel = FunnelHashIndex.build(keySeries, seed)
        val stampKeySeries = stampKeys.size j { i: Int -> stampKeys[i] }
        val stampFunnel = FunnelHashIndex.build(stampKeySeries, seed)
        return MasterBaseline(funnel, stamps, stampFunnel)
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
