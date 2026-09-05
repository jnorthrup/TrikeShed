package borg.trikeshed.narsese

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext

/** One unit of work for the bag. Guest-facing paths clamp evidence before minting. */
sealed class BeliefIntake {
    /** New signal at a budget; same angular revises (evidence union), never duplicates. */
    data class Mint(
        val signal: SemanticSignal,
        val budget: BudgetCoord,
        val receiptCid: ContentId? = null,
        val evidenceBasis: EvidenceBasis? = null,
        /** The Narsese surface of the signal, when the minter knows it. A belief
         *  shown as a bare angular is a mistake; carry the expression with it. */
        val gloss: String? = null,
    ) : BeliefIntake()

    /** Evidence delta onto an existing angular; budget untouched. */
    data class Reinforce(val angular: Long, val delta: EvidenceCoord, val receiptCid: ContentId? = null) : BeliefIntake()

    /** Attention change only: rekey to the new budget; evidence untouched. */
    data class Attend(val angular: Long, val budget: BudgetCoord) : BeliefIntake()

    /** Curation pulse: decay every budget via the element's decayFn, then evict below floor. */
    data object DecayTick : BeliefIntake()
}

sealed class BeliefEvent {
    data class Minted(val angular: Long, val receiptCid: ContentId?, val gloss: String? = null) : BeliefEvent()
    data class Revised(val angular: Long, val evidence: EvidenceCoord, val gloss: String? = null) : BeliefEvent()
    data class Attended(val angular: Long, val budget: BudgetCoord) : BeliefEvent()
    data class Evicted(val angular: Long, val spillCid: ContentId?) : BeliefEvent()
    data class Contradicted(val angular: Long, val subjectCid: String) : BeliefEvent()
}

/**
 * BeliefBagElement — the daemon-owned NarseseBag as a CCEK element.
 *
 * AIKR bounds made physical: bounded intake channel (backpressure, never
 * UNLIMITED), bounded bag [capacity], and eviction that is ATTENTION-zero,
 * never evidence-loss — the victim's canonical bytes go to CAS and a re-mint
 * of the same angular revives them by fetch + revise. "Evidence never decays,
 * only attention does", literally.
 *
 * The bag map itself is the existing `Map<Join<Long,Long>, SemanticSignal>`
 * algebra (reviseInto / recallNear / recallByExpectation reused verbatim);
 * budget rides in the key, so an internal angular→key index keeps attention
 * changes from orphaning entries.
 *
 * WAL: thin ordering log over [DurableAppendLog] (CRC frames, torn-tail
 * truncate) — group-committed (flush every [flushEvery] appends and at
 * drain/DecayTick), CAS-addressed payloads per the durability contract.
 */
class BeliefBagElement(
    val capacity: Int = 4096,
    private val cas: CasStore? = null,
    private val wal: DurableAppendLog? = null,
    private val flushEvery: Int = 32,
    /** Phase-3 seam: AttentionEconomy::decay lands here; identity until then. */
    private val decayFn: (BudgetCoord) -> BudgetCoord = { it },
    /** Eviction floor applied on DecayTick: priority below this is archive-eligible. */
    private val priorityFloor: Float = 0.0f,
    /**
     * β inverse-temperature supplier — the daemon wires quota/lease pressure;
     * the pristine resonance layer will consume it. Never WAL'd.
     */
    private val temperature: () -> Float = { 1f },
    /**
     * Post-tick hook, invoked at the END of every DecayTick — derived-state
     * rebuild seam (e.g. moments). Never WAL'd.
     */
    private val afterTick: () -> Unit = {},
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    companion object Key : AsyncContextKey<BeliefBagElement>()

    override val key: CoroutineContext.Key<*> get() = Key

    val intake: Channel<BeliefIntake> = Channel(capacity = 256)

    private val _events = MutableSharedFlow<BeliefEvent>(replay = 0, extraBufferCapacity = 1024)
    val beliefEvents: SharedFlow<BeliefEvent> get() = _events

    // The attention table: funnel-probe geometry × stochastic hijack replacement
    // (narchy HijackBag × Krapivin funnel — see HijackBeliefBag). The intake
    // channel is its single writer; readers cross its volatile stamp.
    private val hijack = HijackBeliefBag(capacity)
    /** Exact ancestry is retained when supplied; Bloom remains the fast hint. */
    private val basisByAngular = HashMap<Long, EvidenceBasis>()
    private val receiptByAngular = HashMap<Long, ContentId>()
    // angular → the Narsese surface the minter supplied; revisions keep the
    // latest one so a receipt can always show the expression, not a coordinate.
    private val glossByAngular = HashMap<Long, String>()

    /** The expression minted under [angular], when any minter supplied one. */
    fun glossOf(angular: Long): String? = glossByAngular[angular]

    // ── NAL-9: the moment field — the bag's self-model, rebuilt lazily on a
    // dirty flag (derived state: never WAL'd, milliseconds at d=64, n≤capacity).
    private val moments = MomentField()
    private val momentsMutex = Mutex()
    @Volatile private var momentsDirty = true
    private var walSeq = 0L
    private var unflushed = 0

    val size: Int get() = hijack.size

    /** Current β inverse-temperature (live supplier read; not a cached value). */
    fun temperatureNow(): Float = temperature()

    // ── lifecycle ─────────────────────────────────────────────────────

    override suspend fun open() {
        super.open()
        wal?.replay { seq, payload ->
            if (seq > walSeq) walSeq = seq
            applyWalRecord(payload)
        }
        if (state == ElementState.OPEN) state = ElementState.ACTIVE
        CoroutineScope(supervisor + Dispatchers.Default).launch {
            for (cmd in intake) handle(cmd)
        }
    }

    override suspend fun drain() {
        intake.close()
        // In-flight intakes finish via the consumer loop; flush what's buffered.
        wal?.flush()
        super.drain()
    }

    // ── reads (COW: the map reference is immutable, superseded on write) ──

    fun snapshot(): Map<Twin<Long>, SemanticSignal> {
        val out = HashMap<Twin<Long>, SemanticSignal>(hijack.size)
        hijack.forEach { s -> out[s.angular j s.budget.packed] = s.signal }
        return out
    }

    fun recallNear(centroid: Long, maxDistance: Int): Series<SemanticSignal> {
        val near = ArrayList<HijackBeliefBag.Slot>()
        hijack.forEach { s -> if (hamming(s.angular, centroid) <= maxDistance) near.add(s) }
        near.sortBy { hamming(it.angular, centroid) }
        return near.size j { i: Int -> near[i].signal }
    }

    /** Top-k by expectation × priority — the render/selection ranking (deterministic). */
    fun recallTop(k: Int): Series<Join<SemanticSignal, BudgetCoord>> {
        val all = ArrayList<HijackBeliefBag.Slot>(hijack.size)
        hijack.forEach { all.add(it) }
        all.sortWith(compareByDescending<HijackBeliefBag.Slot> { it.pri }.thenBy { it.angular })
        val ranked = all.take(k)
        return ranked.size j { i: Int -> ranked[i].signal j ranked[i].budget }
    }

    /** Primacy-weighted stochastic attention sample (the hijack/funnel recall). */
    fun recallSample(k: Int): Series<Join<SemanticSignal, BudgetCoord>> {
        val picked = hijack.sample(k)
        return picked.size j { i: Int -> picked[i].signal j picked[i].budget }
    }

    /** Funnel depth of a belief — its attention standing, physically. -1 = not resident. */
    fun levelOf(angular: Long): Int = hijack.levelOf(angular)

    /**
     * One flat autovec sweep over every action potential: the support front
     * (synonym peaks, positive evidence) and refutation front (antonym peaks,
     * negative evidence) of a solver proposal. Frontier additions participate
     * with zero lag — the vector plane mirrors at write time.
     */
    fun resonate(centroid: Long, k: Int = 8): HijackBeliefBag.Resonance = hijack.resonate(centroid, k)

    /** The self-model, rebuilt if any intake landed since the last build. */
    suspend fun field(): MomentField {
        if (momentsDirty) momentsMutex.withLock {
            if (momentsDirty) {
                moments.rebuild(hijack)
                momentsDirty = false
            }
        }
        return moments
    }

    /**
     * Mahalanobis resonance over the moment field — whitening replaces the s⁴
     * contrast hack from first principles; β comes from the temperature seam
     * (quota pressure: hot = exploratory, cold = sharp).
     */
    suspend fun resonateWhitened(centroid: Long, k: Int = 8): MomentField.Resonance =
        field().resonate(centroid, k, beta = temperatureNow())

    fun budgetOf(angular: Long): BudgetCoord? = hijack.get(angular)?.budget

    /** The resident signal under [angular], so a receipt can name what it is about. */
    fun signalOf(angular: Long): SemanticSignal? = hijack.get(angular)?.signal

    // ── intake processing (single consumer: the bag's serial spine) ────

    private suspend fun handle(cmd: BeliefIntake) {
        when (cmd) {
            is BeliefIntake.Mint -> mint(cmd.signal, cmd.budget, cmd.receiptCid, cmd.evidenceBasis, cmd.gloss)
            is BeliefIntake.Reinforce -> reinforce(cmd.angular, cmd.delta)
            is BeliefIntake.Attend -> attend(cmd.angular, cmd.budget)
            BeliefIntake.DecayTick -> decayAll()
        }
        momentsDirty = true
        maybeFlush()
    }

    private fun mint(
        incoming: SemanticSignal,
        budget: BudgetCoord,
        receiptCid: ContentId?,
        evidenceBasis: EvidenceBasis?,
        gloss: String? = null,
    ) {
        val angular = incoming.angular
        if (gloss != null) glossByAngular[angular] = gloss
        val shown = glossByAngular[angular]
        val revived = if (hijack.get(angular) == null && cas != null) reviveFromCas(incoming) else incoming
        val existing = hijack.get(angular)
        val incomingReceipt = receiptCid ?: ContentId.of(SignalCodec.encode(revived))
        val explicitRevision = existing != null && evidenceBasis != null
        val prepared = if (explicitRevision) {
            val decision = OverlapSafeRevision.revise(
                receiptByAngular[angular],
                incomingReceipt,
                existing!!.signal.evidence,
                revived.evidence,
                basisByAngular[angular],
                evidenceBasis,
            )
            if (!decision.accepted) return
            revived.copy(evidence = decision.evidence)
        } else revived
        val outcome = hijack.put(HijackBeliefBag.Slot(angular, budget, prepared)) { existing, inc ->
            // NARS revision at the cell: evidence union + basis Bloom union, incoming metadata wins
            HijackBeliefBag.Slot(
                angular,
                inc.budget,
                inc.signal.copy(
                    evidence = if (explicitRevision) inc.signal.evidence else revise(existing.signal.evidence, inc.signal.evidence),
                    basisBloom = existing.signal.basisBloom or inc.signal.basisBloom,
                ),
            )
        }
        when (outcome) {
            is HijackBeliefBag.Put.Placed -> {
                recordBasis(angular, incomingReceipt, evidenceBasis)
                walAppend("B:${outcome.slot.budget.packed}:" + SignalCodec.encode(outcome.slot.signal).decodeToString())
                _events.tryEmit(
                    if (outcome.merged) BeliefEvent.Revised(angular, outcome.slot.signal.evidence, shown)
                    else BeliefEvent.Minted(angular, receiptCid, shown),
                )
            }
            is HijackBeliefBag.Put.Hijacked -> {
                recordBasis(angular, incomingReceipt, evidenceBasis)
                walAppend("B:${outcome.slot.budget.packed}:" + SignalCodec.encode(outcome.slot.signal).decodeToString())
                _events.tryEmit(BeliefEvent.Minted(angular, receiptCid, shown))
                spill(outcome.victim)
            }
            is HijackBeliefBag.Put.Rejected -> {
                // lost the roulette at every funnel level: attention refused, evidence preserved
                spill(outcome.incoming)
            }
        }
        if (incoming.relation == RelationKind.CONTRADICTION && outcome !is HijackBeliefBag.Put.Rejected) {
            _events.tryEmit(BeliefEvent.Contradicted(angular, incoming.subjectCid))
        }
    }

    private fun recordBasis(angular: Long, receiptCid: ContentId, basis: EvidenceBasis?) {
        receiptByAngular[angular] = receiptCid
        if (basis != null) {
            val prior = basisByAngular[angular]
            basisByAngular[angular] = if (prior == null) basis else unionBasis(prior, basis)
        }
    }

    private fun unionBasis(a: EvidenceBasis, b: EvidenceBasis): EvidenceBasis {
        val seen = HashSet<ContentId>()
        val leaves = mutableListOf<ContentId>()
        for (source in listOf(a, b)) for (i in 0 until source.leaves.size) {
            val leaf = source.leaves[i]
            if (seen.add(leaf)) leaves.add(leaf)
        }
        return EvidenceBasis.of(*leaves.toTypedArray())
    }

    private fun reinforce(angular: Long, delta: EvidenceCoord) {
        val existing = hijack.get(angular) ?: return
        val merged = existing.signal.copy(evidence = revise(existing.signal.evidence, delta))
        hijack.put(HijackBeliefBag.Slot(angular, existing.budget, merged)) { _, inc -> inc }
        walAppend("B:${existing.budget.packed}:" + SignalCodec.encode(merged).decodeToString())
        _events.tryEmit(BeliefEvent.Revised(angular, merged.evidence))
    }

    private fun attend(angular: Long, budget: BudgetCoord) {
        val existing = hijack.get(angular) ?: return
        if (existing.budget.packed == budget.packed) return
        hijack.put(HijackBeliefBag.Slot(angular, budget, existing.signal)) { _, inc -> inc }
        walAppend("A:$angular:${budget.packed}")
        _events.tryEmit(BeliefEvent.Attended(angular, budget))
    }

    private fun decayAll() {
        // one in-place pass, one WAL group: attention decays, floors evict (ARCHIVED = spill)
        val evicted = hijack.updateEach { s ->
            val decayed = decayFn(s.budget)
            when {
                priorityFloor > 0f && decayed.pf < priorityFloor -> null
                decayed.packed == s.budget.packed -> s
                else -> HijackBeliefBag.Slot(s.angular, decayed, s.signal)
            }
        }
        walAppend("D:$walSeq")
        for (v in evicted) spill(v)
        afterTick() // post-tick seam: derived state (e.g. moments) rebuilds AFTER spills settle
    }

    // ── spill: attention lost, evidence preserved in CAS ─────────────

    private fun spill(slot: HijackBeliefBag.Slot) {
        val spillCid = cas?.put(SignalCodec.encode(slot.signal))
        if (spillCid != null) spillCids = spillCids + (slot.angular to spillCid)
        walAppend("E:${slot.angular}:${spillCid?.hex ?: ""}")
        _events.tryEmit(BeliefEvent.Evicted(slot.angular, spillCid))
    }

    /** A re-mint of a previously spilled angular revives its permanent evidence. */
    private fun reviveFromCas(incoming: SemanticSignal): SemanticSignal {
        val spilled = spillCids[incoming.angular] ?: return incoming
        spillCids = spillCids - incoming.angular
        val bytes = cas?.get(spilled) ?: return incoming
        val prior = runCatching { SignalCodec.decode(bytes) }.getOrNull() ?: return incoming
        return incoming.copy(evidence = revise(prior.evidence, incoming.evidence))
    }

    @Volatile private var spillCids: Map<Long, ContentId> = emptyMap()

    // ── WAL ───────────────────────────────────────────────────────────

    private fun walAppend(payload: String) {
        wal?.append(++walSeq, payload.encodeToByteArray())
        unflushed++
    }

    private fun maybeFlush() {
        if (unflushed >= flushEvery) {
            wal?.flush()
            unflushed = 0
        }
    }

    private fun applyWalRecord(payload: ByteArray) {
        val text = payload.decodeToString()
        when {
            text.startsWith("B:") -> {
                val rest = text.substring(2)
                val colon = rest.indexOf(':')
                if (colon <= 0) return
                val budget = rest.substring(0, colon).toLongOrNull() ?: return
                val signal = runCatching { SignalCodec.decode(rest.substring(colon + 1).encodeToByteArray()) }.getOrNull() ?: return
                // deterministic replay: force-place, no roulette; late displacements were E-recorded anyway
                hijack.place(HijackBeliefBag.Slot(signal.angular, BudgetCoord(budget), signal))
            }
            text.startsWith("A:") -> {
                val parts = text.substring(2).split(':')
                if (parts.size != 2) return
                val angular = parts[0].toLongOrNull() ?: return
                val budget = parts[1].toLongOrNull() ?: return
                val existing = hijack.get(angular) ?: return
                hijack.place(HijackBeliefBag.Slot(angular, BudgetCoord(budget), existing.signal))
            }
            text.startsWith("E:") -> {
                val parts = text.substring(2).split(':', limit = 2)
                val angular = parts[0].toLongOrNull() ?: return
                hijack.remove(angular)
                if (parts.size == 2 && parts[1].isNotEmpty()) {
                    spillCids = spillCids + (angular to ContentId("sha256:" + parts[1]))
                }
            }
            // "D:" decay markers are group boundaries; the following B/A records carry state
        }
    }
}
