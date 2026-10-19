package borg.trikeshed.graal

import borg.trikeshed.context.*
import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.concurrency.Channel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * BlackboardChangesFactElement — the tendon between the daemon blackboard and
 * the production system; the facts/ plane's entry into the one fact plane.
 *
 * Every admitted key of [blackboard] becomes, in order:
 *   a Rete fact `FactId("blackboard", key)`: `assert` on first sight, `modify`
 *   when the key's content version changes, `retract` when the key vanishes
 *   from the board or the admit list stops accepting it.
 *
 * Fact shape (the [PlaneFacts] conventions): `kind="blackboard"`, `key`, `actor`
 * (the provenance language), `atMs` (the provenance stamp), plus the value
 * flattened by [flatten] — a Map value's entries become string-valued fields
 * (a name colliding with a reserved field is prefixed `v.`), a scalar lands in
 * `value`, any other object lands in `value` through `toString`. The version is
 * `PlaneFacts.versionOf(fields - atMs)`: the CONTENT version, so a same-value
 * re-put (new stamp, same bytes) is not a new version and evaluates no rule —
 * the same discipline as the couch tendon keying its version on the blob cid
 * rather than the sequence. `atMs` therefore reads as "when this version
 * landed".
 *
 * Diff basis. [ConfixBlackboard.revisions] wakes a bounded, serial projection
 * of one atomic [ConfixBlackboard.snapshot]. Per-key revisions avoid re-hashing
 * unchanged entries; content cids still suppress same-value re-put evaluation.
 * Values and provenance always come from the same snapshot, including removals.
 *
 * Loop guard. The admit predicate defaults to [BlackboardNamespaces.admitByDefault],
 * which refuses the rule-firing outputs (`kanban/rule/`, `narsese/curation/`,
 * `narsese/rete/firing/`). Without it a production with interest
 * `kind=blackboard` fires, the sink puts a fresh `kanban/rule/…` key, the next
 * drain asserts it as a fresh fact, the network evaluates again, the sink puts
 * again — unbounded. Refraction cannot close that loop because every receipt is
 * a new FactId. The element's test pins the bound.
 *
 * Re-entrancy. The deprecated synchronous `ConfixBlackboard.subscribe` shim is
 * never used: it would run this element's writes on the putter's thread — and a
 * putter is, in the daemon, the production sink running inside
 * `ReteNetwork.evaluateRules` under the network's non-reentrant write lock.
 * A one-slot userspace Channel coalesces wakeups, never facts: each pass reads
 * current state. All Rete writes are serialized with manual [drainKeys] calls.
 *
 * For an opened owner, [drain] joins the revision captured at entry, detaches the
 * collector, and joins queued work before completing the supervisor. Producers
 * must drain first. This joins Rete writes and synchronous production delivery,
 * not downstream actions independently admitted by a production sink. A failed
 * projection remains a failed barrier/close, never a successful watermark.
 * No clock (stamps come from the board), no sockets.
 */
@OptIn(ExperimentalAtomicApi::class)
class BlackboardChangesFactElement(
    private val blackboard: ConfixBlackboard,
    private val rete: ReteNetwork,
    private val board: BlackboardContext = BlackboardContext(id = PlaneFacts.BLACKBOARD),
    /** Only keys accepted here become facts (default: everything but the rule-firing outputs, see [BlackboardNamespaces]). */
    private val admit: (String) -> Boolean = BlackboardNamespaces::admitByDefault,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<BlackboardChangesFactElement>() {
        /** Field a scalar or opaque value lands in. */
        const val VALUE = "value"
        /** Prefix for a Map entry whose name collides with a reserved plane field. */
        const val COLLISION_PREFIX = "v."

        private val RESERVED = setOf(PlaneFacts.KIND, PlaneFacts.KEY, PlaneFacts.ACTOR, PlaneFacts.AT_MS)

        /**
         * The fields a blackboard entry projects to. Pure: same (key, value,
         * provenance) → same map, so the cid is reproducible from the board alone.
         */
        fun fieldsOf(key: String, value: Any?, provenance: ConfixBlackboard.ProvenanceEntry): Map<String, Any?> {
            val fields = LinkedHashMap<String, Any?>()
            fields[PlaneFacts.KIND] = PlaneFacts.BLACKBOARD
            fields[PlaneFacts.KEY] = key
            fields[PlaneFacts.ACTOR] = provenance.language
            fields[PlaneFacts.AT_MS] = provenance.timestamp
            flatten(value, fields)
            return fields
        }

        /**
         * Flattening table (merger brief, section 2):
         *  - a Map: each entry becomes a field named by the entry key (`toString` for a
         *    non-String key); a name that is one of the four reserved fields is
         *    prefixed [COLLISION_PREFIX]; entry values go through [scalarText];
         *  - a String / Number / Boolean: one field [VALUE] holding [scalarText];
         *  - a List / Array: one field [VALUE] holding its canonical JSON;
         *  - null: one field [VALUE] holding null (the key exists with no value);
         *  - anything else (a PointcutLanding, a receipt object): [VALUE] = `toString`.
         */
        fun flatten(value: Any?, into: MutableMap<String, Any?>) {
            when (value) {
                is Map<*, *> -> for ((k, v) in value) {
                    val name = k.toString()
                    into[if (name in RESERVED) COLLISION_PREFIX + name else name] = scalarText(v)
                }
                else -> into[VALUE] = scalarText(value)
            }
        }

        /** String-valued projection of one entry: null stays null, strings pass, scalars print, structures print as canonical JSON, objects as `toString`. */
        fun scalarText(v: Any?): String? = when (v) {
            null -> null
            is String -> v
            is Number, is Boolean -> v.toString()
            is Map<*, *>, is Iterable<*>, is Array<*> -> PlaneFacts.canonicalJson(v)
            is ContentId -> v.value
            else -> v.toString()
        }

        /** The content version: every field but the stamp. */
        fun versionOf(fields: Map<String, Any?>): ContentId = PlaneFacts.versionOf(fields - PlaneFacts.AT_MS)
    }

    override val key: CoroutineContext.Key<*> get() = Key

    private class Known(val revision: Long, val cid: ContentId)
    private class Projection(val owner: BlackboardChangesFactElement) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Projection>
    }
    private data class Progress(val revision: Long = -1, val failure: Throwable? = null, val closed: Boolean = false)

    private val wake = Channel.buffered<Unit>(1)
    private val lifecycle = Mutex()
    private val projection = Mutex()
    private val progress = MutableStateFlow(Progress())
    private val accepting = AtomicBoolean(true)
    private var collector: Job? = null
    private var consumer: Job? = null
    @Volatile private var unsubscribing = false
    private val known = HashMap<String, Known>()

    /** Successfully completed fact operations, not downstream action receipts. */
    var factsApplied: Long = 0L
        private set

    /** Atomic snapshot projection passes attempted. */
    var drains: Long = 0L
        private set

    private fun failed(cause: Throwable) {
        progress.update { if (it.failure == null) it.copy(failure = cause) else it }
    }

    private suspend fun checkCallback() {
        check(currentCoroutineContext()[Projection]?.owner !== this) {
            "A blackboard projection cannot join itself"
        }
    }

    override suspend fun open() = lifecycle.withLock {
        if (state != ElementState.CREATED) return@withLock
        check(accepting.load()) { "Blackboard projection admission stopped" }
        progress.value.failure?.let { throw it }
        supervisor.ensureActive()
        state = ElementState.OPEN
        val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + supervisor + this)
        collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                blackboard.revisions.collect { wake.kChannel.trySend(Unit) }
            } catch (cause: Throwable) {
                if (!unsubscribing) {
                    failed(cause)
                    wake.close()
                }
            }
        }
        consumer = scope.launch {
            try {
                for (unit in wake.kChannel) projection.withLock { projectSnapshot() }
            } catch (cause: Throwable) {
                failed(cause)
            } finally {
                wake.close()
                progress.update { it.copy(closed = true) }
            }
        }
        state = ElementState.ACTIVE
    }

    /** Serial explicit pass, also usable before open; stopped owners reject new work. */
    suspend fun drainKeys() {
        checkCallback()
        currentCoroutineContext().ensureActive()
        projection.withLock {
            check(accepting.load()) {
                "Blackboard projection admission stopped"
            }
            // The admitted pass belongs to this supervisor, not its caller's waiting job.
            withContext(supervisor + this@BlackboardChangesFactElement) { projectSnapshot() }
        }
    }

    private suspend fun projectSnapshot(): Unit = withContext(Projection(this@BlackboardChangesFactElement)) {
        progress.value.failure?.let { throw it }
        try {
            drains++
            val snapshot = blackboard.snapshot()
            val live = snapshot.values.keys
            for ((k, value) in snapshot.values) {
                if (!admit(k)) {
                    if (known[k] != null) {
                        rete.retract(FactId(board.id, k))
                        known.remove(k)
                        factsApplied++
                    }
                    continue
                }
                val provenance = checkNotNull(snapshot.provenance[k])
                val prior = known[k]
                if (prior != null && prior.revision == provenance.revision) continue
                val fields = fieldsOf(k, value, provenance)
                val cid = versionOf(fields)
                val factId = FactId(board.id, k)
                if (prior == null) {
                    rete.assert(factId, fields, cid, board)
                    factsApplied++
                } else if (prior.cid != cid) {
                    rete.modify(factId, fields, cid)
                    factsApplied++
                }
                known[k] = Known(provenance.revision, cid)
            }
            for (k in known.keys.filter { it !in live }) {
                rete.retract(FactId(board.id, k))
                known.remove(k)
                factsApplied++
            }
            progress.update { it.copy(revision = snapshot.revision) }
        } catch (cause: Throwable) {
            failed(cause)
            throw cause
        }
    }

    /** Completion means the Rete calls returned, not merely that a wakeup was accepted. */
    suspend fun awaitRevision(revision: Long) {
        checkCallback()
        require(revision >= 0) { "Blackboard revision must be nonnegative" }
        val observed = progress.first { it.revision >= revision || it.failure != null || it.closed }
        observed.failure?.let { throw it }
        check(observed.revision >= revision) { "Blackboard projection closed before revision $revision" }
    }

    override suspend fun drain(): Unit = withContext(NonCancellable) {
        checkCallback()
        accepting.store(false)
        lifecycle.withLock {
            if (state == ElementState.CLOSED) {
                progress.value.failure?.let { throw it }
                return@withLock
            }
            val revision = blackboard.snapshot().revision
            state = ElementState.DRAINING
            var failure = progress.value.failure
            suspend fun release(action: suspend () -> Unit) {
                try { action() }
                catch (cause: Throwable) {
                    val first = failure
                    if (first == null) failure = cause else if (first !== cause) first.addSuppressed(cause)
                }
            }
            if (consumer != null) {
                // Also covers a writer between its atomic publication and revision notification.
                wake.kChannel.trySend(Unit)
                release { awaitRevision(revision) }
            }
            unsubscribing = true
            release { collector?.cancelAndJoin() }
            wake.close()
            release { consumer?.join() }
            release { projection.withLock { } }
            supervisor.complete()
            release { supervisor.join() }
            progress.value.failure?.let { if (failure == null) failure = it }
            failure?.let { failed(it) }
            progress.update { it.copy(closed = true) }
            state = ElementState.CLOSED
            failure?.let { throw it }
        }
    }

    override suspend fun close() = drain()
}
