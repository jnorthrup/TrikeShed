package borg.trikeshed.graal

import borg.trikeshed.parse.confix.*
import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import kotlinx.datetime.Clock

/**
 * ConfixBlackboard — content-addressed blackboard backed by ConfixDoc.
 * 
 * Provides a shared workspace for polyglot language communication with:
 * - Content-addressed storage (ConfixDoc as key)
 * - Event subscription via CursorFacet pattern
 * - Provenance tracking for each entry
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
    
    /** Subscribe to blackboard changes */
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
    
    /** Subscribe to changes */
    fun subscribe(handler: (ConfixDoc) -> Unit): () -> Unit {
        subscribers.add(handler)
        return { subscribers.remove(handler) }
    }
    
    private fun notifySubscribers() {
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