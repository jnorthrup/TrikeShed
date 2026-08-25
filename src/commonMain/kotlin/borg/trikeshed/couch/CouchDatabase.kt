package borg.trikeshed.couch

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.channels.Channel

/**
 * CouchDatabase — one CouchDB 1.6/1.7-shaped database over the CAS-collapsed [CouchStore].
 *
 * Everything a peer can ask for is a blob or an index over blobs:
 *   - a document revision `gen-<hex>` names the CAS blob holding its canonical body
 *     (see [CouchStoreFactory.casBacked]);
 *   - an attachment document carries `contentId`; its bytes are the CAS blob of that id and
 *     surface on the wire as a 1.x `_attachments` stub (`digest: "sha256-<hex>"`);
 *   - `_changes` is the committed-frame log; `_revs_diff` asks which revisions (= blobs) a node
 *     lacks; `_bulk_docs new_edits=false` lands foreign revisions; `_local/…` holds replication
 *     checkpoints and is never replicated.
 *
 * commonMain, no sockets, no clock: the HTTP worker hands `(method, path, query, body)` in and
 * renders the JSON map back. Sequence numbers on the wire are 1-based (`frame.sequence + 1`) so
 * `since=0` means "everything" and `since=update_seq` means "nothing", as in 1.x.
 */
class CouchDatabase(
    val name: String,
    val store: CouchStore,
    val cas: CasStore,
) {
    // ── _local documents (checkpoints): per-node, never in _changes ──
    private val locals = mutableMapOf<String, Map<String, Any?>>()
    private var localSeq = 0L

    /** Design-doc rewrites: read from `_design/forge` so the app's routing is itself a document. */
    val designId = "_design/forge"

    // ── info ──────────────────────────────────────────────────────

    /** 1-based update sequence; 0 for an empty database. */
    val updateSeq: Long
        get() {
            val s = store.changes.series()
            return if (s.size == 0) 0L else s[s.size - 1].sequence + 1
        }

    fun info(): Map<String, Any?> {
        // ⚡ Bolt: Using store.ids() for zero-allocation ID scanning instead of store.all().
        // Avoiding materializing full documents in memory just to count them reduces GC pressure.
        var docCount = 0
        val ids = store.ids()
        for (i in 0 until ids.a) {
            val id = ids.b(i)
            // ⚡ Bolt: Using store.head.isDeleted(id) directly is faster than loading the doc and checking for a tombstone flag.
            if (!store.head.isDeleted(id) && !id.startsWith("_design/")) {
                docCount++
            }
        }
        return mapOf(
            "db_name" to name,
            "doc_count" to docCount,
            "update_seq" to updateSeq,
            "instance_start_time" to "0",
        )
    }

    // ── documents ─────────────────────────────────────────────────

    /** The document as its 1.x JSON body, or null when absent/deleted. */
    fun docJson(id: String): Map<String, Any?>? {
        val doc = store.get(id) ?: return null
        if (isTombstone(doc)) return null
        return render(doc, store.head.getRev(id))
    }

    /** 201 {ok,id,rev} | 409 conflict. */
    fun put(id: String, body: Map<String, Any?>, rev: String?): Map<String, Any?> {
        val doc = toDocument(id, body)
        val ok = store.put(doc, rev ?: body["_rev"] as? String)
        return if (ok) mapOf("ok" to true, "id" to id, "rev" to store.head.getRev(id)) else conflict(id)
    }

    fun delete(id: String, rev: String?): Map<String, Any?> {
        if (store.get(id) == null || isTombstone(store.get(id)!!)) return mapOf("error" to "not_found", "reason" to "missing")
        if (rev == null) return conflict(id)
        return if (store.delete(id, rev)) mapOf("ok" to true, "id" to id, "rev" to store.head.getRev(id)) else conflict(id)
    }

    /** `GET /{db}/_all_docs` — ids in collation order, `{id,key,value:{rev}}` rows. */
    fun allDocs(
        startkey: String? = null,
        endkey: String? = null,
        limit: Int = Int.MAX_VALUE,
        skip: Int = 0,
        descending: Boolean = false,
        includeDocs: Boolean = false,
        keys: List<String>? = null,
    ): Map<String, Any?> {
        // ⚡ Bolt: Replaced store.all().filter {...}.map {...}.sorted() with zero-allocation store.ids() scanning
        // and direct ArrayList construction. Prevents O(N) allocation of full documents.
        val storeIds = store.ids()
        val liveIdsList = ArrayList<String>(storeIds.a)
        for (i in 0 until storeIds.a) {
            val id = storeIds.b(i)
            if (!store.head.isDeleted(id)) {
                liveIdsList.add(id)
            }
        }
        var ids = liveIdsList.sorted()
        if (keys != null) {
            val set = keys.toSet(); ids = keys.filter { it in set }
        } else {
            if (startkey != null) ids = ids.filter { if (descending) it <= startkey else it >= startkey }
            if (endkey != null) ids = ids.filter { if (descending) it >= endkey else it <= endkey }
            if (descending) ids = ids.reversed()
        }
        val total = liveIdsList.size
        val page = ids.drop(skip).take(limit)
        return mapOf(
            "total_rows" to total,
            "offset" to skip.coerceAtMost(ids.size),
            "rows" to page.map { id ->
                val rev = store.head.getRev(id)
                val row = linkedMapOf<String, Any?>("id" to id, "key" to id, "value" to mapOf("rev" to rev))
                if (includeDocs) row["doc"] = docJson(id)
                row
            },
        )
    }

    // ── _changes ──────────────────────────────────────────────────

    /** One 1.x change row. */
    fun changeRow(frame: CouchCommittedFrame, includeDocs: Boolean): Map<String, Any?> {
        val row = linkedMapOf<String, Any?>(
            "seq" to frame.sequence + 1,
            "id" to frame.docId,
            "changes" to listOf(mapOf("rev" to frame.rev)),
        )
        if (frame.deleted) row["deleted"] = true
        if (includeDocs) row["doc"] = if (frame.deleted) mapOf("_id" to frame.docId, "_rev" to frame.rev, "_deleted" to true) else frame.doc?.let { render(it, frame.rev) }
        return row
    }

    /** Frames with external seq > [since], newest last. */
    suspend fun framesSince(since: Long): List<CouchCommittedFrame> {
        val s = store.changes.afterSequence(since - 1)
        return List(s.size) { s[it] }
    }

    suspend fun changes(since: Long, limit: Int = Int.MAX_VALUE, includeDocs: Boolean = false): Map<String, Any?> {
        val frames = framesSince(since).take(limit)
        val last = if (frames.isEmpty()) since else frames.last().sequence + 1
        return mapOf("results" to frames.map { changeRow(it, includeDocs) }, "last_seq" to last)
    }

    /** A wake-up channel for feed=continuous|longpoll: one token per commit, conflated. */
    fun commitSignal(): Pair<Channel<Unit>, () -> Unit> {
        val ch = Channel<Unit>(Channel.CONFLATED)
        val cancel = store.changes.subscribe { ch.trySend(Unit) }
        return ch to cancel
    }

    // ── replication primitives ────────────────────────────────────

    /** `POST /{db}/_revs_diff` — which of the offered revisions this node lacks (head-only rev tree). */
    fun revsDiff(offered: Map<String, List<String>>): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for ((id, revs) in offered) {
            val head = store.head.getRev(id)
            val missing = revs.filter { it != head }
            if (missing.isNotEmpty()) out[id] = mapOf("missing" to missing)
        }
        return out
    }

    /** `POST /{db}/_bulk_docs` — `new_edits=false` lands foreign revisions; otherwise ordinary puts. */
    fun bulkDocs(docs: List<Map<String, Any?>>, newEdits: Boolean): List<Map<String, Any?>> = docs.map { body ->
        val id = body["_id"] as? String ?: ContentId.of(JsonSupport.stringify(body).encodeToByteArray()).hex
        val deleted = body["_deleted"] == true
        if (!newEdits) {
            val rev = body["_rev"] as? String ?: return@map mapOf("id" to id, "error" to "bad_request", "reason" to "new_edits=false requires _rev")
            val doc = if (deleted) null else toDocument(id, body)
            val ok = store.putReplicated(doc, id, rev, deleted)
            // The landed revision must also exist as a blob here, whoever minted the rev.
            if (ok && doc != null && (revToCid(rev)?.let { cas.get(it) } == null)) cas.put(CouchStoreFactory.canonicalBody(doc))
            if (ok) mapOf("ok" to true, "id" to id, "rev" to rev) else mapOf("id" to id, "error" to "conflict", "reason" to "newer revision present")
        } else if (deleted) {
            delete(id, body["_rev"] as? String)
        } else {
            put(id, body, body["_rev"] as? String)
        }
    }

    // ── _local ────────────────────────────────────────────────────

    fun localGet(id: String): Map<String, Any?>? = locals[id]

    fun localPut(id: String, body: Map<String, Any?>): Map<String, Any?> {
        val rev = "0-${++localSeq}"
        locals[id] = linkedMapOf<String, Any?>("_id" to "_local/$id", "_rev" to rev) + body.filterKeys { it != "_id" && it != "_rev" }
        return mapOf("ok" to true, "id" to "_local/$id", "rev" to rev)
    }

    fun localDelete(id: String): Map<String, Any?> =
        if (locals.remove(id) != null) mapOf("ok" to true, "id" to "_local/$id") else mapOf("error" to "not_found", "reason" to "missing")

    // ── blobs: attachments, bodies, raw blocks ────────────────────

    /** Attachment bytes for a path document: `(content_type, bytes)`; null if absent, deleted or blob missing. */
    fun attachment(id: String): Pair<String, ByteArray>? {
        val doc = store.get(id) ?: return null
        if (isTombstone(doc)) return null
        val cid = field(doc, "contentId") as? String ?: return null
        val bytes = cas.get(ContentId(cid)) ?: return null
        return (field(doc, "contentType") as? String ?: "application/octet-stream") to bytes
    }

    /** The canonical body blob a revision names, if this node holds it. */
    fun bodyBlob(rev: String): ByteArray? = revToCid(rev)?.let { cas.get(it) }

    fun blockGet(cid: String): ByteArray? = runCatching { ContentId(cid) }.getOrNull()?.let { cas.get(it) }
    fun blockPut(bytes: ByteArray): ContentId = cas.put(bytes)

    /** CIDs a document's replication needs beyond its body: today, its attachment blob. */
    fun referencedCids(body: Map<String, Any?>): List<String> =
        listOfNotNull((body["contentId"] as? String)?.takeIf { it.startsWith("sha256:") })

    /** Same, from a decoded [Document]. */
    fun referencedCids(doc: Document): List<String> =
        listOfNotNull((doc.fields.firstOrNull { it.name == "contentId" }?.value as? String)?.takeIf { it.startsWith("sha256:") })

    // ── rewrites (CouchApp) ───────────────────────────────────────

    /** Seed `_design/forge` with the rewrite table that hoists `docs/` to `/`. Idempotent. */
    fun ensureDesignDoc(vhostRoot: String = "docs/") {
        if (store.get(designId)?.let { !isTombstone(it) } == true) return
        val rewrites = listOf(
            mapOf("from" to "/", "to" to "${vhostRoot}index.html"),
            mapOf("from" to "/*", "to" to "$vhostRoot*"),
        )
        store.put(Document(designId, listOf(Field("language", "confix"), Field("rewrites", rewrites), Field("vhost_root", vhostRoot))))
    }

    /**
     * Apply `_design/forge.rewrites` to a request path (`/`, `/styles.css`, `/build/live/...`) and
     * return the logical attachment id under [prefix]. Rules: exact `from`, or `from` ending in `*`.
     */
    fun rewrite(path: String, prefix: String): String? {
        val design = store.get(designId) ?: return null
        val rules = asList(field(design, "rewrites")) ?: return null
        val p = if (path.isEmpty()) "/" else path
        for (rule in rules) {
            val r = rule as? Map<*, *> ?: continue
            val from = r["from"] as? String ?: continue
            val to = r["to"] as? String ?: continue
            if (from == p) return prefix + to
            if (from.endsWith("*") && p.startsWith(from.dropLast(1))) {
                val rest = p.removePrefix(from.dropLast(1))
                val target = if (to.endsWith("*")) to.dropLast(1) + rest else to
                if (rest.isEmpty() || rest.endsWith("/")) continue
                return prefix + target
            }
        }
        return null
    }

    // ── helpers ───────────────────────────────────────────────────

    fun isTombstone(doc: Document): Boolean =
        doc.fields.any { (it.name == "_deleted" && it.value == true) }

    private fun field(doc: Document, name: String): Any? = doc.fields.firstOrNull { it.name == name }?.value

    private fun conflict(id: String) = mapOf("error" to "conflict", "reason" to "Document update conflict.", "id" to id)

    fun render(doc: Document, rev: String?): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("_id" to doc.id, "_rev" to rev)
        for (f in doc.fields) if (!f.name.startsWith("_")) m[f.name] = f.value
        val cid = field(doc, "contentId") as? String
        if (cid != null && cid.startsWith("sha256:")) {
            m["_attachments"] = mapOf(
                "content" to mapOf(
                    "content_type" to (field(doc, "contentType") ?: "application/octet-stream"),
                    "length" to ((field(doc, "length") as? String)?.toLongOrNull() ?: 0L),
                    "digest" to "sha256-${cid.removePrefix("sha256:")}",
                    "cid" to cid,
                    "stub" to true,
                ),
            )
        }
        return m
    }

    fun toDocument(id: String, body: Map<String, Any?>): Document =
        Document(id, body.entries.filter { !it.key.startsWith("_") && it.value != null }.map { Field(it.key, it.value!!) })

    companion object {
        /** JSON arrays arrive as `Array<Any?>` from JsonSupport and as `List` from CBOR/Kotlin; accept both. */
        fun asList(v: Any?): List<Any?>? = when (v) {
            is List<*> -> v
            is Array<*> -> v.toList()
            else -> null
        }

        /** `gen-sha256:<hex>` (this store's minting) or `gen-<hex>`; null for foreign/short hashes. */
        fun revToCid(rev: String): ContentId? {
            val hex = rev.substringAfter("-", "").removePrefix("sha256:")
            return if (hex.length == 64) runCatching { ContentId("sha256:$hex") }.getOrNull() else null
        }
    }
}
