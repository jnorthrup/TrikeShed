package borg.trikeshed.couch

import borg.trikeshed.collections.mutableSeriesOf
import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.cascade.Count
import borg.trikeshed.lib.cascade.Trie
import borg.trikeshed.lib.cascade.cascadeWorthCaching
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * P2 incremental view tendon: changed documents are mapped exactly once, their prior
 * emissions are replaced/retracted, bounded reductions are regenerated from cached
 * emissions, and the last external sequence is checkpointed under
 * `_local/view/<ddoc>/<view>` (invisible to `_changes`).
 *
 * View evaluation is still [ViewServer] — this class owns scheduling/cache/checkpoint,
 * not a second expression evaluator. Its Trie is the Cascade cached-prefix structure;
 * one synthetic leaf per emitted row keeps prefix counts rereduce-safe. The cache-worth
 * decision is logged on every rebuild and never silently overridden.
 */
class IncrementalViewElement(
    private val db: CouchDatabase,
    val definition: ViewDefinition,
    private val log: (String) -> Unit = {},
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<IncrementalViewElement>()
    override val key: CoroutineContext.Key<*> get() = Key

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var cancelSubscription: (() -> Unit)? = null
    private val mappedRows = linkedMapOf<String, List<ViewRow>>()
    private val server = ViewServer()
    private var trie = Trie<Char, Int>(Count)
    private var _answer = ViewResult()

    val checkpointId: String = "view/${definition.ddoc.removePrefix("_design/")}/${definition.viewName}"
    private var nextSeq: Long = 0L
    var framesDrained: Long = 0L
        private set
    var documentsMapped: Long = 0L
        private set
    var cacheDecision: Boolean = false
        private set

    init {
        restoreCheckpoint()
        if (mappedRows.isNotEmpty()) rebuildAnswer()
    }

    fun answer(): ViewResult = _answer

    /** Cached emitted-row count beneath a string prefix. */
    fun cachedPrefixCount(prefix: String): Int = trie[prefix.length j { i: Int -> prefix[i] }]

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

    /** Pull only frames after the persisted checkpoint. Safe and idempotent. */
    suspend fun drainFrames() {
        val frames = db.framesSince(nextSeq)
        if (frames.isEmpty()) return
        for (frame in frames) {
            framesDrained++
            if (!frame.docId.startsWith("_design/")) apply(frame)
            nextSeq = frame.sequence + 1
        }
        rebuildAnswer()
        db.localPut(checkpointId, mapOf(
            "last_seq" to nextSeq,
            // Persist the mapped-emission cache beside the sequence. Restart therefore
            // drains ONLY new frames and still answers over the full corpus — a sequence
            // checkpoint without reducer state would return only post-restart documents.
            "mapped" to mappedRows.mapValues { (_, rows) -> rows.map { row ->
                mapOf("key" to row.key, "value" to row.value, "docId" to row.docId, "jsPath" to row.jsPath)
            } },
        ))
    }

    private fun apply(frame: CouchCommittedFrame) {
        if (frame.deleted || frame.doc == null) {
            mappedRows.remove(frame.docId)
            return
        }
        val mapOnly = definition.copy(reduceFn = null)
        val result = server.execute(mapOnly, listOf(frame.doc))
        val rows = ArrayList<ViewRow>(result.size)
        for (row in result.rows) rows.add(row)
        mappedRows[frame.docId] = rows
        documentsMapped++
    }

    private fun rebuildAnswer() {
        val all = mutableSeriesOf<ViewRow>()
        var rowCount = 0
        for ((_, rows) in mappedRows) {
            for (row in rows) { all.append(row); rowCount++ }
        }
        val raw = ViewResult(all)
        _answer = when (val r = definition.reduceFn) {
            is ReduceFunction.Builtin -> raw.reduce(r.name)
            null -> raw
            is ReduceFunction.Custom -> error("incremental views accept bounded LCNC built-ins only")
        }

        // Rebuild the prefix cache from the already-mapped rows. Each synthetic key is
        // unique by key/doc/index, while its leading chars preserve the view-key prefix.
        trie = Trie(Count)
        var ordinal = 0
        for (row in all) {
            val key = "${row.key}\u0000${row.docId}\u0000${ordinal++}"
            trie.put(key.length j { i: Int -> key[i] }, 1)
        }
        val depth = 4
        val fanout = 16
        cacheDecision = cascadeWorthCaching(rowCount, depth, fanout, queriesPerWrite = 8.0, groups = _answer.size)
        log("incremental-view ${definition.fullName}: cache=$cacheDecision rows=$rowCount depth=$depth fanout=$fanout groups=${_answer.size}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun restoreCheckpoint() {
        mappedRows.clear()
        val cp = db.localGet(checkpointId)
        val value = cp?.get("last_seq")
        nextSeq = when (value) {
            is Number -> value.toLong()
            is String -> value.toDoubleOrNull()?.toLong() ?: 0L
            else -> 0L
        }
        val mapped = cp?.get("mapped") as? Map<*, *> ?: return
        for ((docIdAny, rowsAny) in mapped) {
            val docId = docIdAny?.toString() ?: continue
            val encoded = rowsAny as? List<*> ?: continue
            val rows = ArrayList<ViewRow>(encoded.size)
            for (item in encoded) {
                val m = item as? Map<*, *> ?: continue
                rows.add(ViewRow(m["key"], m["value"], m["docId"]?.toString() ?: docId, m["jsPath"]?.toString() ?: ""))
            }
            mappedRows[docId] = rows
        }
    }

    private fun checkpointSequence(): Long {
        val id = "view/${definition.ddoc.removePrefix("_design/")}/${definition.viewName}"
        val value = db.localGet(id)?.get("last_seq")
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toDoubleOrNull()?.toLong() ?: 0L
            else -> 0L
        }
    }

    override suspend fun close() {
        cancelSubscription?.invoke()
        cancelSubscription = null
        wake.close()
        super.close()
    }
}
