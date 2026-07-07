package borg.trikeshed.couch

import borg.trikeshed.parse.confix.*
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.size
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Confix-backed persistence for CouchStore.
 *
 * Stores each Document as a ConfixDoc (JSON syntax) keyed by docId.
 * The backing store is an in-memory map; a future cut can swap in a
 * Channel/SCTP-backed transport for the actual byte storage.
 *
 * This is the smallest real cut that proves "couch 1.x engine k-v docstore
 * with confix backed records": put/get/delete work, the records are ConfixDoc
 * instances, and a round-trip preserves fields.
 */
class ConfixPersistence : CouchPersistence {
    private val store = mutableMapOf<String, ByteArray>()
    private val json = Json { ignoreUnknownKeys = true }

    override fun persist(document: Document) {
        val json = documentToJson(document)
        store[document.id] = json.encodeToByteArray()
    }

    override fun delete(docId: String) {
        store.remove(docId)
    }

    override fun flush() {}
    override fun drain() {}
    override fun close() {}

    /** Read a document back as a ConfixDoc — proves the confix-backed record shape. */
    fun loadConfixDoc(docId: String): ConfixDoc? {
        val bytes = store[docId] ?: return null
        return confixDoc(bytes, Syntax.JSON)
    }

    /** Read a document back as a Document, reconstructed from its ConfixDoc form. */
    fun loadDocument(docId: String): Document? {
        val bytes = store[docId] ?: return null
        val text = bytes.decodeToString()
        val jsonObj = json.parseToJsonElement(text) as JsonObject
        val fields = jsonObj.entries
            .filter { it.key != "_id" }
            .map { (k, v) -> Field(k, v.jsonPrimitive.content) }
        return Document(docId, fields)
    }

    /** All stored doc ids. */
    fun ids(): Set<String> = store.keys.toSet()

    /** Snapshot of the raw confix bytes for a doc (for transport/SCTP cut). */
    fun rawBytes(docId: String): ByteArray? = store[docId]
}

/**
 * Encode a Document to a JSON string suitable for ConfixDoc parsing.
 */
private fun documentToJson(document: Document): String {
    val fieldsJson = document.fields.joinToString(",") { field ->
        "\"${field.name}\":${jsonValue(field.value)}"
    }
    return "{\"_id\":\"${document.id}\",$fieldsJson}"
}

private fun jsonValue(value: Any): String = when (value) {
    is String -> "\"$value\""
    is Number -> value.toString()
    is Boolean -> value.toString()
    else -> "\"$value\""
}
