package borg.trikeshed.utils.rfxhttp

import borg.trikeshed.couch.ConfixDocStore
import borg.trikeshed.couch.ConfixDocStoreEntry
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.couch.KeyExpr
import borg.trikeshed.couch.MapFunction
import borg.trikeshed.couch.ReduceFunction
import borg.trikeshed.couch.ValueExpr
import borg.trikeshed.couch.ViewDefinition
import borg.trikeshed.couch.ViewServer
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.confix.reify
import borg.trikeshed.parse.json.JsonSupport

/**
 * Reactor Couch RequestFactory — commonMain. Implements [RequestFactoryHandler].
 *
 * Envelope (RequestFactory's batched-operations shape, JSON/Confix body):
 *   { "operations": [ { "op": "put"|"get"|"delete"|"list"|"query", "id"?, "rev"?, "doc"?, "prefix"?, "view"? }, … ] }
 * A payload without "operations" is one implicit put of the whole payload (the legacy adapter's behaviour).
 *
 * Receipts are CouchTx-shaped per operation: { ok, id, rev, error?, reason? }; errors are per-op, never per-batch.
 * "query" adds rows and proofCid (the MapReduceProofReceipt content id from ViewServer.executeWithProof).
 * Ids default to the content hash of the document — idempotent puts, no clock, no platform call.
 *
 * view spec: { "ddoc"?: "_design/x", "name": "v", "key"?: "field" | {"field"|"path"|"const"} (default doc id),
 *              "value"?: "doc" | "field" | {"field"|"path"|"const"} (default 1),
 *              "reduce"?: "_count"|"_sum"|"_stats" | {"dsl": "<confix reducer>"}, "prefix"?: "<id prefix>" }
 */
class CouchRequestFactory(
    val store: ConfixDocStore,
    val viewServer: ViewServer = ViewServer(),
) : RequestFactoryHandler {

    override suspend fun processRequest(payload: String): String = JsonSupport.stringify(process(payload))

    fun process(payload: String): Map<String, Any?> {
        if (payload.isBlank()) return failure(null, "empty", "blank payload")
        val root = try {
            JsonSupport.parse(payload)
        } catch (e: Throwable) {
            return failure(null, "parse", e.message ?: "unparseable payload")
        }
        val ops = (root as? Map<*, *>)?.get("operations") as? List<*>
            ?: return mapOf("ok" to true, "receipts" to listOf(put(null, null, payload)))
        val receipts = ops.map { op ->
            (op as? Map<*, *>)?.let(::dispatch) ?: failure(null, "op", "operation is not an object")
        }
        return mapOf("ok" to receipts.all { it["ok"] == true }, "receipts" to receipts)
    }

    private fun dispatch(op: Map<*, *>): Map<String, Any?> {
        val id = op["id"] as? String
        val rev = op["rev"] as? String
        val verb = op["op"] as? String ?: return failure(id, "op", "op required")
        return try {
            when (verb) {
                "put" -> {
                    val doc = op["doc"] ?: return failure(id, "put", "doc required")
                    put(id, rev, JsonSupport.stringify(doc))
                }
                "get" -> get(id ?: return failure(null, "get", "id required"))
                "delete" -> delete(
                    id ?: return failure(null, "delete", "id required"),
                    rev ?: return failure(id, "delete", "rev required"),
                )
                "list" -> list(op["prefix"] as? String ?: "")
                "query" -> query(op["view"] as? Map<*, *> ?: return failure(null, "query", "view required"))
                else -> failure(id, verb, "unknown op")
            }
        } catch (e: Throwable) {
            failure(id, verb, e.message ?: (e::class.simpleName ?: "error"))
        }
    }

    private fun put(id: String?, rev: String?, docJson: String): Map<String, Any?> {
        val docId = id ?: ContentId.of(docJson.encodeToByteArray()).hex
        val entry = store.put(docId, docJson, rev)
            ?: return failure(docId, "conflict", "rev mismatch; current rev is ${store[docId]?.rev}")
        return ok(entry)
    }

    private fun get(id: String): Map<String, Any?> {
        val entry = store[id] ?: return failure(id, "not_found", "no such document")
        return ok(entry) + ("doc" to entry.doc.reify(ROOT_TOKEN))
    }

    private fun delete(id: String, rev: String): Map<String, Any?> =
        if (store.delete(id, rev)) mapOf("ok" to true, "id" to id, "rev" to rev)
        else failure(id, if (store.contains(id)) "conflict" else "not_found", "delete refused")

    private fun list(prefix: String): Map<String, Any?> {
        val entries = store.byIdPrefix(prefix)
        return mapOf(
            "ok" to true,
            "rows" to List(entries.size) { i -> mapOf("id" to entries[i].id, "rev" to entries[i].rev) },
        )
    }

    private fun query(view: Map<*, *>): Map<String, Any?> {
        val name = view["name"] as? String ?: return failure(null, "query", "view.name required")
        val def = ViewDefinition(
            ddoc = view["ddoc"] as? String ?: "_design/rf",
            viewName = name,
            mapFn = MapFunction.Emit(keyExpr(view["key"]), valueExpr(view["value"])),
            reduceFn = reduceFn(view["reduce"]),
        )
        val proof = viewServer.executeWithProof(def, documents(view["prefix"] as? String ?: ""))
        val result = proof.result
        return mapOf(
            "ok" to true,
            "view" to def.fullName,
            "rows" to List(result.size) { i ->
                val row = result[i]
                mapOf("key" to row.key, "value" to row.value, "id" to row.docId)
            },
            "proofCid" to proof.receipt.contentId.hex,
        )
    }

    private fun documents(prefix: String): List<Document> {
        val entries = store.byIdPrefix(prefix)
        return List(entries.size) { i -> entries[i].toDocument() }
    }

    private fun ConfixDocStoreEntry.toDocument(): Document {
        val body = doc.reify(ROOT_TOKEN) as? Map<*, *> ?: emptyMap<Any?, Any?>()
        return Document(
            id,
            body.entries
                .filter { it.key != "_id" }
                .map { Field(it.key.toString(), it.value ?: "null") },
        )
    }

    private fun keyExpr(spec: Any?): KeyExpr = when (spec) {
        null -> KeyExpr.DocId
        is String -> KeyExpr.DocField(spec)
        is Map<*, *> -> when {
            spec["path"] is String -> KeyExpr.JsPathExpr(spec["path"] as String)
            spec["field"] is String -> KeyExpr.DocField(spec["field"] as String)
            spec.containsKey("const") -> KeyExpr.Const(spec["const"])
            else -> KeyExpr.DocId
        }
        else -> KeyExpr.Const(spec)
    }

    private fun valueExpr(spec: Any?): ValueExpr = when (spec) {
        null -> ValueExpr.Const(1)
        "doc" -> ValueExpr.DocValue
        is String -> ValueExpr.DocField(spec)
        is Map<*, *> -> when {
            spec["path"] is String -> ValueExpr.JsPathExpr(spec["path"] as String)
            spec["field"] is String -> ValueExpr.DocField(spec["field"] as String)
            spec.containsKey("const") -> ValueExpr.Const(spec["const"])
            else -> ValueExpr.DocValue
        }
        else -> ValueExpr.Const(spec)
    }

    private fun reduceFn(spec: Any?): ReduceFunction? = when (spec) {
        null -> null
        is String -> ReduceFunction.Builtin(spec)
        is Map<*, *> -> (spec["dsl"] as? String)?.let(ReduceFunction::Custom)
        else -> null
    }

    private fun ok(entry: ConfixDocStoreEntry): Map<String, Any?> =
        mapOf("ok" to true, "id" to entry.id, "rev" to entry.rev)

    private fun failure(id: String?, error: String, reason: String): Map<String, Any?> =
        mapOf("ok" to false, "id" to id, "error" to error, "reason" to reason)

    private companion object {
        /** Token index of a ConfixDoc's root value (`ConfixKit.kt:203`, TreeCursor[0]). */
        const val ROOT_TOKEN = 0
    }
}
