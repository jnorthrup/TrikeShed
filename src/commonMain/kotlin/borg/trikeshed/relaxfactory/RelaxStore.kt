package borg.trikeshed.relaxfactory

import borg.trikeshed.couch.ConfixDocStore
import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.replicate.CouchReplicator
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport

/**
 * The store a [CouchRequestFactory] resolves requests against.
 *
 * RequestFactory's lineage is *one* typed request unit that resolves to a report over *the* store —
 * so the envelope must not have a store of its own. Before this port existed it did: the factory
 * wrote into a `ConfixDocStore` (UUID revisions, private WAL) while the mounted wire wrote into the
 * `CouchDatabase` behind `_changes`/`_replicate`/`_cas`. A document put through the envelope was
 * therefore invisible to replication — the revision named no CAS blob, so no peer could ask for it.
 *
 * [of] over a [CouchDatabase] is the canonical binding: revisions are `gen-sha256:<hex>`, every put
 * appends a committed frame, and the same bytes answer on `_cas`. [of] over a [ConfixDocStore] is
 * kept for [CouchHttpSurface], whose store has no changes log — it reports [lanes] as null and the
 * distributed operations answer `not_implemented` rather than pretending.
 *
 * Shapes here are the wire's own (`{ok,id,rev}`, `{error,reason}`), not a second vocabulary: an
 * envelope operation and its REST route return the same map, which is what makes the two mountings
 * of this surface substitutable.
 */
interface RelaxStore {
    /** The database name, as `_replicate` and the router spell it. */
    val name: String

    /** The document in full Couch shape (`_id`/`_rev`, `_attachments` stubs); null when absent or deleted. */
    fun get(id: String): Map<String, Any?>?

    /** `{ok,id,rev}` on success, `{error,reason,id}` on conflict — the `PUT /{db}/{id}` reply. */
    fun put(id: String, json: String, rev: String?): Map<String, Any?>

    fun delete(id: String, rev: String?): Map<String, Any?>

    /** `(id, rev)` for every live document under [prefix], design docs included. */
    fun ids(prefix: String): List<Pair<String, String?>>

    /** The projection `query` maps and reduces over. */
    val viewDocs: ViewDocs

    /** The replication/CAS lanes, or null for a store that has no changes log. */
    val lanes: RelaxLanes? get() = null

    companion object {
        /** The canonical binding: the database the daemon serves on `_changes`, `_replicate` and `_cas`. */
        fun of(db: CouchDatabase, replicator: CouchReplicator? = null): RelaxStore =
            CouchDatabaseRelaxStore(db, replicator)

        /** [CouchHttpSurface]'s store: documents only, no changes log, no CAS. */
        fun of(store: ConfixDocStore): RelaxStore = object : RelaxStore {
            override val name: String get() = "confix"

            override fun get(id: String): Map<String, Any?>? {
                val e = store[id] ?: return null
                return linkedMapOf<String, Any?>("_id" to e.id, "_rev" to e.rev) + e.jsonBody()
            }

            override fun put(id: String, json: String, rev: String?): Map<String, Any?> {
                val entry = store.put(id, json, rev)
                    ?: return mapOf("error" to "conflict", "reason" to "rev mismatch; current rev is ${store[id]?.rev}", "id" to id)
                return mapOf("ok" to true, "id" to entry.id, "rev" to entry.rev)
            }

            override fun delete(id: String, rev: String?): Map<String, Any?> = when {
                rev == null -> mapOf("error" to "conflict", "reason" to "delete refused", "id" to id)
                store.delete(id, rev) -> mapOf("ok" to true, "id" to id, "rev" to rev)
                store.contains(id) -> mapOf("error" to "conflict", "reason" to "delete refused", "id" to id)
                else -> mapOf("error" to "not_found", "reason" to "delete refused", "id" to id)
            }

            override fun ids(prefix: String): List<Pair<String, String?>> {
                val entries = store.byIdPrefix(prefix)
                return List(entries.size) { i -> entries[i].id to entries[i].rev }
            }

            override val viewDocs: ViewDocs get() = ViewDocs.of(store)
        }
    }
}

/**
 * The lanes a replicating database has beyond documents: the changes feed a peer reads, the
 * revision diff it answers, the bulk landing of foreign revisions, its `_local` checkpoints, and
 * the CAS blocks that carry every payload (the same blocks the IPFS `/api/v0/block/…` aliases
 * serve). Present so a proxy can drive m2m and IPFS sync through the batched envelope instead of
 * needing a second client for the REST routes.
 */
interface RelaxLanes {
    suspend fun changes(since: Long, limit: Int, includeDocs: Boolean): Map<String, Any?>

    fun allDocs(
        startkey: String?, endkey: String?, limit: Int, skip: Int,
        descending: Boolean, includeDocs: Boolean, keys: List<String>?,
    ): Map<String, Any?>

    fun revsDiff(offered: Map<String, List<String>>): Map<String, Any?>

    fun bulkDocs(docs: List<Map<String, Any?>>, newEdits: Boolean): List<Map<String, Any?>>

    fun localGet(id: String): Map<String, Any?>?
    fun localPut(id: String, body: Map<String, Any?>): Map<String, Any?>

    fun blockGet(cid: String): ByteArray?
    fun blockPut(bytes: ByteArray): String

    /** `pull`/`push` against a peer URL; null when no replicator is bound to this mounting. */
    suspend fun replicate(direction: String, peer: String, since: Long?): Map<String, Any?>?

    /** The project headings over this database — see [borg.trikeshed.couch.Projects]. */
    val projects: borg.trikeshed.couch.Projects
}

private class CouchDatabaseRelaxStore(
    val db: CouchDatabase,
    val replicator: CouchReplicator?,
) : RelaxStore, RelaxLanes {

    override val name: String get() = db.name
    override val lanes: RelaxLanes get() = this

    override fun get(id: String): Map<String, Any?>? = db.docJson(id)

    override fun put(id: String, json: String, rev: String?): Map<String, Any?> {
        val body = runCatching { JsonSupport.parse(json) }.getOrNull() as? Map<*, *>
            ?: return mapOf("error" to "bad_request", "reason" to "Document must be a JSON object", "id" to id)
        @Suppress("UNCHECKED_CAST")
        return db.put(id, body as Map<String, Any?>, rev ?: body["_rev"] as? String)
    }

    override fun delete(id: String, rev: String?): Map<String, Any?> = db.delete(id, rev)

    override fun ids(prefix: String): List<Pair<String, String?>> =
        db.store.all().filter { !db.isTombstone(it) && it.id.startsWith(prefix) }
            .map { it.id to db.store.head.getRev(it.id) }

    override val viewDocs: ViewDocs get() = ViewDocs.of(db)

    // ── lanes ─────────────────────────────────────────────────────

    override suspend fun changes(since: Long, limit: Int, includeDocs: Boolean): Map<String, Any?> =
        db.changes(since, limit, includeDocs)

    override fun allDocs(
        startkey: String?, endkey: String?, limit: Int, skip: Int,
        descending: Boolean, includeDocs: Boolean, keys: List<String>?,
    ): Map<String, Any?> = db.allDocs(startkey, endkey, limit, skip, descending, includeDocs, keys)

    override fun revsDiff(offered: Map<String, List<String>>): Map<String, Any?> = db.revsDiff(offered)

    override fun bulkDocs(docs: List<Map<String, Any?>>, newEdits: Boolean): List<Map<String, Any?>> =
        db.bulkDocs(docs, newEdits)

    override fun localGet(id: String): Map<String, Any?>? = db.localGet(id)
    override fun localPut(id: String, body: Map<String, Any?>): Map<String, Any?> = db.localPut(id, body)

    override fun blockGet(cid: String): ByteArray? = db.blockGet(cid)
    override fun blockPut(bytes: ByteArray): String = db.blockPut(bytes).value

    override val projects: borg.trikeshed.couch.Projects by lazy { borg.trikeshed.couch.Projects(db) }

    override suspend fun replicate(direction: String, peer: String, since: Long?): Map<String, Any?>? {
        val r = replicator ?: return null
        val report = if (direction == "push") r.push(peer, since) else r.pull(peer, since)
        return report.toMap() + ("_local_id" to CouchReplicator.replicationId(
            direction,
            if (direction == "pull") peer else db.name,
            if (direction == "pull") db.name else peer,
        ))
    }
}
