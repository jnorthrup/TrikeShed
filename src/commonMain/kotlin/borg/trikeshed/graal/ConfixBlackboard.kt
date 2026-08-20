package borg.trikeshed.graal

import borg.trikeshed.parse.confix.*
import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.Clock

/**
 * ConfixBlackboard — content-addressed blackboard backed by ConfixDoc.
 *
 * Provides a shared workspace for polyglot language communication with:
 * - Content-addressed storage (ConfixDoc as key)
 * - Reactor-form change stream via [changes] (SharedFlow<ConfixDoc>)
 * - Provenance tracking for each entry
 *
 * Reactor/lifecycle notes (CCEK-style):
 * - [state] is the single source of truth; every mutator ([put], [remove], [merge])
 *   emits the post-mutation ConfixDoc snapshot into [changes].
 * - Emission uses `tryEmit` against `extraBufferCapacity`, so mutators stay
 *   synchronous and never suspend; slow collectors may drop intermediate
 *   snapshots but always converge on a later (newer) [state].
 * - The blackboard owns no coroutine scope: collectors bring their own scope
 *   and simply stop collecting to detach — no close/shutdown is required.
 * - The legacy callback [subscribe] remains as a deprecated synchronous shim.
 */
class ConfixBlackboard {
    
    // Internal map for immediate access, synced to ConfixDoc on mutations
    private val store = mutableMapOf<String, Any?>()
    
    private var doc: ConfixDoc = emptyConfix()
    
    /** The current ConfixDoc — content-addressed state */
    val state: ConfixDoc get() = doc
    
    /** Provenance map: key -> source language + timestamp */
    private val provenance = mutableMapOf<String, ProvenanceEntry>()
    
    data class ProvenanceEntry(
        val language: String,
        val timestamp: Long,
        val sourceLocation: String? = null
    )
    
    /**
     * Reactor-form change stream. Each mutation emits the post-mutation [state].
     * `extraBufferCapacity` + `DROP_OLDEST` guarantee `tryEmit` always succeeds
     * without suspending in the synchronous mutators: under back-pressure the
     * *oldest* buffered snapshot is discarded, never the newest one.
     */
    private val _changes = MutableSharedFlow<ConfixDoc>(
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
        store[key] = value
        provenance[key] = ProvenanceEntry(language, Clock.System.now().toEpochMilliseconds())
        doc = doc.set(key, value)
        notifySubscribers()
        return this
    }
    
    /** Get value at key */
    fun get(key: String): Any? = store[key]
    
    /** Get provenance for key */
    fun getProvenance(key: String): ProvenanceEntry? = provenance[key]
    
    /** Delete key */
    fun remove(key: String): ConfixBlackboard {
        store.remove(key)
        provenance.remove(key)
        doc = doc.remove(key)
        notifySubscribers()
        return this
    }
    
    /** Check if key exists */
    fun has(key: String): Boolean = store.containsKey(key)
    
    /** Get all keys */
    fun keys(): List<String> = store.keys.toList()
    
    /** Merge another ConfixDoc into blackboard */
    fun merge(other: ConfixDoc, language: String): ConfixBlackboard {
        for (key in other.keys()) {
            put(key, other.get(key), language)
        }
        return this
    }
    
    /**
     * Subscribe to changes (legacy synchronous shim).
     *
     * Handlers are invoked synchronously inside each mutator, mirroring the
     * pre-reactor behavior, and receive the same snapshots emitted on [changes].
     */
    @Deprecated(
        message = "Collect the changes SharedFlow instead; this synchronous shim exists only for legacy callers.",
        replaceWith = ReplaceWith("changes"),
    )
    fun subscribe(handler: (ConfixDoc) -> Unit): () -> Unit {
        subscribers.add(handler)
        return { subscribers.remove(handler) }
    }

    private fun notifySubscribers() {
        _changes.tryEmit(doc)
        subscribers.forEach { it(doc) }
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
        append("{")
        append("\"$key\":")
        append(when (value) {
            null -> "null"
            is String -> "\"$value\""
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> "\"$value\""
        })
        append("}")
    }
    return confixDoc(json.encodeToByteArray(), Syntax.JSON)
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