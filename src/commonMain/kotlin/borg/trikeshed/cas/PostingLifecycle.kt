package borg.trikeshed.cas

import borg.trikeshed.cursor.IntArrayList
import borg.trikeshed.lib.intp.*

/**
 * Evoke's convergent segmented index lifecycle (`docs/convergent-segmented-index.md`,
 * `docs/maintenance-lifecycle.md`, `src/evoke_am.c`) over in-memory postings: two linked-L0
 * frontiers, sealed segments, term-local folds, retired page ranges and a one-action maintenance
 * selector. What PostgreSQL supplies in Evoke is out of scope here and said so where it matters:
 * there are no transactions (every appended record is committed, so the `xid_horizon` deferral
 * never fires), no relation file (tail truncation is not an action), no model (semantic
 * completion, accelerator refresh and impact folds are not actions), and no VACUUM (RETIRE records
 * come from [retire]). Sizes are modelled from record and posting counts so the byte and page
 * thresholds mean what Evoke's do.
 *
 * Exact read = per term: major fold + minor fold + extents past the fold watermark + both L0
 * frontiers − retirements, scored with live statistics; it equals a fresh [TermPostings] over the
 * live rows after every action, and a reader that [pin]s a root blocks page reuse until released.
 */
/** The bytes of an L0 record without atoms: a RETIRE, or an UPSERT's header. */
const val RETIRE_BYTES = 112L

/** Evoke's 8 KiB pages a byte count occupies. */
fun l0Pages(bytes: Long): Int = ((bytes + 8_191) / 8_192).toInt()

class PostingLifecycle(
    val analyzer: Analyzer = Analyzer(),
    val params: Bm25Params = Bm25Params(),
    val limits: Limits = Limits(),
    private val clock: () -> Long = { 0L },
) {
    /** Evoke's constants (`evoke_segments.h`, `evoke_am.c`); tests shrink them. */
    data class Limits(
        val l0MaxRecords: Int = 131_072,
        val l0MaxPages: Int = 1_024,
        val l0RotationRecords: Int = l0MaxRecords / 2,
        val l0RotationPages: Int = l0MaxPages / 2,
        val manifestMaxSegments: Int = 1_024,
        val maxExtentsPerTerm: Int = 64,
        val compactionMaxInputSegments: Int = 8,
        val compactionMaxInputBytes: Long = 64L shl 20,
        val compactionTierWidth: Int = 4,
        val extentPressure: Int = 6,
        val foldTargetTailExtents: Int = 3,
        val reclaimRetiredRangeThreshold: Int = 8,
        val reclaimActionBlockBudget: Long = 8_192,
        val lowDebtIntervalMs: Long = 3_600_000,
        val blockBytes: Int = 8_192,
        val sizeClassBaseBytes: Long = 65_536,
    )

    enum class RecordKind { UPSERT, RETIRE }

    /** One linked-L0 record: a document version (its atoms and frequencies) or a retirement. */
    class L0Record(val kind: RecordKind, val sequence: Long, val slot: Int, val docLen: Int, val atoms: IntArray, val tfs: IntArray) {
        val bytes: Long get() = RETIRE_BYTES + 8L * atoms.size
    }

    /** A frontier of records; [pages] follows the record bytes. */
    class Frontier(val segmentId: Int) {
        val records = ArrayList<L0Record>()
        var bytes = 0L
        val pages: Int get() = l0Pages(bytes)
        val minSequence: Long get() = records.firstOrNull()?.sequence ?: -1
        val maxSequence: Long get() = records.lastOrNull()?.sequence ?: -1
    }

    /** One term's postings inside one sealed object: `(slot, tf)` runs plus the sequence span they cover. */
    class Run(val slots: IntArray, val tfs: IntArray, val minSequence: Long, val maxSequence: Long) {
        val size: Int get() = slots.size
    }

    /** A sealed, immutable segment: per-term extents and the retirements it carries. */
    class Segment(
        val id: Int, val minSequence: Long, val maxSequence: Long, val extents: Map<Int, Run>, val retirements: IntArray,
        val historyBarrier: Boolean = false, private val sizeClassBase: Long = 65_536,
    ) {
        val bytes: Long = 112L + extents.values.sumOf { 8L * it.size } + 16L * retirements.size
        val sizeClass: Int get() = sizeClassOf(bytes, sizeClassBase)
    }

    /** A term's neutral fold: one contiguous run covering every event up to [coverage]. */
    class Fold(val run: Run, val coverage: Long) {
        val bytes: Long get() = 112L + 8L * run.size
    }

    /** Per-term fold state: major (coverage c0) and minor (coverage c1 > c0). */
    class TermFolds(var major: Fold? = null, var minor: Fold? = null) {
        val coverage: Long get() = minor?.coverage ?: major?.coverage ?: -1
    }

    data class RetiredRange(val blocks: Long, val retiredAtSequence: Long)

    /** The `evoke_maintenance_result(maintained=…, reason=…, mode=…, …)` text, kept as fields too. */
    data class MaintenanceResult(val maintained: Boolean, val reason: String, val mode: String, val extras: Map<String, Any> = emptyMap()) {
        override fun toString(): String = buildString {
            append("evoke_maintenance_result(maintained=").append(maintained).append(", reason=").append(reason).append(", mode=").append(mode)
            for ((k, v) in extras) append(", ").append(k).append('=').append(v)
            append(')')
        }
    }

    val lexicon = AtomLexicon()
    private var nextSequence = 0L
    private var nextSegmentId = 1
    private var nextSlot = 0
    private val slotKeys = ArrayList<Any?>()
    private var slotLen = IntArray(16)
    private var slotLive = BooleanArray(16)
    private var slotSequence = LongArray(16)
    private val slotOfKey = HashMap<Any?, Int>()

    var active = Frontier(nextSegmentId++)
        private set
    var pending: Frontier? = null
        private set
    val segments = ArrayList<Segment>()
    val folds = HashMap<Int, TermFolds>()
    val retiredRanges = ArrayList<RetiredRange>()
    private var pinnedRoots = 0
    private var lowDebtMarkedAt: Long? = null

    /** Bumped by every seal: the corpus statistics an epoch-specialized image would be bound to. */
    var statisticsEpoch = 0L
        private set

    /** Live documents and their total length: the exact statistics every read uses. */
    var documents = 0
        private set
    private var totalLength = 0L

    // ── foreground writes ───────────────────────────────────────────────────────────────────────

    fun upsert(key: Any?, text: CharSequence): Long = upsertTokens(key, analyzer.tokens(text))

    /**
     * Appends an UPSERT (retiring the key's previous version first, as a new TID does) to the active
     * frontier, rotating it inline at the soft threshold when no pending frontier exists; at the hard
     * limit with the pending frontier still unsealed the write is refused (Evoke's
     * "active L0 hard frontier is exhausted while pending L0 is unsealed").
     */
    fun upsertTokens(key: Any?, tokens: List<String>): Long {
        admit(if (slotOfKey[key]?.let { slotLive[it] } == true) 2 else 1)
        slotOfKey[key]?.let { retireSlot(it) }
        val counts = LinkedHashMap<Int, Int>()
        for (t in tokens) lexicon.intern(t).id.let { counts[it] = (counts[it] ?: 0) + 1 }
        val slot = nextSlot++
        if (slot == slotLen.size) { slotLen = slotLen.copyOf(slot * 2); slotLive = slotLive.copyOf(slot * 2); slotSequence = slotSequence.copyOf(slot * 2) }
        slotKeys += key
        slotOfKey[key] = slot
        slotLen[slot] = tokens.size
        slotLive[slot] = true
        documents++
        totalLength += tokens.size
        val sequence = nextSequence++
        slotSequence[slot] = sequence
        append(L0Record(RecordKind.UPSERT, sequence, slot, tokens.size, counts.keys.toIntArray(), counts.values.toIntArray()))
        return sequence
    }

    /** Appends a RETIRE for [key]'s live version (what VACUUM's bulkdelete does in Evoke); false when absent. */
    fun retire(key: Any?): Boolean = slotOfKey[key]?.let { admit(1); retireSlot(it); true } ?: false

    /**
     * Refuses the [records] appends a write is about to make while they would meet the hard limit of
     * the active frontier with the pending one unsealed (Evoke's "active L0 hard frontier is exhausted"),
     * before any state changes: [append]'s own check is reached only after [retireSlot] has flipped the
     * directory. The appends are replayed against [append]'s rule, a RETIRE record (then the UPSERT)
     * when the key is live, so an inline rotation inside the sequence and the RETIRE's bytes count.
     */
    fun admit(records: Int) {
        var size = active.records.size
        var bytes = active.bytes
        var rotated = pending != null
        repeat(records) { i ->
            if (size > 0 && (size >= limits.l0RotationRecords || l0Pages(bytes) >= limits.l0RotationPages)) {
                if (!rotated) { rotated = true; size = 0; bytes = 0L }
                else if (size >= limits.l0MaxRecords || l0Pages(bytes) >= limits.l0MaxPages)
                    throw IllegalStateException("evoke active L0 hard frontier is exhausted while pending L0 is unsealed")
            }
            size++
            if (i < records - 1) bytes += RETIRE_BYTES
        }
    }

    private fun retireSlot(slot: Int) {
        if (!slotLive[slot]) return
        slotLive[slot] = false
        documents--
        totalLength -= slotLen[slot]
        slotOfKey.remove(slotKeys[slot])
        append(L0Record(RecordKind.RETIRE, nextSequence++, slot, slotLen[slot], IntArray(0), IntArray(0)))
    }

    private fun append(record: L0Record) {
        val a = active
        if (a.records.isNotEmpty() && (a.records.size >= limits.l0RotationRecords || a.pages >= limits.l0RotationPages)) {
            if (pending == null) rotate()
            else check(a.records.size < limits.l0MaxRecords && a.pages < limits.l0MaxPages) {
                "evoke active L0 hard frontier is exhausted while pending L0 is unsealed"
            }
        }
        active.records += record
        active.bytes += record.bytes
        if (lowDebtMarkedAt == null) lowDebtMarkedAt = clock()
    }

    /** Rotation: the active frontier becomes the pending one and a fresh active starts; a root change, no payload. */
    private fun rotate() {
        check(pending == null && active.records.isNotEmpty())
        pending = active
        active = Frontier(nextSegmentId++)
    }

    // ── readers ─────────────────────────────────────────────────────────────────────────────────

    /** A reader pins the current root; page reuse waits until every pin is [release]d. */
    fun pin(): Int = ++pinnedRoots
    fun release() { check(pinnedRoots > 0); pinnedRoots-- }

    /** Exact BM25 scores by key over the read plan: folds + uncovered extents + visible L0 − retirements. */
    fun scores(query: CharSequence): Map<Any?, Double> {
        val (hits, count, acc) = accumulate(query) ?: return emptyMap()
        return HashMap<Any?, Double>(count * 2).apply { for (i in 0 until count) put(slotKeys[hits[i]], acc[hits[i]]) }
    }

    /** The [k] best hits (every hit when k < 0): score descending, then slot ascending, Evoke's order and [TermPostings.topKTokens]'s rule. */
    fun topK(query: CharSequence, k: Int): List<PostingHit> {
        val (hits, count, acc) = accumulate(query) ?: return emptyList()
        hits.sort(0, count)
        val scores = DoubleArray(count) { acc[hits[it]] }
        return borg.trikeshed.lib.doublep.topK(scores, count, if (k < 0) count else k, descending = true).map { i ->
            hits[i].let { slot -> PostingHit(slot, slotKeys[slot], scores[i]) }
        }
    }

    /** The read plan's accumulation for [query]: the hit slots, their count and every slot's score; null while the index is empty. */
    fun accumulate(query: CharSequence): Triple<IntArray, Int, DoubleArray>? {
        if (documents == 0) return null
        val weights = LinkedHashMap<Int, Int>()
        for (t in analyzer.tokens(query)) lexicon.find(t)?.let { weights[it.id] = (weights[it.id] ?: 0) + 1 }
        val n = documents.toDouble()
        val lavg = totalLength.toDouble() / documents
        val acc = DoubleArray(nextSlot)
        val touched = BooleanArray(nextSlot)
        val hits = IntArray(nextSlot)
        var count = 0
        for ((atom, weight) in weights) {
            val (slots, tfs) = plan(atom)
            var df = 0
            for (s in slots) if (slotLive[s]) df++
            if (df == 0) continue
            val idf = bm25Idf(params.idfMethod, df.toDouble(), n)
            count = bm25Accumulate(slots, tfs, slots.size, slotLen, lavg, idf, weight.toDouble(), params, slotLive, acc, touched, hits, count)
        }
        return Triple(hits, count, acc)
    }

    /** One term's postings as the read plan assembles them (retired slots included; readers skip them). */
    private fun plan(atom: Int): Pair<IntArray, IntArray> {
        val slots = IntArrayList(); val tfs = IntArrayList()
        fun add(run: Run) { for (i in 0 until run.size) { slots.add(run.slots[i]); tfs.add(run.tfs[i]) } }
        val fold = folds[atom]
        fold?.major?.let { add(it.run) }
        fold?.minor?.let { add(it.run) }
        val covered = fold?.coverage ?: -1
        for (s in segments) s.extents[atom]?.let { if (it.minSequence > covered) add(it) }
        for (f in listOfNotNull(pending, active)) for (r in f.records) if (r.kind == RecordKind.UPSERT) {
            val i = r.atoms.indexOf(atom)
            if (i >= 0) { slots.add(r.slot); tfs.add(r.tfs[i]) }
        }
        return slots.array.copyOf(slots.size) to tfs.array.copyOf(tfs.size)
    }

    // ── due predicates ──────────────────────────────────────────────────────────────────────────

    fun checkpointDue(): Boolean = active.records.isNotEmpty() && (active.records.size >= limits.l0RotationRecords || active.pages >= limits.l0RotationPages)

    fun lowDebtDue(): Boolean = active.records.isNotEmpty() && lowDebtMarkedAt?.let { clock() - it >= limits.lowDebtIntervalMs } == true

    fun reclamationDue(): Boolean = retiredRanges.size >= limits.reclaimRetiredRangeThreshold || retiredRanges.sumOf { it.blocks } >= limits.reclaimActionBlockBudget

    /** Extents per term: the segments holding the term past its fold watermark. */
    fun extentCount(atom: Int): Int { val c = folds[atom]?.coverage ?: -1; return segments.count { s -> s.extents[atom]?.let { it.minSequence > c } == true } }

    private fun pressuredTerm(): Int? = (0 until lexicon.size).firstOrNull { extentCount(it) >= limits.extentPressure }

    fun structuralFoldDue(): Boolean = reclamationDue() || (segments.size >= 2 && selectCompaction() != null) || (segments.size >= 2 && pressuredTerm() != null)

    /** Evoke's strict due: any work the selector will certainly take. */
    fun strictDue(): Boolean = pending != null || checkpointDue() || (pending == null && active.records.isEmpty() && structuralFoldDue())

    fun due(): Boolean = strictDue() || lowDebtDue()

    // ── the selector ────────────────────────────────────────────────────────────────────────────

    /**
     * Exactly one bounded action, in the C order: rotate (checkpoint due, no pending) → seal pending
     * (a blocked seal cascades to compaction, then fold-forward, then a structural fold, with
     * `pending_blocked=true`) → [semantic completion: not applicable] → reclaim retired pages →
     * compact segments → structural term fold → [tail cleanup, workload fold, accelerator: not
     * applicable] → `no_pending`. Never waits on a reader: a busy fence defers the action.
     */
    fun tryMaintain(allowPeriodicLowDebt: Boolean = true): MaintenanceResult {
        if (pending == null && (checkpointDue() || (allowPeriodicLowDebt && lowDebtDue()))) {
            rotate()
            return MaintenanceResult(true, "active_l0_rotated", "segment_rotation")
        }
        if (pending != null) {
            val seal = trySeal()
            if (seal.reason == "pending_l0_sealed") return seal
            // COMPACTION_REQUIRED: relieve the structural pressure in this tick, pending stays blocked.
            val compaction = tryCompact()
            if (compaction.maintained || compaction.reason == "fold_watermark") return compaction.withExtra("pending_blocked", true)
            val fold = tryStructuralFold()
            if (fold.maintained) return fold.withExtra("pending_blocked", true)
            return MaintenanceResult(false, seal.reason, "segment_compaction", mapOf("pending_blocked" to true))
        }
        tryReclaim().let { if (it.maintained || it.reason == "reader_fence_busy") return it }
        tryCompact().let { if (it.maintained) return it }
        tryStructuralFold().let { if (it.maintained) return it }
        return MaintenanceResult(false, "no_pending", "convergent_segment")
    }

    /** For automatic policies `evoke_index_maintain` is the same single action as `try_maintain`. */
    fun maintain(): MaintenanceResult = tryMaintain()

    private fun MaintenanceResult.withExtra(k: String, v: Any) = copy(extras = extras + (k to v))

    /** Seal: the pending frontier becomes one immutable segment; `segment_limit` and `term_extent_limit` require compaction first. */
    fun trySeal(): MaintenanceResult {
        val p = pending ?: return MaintenanceResult(false, "no_pending", "segment_seal")
        if (segments.size >= limits.manifestMaxSegments) return MaintenanceResult(false, "segment_limit", "segment_seal", mapOf("compaction_required" to true))
        val runs = HashMap<Int, Pair<IntArrayList, IntArrayList>>()
        val retirements = IntArrayList()
        for (r in p.records) when (r.kind) {
            RecordKind.UPSERT -> for (i in r.atoms.indices) runs.getOrPut(r.atoms[i]) { IntArrayList() to IntArrayList() }.let { it.first.add(r.slot); it.second.add(r.tfs[i]) }
            RecordKind.RETIRE -> retirements.add(r.slot)
        }
        if (runs.keys.any { extentCount(it) >= limits.maxExtentsPerTerm })
            return MaintenanceResult(false, "term_extent_limit", "segment_seal", mapOf("compaction_required" to true))
        val extents = runs.mapValues { (_, v) -> Run(v.first.array.copyOf(v.first.size), v.second.array.copyOf(v.second.size), p.minSequence, p.maxSequence) }
        segments += Segment(p.segmentId, p.minSequence, p.maxSequence, extents, retirements.array.copyOf(retirements.size), sizeClassBase = limits.sizeClassBaseBytes)
        pending = null
        statisticsEpoch++
        lowDebtMarkedAt = if (active.records.isEmpty()) null else clock()
        return MaintenanceResult(true, "pending_l0_sealed", "segment_seal", mapOf("compaction_required" to false))
    }

    class CompactionPlan(val first: Int, val count: Int, val bytes: Long, val reason: String)

    /**
     * `evoke_am_segment_compaction_select`: a contiguous run of 2..8 segments under 64 MiB, by reason
     * EXTENTS (a term at extent pressure: the span of each pair of its extents), TIER (4 consecutive
     * segments of one size class), SEGMENT_LIMIT (the manifest nearly full: adjacent pairs); the best
     * plan has the fewest segments, then the fewest bytes, then the lowest index.
     */
    fun selectCompaction(): CompactionPlan? {
        var best: CompactionPlan? = null
        fun consider(first: Int, last: Int, reason: String) {
            val count = last - first + 1
            if (count < 2 || count > limits.compactionMaxInputSegments) return
            val bytes = (first..last).sumOf { segments[it].bytes }
            if (bytes > limits.compactionMaxInputBytes) return
            val plan = CompactionPlan(first, count, bytes, reason)
            val b = best
            if (b == null || plan.count < b.count || (plan.count == b.count && (plan.bytes < b.bytes || (plan.bytes == b.bytes && plan.first < b.first)))) best = plan
        }
        for (atom in 0 until lexicon.size) {
            if (extentCount(atom) < limits.extentPressure) continue
            val covered = folds[atom]?.coverage ?: -1
            val holders = segments.indices.filter { segments[it].extents[atom]?.let { e -> e.minSequence > covered } == true }
            for (i in holders.indices) for (j in i + 1 until holders.size) consider(holders[i], holders[j], "extents")
        }
        if (best != null) return best
        for (first in 0..segments.size - limits.compactionTierWidth) {
            val cls = segments[first].sizeClass
            if ((first until first + limits.compactionTierWidth).all { segments[it].sizeClass == cls }) consider(first, first + limits.compactionTierWidth - 1, "tier")
        }
        if (best != null) return best
        if (segments.size + 1 >= limits.manifestMaxSegments) for (first in 0 until segments.size - 1) consider(first, first + 1, "segment_limit")
        return best
    }

    /** A term fold whose coverage falls strictly inside the range's span makes the range unsafe to replace. */
    private fun foldConflict(plan: CompactionPlan): Int? {
        val lo = segments[plan.first].minSequence
        val hi = segments[plan.first + plan.count - 1].maxSequence
        return folds.entries.firstOrNull { (_, f) -> f.coverage in lo until hi }?.key
    }

    /** Compaction: merge the selected segments into one, dropping retired documents; a fold-watermark conflict becomes a fold-forward. */
    fun tryCompact(): MaintenanceResult {
        val plan = selectCompaction() ?: return MaintenanceResult(false, "no_pending", "segment_compaction")
        foldConflict(plan)?.let { atom ->
            val forward = foldForward(atom, segments[plan.first + plan.count - 1].maxSequence)
            return MaintenanceResult(forward, if (forward) "term_fold_forward" else "fold_watermark", "term_fold_forward", mapOf("term_id" to atom, "compaction_first_segment" to plan.first, "compaction_segment_count" to plan.count))
        }
        val inputs = segments.subList(plan.first, plan.first + plan.count).toList()
        val merged = HashMap<Int, Pair<IntArrayList, IntArrayList>>()
        var reclaimed = 0
        for (s in inputs) for ((atom, run) in s.extents) {
            val covered = folds[atom]?.coverage ?: -1
            if (run.minSequence <= covered) continue
            val target = merged.getOrPut(atom) { IntArrayList() to IntArrayList() }
            for (i in 0 until run.size) if (slotLive[run.slots[i]]) { target.first.add(run.slots[i]); target.second.add(run.tfs[i]) } else reclaimed++
        }
        val minSeq = inputs.first().minSequence
        val maxSeq = inputs.last().maxSequence
        val extents = merged.filterValues { it.first.size > 0 }.mapValues { (_, v) -> Run(v.first.array.copyOf(v.first.size), v.second.array.copyOf(v.second.size), minSeq, maxSeq) }
        val retirements = inputs.flatMap { it.retirements.toList() }.filter { !slotLive[it] && slotSequence[it] < minSeq }.toIntArray()
        val out = Segment(nextSegmentId++, minSeq, maxSeq, extents, retirements, historyBarrier = extents.isEmpty(), sizeClassBase = limits.sizeClassBaseBytes)
        for (s in inputs) retiredRanges += RetiredRange(blocksOf(s.bytes), maxSeq)
        for (i in 0 until plan.count) segments.removeAt(plan.first)
        segments.add(plan.first, out)
        return MaintenanceResult(true, "segments_compacted", "segment_compaction", mapOf("first_segment" to plan.first, "segment_count" to plan.count, "input_bytes" to plan.bytes, "reclaimed_documents" to reclaimed, "reason" to plan.reason))
    }

    /** Structural term fold: the pressured term's oldest extents fold until 3 remain; MINOR when a fold exists, else MAJOR; a minor at half the major promotes. */
    fun tryStructuralFold(): MaintenanceResult {
        val atom = pressuredTerm() ?: return MaintenanceResult(false, "no_pending", "term_structural_fold")
        val state = folds.getOrPut(atom) { TermFolds() }
        val covered = state.coverage
        val holders = segments.filter { s -> s.extents[atom]?.let { it.minSequence > covered } == true }
        val consume = holders.size - limits.foldTargetTailExtents
        if (consume <= 0) return MaintenanceResult(false, "no_pending", "term_structural_fold")
        val taken = holders.take(consume)
        val slots = IntArrayList(); val tfs = IntArrayList()
        state.minor?.run?.let { for (i in 0 until it.size) { slots.add(it.slots[i]); tfs.add(it.tfs[i]) } }
        for (s in taken) s.extents.getValue(atom).let { for (i in 0 until it.size) { slots.add(it.slots[i]); tfs.add(it.tfs[i]) } }
        val coverage = taken.last().maxSequence
        val run = Run(slots.array.copyOf(slots.size), tfs.array.copyOf(tfs.size), state.minor?.run?.minSequence ?: taken.first().minSequence, coverage)
        state.minor?.let { retiredRanges += RetiredRange(blocksOf(it.bytes), coverage) }
        val level: String
        if (state.major == null) { state.major = Fold(run, coverage); state.minor = null; level = "major" }
        else {
            val minor = Fold(run, coverage)
            val major = state.major!!
            if (minor.bytes >= (major.bytes + 1) / 2) {
                val all = IntArrayList(); val allTf = IntArrayList()
                for (i in 0 until major.run.size) { all.add(major.run.slots[i]); allTf.add(major.run.tfs[i]) }
                for (i in 0 until run.size) { all.add(run.slots[i]); allTf.add(run.tfs[i]) }
                retiredRanges += RetiredRange(blocksOf(major.bytes), coverage)
                state.major = Fold(Run(all.array.copyOf(all.size), allTf.array.copyOf(allTf.size), major.run.minSequence, coverage), coverage)
                state.minor = null
                level = "major_promotion"
            } else { state.minor = minor; level = "minor" }
        }
        return MaintenanceResult(true, "term_folded", "term_structural_fold", mapOf("term_id" to atom, "coverage_sequence" to coverage, "consumed_extents" to consume, "remaining_extents" to extentCount(atom), "fold_level" to level))
    }

    /** Advances [atom]'s fold over the extents up to [through], so a compaction range no longer straddles its watermark. */
    private fun foldForward(atom: Int, through: Long): Boolean {
        val state = folds[atom] ?: return false
        val covered = state.coverage
        val taken = segments.filter { s -> s.extents[atom]?.let { it.minSequence > covered && it.maxSequence <= through } == true }
        if (taken.isEmpty()) return false
        val base = state.minor ?: state.major ?: return false
        val slots = IntArrayList(); val tfs = IntArrayList()
        for (i in 0 until base.run.size) { slots.add(base.run.slots[i]); tfs.add(base.run.tfs[i]) }
        for (s in taken) s.extents.getValue(atom).let { for (i in 0 until it.size) { slots.add(it.slots[i]); tfs.add(it.tfs[i]) } }
        retiredRanges += RetiredRange(blocksOf(base.bytes), through)
        val fold = Fold(Run(slots.array.copyOf(slots.size), tfs.array.copyOf(tfs.size), base.run.minSequence, through), through)
        if (state.minor != null) state.minor = fold else state.major = fold
        return true
    }

    /** Reclaim up to 8,192 blocks of retired ranges behind the reader fence; a pinned root defers it. */
    fun tryReclaim(): MaintenanceResult {
        if (!reclamationDue()) return MaintenanceResult(false, "below_retirement_threshold", "segment_reclamation")
        if (pinnedRoots > 0) return MaintenanceResult(false, "reader_fence_busy", "segment_reclamation")
        var budget = limits.reclaimActionBlockBudget
        var ranges = 0; var blocks = 0L
        while (retiredRanges.isNotEmpty() && budget > 0) {
            val r = retiredRanges.removeAt(0)
            val take = minOf(r.blocks, budget)
            budget -= take; blocks += take; ranges++
            if (take < r.blocks) retiredRanges.add(0, RetiredRange(r.blocks - take, r.retiredAtSequence))
        }
        return MaintenanceResult(true, "retired_pages_reclaimed", "segment_reclamation", mapOf("retired_ranges" to ranges, "retired_blocks" to blocks, "next_retired_ranges" to retiredRanges.size))
    }

    /** `evoke_index_status`'s lifecycle keys: delta, segments, folds, retirements, due flags, the epoch. */
    fun status(): Map<String, Any?> = linkedMapOf(
        "delta" to linkedMapOf(
            "records" to (active.records.size + (pending?.records?.size ?: 0)),
            "bytes" to (active.bytes + (pending?.bytes ?: 0L)),
            "upserts" to (active.records.count { it.kind == RecordKind.UPSERT } + (pending?.records?.count { it.kind == RecordKind.UPSERT } ?: 0)),
            "retirements" to (active.records.count { it.kind == RecordKind.RETIRE } + (pending?.records?.count { it.kind == RecordKind.RETIRE } ?: 0)),
            "active" to linkedMapOf("segment_id" to active.segmentId, "records" to active.records.size, "bytes" to active.bytes, "pages" to active.pages),
            "pending" to pending?.let { linkedMapOf("segment_id" to it.segmentId, "records" to it.records.size, "bytes" to it.bytes, "pages" to it.pages) },
        ),
        "segment_count" to segments.size,
        "docs" to documents,
        "document_slot_high_watermark" to nextSlot,
        "statistics_epoch" to statisticsEpoch,
        "folds" to folds.size,
        "retired_hint_ranges" to retiredRanges.size,
        "retired_hint_blocks" to retiredRanges.sumOf { it.blocks },
        "checkpoint_due" to checkpointDue(),
        "reclamation_due" to reclamationDue(),
        "strict_due" to strictDue(),
        "due" to due(),
    )

    private fun blocksOf(bytes: Long): Long = (bytes + limits.blockBytes - 1) / limits.blockBytes

    companion object {
        /**
         * `evoke_index_maintain_due(max_indexes)`: classify every index, order stale-free by tier
         * (structural 0, optional 1, idle 2) then index order, run one action each and answer up to
         * [maxIndexes] results that maintained; more indexes may be tried than rows answered.
         */
        fun maintainDue(indexes: List<PostingLifecycle>, maxIndexes: Int = 1): List<Pair<Int, MaintenanceResult>> {
            if (maxIndexes < 1) return emptyList()
            val ranked = indexes.indices.filter { indexes[it].due() }.sortedWith(compareBy({ if (indexes[it].strictDue()) 0 else 1 }, { it }))
            val out = ArrayList<Pair<Int, MaintenanceResult>>()
            for (i in ranked) {
                if (out.size >= maxIndexes) break
                val result = indexes[i].tryMaintain()
                if (result.maintained) out += i to result
            }
            return out
        }
    }
}

/** Evoke's segment size class: the power of two of [bytes] over [base] (65,536 bytes). */
private fun sizeClassOf(bytes: Long, base: Long): Int { var c = 0; var b = bytes / base; while (b > 1) { b = b shr 1; c++ }; return c }
