package borg.trikeshed.couch

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.job.ContentId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * CouchChangesFactElement — the tendon between the store and the production system.
 *
 * Every committed frame of [db] (`_changes`) becomes, in order:
 *   1. a [CouchReportEvent.Committed] on the report bus ([CouchReportReactorElement]), so anything
 *      blackboard-wired sees document events the same way it sees map/reduce and pointcut events;
 *   2. a Rete fact: `assert` on first sight, `modify` on a later revision, `retract` on delete.
 *      Fact identity is `(db name, doc id)`; the version is the revision's blob CID, so a fact's
 *      version and the bytes it was derived from are the same address.
 *
 * Replication lands frames through the same commit boundary, so a peer's documents fire rules
 * here exactly as local writes do — the network is a fact source, not a special case.
 *
 * CCEK element: subscribes on [open], drains its channel on the element supervisor, unsubscribes
 * on [close]. No clock, no sockets.
 */
class CouchChangesFactElement(
    private val db: CouchDatabase,
    private val rete: ReteNetwork,
    private val report: CouchReportReactorElement? = null,
    private val board: BlackboardContext = BlackboardContext(id = db.name),
    /** Only document ids accepted here become facts (default: everything but `_design/`). */
    private val admit: (CouchCommittedFrame) -> Boolean = { !it.docId.startsWith("_design/") },
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<CouchChangesFactElement>()

    override val key: CoroutineContext.Key<*> get() = Key

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var cancelSubscription: (() -> Unit)? = null
    private var nextSeq: Long = 0L
    private val known = HashSet<String>()

    /** Frames turned into facts so far. */
    var factsApplied: Long = 0L
        private set

    override suspend fun open() {
        if (state != ElementState.CREATED) return
        super.open()
        cancelSubscription = db.store.changes.subscribe { wake.trySend(Unit) }
        CoroutineScope(supervisor + Dispatchers.Default).launch {
            try {
                drainFrames()
                for (unit in wake) drainFrames()
            } catch (_: kotlinx.coroutines.CancellationException) {
            }
        }
        state = ElementState.ACTIVE
    }

    /** Pull every frame since the last one seen and apply it. Safe to call repeatedly. */
    suspend fun drainFrames() {
        val frames = db.framesSince(nextSeq)
        for (f in frames) {
            apply(f)
            nextSeq = f.sequence + 1
        }
    }

    private suspend fun apply(f: CouchCommittedFrame) {
        report?.ingest(CouchReportEvent.Committed(f.docId, f.rev, f.sequence + 1, f.deleted))
        if (!admit(f)) {
            // Admit-flip retraction: a doc we once admitted whose new revision the filter
            // refuses must LEAVE working memory — otherwise a stale version keeps matching
            // (retraction correctness, the industry's #1 sinkhole).
            if (known.remove(f.docId)) rete.retract(FactId(db.name, f.docId))
            return
        }
        val factId = FactId(db.name, f.docId)
        val version = CouchDatabase.revToCid(f.rev) ?: ContentId.of(f.rev.encodeToByteArray())
        if (f.deleted) {
            if (known.remove(f.docId)) rete.retract(factId)
            return
        }
        val fields = linkedMapOf<String, Any?>("_id" to f.docId, "_rev" to f.rev, "_seq" to f.sequence + 1)
        f.doc?.fields?.forEach { fields[it.name] = it.value }
        if (known.add(f.docId)) rete.assert(factId, fields, version, board) else rete.modify(factId, fields, version)
        factsApplied++
    }

    override suspend fun close() {
        cancelSubscription?.invoke()
        cancelSubscription = null
        wake.close()
        super.close()
    }
}
