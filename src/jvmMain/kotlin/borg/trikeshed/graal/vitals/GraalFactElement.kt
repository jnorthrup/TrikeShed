package borg.trikeshed.graal.vitals

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.job.ContentId
import borg.trikeshed.pointcut.PointcutBlackboardAdapter.PointcutLanding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

/**
 * GraalFactElement — the runtime's tendon into the production system: the `graal`
 * partition of the plane ([PlaneFacts.GRAAL]).
 *
 * Three producers feed it, none of which touched a plane before:
 *  - [JvmVitals.events] (`compile` / `deopt` / `gc`, already reduced to the fields
 *    worth animating) — folded into bounded STATE facts, one per accumulator, never
 *    one FactId per event: `gc/<collector>`, `jit`, `deopt/<method>` (at most
 *    [deoptCap] distinct methods; the cap is logged once and every further method is
 *    counted in [deoptDropped], not projected).
 *  - a tick over [snapshotSupplier] ([JvmVitals.snapshot]) — `vitals/memory` from the
 *    `memory` section and `alloc/<class>` for the top [allocTopN] classes of
 *    `gc.lane.allocation`; a class that leaves the top-N is retracted, so the alloc
 *    fact count is bounded by N.
 *  - every [PointcutLanding] on [pointcutFlows] (the daemon adapter's `flow`, the
 *    Hypervisor's, both) — `FactId(graal, landing.key)` with exactly
 *    [PointcutLanding.toFields] plus the reserved fields, so the fact's localId is
 *    the blackboard key is the couch doc id (one identity, three planes).
 *
 * Every fact carries [PlaneFacts.KIND] (the interest handle), [PlaneFacts.KEY]
 * (= localId, the inverse pointer to the accumulator) and [PlaneFacts.ACTOR]
 * ([ACTOR_JVMVITALS] for runtime state, the facet id for a landing).
 * [PlaneFacts.AT_MS] rides only the facts that ARE a sample or an observation
 * (`vitals/memory`, `pointcut`; a gc fact keeps `lastAtMs`): accumulators such as
 * `jit`, `deopt/<method>` and `alloc/<class>` are their counters, and stamping them per tick would
 * defeat the no-op rule below.
 *
 * versionCid = [PlaneFacts.versionOf] the fields. The element remembers the cid it
 * last landed per localId and SKIPS `modify` when the cid is unchanged
 * ([ReteNetwork.modify] always re-evaluates; [ReteNetwork.assert] of an identical
 * fact is already a no-op), so the same landing twice, or an unchanged alloc row
 * across ticks, costs the network nothing — an observer sees one op.
 *
 * Concurrency: the network serializes its own writers; this element serializes its
 * OWN accumulators (the event collector, each landing collector and the ticker run
 * on the element supervisor concurrently) behind [stateLock]. Nothing here runs
 * inside a [ReteNetwork] observer, so the non-reentrant write lock is never re-taken.
 *
 * Lifecycle mirrors [borg.trikeshed.couch.CouchChangesFactElement]: collectors are
 * launched on [supervisor] + Default in [open] and cancelled by [close]. The public
 * [onEvent], [onLanding] and [tick] are the collectors' bodies, exposed so the
 * element is provable without JFR: a test hands in a synthetic event flow and a
 * synthetic snapshot ([JvmVitals] is only the default provider, see the secondary
 * constructor) and drives them directly, or opens the element and lets the flows do it.
 */
class GraalFactElement(
    private val rete: ReteNetwork,
    /** The runtime event feed ([JvmVitals.events] in the daemon; any flow of [JvmVitals.VitalEvent] in a test). */
    private val events: Flow<JvmVitals.VitalEvent>,
    /** The instrument-cluster read the ticker folds ([JvmVitals.snapshot] in the daemon). */
    private val snapshotSupplier: () -> Map<String, Any?>,
    /** Every pointcut adapter whose landings become facts — the daemon's and the Hypervisor's. */
    private val pointcutFlows: List<Flow<PointcutLanding>> = emptyList(),
    /** Ticker period; `<= 0` means no ticker (a test calls [tick] itself). */
    private val tickMs: Long = DEFAULT_TICK_MS,
    /** Most distinct `deopt/<method>` facts kept; the rest are counted in [deoptDropped]. */
    private val deoptCap: Int = DEFAULT_DEOPT_CAP,
    /** Alloc rows projected per tick, by sampled bytes descending. */
    private val allocTopN: Int = DEFAULT_ALLOC_TOP_N,
    /** Stamp for `vitals/memory` samples. */
    private val clock: () -> Long = System::currentTimeMillis,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    /** The daemon shape: one live [JvmVitals] is both the event feed and the snapshot. */
    constructor(
        vitals: JvmVitals,
        rete: ReteNetwork,
        pointcutFlows: List<Flow<PointcutLanding>> = emptyList(),
        tickMs: Long = DEFAULT_TICK_MS,
        parentJob: Job? = null,
    ) : this(
        rete = rete,
        events = vitals.events,
        snapshotSupplier = vitals::snapshot,
        pointcutFlows = pointcutFlows,
        tickMs = tickMs,
        parentJob = parentJob,
    )

    companion object Key : AsyncContextKey<GraalFactElement>() {
        const val DEFAULT_TICK_MS = 5_000L
        const val DEFAULT_DEOPT_CAP = 256
        const val DEFAULT_ALLOC_TOP_N = 32

        /** [PlaneFacts.ACTOR] on every runtime-state fact. */
        const val ACTOR_JVMVITALS = "jvmvitals"

        const val KIND_GC = "gc"
        const val KIND_JIT = "jit"
        const val KIND_DEOPT = "deopt"
        const val KIND_MEMORY = "memory"
        const val KIND_ALLOC = "alloc"
        const val KIND_POINTCUT = "pointcut"

        const val LOCAL_JIT = "jit"
        const val LOCAL_MEMORY = "vitals/memory"
        const val GC_PREFIX = "gc/"
        const val DEOPT_PREFIX = "deopt/"
        const val ALLOC_PREFIX = "alloc/"

        private fun asLong(v: Any?): Long = when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    override val key: CoroutineContext.Key<*> get() = Key

    private val board = BlackboardContext(PlaneFacts.GRAAL)

    /** Guards the accumulators and [landed]; never held across anything but a rete write. */
    private val stateLock = Mutex()

    /** localId -> the cid last landed for it; presence = the fact is in working memory. */
    private val landed = HashMap<String, ContentId>()

    private class GcState(var collections: Long = 0, var pauseMsTotal: Long = 0, var lastPauseMs: Long = 0, var lastCause: String = "?", var lastAtMs: Long = 0)
    private class DeoptState(var count: Long = 0, var reason: String = "?", var action: String = "?")

    private val gc = HashMap<String, GcState>()
    private val deopt = HashMap<String, DeoptState>()
    private var compilations = 0L
    private var osr = 0L
    private var compiledBytes = 0L
    private var allocKnown: Set<String> = emptySet()
    private var deoptCapLogged = false

    /** Ops actually handed to the network (asserts + modifies + retracts); an unchanged cid is not one. */
    var factsApplied: Long = 0L
        private set

    /** Deopt events whose method was a NEW one past [deoptCap] — counted, never projected. */
    var deoptDropped: Long = 0L
        private set

    override suspend fun open() {
        if (state != ElementState.CREATED) return
        super.open()
        val scope = CoroutineScope(supervisor + Dispatchers.Default)
        scope.launch { events.collect { onEvent(it) } }
        for (flow in pointcutFlows) scope.launch { flow.collect { onLanding(it) } }
        if (tickMs > 0) scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(tickMs)
            }
        }
        state = ElementState.ACTIVE
    }

    // ── producers ────────────────────────────────────────────────

    /** Fold one runtime event into its state fact. `cpu` (and any unknown kind) is not a fact. */
    suspend fun onEvent(event: JvmVitals.VitalEvent) {
        when (event.kind) {
            "gc" -> onGc(event)
            "compile" -> onCompile(event)
            "deopt" -> onDeopt(event)
        }
    }

    private suspend fun onGc(event: JvmVitals.VitalEvent) = stateLock.withLock {
        val collector = event.detail["name"]?.toString() ?: "?"
        val s = gc.getOrPut(collector) { GcState() }
        s.collections++
        s.lastPauseMs = asLong(event.detail["pauseMs"])
        s.pauseMsTotal += s.lastPauseMs
        s.lastCause = event.detail["cause"]?.toString() ?: "?"
        s.lastAtMs = event.detail["atMs"]?.let(::asLong) ?: event.atMs
        val localId = GC_PREFIX + collector
        land(
            localId,
            linkedMapOf(
                PlaneFacts.KIND to KIND_GC,
                PlaneFacts.KEY to localId,
                PlaneFacts.ACTOR to ACTOR_JVMVITALS,
                "collector" to collector,
                "collections" to s.collections,
                "pauseMsTotal" to s.pauseMsTotal,
                "lastPauseMs" to s.lastPauseMs,
                "lastCause" to s.lastCause,
                "lastAtMs" to s.lastAtMs,
            ),
        )
    }

    private suspend fun onCompile(event: JvmVitals.VitalEvent) = stateLock.withLock {
        compilations++
        if (event.detail["osr"] == true) osr++
        compiledBytes += asLong(event.detail["codeSize"])
        land(
            LOCAL_JIT,
            linkedMapOf(
                PlaneFacts.KIND to KIND_JIT,
                PlaneFacts.KEY to LOCAL_JIT,
                PlaneFacts.ACTOR to ACTOR_JVMVITALS,
                "compilations" to compilations,
                "osr" to osr,
                "compiledBytes" to compiledBytes,
            ),
        )
    }

    private suspend fun onDeopt(event: JvmVitals.VitalEvent) = stateLock.withLock {
        val method = event.detail["method"]?.toString() ?: "?"
        val s = deopt[method] ?: run {
            if (deopt.size >= deoptCap) {
                deoptDropped++
                if (!deoptCapLogged) {
                    deoptCapLogged = true
                    System.err.println("[GraalFactElement] deopt/<method> cap of $deoptCap distinct methods reached; further methods are counted in deoptDropped, not projected")
                }
                return@withLock
            }
            DeoptState().also { deopt[method] = it }
        }
        s.count++
        s.reason = event.detail["reason"]?.toString() ?: "?"
        s.action = event.detail["action"]?.toString() ?: "?"
        val localId = DEOPT_PREFIX + method
        land(
            localId,
            linkedMapOf(
                PlaneFacts.KIND to KIND_DEOPT,
                PlaneFacts.KEY to localId,
                PlaneFacts.ACTOR to ACTOR_JVMVITALS,
                "method" to method,
                "reason" to s.reason,
                "action" to s.action,
                "count" to s.count,
            ),
        )
    }

    /**
     * One landing -> one fact keyed by the landing's own blackboard key. The fields
     * are [PointcutLanding.toFields] verbatim plus the reserved four; `atMs` is the
     * coordinate's stamp, so the same landing replayed lands on the same cid.
     */
    suspend fun onLanding(landing: PointcutLanding) = stateLock.withLock {
        val fields = LinkedHashMap<String, Any?>()
        fields[PlaneFacts.KIND] = KIND_POINTCUT
        fields[PlaneFacts.KEY] = landing.key
        fields[PlaneFacts.ACTOR] = landing.facet.id
        fields[PlaneFacts.AT_MS] = landing.coordinate.timestamp
        fields.putAll(landing.toFields())
        land(landing.key, fields)
    }

    /**
     * Fold the instrument cluster: `vitals/memory` from `memory`, `alloc/<class>` for
     * the top-N rows of `gc.lane.allocation` (a row that fell out of the top-N is
     * retracted). A snapshot without a section leaves that section's facts alone.
     */
    suspend fun tick() {
        val snapshot = snapshotSupplier()
        stateLock.withLock {
            val memory = snapshot["memory"] as? Map<*, *>
            if (memory != null) {
                land(
                    LOCAL_MEMORY,
                    linkedMapOf(
                        PlaneFacts.KIND to KIND_MEMORY,
                        PlaneFacts.KEY to LOCAL_MEMORY,
                        PlaneFacts.ACTOR to ACTOR_JVMVITALS,
                        PlaneFacts.AT_MS to clock(),
                        "heapUsed" to asLong(memory["heapUsed"]),
                        "heapCommitted" to asLong(memory["heapCommitted"]),
                        "heapMax" to asLong(memory["heapMax"]),
                        "metaspaceUsed" to asLong(memory["metaspaceUsed"]),
                    ),
                )
            }
            val allocation = ((snapshot["gc"] as? Map<*, *>)?.get("lane") as? Map<*, *>)?.get("allocation") as? List<*>
            if (allocation != null) {
                val top = allocation.asSequence()
                    .mapNotNull { it as? Map<*, *> }
                    .mapNotNull { row -> row["class"]?.toString()?.let { it to asLong(row["bytes"]) } }
                    .sortedByDescending { it.second }
                    .take(allocTopN)
                    .toList()
                val now = LinkedHashSet<String>()
                for ((className, bytes) in top) {
                    now.add(className)
                    val localId = ALLOC_PREFIX + className
                    land(
                        localId,
                        linkedMapOf(
                            PlaneFacts.KIND to KIND_ALLOC,
                            PlaneFacts.KEY to localId,
                            PlaneFacts.ACTOR to ACTOR_JVMVITALS,
                            "class" to className,
                            "bytes" to bytes,
                        ),
                    )
                }
                for (gone in allocKnown - now) retract(ALLOC_PREFIX + gone)
                allocKnown = now
            }
        }
    }

    // ── the one write path ───────────────────────────────────────

    /** Assert when unknown, modify when the cid moved, nothing when it did not. Caller holds [stateLock]. */
    private suspend fun land(localId: String, fields: Map<String, Any?>) {
        val cid = PlaneFacts.versionOf(fields)
        val previous = landed[localId]
        if (previous == cid) return
        val factId = FactId(PlaneFacts.GRAAL, localId)
        if (previous == null) rete.assert(factId, fields, cid, board) else rete.modify(factId, fields, cid)
        landed[localId] = cid
        factsApplied++
    }

    /** Caller holds [stateLock]. */
    private suspend fun retract(localId: String) {
        if (landed.remove(localId) == null) return
        rete.retract(FactId(PlaneFacts.GRAAL, localId))
        factsApplied++
    }

    /** Detach hygiene: every fact this element landed leaves working memory with proper retraction. */
    suspend fun retractAll() = stateLock.withLock {
        for (localId in landed.keys.toList()) retract(localId)
        allocKnown = emptySet()
    }
}
