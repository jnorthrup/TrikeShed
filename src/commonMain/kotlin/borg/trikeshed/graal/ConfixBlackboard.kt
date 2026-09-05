package borg.trikeshed.graal

import borg.trikeshed.parse.confix.*
import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.Clock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * ConfixBlackboard — content-addressed blackboard backed by ConfixDoc.
 *
 * Provides a shared workspace for polyglot language communication with:
 * - Content-addressed storage (ConfixDoc as key)
 * - Reactor-form change stream via [changes] (SharedFlow<ConfixDoc>)
 * - Provenance tracking for each entry
 *
 * Reactor/lifecycle notes (CCEK-style):
 * - Every mutator ([put], [remove], [merge]) emits the post-mutation [state]
 *   into [changes], so collectors observe exactly one snapshot per mutation.
 * - **Caveat: [state] is not a full view of the board.** The authoritative
 *   key/value store is the private map behind [get] / [keys] / [has]; `doc` is
 *   rebuilt by a single-key `ConfixDoc.set` and `ConfixDoc.remove` is a no-op,
 *   so an emitted snapshot carries only the most recently written key and never
 *   reflects a deletion. This predates the reactor form — collectors that need
 *   the whole board must read [get] / [keys] rather than parse the emitted doc.
 *   Making `doc` a faithful multi-key projection is follow-up work.
 * - Cost note: each [put] rebuilds and reparses a one-key JSON document inline on
 *   the caller's thread, so a bulk flush pays one parse per entry. Fixing that
 *   means making `doc` lazy or incremental, which is the same follow-up as above.
 * - Emission uses `tryEmit` with `DROP_OLDEST`, so it always succeeds and the
 *   mutators stay synchronous and non-suspending. A slow collector loses
 *   intermediate snapshots under back-pressure, but the values it does receive
 *   are always the newest ones, and `replay = 1` hands a late subscriber the
 *   current snapshot instead of nothing.
 * - Delta 2026-09-05 (fan-out): the store is copy-on-write behind one atomic
 *   cell. It used to be a bare mutable map ("confine writes to a single
 *   writer"), but the kanban module writes from its committed collector, its
 *   claim workers and its fan-out worker at once, and a `keys()` snapshot taken
 *   under a concurrent `put` tore (a null slot, a ConcurrentModificationException)
 *   — a parent's merge claim died on it. Every read now sees one immutable
 *   snapshot; every mutator publishes a new one with compare-and-set, so a
 *   concurrent put is never lost. A put copies the map (≈1k keys on the live
 *   daemon: tens of microseconds), the same order as the per-put doc rebuild.
 *   The emitted ConfixDoc values are immutable, so collectors are safe anywhere.
 * - The blackboard owns no coroutine scope: collectors bring their own scope
 *   and simply stop collecting to detach — no close/shutdown is required.
 * - The legacy callback [subscribe] remains as a deprecated synchronous shim.
 */
@OptIn(ExperimentalAtomicApi::class)
class ConfixBlackboard {

    /** One immutable snapshot of the board: the key/value map, its provenance, the last-written doc. */
    private class Cell(val store: Map<String, Any?>, val provenance: Map<String, ProvenanceEntry>, val doc: ConfixDoc)

    // Internal map for immediate access, synced to ConfixDoc on mutations —
    // published whole on every mutation (see the class note).
    private val cell = AtomicReference(Cell(emptyMap(), emptyMap(), emptyConfix()))

    private val store: Map<String, Any?> get() = cell.load().store
    private val provenance: Map<String, ProvenanceEntry> get() = cell.load().provenance
    private val doc: ConfixDoc get() = cell.load().doc

    /** The current ConfixDoc — content-addressed state */
    val state: ConfixDoc get() = doc

    /** Publish the snapshot [next] derives from the current one; retried until no writer raced us. */
    private inline fun mutate(next: (Cell) -> Cell): ConfixDoc {
        while (true) {
            val cur = cell.load()
            val new = next(cur)
            if (cell.compareAndSet(cur, new)) return new.doc
        }
    }

    data class ProvenanceEntry(
        val language: String,
        val timestamp: Long,
        val sourceLocation: String? = null
    )
    
    /**
     * Reactor-form change stream. Each mutation emits the post-mutation [state].
     *
     * `DROP_OLDEST` over a non-zero buffer makes `tryEmit` *always succeed*, which
     * is what lets the mutators stay plain non-suspending functions: under
     * back-pressure the **oldest** buffered snapshot is discarded, never the
     * newest, so a lagging collector still converges on current truth.
     * `replay = 1` closes the subscribe-after-mutate race — a collector that
     * attaches late is handed the latest snapshot rather than waiting for the
     * next write.
     */
    private val _changes = MutableSharedFlow<ConfixDoc>(
        replay = 1,
        extraBufferCapacity = CHANGE_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Hot stream of ConfixDoc snapshots, one per mutation. Collect to observe the blackboard. */
    val changes: SharedFlow<ConfixDoc> = _changes.asSharedFlow()

    /** Legacy synchronous shim — see [subscribe]. */
    private val subscribers = mutableListOf<(ConfixDoc) -> Unit>()
    
    // ─────────────────────────────────────────────────────────────────
    // Confix operations
    // ─────────────────────────────────────────────────────────────────
    
    /** Put a value at key */
    fun put(key: String, value: Any?, language: String): ConfixBlackboard {
        val entry = ProvenanceEntry(language, Clock.System.now().toEpochMilliseconds())
        val newDoc = mutate { cur -> Cell(cur.store + (key to value), cur.provenance + (key to entry), cur.doc.set(key, value)) }
        notifySubscribers(newDoc)
        return this
    }
    
    /** Get value at key */
    fun get(key: String): Any? = store[key]
    
    /** Get provenance for key */
    fun getProvenance(key: String): ProvenanceEntry? = provenance[key]
    
    /** Delete key */
    fun remove(key: String): ConfixBlackboard {
        val newDoc = mutate { cur -> Cell(cur.store - key, cur.provenance - key, cur.doc.remove(key)) }
        notifySubscribers(newDoc)
        return this
    }
    
    /** Check if key exists */
    fun has(key: String): Boolean = store.containsKey(key)
    
    /** Get all keys — a stable snapshot, never torn by a concurrent writer. */
    fun keys(): List<String> = store.keys.toList()
    
    /** Merge another ConfixDoc into blackboard */
    fun merge(other: ConfixDoc, language: String): ConfixBlackboard {
        for (key in other.keys()) {
            put(key, other.get(key), language)
        }
        return this
    }

    /** Merge another ConfixBlackboard into this blackboard */
    fun merge(other: ConfixBlackboard, language: String = "merge"): ConfixBlackboard {
        for (key in other.keys()) {
            put(key, other.get(key), language)
        }
        return this
    }

    /** Merge a BlackboardContext into this blackboard */
    fun merge(context: BlackboardContext, language: String = "context"): ConfixBlackboard {
        put("context.id", context.id, language)
        context.tags.forEach { (k, v) ->
            put("tag.$k", v, language)
        }
        context.columnOverlays.forEach { (colIdx, overlay) ->
            put("column.$colIdx.name", overlay.name, language)
            put("column.$colIdx.role", overlay.defaultRole.name, language)
            overlay.description?.let { put("column.$colIdx.description", it, language) }
        }
        context.provenance?.let { prov ->
            put("context.provenance.source", prov.source, language)
            put("context.provenance.timestamp", prov.timestamp, language)
            prov.creator?.let { put("context.provenance.creator", it, language) }
        }
        return this
    }
    
    /**
     * Subscribe to changes (legacy synchronous shim).
     *
     * Handlers are invoked synchronously inside each mutator, mirroring the
     * pre-reactor behavior, and receive the same snapshots emitted on [changes].
     */
    // No ReplaceWith: `changes` is a SharedFlow property, not a drop-in for a
    // (handler) -> unsubscribe call, so a mechanical quick-fix would not compile.
    @Deprecated(
        message = "Collect the changes SharedFlow instead; this synchronous shim exists only for legacy callers.",
    )
    fun subscribe(handler: (ConfixDoc) -> Unit): () -> Unit {
        subscribers.add(handler)
        return { subscribers.remove(handler) }
    }

    private fun notifySubscribers(doc: ConfixDoc) {
        _changes.tryEmit(doc)
        // Copy before dispatch: a legacy handler is allowed to unsubscribe itself
        // from inside the callback, which would otherwise mutate the list mid-iteration.
        if (subscribers.isNotEmpty()) subscribers.toList().forEach { it(doc) }
    }
    
    /** Get snapshot as Series<Pair> for cursor operations */
    fun toSeries(): Series<Pair<String, Any?>> {
        val pairs = keys().map { it to get(it) }
        return pairs.size j { pairs[it] }
    }
    
    // ─────────────────────────────────────────────────────────────────
    // Factory
    // ─────────────────────────────────────────────────────────────────
    
    companion object {
        /** Buffered snapshots per collector before the oldest is dropped. */
        const val CHANGE_BUFFER_CAPACITY: Int = 64

        fun empty(): ConfixBlackboard = ConfixBlackboard()
        
        fun fromMap(map: Map<String, Any?>, language: String = "init"): ConfixBlackboard {
            return ConfixBlackboard().apply {
                map.forEach { (k, v) -> put(k, v, language) }
            }
        }
    }
}

/**
 * ConfixDoc extension to work as a simple key-value store
 */
private fun emptyConfix(): ConfixDoc = confixDoc("{}".encodeToByteArray(), Syntax.JSON)

private fun ConfixDoc.set(key: String, value: Any?): ConfixDoc {
    val json = buildString {
        append('{')
        appendJsonString(key)
        append(':')
        when (value) {
            null -> append("null")
            is Number -> append(value.toString())
            is Boolean -> append(value.toString())
            is String -> appendJsonString(value)
            else -> appendJsonString(value.toString())
        }
        append('}')
    }
    return confixDoc(json.encodeToByteArray(), Syntax.JSON)
}

/**
 * Append [raw] as a quoted, RFC 8259 §7-escaped JSON string.
 *
 * Keys and string values reach this doc straight from caller input, so raw
 * interpolation would let a quote, backslash, or control character break out of
 * the literal and corrupt the document.
 */
private fun StringBuilder.appendJsonString(raw: String) {
    append('"')
    for (c in raw) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")
        else -> if (c < ' ') {
            append("\\u")
            append(c.code.toString(16).padStart(4, '0'))
        } else {
            append(c)
        }
    }
    append('"')
}

private fun ConfixDoc.get(key: String): Any? = this.value(key)

private fun ConfixDoc.remove(key: String): ConfixDoc = this

private fun ConfixDoc.has(key: String): Boolean = this.docAt(key) != null

private fun ConfixDoc.keys(): List<String> {
    val r = this.root
    if (r != null && r.tag == IOMemento.IoObject) {
        val keys = mutableListOf<String>()
        val ch = r.kids
        var i = 0
        while (i + 1 < ch.size) {
            val k = ch[i]
            if (k.tag == IOMemento.IoString) {
                val key = k.reify(this.src) as? String
                if (key != null) keys.add(key)
            }
            i += 2
        }
        return keys
    }
    return emptyList()
}