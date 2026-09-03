package borg.trikeshed.graal

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.job.ContentId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
 * Diff basis. `ConfixBlackboard.changes` emits a one-key doc for the key just
 * put and cannot describe a removal (its `doc.remove` is a no-op), and its
 * DROP_OLDEST buffer can lose the hint under burst — so, like
 * BlackboardWire.emitDeltas, the stream is only a WAKE. [drainKeys] re-diffs
 * `keys()` against a per-key (provenance stamp, value reference, versionCid)
 * record: an unchanged stamp and the same value object mean the key was not
 * put again since last drain and cost nothing; a new stamp (or a new object
 * under the same millisecond stamp) re-flattens and re-hashes, and the fact is
 * modified only when the cid moved. Keys absent from `keys()` are retracted.
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
 * The wake is a CONFLATED channel fed from the `changes` flow on this element's
 * own coroutine; every Rete write happens on the drain coroutine.
 *
 * CCEK element: collects on [open], drains on the element supervisor, stops
 * collecting on [close]. No clock (stamps come from the board), no sockets.
 */
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

    /** What a key looked like at its last drain; the diff basis. */
    private class Known(val stamp: Long, val value: Any?, val cid: ContentId)

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var collector: Job? = null
    /** Every key currently in working memory, with its diff record. */
    private val known = HashMap<String, Known>()

    /** Keys turned into fact ops (assert, modify, retract) so far. */
    var factsApplied: Long = 0L
        private set

    /** Drain passes run so far (each is one full keys() diff). */
    var drains: Long = 0L
        private set

    override suspend fun open() {
        if (state != ElementState.CREATED) return
        super.open()
        val scope = CoroutineScope(supervisor + Dispatchers.Default)
        // replay=1 on changes hands the collector the current board at attach, so the
        // first wake arrives without waiting for a put.
        collector = scope.launch {
            try {
                blackboard.changes.collect { wake.trySend(Unit) }
            } catch (_: kotlinx.coroutines.CancellationException) {
            }
        }
        scope.launch {
            try {
                drainKeys()
                for (unit in wake) drainKeys()
            } catch (_: kotlinx.coroutines.CancellationException) {
            }
        }
        state = ElementState.ACTIVE
    }

    /**
     * Diff the board against [known] and apply every difference. Safe to call
     * repeatedly and from a test without [open] (no dispatcher race). Named as the
     * couch tendon names `drainFrames`: `drain()` is the element lifecycle verb.
     */
    suspend fun drainKeys() {
        drains++
        // The blackboard's maps are single-writer/unguarded-read: a keys() snapshot can
        // throw mid-grow under a concurrent writer. Dropping one pass is safe — the next
        // wake re-diffs.
        val keys = runCatching { blackboard.keys() }.getOrNull() ?: return
        val live = HashSet<String>(keys.size * 2)
        for (k in keys) {
            live.add(k)
            if (!admit(k)) {
                // Admit-flip retraction: a key we once admitted that the list now refuses must
                // LEAVE working memory — otherwise a stale version keeps matching.
                if (known.remove(k) != null) {
                    rete.retract(FactId(board.id, k))
                    factsApplied++
                }
                continue
            }
            // get/getProvenance are separate reads; a remove between keys() and here shows as
            // a missing provenance and is picked up as a vanished key on the next pass.
            val provenance = blackboard.getProvenance(k)
            if (provenance == null) {
                live.remove(k)
                continue
            }
            val value = blackboard.get(k)
            val prior = known[k]
            if (prior != null && prior.stamp == provenance.timestamp && prior.value === value) continue
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
            known[k] = Known(provenance.timestamp, value, cid)
        }
        if (known.size > live.size || known.keys.any { it !in live }) {
            // Collected first: retract suspends, and known is this coroutine's to mutate.
            val vanished = known.keys.filter { it !in live }
            for (k in vanished) {
                known.remove(k)
                rete.retract(FactId(board.id, k))
                factsApplied++
            }
        }
    }

    override suspend fun close() {
        collector?.cancel()
        collector = null
        wake.close()
        super.close()
    }
}
