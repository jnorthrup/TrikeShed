@file:OptIn(ExperimentalEncodingApi::class)

package borg.trikeshed.relaxfactory

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.replicate.HttpExchange
import borg.trikeshed.parse.json.JsonSupport
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * How an envelope reaches a [CouchRequestFactory]: JSON in, JSON out, one exchange.
 *
 * The two bindings are the point of the whole file. [local] resolves against a factory in this
 * process — the daemon addressing its own store, a browser addressing the replica in its tab — and
 * touches no socket. [http] resolves against a peer's `_relax` mount over whatever [HttpExchange] the
 * host has (the daemon binds it to HTX; a test binds it to another database in-process). Same
 * envelope, same receipts, so the proxy above it cannot tell which it is talking to, and code that
 * reads state does not fork on where the state lives.
 */
fun interface RelaxTransport {
    suspend fun exchange(envelopeJson: String): String

    companion object {
        /** Server-side state: the factory in this process, no wire. */
        fun local(factory: CouchRequestFactory): RelaxTransport =
            RelaxTransport { envelope -> factory.processRequest(envelope) }

        /** Server-side state, spelled from the database — the daemon's own binding. */
        fun local(db: CouchDatabase, replicator: borg.trikeshed.couch.replicate.CouchReplicator? = null): RelaxTransport =
            local(CouchRequestFactory.forDatabase(db, replicator))

        /**
         * Client-side state: a peer's `_relax` mount. [base] is the database root
         * (`http://host:port/trikeshed`); the `_relax` segment is appended here so callers spell a
         * database, not a route.
         */
        fun http(exchange: HttpExchange, base: String): RelaxTransport {
            val url = base.trimEnd('/') + "/_relax"
            return RelaxTransport { envelope ->
                val reply = exchange.call("POST", url, envelope.encodeToByteArray(), "application/json")
                if (reply.ok) reply.text
                else JsonSupport.stringify(
                    mapOf(
                        "ok" to false,
                        "receipts" to listOf(mapOf("ok" to false, "error" to "transport", "reason" to "peer answered ${reply.status}")),
                    ),
                )
            }
        }
    }
}

/**
 * One typed request unit. `toMap` is the only place an operation's wire spelling is written, so the
 * proxy and the factory cannot drift into two dialects.
 */
sealed class RelaxOp {
    abstract fun toMap(): Map<String, Any?>

    /** `id` null means the store derives it from the content hash — an idempotent put. */
    data class Put(val doc: Map<String, Any?>, val id: String? = null, val rev: String? = null) : RelaxOp() {
        override fun toMap() = mapOf("op" to "put", "id" to id, "rev" to rev, "doc" to doc)
    }

    data class Get(val id: String) : RelaxOp() {
        override fun toMap() = mapOf("op" to "get", "id" to id)
    }

    data class Delete(val id: String, val rev: String) : RelaxOp() {
        override fun toMap() = mapOf("op" to "delete", "id" to id, "rev" to rev)
    }

    data class ListIds(val prefix: String = "") : RelaxOp() {
        override fun toMap() = mapOf("op" to "list", "prefix" to prefix)
    }

    /** The inline view spec documented on [CouchRequestFactory]; the receipt carries its `proofCid`. */
    data class Query(val view: Map<String, Any?>) : RelaxOp() {
        override fun toMap() = mapOf("op" to "query", "view" to view)
    }

    /**
     * A view the design docs already declare, addressed as the store names it —
     * `_design/<ddoc>/_view/<name>`. The same report `GET /{db}/_design/…/_view/…` returns.
     */
    data class View(val ddoc: String, val name: String, val params: Map<String, Any?> = emptyMap()) : RelaxOp() {
        override fun toMap() = mapOf("op" to "view", "ddoc" to ddoc, "name" to name, "params" to params)
    }

    data class AllDocs(
        val startkey: String? = null,
        val endkey: String? = null,
        val limit: Int? = null,
        val skip: Int = 0,
        val descending: Boolean = false,
        val includeDocs: Boolean = false,
        val keys: List<String>? = null,
    ) : RelaxOp() {
        override fun toMap() = mapOf(
            "op" to "all_docs", "startkey" to startkey, "endkey" to endkey, "limit" to limit,
            "skip" to skip, "descending" to descending, "include_docs" to includeDocs, "keys" to keys,
        )
    }

    data class Changes(val since: Long = 0L, val limit: Int? = null, val includeDocs: Boolean = false) : RelaxOp() {
        override fun toMap() = mapOf("op" to "changes", "since" to since, "limit" to limit, "include_docs" to includeDocs)
    }

    data class RevsDiff(val revs: Map<String, List<String>>) : RelaxOp() {
        override fun toMap() = mapOf("op" to "revs_diff", "revs" to revs)
    }

    data class BulkDocs(val docs: List<Map<String, Any?>>, val newEdits: Boolean = true) : RelaxOp() {
        override fun toMap() = mapOf("op" to "bulk_docs", "docs" to docs, "new_edits" to newEdits)
    }

    data class LocalGet(val id: String) : RelaxOp() {
        override fun toMap() = mapOf("op" to "local_get", "id" to id)
    }

    data class LocalPut(val id: String, val doc: Map<String, Any?>) : RelaxOp() {
        override fun toMap() = mapOf("op" to "local_put", "id" to id, "doc" to doc)
    }

    /** The CAS block lane — the same blocks `_cas/{cid}` and IPFS `/api/v0/block/get` serve. */
    data class BlockGet(val cid: String) : RelaxOp() {
        override fun toMap() = mapOf("op" to "block_get", "cid" to cid)
    }

    data class BlockPut(val data: ByteArray) : RelaxOp() {
        override fun toMap() = mapOf("op" to "block_put", "data" to Base64.encode(data))
        override fun equals(other: Any?) = other is BlockPut && data.contentEquals(other.data)
        override fun hashCode() = data.contentHashCode()
    }

    /** m2m sync: ask the node holding this state to pull from, or push to, [peer]. */
    data class Replicate(val direction: String, val peer: String, val since: Long? = null) : RelaxOp() {
        override fun toMap() = mapOf("op" to "replicate", "direction" to direction, "peer" to peer, "since" to since)
    }

    // ── projects: the heading a document hangs under ──────────────

    /** Declare a project heading, or update its manifest. */
    data class ProjectPut(val id: String, val doc: Map<String, Any?> = emptyMap()) : RelaxOp() {
        override fun toMap() = mapOf("op" to "project_put", "id" to id, "doc" to doc)
    }

    data class ProjectGet(val id: String) : RelaxOp() {
        override fun toMap() = mapOf("op" to "project_get", "id" to id)
    }

    data object ProjectList : RelaxOp() {
        override fun toMap() = mapOf("op" to "project_list")
    }

    /** What hangs under the heading, optionally narrowed to a sub-path. */
    data class ProjectDocs(val id: String, val under: String = "", val includeDocs: Boolean = false) : RelaxOp() {
        override fun toMap() = mapOf("op" to "project_docs", "id" to id, "under" to under, "include_docs" to includeDocs)
    }
}

/** One operation's receipt, read positionally against the operation that produced it. */
class RelaxReceipt(val fields: Map<String, Any?>) {
    val ok: Boolean get() = fields["ok"] == true
    val id: String? get() = fields["id"] as? String
    val rev: String? get() = fields["rev"] as? String
    val error: String? get() = fields["error"] as? String
    val reason: String? get() = fields["reason"] as? String

    @Suppress("UNCHECKED_CAST")
    val doc: Map<String, Any?>? get() = fields["doc"] as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val rows: List<Map<String, Any?>>
        get() = CouchDatabase.asList(fields["rows"])?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    val results: List<Map<String, Any?>>
        get() = CouchDatabase.asList(fields["results"])?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

    /** The map-reduce proof of a `query` — the receipt anyone can replay. */
    val proofCid: String? get() = fields["proofCid"] as? String

    val cid: String? get() = fields["cid"] as? String

    /** A `block_get` payload, decoded; null when the operation was not a block read or it failed. */
    val data: ByteArray? get() = (fields["data"] as? String)?.let { runCatching { Base64.decode(it) }.getOrNull() }

    val lastSeq: Long? get() = (fields["last_seq"] as? Number)?.toLong()

    @Suppress("UNCHECKED_CAST")
    val diff: Map<String, Any?> get() = fields["diff"] as? Map<String, Any?> ?: emptyMap()

    override fun toString(): String = JsonSupport.stringify(fields)
}

/** A whole batch's answer: [ok] only when every receipt is. */
class RelaxBatch(val ok: Boolean, val receipts: List<RelaxReceipt>) {
    operator fun get(i: Int): RelaxReceipt = receipts[i]
    val first: RelaxReceipt get() = receipts.first()
    val size: Int get() = receipts.size

    /** The receipts that failed — what a caller checks before trusting a batch it did not inspect. */
    val failures: List<RelaxReceipt> get() = receipts.filterNot { it.ok }
}

/**
 * RequestFactoryProxy — the client half of the RxF lineage, in commonMain so every Kotlin target
 * compiles the same proxy code.
 *
 * The distributed design asks one thing of this type: that reading or writing state look identical
 * whether the state is here or on another node. So the proxy holds no store, only an [RelaxTransport],
 * and the two bindings of that transport are `local` (the store in this process) and `http` (a
 * peer's `_relax` mount). A component written against this proxy runs unchanged in the daemon, in a
 * browser tab against its own replica, and in a browser tab against the daemon.
 *
 * ```kotlin
 * val here  = RequestFactoryProxy(RelaxTransport.local(db))                       // server-side state
 * val there = RequestFactoryProxy(RelaxTransport.http(htx, "http://host/trikeshed")) // client-side state
 * // identical from here on:
 * val rev = here.put(mapOf("type" to "widget"), id = "w1").rev
 * there.replicate("pull", "http://host/trikeshed")
 * ```
 */
class RequestFactoryProxy(private val transport: RelaxTransport) {

    /** The batch is the unit: everything stated in one exchange, one receipt per operation. */
    suspend fun submit(ops: List<RelaxOp>): RelaxBatch {
        // Unset is absent, not null: an operation omitting `id` asks the store to derive one, and
        // omitting `rev` asks for no rev check — the same distinction the routes make.
        val envelope = JsonSupport.stringify(
            mapOf("operations" to ops.map { op -> op.toMap().filterValues { it != null } }),
        )
        val reply = runCatching { JsonSupport.parse(transport.exchange(envelope)) }.getOrNull() as? Map<*, *>
            ?: return RelaxBatch(false, listOf(RelaxReceipt(mapOf("ok" to false, "error" to "parse", "reason" to "unreadable reply"))))
        @Suppress("UNCHECKED_CAST")
        val receipts = CouchDatabase.asList(reply["receipts"])
            ?.mapNotNull { (it as? Map<String, Any?>)?.let(::RelaxReceipt) }
            ?: emptyList()
        return RelaxBatch(reply["ok"] == true, receipts)
    }

    suspend fun submit(vararg ops: RelaxOp): RelaxBatch = submit(ops.toList())

    // ── one-operation conveniences ────────────────────────────────
    // Each is `submit` with a single op; batching stays available for callers that want one trip.

    suspend fun put(doc: Map<String, Any?>, id: String? = null, rev: String? = null): RelaxReceipt =
        submit(RelaxOp.Put(doc, id, rev)).first

    suspend fun get(id: String): RelaxReceipt = submit(RelaxOp.Get(id)).first

    suspend fun delete(id: String, rev: String): RelaxReceipt = submit(RelaxOp.Delete(id, rev)).first

    suspend fun list(prefix: String = ""): RelaxReceipt = submit(RelaxOp.ListIds(prefix)).first

    suspend fun query(view: Map<String, Any?>): RelaxReceipt = submit(RelaxOp.Query(view)).first

    /** Read a stored design-doc view — the envelope's spelling of the `_view` route. */
    suspend fun view(ddoc: String, name: String, params: Map<String, Any?> = emptyMap()): RelaxReceipt =
        submit(RelaxOp.View(ddoc, name, params)).first

    suspend fun allDocs(includeDocs: Boolean = false): RelaxReceipt = submit(RelaxOp.AllDocs(includeDocs = includeDocs)).first

    suspend fun changes(since: Long = 0L, limit: Int? = null, includeDocs: Boolean = false): RelaxReceipt =
        submit(RelaxOp.Changes(since, limit, includeDocs)).first

    suspend fun revsDiff(revs: Map<String, List<String>>): RelaxReceipt = submit(RelaxOp.RevsDiff(revs)).first

    suspend fun bulkDocs(docs: List<Map<String, Any?>>, newEdits: Boolean = true): RelaxReceipt =
        submit(RelaxOp.BulkDocs(docs, newEdits)).first

    suspend fun localGet(id: String): RelaxReceipt = submit(RelaxOp.LocalGet(id)).first

    suspend fun localPut(id: String, doc: Map<String, Any?>): RelaxReceipt = submit(RelaxOp.LocalPut(id, doc)).first

    /** IPFS sync: read a block by cid from whichever side this proxy addresses. */
    suspend fun blockGet(cid: String): ByteArray? = submit(RelaxOp.BlockGet(cid)).first.data

    suspend fun blockPut(data: ByteArray): String? = submit(RelaxOp.BlockPut(data)).first.cid

    /** m2m sync: run one replication pass and read its report. */
    suspend fun replicate(direction: String, peer: String, since: Long? = null): RelaxReceipt =
        submit(RelaxOp.Replicate(direction, peer, since)).first

    /** Declare a project heading, or update its manifest. */
    suspend fun projectPut(id: String, doc: Map<String, Any?> = emptyMap()): RelaxReceipt =
        submit(RelaxOp.ProjectPut(id, doc)).first

    suspend fun project(id: String): RelaxReceipt = submit(RelaxOp.ProjectGet(id)).first

    suspend fun projects(): RelaxReceipt = submit(RelaxOp.ProjectList).first

    /** The documents under a heading; [under] narrows to a sub-path within the project. */
    suspend fun projectDocs(id: String, under: String = "", includeDocs: Boolean = false): RelaxReceipt =
        submit(RelaxOp.ProjectDocs(id, under, includeDocs)).first
}
