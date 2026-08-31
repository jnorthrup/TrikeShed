@file:OptIn(ExperimentalEncodingApi::class)

package borg.trikeshed.relaxfactory

import borg.trikeshed.couch.ConfixDocStore
import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.couch.MapFunction
import borg.trikeshed.couch.ProjectPath
import borg.trikeshed.couch.ViewDefinition
import borg.trikeshed.couch.ViewServer
import borg.trikeshed.couch.replicate.CouchReplicator
import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.json.JsonSupport
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Reactor Couch RequestFactory — commonMain. Implements [RequestFactoryHandler].
 *
 * A request is a factory-produced typed unit that resolves to a report over the store. The unit is
 * batched, so a caller states everything it wants in one exchange and gets one receipt per item:
 *
 *   { "operations": [ { "op": "put"|"get"|"delete"|"list"|"query"|…, … }, … ] }
 *
 * A payload without "operations" is one implicit put of the whole payload (the legacy adapter's
 * behaviour). Receipts are CouchTx-shaped per operation: `{ ok, id, rev, error?, reason? }`; errors
 * are per-op, never per-batch, so a bad operation costs its own receipt and nothing else.
 *
 * The store is an [RelaxStore], not a store of the factory's own. Bound to a [CouchDatabase] (the
 * canonical binding, [forDatabase]) every operation here is the batched face of a route the wire
 * already serves: a `put` mints the same `gen-sha256:<hex>` revision, appends the same committed
 * frame, and lands the same CAS blob as `PUT /{db}/{id}` — which is what makes a document written
 * through the envelope replicate. Bound to a [ConfixDocStore] ([forConfixStore], what
 * [CouchHttpSurface] mounts) there is no changes log, and the lane operations answer
 * `not_implemented` rather than silently doing something else.
 *
 * ## Document operations
 * `put` (id?, rev?, doc) · `get` (id) · `delete` (id, rev) · `list` (prefix?) · `query` (view) ·
 * `view` (ddoc, name, params?).
 * Ids default to the content hash of the document — idempotent puts, no clock, no platform call.
 *
 * `query` carries a view definition inline; `view` names one the design docs already declare and
 * runs the `_view` route's own body, so a report is addressable as `_design/<ddoc>/_view/<name>`
 * from inside a batch exactly as it is over HTTP.
 *
 * ## Lane operations — replication and CAS, for [RequestFactoryProxy] clients
 * `all_docs` (startkey?, endkey?, limit?, skip?, descending?, include_docs?, keys?) ·
 * `changes` (since?, limit?, include_docs?) · `revs_diff` (revs: {id:[rev…]}) ·
 * `bulk_docs` (docs:[…], new_edits?) · `local_get`/`local_put` (id, doc?) ·
 * `block_get` (cid) → base64 · `block_put` (data: base64) → {cid} ·
 * `replicate` (direction: "pull"|"push", peer, since?).
 *
 * ## Project operations — the heading a document hangs under
 * `project_put` (id, doc?) · `project_get` (id) · `project_list` ·
 * `project_docs` (id, under?, include_docs?). See [borg.trikeshed.couch.ProjectPath] for the
 * grammar and [borg.trikeshed.couch.Projects] for what a heading knows about itself.
 *
 * The block operations are the same CAS the `_cas/{cid}` route and the IPFS `/api/v0/block/…`
 * aliases serve — base64 because the envelope is JSON; a bulk transfer should still use the binary
 * `_cas/_bulk` lane, which is why the replicator does.
 *
 * view spec: { "ddoc"?: "_design/x", "name": "v", "key"?: "field" | {"field"|"path"|"const"} (default doc id),
 *              "value"?: "doc" | "field" | {"field"|"path"|"const"} (default 1),
 *              "reduce"?: "_count"|"_sum"|"_stats"|"rollup-count"|"_cascade"
 *                       | {"cascade": true | {"metrics": [...]}} | {"dsl": "<confix reducer>"},
 *              "prefix"?: "<id prefix>",
 *              plus the 1.6.2 view params via [ViewQuery.fromEnvelope]: startkey, endkey, inclusive_end,
 *              descending, skip, limit, group, group_level, include_docs; exact key under "params": {"key": …} }
 * query receipt: { ok, view, rows:[{key,value,id[,doc]}], total_rows, offset, proofCid }
 */
class CouchRequestFactory(
    val store: RelaxStore,
    val viewServer: ViewServer = ViewServer(),
    val rpcTargets: Map<String, RequestFactoryRpcTarget> = emptyMap(),
) : RequestFactoryHandler {

    /** The store's replication/CAS lanes, when it has them. */
    private val lanes: RelaxLanes? get() = store.lanes

    override suspend fun processRequest(payload: String): String = JsonSupport.stringify(process(payload))

    suspend fun process(payload: String): Map<String, Any?> {
        if (payload.isBlank()) return failure(null, "empty", "blank payload")
        val root = try {
            JsonSupport.parse(payload)
        } catch (e: Throwable) {
            return failure(null, "parse", e.message ?: "unparseable payload")
        }
        val ops = CouchDatabase.asList((root as? Map<*, *>)?.get("operations"))
            ?: return mapOf("ok" to true, "receipts" to listOf(put(null, null, payload)))
        val receipts = ops.map { op ->
            (op as? Map<*, *>)?.let { dispatch(it) } ?: failure(null, "op", "operation is not an object")
        }
        return mapOf("ok" to receipts.all { it["ok"] == true }, "receipts" to receipts)
    }

    private suspend fun dispatch(op: Map<*, *>): Map<String, Any?> {
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
                "view" -> storedView(op)
                "all_docs" -> allDocs(op)
                "changes" -> changes(op)
                "revs_diff" -> revsDiff(op)
                "bulk_docs" -> bulkDocs(op)
                "local_get" -> localGet(id ?: return failure(null, verb, "id required"))
                "local_put" -> localPut(id ?: return failure(null, verb, "id required"), op["doc"])
                "block_get" -> blockGet(op["cid"] as? String ?: return failure(null, verb, "cid required"))
                "block_put" -> blockPut(op["data"] as? String ?: return failure(null, verb, "data required (base64)"))
                "replicate" -> replicate(op)
                "rpc" -> rpc(op)
                "project_put" -> projectPut(op)
                "project_get" -> projectGet(id ?: return failure(null, verb, "id required"))
                "project_list" -> projectList()
                "project_docs" -> projectDocs(op, id ?: return failure(null, verb, "id required"))
                else -> failure(id, verb, "unknown op")
            }
        } catch (e: Throwable) {
            failure(id, verb, e.message ?: (e::class.simpleName ?: "error"))
        }
    }

    // ── documents ─────────────────────────────────────────────────

    private fun put(id: String?, rev: String?, docJson: String): Map<String, Any?> {
        val docId = id ?: ContentId.of(docJson.encodeToByteArray()).hex
        return receipt(docId, store.put(docId, docJson, rev))
    }

    private fun get(id: String): Map<String, Any?> {
        val doc = store.get(id) ?: return failure(id, "not_found", "no such document")
        return mapOf("ok" to true, "id" to id, "rev" to doc["_rev"], "doc" to doc)
    }

    private fun delete(id: String, rev: String): Map<String, Any?> = receipt(id, store.delete(id, rev))

    private fun list(prefix: String): Map<String, Any?> = mapOf(
        "ok" to true,
        "rows" to store.ids(prefix).map { (id, rev) -> mapOf("id" to id, "rev" to rev) },
    )

    /**
     * `view` — a report addressed the way the store names it: `_design/<ddoc>/_view/<name>`.
     *
     * `query` carries its view definition inline, which is right for an ad-hoc report but meant a
     * batch could not ask for a view the design docs already declare — the one thing the `_view`
     * route could do that the envelope could not. This runs the route's own [ViewRoute] body, so
     * the two answers are the same answer.
     */
    private fun storedView(op: Map<*, *>): Map<String, Any?> {
        val ddoc = (op["ddoc"] as? String)?.let { if (it.startsWith("_design/")) it else "_design/$it" }
            ?: return failure(null, "view", "ddoc required")
        val name = op["name"] as? String ?: return failure(null, "view", "name required")
        val params = op["params"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val reply = viewRoute.handle(ddoc, name, ViewQuery.fromEnvelope(params))
        if (reply.status != 200) {
            return failure(ddoc, reply.json["error"] as? String ?: "error", reply.json["reason"] as? String ?: "view refused")
        }
        return mapOf("ok" to true, "view" to "$ddoc/$name") + reply.json
    }

    /** The stored-view engine, over this factory's own store and observed ViewServer. */
    private val viewRoute: ViewRoute by lazy { ViewRoute(store.viewDocs, viewServer) }

    // ── query: an inline view definition, with its proof ──────────

    private fun query(view: Map<*, *>): Map<String, Any?> {
        val name = view["name"] as? String ?: return failure(null, "query", "view.name required")
        val reduce = reduceFn(view["reduce"])
        val q = ViewQuery.fromEnvelope(view)
        val wantReduce = q.wantReduce(reduce != null)
        if (wantReduce && reduce == null) return failure(null, "query_parse_error", "Reduce is invalid for map-only views.")
        val def = ViewDefinition(
            ddoc = view["ddoc"] as? String ?: "_design/rf",
            viewName = name,
            mapFn = MapFunction.Emit(keyExpr(view["key"]), valueExpr(view["value"])),
            reduceFn = if (wantReduce) reduce else null,
        )
        val docs = documents(view["prefix"] as? String ?: "")
        val proof = viewServer.executeWithProof(def, docs)
        val mapped = if (wantReduce) viewServer.execute(def.copy(reduceFn = null), docs) else proof.result
        val selected = q.select(mapped)
        val rows: List<Map<String, Any?>> = if (!wantReduce) {
            q.page(selected).map { row ->
                // emit(key, doc) yields the object, as it does on the `_view` route.
                val value = if (view["value"] == "doc") store.get(row.docId) else row.value
                val base = mapOf("key" to row.key, "value" to value, "id" to row.docId)
                if (q.include_docs) base + ("doc" to store.get(row.docId)) else base
            }
        } else {
            val byId = docs.associateBy { it.id }
            val reduced = viewServer.execute(def, selected.map { it.docId }.distinct().mapNotNull(byId::get))
            if (q.grouped)
                List(reduced.size) { i -> val row = reduced[i]; mapOf("key" to row.key, "value" to row.value, "id" to row.docId) }
            else listOf(mapOf("key" to null, "value" to ViewQuery.rereduce(reduce!!, reduced), "id" to null))
        }
        return mapOf(
            "ok" to true,
            "view" to def.fullName,
            "rows" to rows,
            "total_rows" to mapped.size,
            "offset" to q.offset(selected),
            "proofCid" to proof.receipt.contentId.hex,
        )
    }

    /**
     * The documents a query maps over: the same projection the `_view` route uses (live, non-design),
     * narrowed to [prefix]. Sourcing both from [ViewDocs] is what lets the envelope and the route
     * return the same rows for the same view.
     */
    private fun documents(prefix: String): List<Document> =
        store.viewDocs.all()
            .filter { (id, _) -> id.startsWith(prefix) }
            .map { (id, body) ->
                Document(
                    id,
                    body.entries
                        .filter { it.key != "_id" && it.key != "_rev" }
                        .map { Field(it.key, it.value ?: "null") },
                )
            }

    // ── lanes: replication and CAS ────────────────────────────────

    private suspend fun changes(op: Map<*, *>): Map<String, Any?> {
        val l = lanes ?: return noLane("changes")
        val r = l.changes(
            since = num(op["since"]) ?: 0L,
            limit = num(op["limit"])?.toInt() ?: Int.MAX_VALUE,
            includeDocs = op["include_docs"] == true,
        )
        return mapOf("ok" to true) + r
    }

    private fun allDocs(op: Map<*, *>): Map<String, Any?> {
        val l = lanes ?: return noLane("all_docs")
        val r = l.allDocs(
            startkey = op["startkey"] as? String,
            endkey = op["endkey"] as? String,
            limit = num(op["limit"])?.toInt() ?: Int.MAX_VALUE,
            skip = num(op["skip"])?.toInt() ?: 0,
            descending = op["descending"] == true,
            includeDocs = op["include_docs"] == true,
            keys = CouchDatabase.asList(op["keys"])?.map { it.toString() },
        )
        return mapOf("ok" to true) + r
    }

    private fun revsDiff(op: Map<*, *>): Map<String, Any?> {
        val l = lanes ?: return noLane("revs_diff")
        val offered = op["revs"] as? Map<*, *> ?: return failure(null, "revs_diff", "revs required")
        val asked = offered.entries.associate { (k, v) ->
            k.toString() to (CouchDatabase.asList(v)?.map { it.toString() } ?: emptyList())
        }
        return mapOf("ok" to true, "diff" to l.revsDiff(asked))
    }

    private fun bulkDocs(op: Map<*, *>): Map<String, Any?> {
        val l = lanes ?: return noLane("bulk_docs")
        @Suppress("UNCHECKED_CAST")
        val docs = CouchDatabase.asList(op["docs"])?.mapNotNull { it as? Map<String, Any?> }
            ?: return failure(null, "bulk_docs", "docs required")
        val results = l.bulkDocs(docs, newEdits = op["new_edits"] != false)
        return mapOf("ok" to results.all { it["ok"] == true }, "results" to results)
    }

    private fun localGet(id: String): Map<String, Any?> {
        val l = lanes ?: return noLane("local_get")
        val doc = l.localGet(id) ?: return failure(id, "not_found", "no such checkpoint")
        return mapOf("ok" to true, "id" to id, "doc" to doc)
    }

    private fun localPut(id: String, doc: Any?): Map<String, Any?> {
        val l = lanes ?: return noLane("local_put")
        @Suppress("UNCHECKED_CAST")
        val body = doc as? Map<String, Any?> ?: return failure(id, "local_put", "doc required")
        return mapOf("ok" to true) + l.localPut(id, body)
    }

    private fun blockGet(cid: String): Map<String, Any?> {
        val l = lanes ?: return noLane("block_get")
        val bytes = l.blockGet(cid) ?: return failure(cid, "not_found", "no such block")
        return mapOf("ok" to true, "cid" to cid, "size" to bytes.size, "data" to Base64.encode(bytes))
    }

    private fun blockPut(data: String): Map<String, Any?> {
        val l = lanes ?: return noLane("block_put")
        val bytes = runCatching { Base64.decode(data) }.getOrNull()
            ?: return failure(null, "block_put", "data is not base64")
        return mapOf("ok" to true, "cid" to l.blockPut(bytes), "size" to bytes.size)
    }

    private suspend fun replicate(op: Map<*, *>): Map<String, Any?> {
        val l = lanes ?: return noLane("replicate")
        val direction = op["direction"] as? String ?: return failure(null, "replicate", "direction required (pull|push)")
        if (direction != "pull" && direction != "push") return failure(null, "replicate", "direction must be pull or push")
        val peer = op["peer"] as? String ?: return failure(null, "replicate", "peer required")
        val report = l.replicate(direction, peer, num(op["since"]))
            ?: return failure(null, "not_implemented", "no replicator bound to this mounting")
        return mapOf("ok" to true) + report
    }

    // ── Kotlin targets: host-owned RPC through the same proxy lane ───────

    private suspend fun rpc(op: Map<*, *>): Map<String, Any?> {
        val target = op["target"] as? String ?: return failure(null, "rpc", "target required")
        val args = op["args"]?.let { raw ->
            raw as? Map<*, *> ?: return failure(null, "rpc", "args must be an object") + ("target" to target)
        }?.entries?.associate { (k, v) -> k.toString() to v }.orEmpty()
        val fn = rpcTargets[target]
            ?: return failure(null, "no_such_target", "no Kotlin RPC target '$target'") + ("target" to target)
        return mapOf("ok" to true, "target" to target, "result" to fn.call(args))
    }

    // ── projects: the heading a document hangs under ──────────────

    private fun projectPut(op: Map<*, *>): Map<String, Any?> {
        val l = lanes ?: return noLane("project_put")
        val id = op["id"] as? String ?: return failure(null, "project_put", "id required")
        @Suppress("UNCHECKED_CAST")
        val fields = op["doc"] as? Map<String, Any?> ?: emptyMap()
        return receipt(ProjectPath.Manifest(id).id, l.projects.put(id, fields))
    }

    private fun projectGet(id: String): Map<String, Any?> {
        val l = lanes ?: return noLane("project_get")
        // A namespace in use but never declared is a real answer, not a 404: the worktree gateway
        // mints `projects/<repo>/…` without anyone declaring the heading.
        val summary = l.projects.summary(id)
        if (summary["declared"] != true && (summary["doc_count"] as? Int ?: 0) == 0) {
            return failure(id, "not_found", "no such project")
        }
        return mapOf("ok" to true, "id" to id) + summary
    }

    private fun projectList(): Map<String, Any?> {
        val l = lanes ?: return noLane("project_list")
        return mapOf("ok" to true, "rows" to l.projects.summaries())
    }

    private fun projectDocs(op: Map<*, *>, id: String): Map<String, Any?> {
        val l = lanes ?: return noLane("project_docs")
        val under = op["under"] as? String ?: ""
        val ids = l.projects.documents(id, under)
        val includeDocs = op["include_docs"] == true
        return mapOf(
            "ok" to true, "id" to id, "total_rows" to ids.size,
            "rows" to ids.map { docId ->
                val kind = ProjectPath.of(docId)
                val row = linkedMapOf<String, Any?>(
                    "id" to docId,
                    "path" to (kind as? ProjectPath.Content)?.path,
                    "kind" to when (kind) {
                        is ProjectPath.Manifest -> "manifest"
                        is ProjectPath.Design -> "design"
                        is ProjectPath.Local -> "local"
                        is ProjectPath.Content -> "content"
                        null -> "unknown"
                    },
                )
                if (includeDocs) row["doc"] = store.get(docId)
                row
            },
        )
    }

    // ── receipts ──────────────────────────────────────────────────

    /** A store reply (`{ok,id,rev}` or `{error,reason}`) as a receipt; errors keep `ok:false`. */
    private fun receipt(id: String, reply: Map<String, Any?>): Map<String, Any?> =
        if (reply["ok"] == true) mapOf("ok" to true, "id" to (reply["id"] ?: id), "rev" to reply["rev"])
        else failure(id, reply["error"] as? String ?: "error", reply["reason"] as? String ?: "refused")

    private fun noLane(op: String): Map<String, Any?> =
        failure(null, "not_implemented", "$op needs a store with a changes log; this factory is bound to a document store")

    private fun failure(id: String?, error: String, reason: String): Map<String, Any?> =
        mapOf("ok" to false, "id" to id, "error" to error, "reason" to reason)

    private fun num(v: Any?): Long? = (v as? Number)?.toLong() ?: (v as? String)?.toLongOrNull()

    companion object {
        /** The canonical binding: the database `_changes`, `_replicate` and `_cas` answer over. */
        fun forDatabase(
            db: CouchDatabase,
            replicator: CouchReplicator? = null,
            viewServer: ViewServer = ViewServer(),
            rpcTargets: Map<String, RequestFactoryRpcTarget> = emptyMap(),
        ): CouchRequestFactory = CouchRequestFactory(RelaxStore.of(db, replicator), viewServer, rpcTargets)

        /** [CouchHttpSurface]'s document-only store: no changes log, so no lanes. */
        fun forConfixStore(
            store: ConfixDocStore,
            viewServer: ViewServer = ViewServer(),
            rpcTargets: Map<String, RequestFactoryRpcTarget> = emptyMap(),
        ): CouchRequestFactory =
            CouchRequestFactory(RelaxStore.of(store), viewServer, rpcTargets)
    }
}
