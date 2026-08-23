package borg.trikeshed.couch.replicate

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.json.JsonSupport

/** One HTTP round trip. The daemon binds this to HtxElement; tests bind it to another [CouchDatabase] in-process. */
fun interface HttpExchange {
    suspend fun call(method: String, url: String, body: ByteArray?, contentType: String?): HttpReply
}

data class HttpReply(val status: Int, val body: ByteArray) {
    val text: String get() = body.decodeToString()
    val ok: Boolean get() = status in 200..299
}

data class ReplicationReport(
    val direction: String,
    val peer: String,
    val startSeq: Long,
    val lastSeq: Long,
    val docsRead: Int,
    val docsWritten: Int,
    val blobsTransferred: Int,
    val conflicts: Int,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "ok" to true, "direction" to direction, "peer" to peer, "start_seq" to startSeq, "last_seq" to lastSeq,
        "docs_read" to docsRead, "docs_written" to docsWritten, "blobs_transferred" to blobsTransferred, "conflicts" to conflicts,
    )
}

/**
 * CouchDB 1.x replication, CAS-first.
 *
 * The protocol is the classic one — `_changes` → `_revs_diff` → `_bulk_docs(new_edits=false)` with
 * `_local/<id>` checkpoints — but the payload moves as blocks: a revision names its canonical CBOR
 * body blob in the peer's CAS (`GET {db}/_cas/{cid}`), and attachment documents reference their
 * bytes by `contentId`. The replicator pulls every blob it lacks, verifies each against its
 * ContentId, lands it in the local CAS, and only then commits the revision. Nothing is copied that
 * is not a blob; nothing is trusted that does not hash.
 *
 * `continuous` is periodic polling of the normal feed (no long-held exchange on the shared HTX
 * reactor); the caller loops [pull]/[push] on its own cadence.
 */
class CouchReplicator(
    private val local: CouchDatabase,
    private val http: HttpExchange,
    private val batch: Int = 200,
    /** Blocks per `_cas/_bulk` exchange; bounds one reply's size for the peer's listener. */
    private val bulkChunk: Int = 64,
) {
    // ── pull: remote → local ───────────────────────────────────────

    suspend fun pull(source: String, sinceOverride: Long? = null): ReplicationReport {
        val src = source.trimEnd('/')
        val replId = replicationId("pull", src, local.name)
        val start = sinceOverride ?: checkpoint(replId)
        var since = start
        var read = 0; var written = 0; var blobs = 0; var conflicts = 0
        while (true) {
            val page = getJson("$src/_changes?since=$since&limit=$batch&include_docs=true") ?: break
            val results = CouchDatabase.asList(page["results"]) ?: emptyList()
            if (results.isEmpty()) break
            read += results.size
            // Ask ourselves what we lack (local _revs_diff), then fetch only that.
            val offered = results.mapNotNull { r ->
                val m = r as? Map<*, *> ?: return@mapNotNull null
                val id = m["id"] as? String ?: return@mapNotNull null
                val rev = (CouchDatabase.asList(m["changes"])?.firstOrNull() as? Map<*, *>)?.get("rev") as? String ?: return@mapNotNull null
                id to rev
            }
            val missing = local.revsDiff(offered.groupBy({ it.first }, { it.second }))
            // One exchange for every blob this page needs (bodies + attachments) — then the per-doc
            // loop finds them locally; single GETs remain as the fallback for anything the peer omitted.
            val wanted = linkedSetOf<String>()
            for (r in results) {
                val m = r as? Map<*, *> ?: continue
                val id = m["id"] as? String ?: continue
                if (!missing.containsKey(id) || m["deleted"] == true) continue
                val rev = (CouchDatabase.asList(m["changes"])?.firstOrNull() as? Map<*, *>)?.get("rev") as? String ?: continue
                CouchDatabase.revToCid(rev)?.let { if (local.cas.get(it) == null) wanted += it.value }
                @Suppress("UNCHECKED_CAST")
                for (c in local.referencedCids((m["doc"] as? Map<String, Any?>) ?: emptyMap())) if (local.cas.get(ContentId(c)) == null) wanted += c
            }
            if (wanted.isNotEmpty()) blobs += fetchBlobs(src, wanted.toList())
            for (r in results) {
                val m = r as? Map<*, *> ?: continue
                val id = m["id"] as? String ?: continue
                val rev = (CouchDatabase.asList(m["changes"])?.firstOrNull() as? Map<*, *>)?.get("rev") as? String ?: continue
                if (!missing.containsKey(id)) continue
                val deleted = m["deleted"] == true
                if (deleted) {
                    if (local.store.putReplicated(null, id, rev, true)) written++ else conflicts++
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val wireDoc = m["doc"] as? Map<String, Any?>
                // Body: prefer the canonical blob the rev names (bit-exact, hashed); fall back to wire JSON.
                val bodyCid = CouchDatabase.revToCid(rev)
                var doc = bodyCid?.let { cid ->
                    val bytes = local.cas.get(cid) ?: fetchBlob(src, cid)?.also { blobs++ } ?: return@let null
                    CouchStoreFactory.documentFromBody(bytes)
                }
                if (doc == null && wireDoc != null) doc = local.toDocument(id, wireDoc)
                if (doc == null) { conflicts++; continue }
                // Referenced blobs (attachments) must land before the revision is visible.
                var complete = true
                for (cidText in local.referencedCids(wireDoc ?: emptyMap())) {
                    val cid = ContentId(cidText)
                    if (local.cas.get(cid) != null) continue
                    if (fetchBlob(src, cid) == null) { complete = false; break }
                    blobs++
                }
                if (!complete) { conflicts++; continue }
                if (bodyCid == null) local.cas.put(CouchStoreFactory.canonicalBody(doc))
                if (local.store.putReplicated(doc, id, rev, false)) written++ else conflicts++
            }
            val last = (page["last_seq"] as? Number)?.toLong() ?: break
            if (last <= since) break
            since = last
            saveCheckpoint(replId, since, src)
        }
        return ReplicationReport("pull", src, start, since, read, written, blobs, conflicts)
    }

    // ── push: local → remote ───────────────────────────────────────

    suspend fun push(target: String, sinceOverride: Long? = null): ReplicationReport {
        val dst = target.trimEnd('/')
        val replId = replicationId("push", local.name, dst)
        val start = sinceOverride ?: checkpoint(replId)
        var since = start
        var read = 0; var written = 0; var blobs = 0; var conflicts = 0
        while (true) {
            val frames = local.framesSince(since).take(batch)
            if (frames.isEmpty()) break
            read += frames.size
            val offered = frames.groupBy({ it.docId }, { it.rev })
            val diff = postJson("$dst/_revs_diff", offered) ?: break
            val docs = mutableListOf<Map<String, Any?>>()
            for (f in frames) {
                if (!diff.containsKey(f.docId)) continue
                if (f.deleted) { docs += mapOf("_id" to f.docId, "_rev" to f.rev, "_deleted" to true); continue }
                val doc = f.doc ?: continue
                val rendered = local.render(doc, f.rev)
                // Ship blobs first: the body the rev names, then anything the body references.
                var complete = true
                val bodyCid = CouchDatabase.revToCid(f.rev)
                val bodyBytes = bodyCid?.let { local.cas.get(it) } ?: CouchStoreFactory.canonicalBody(doc)
                if (!putBlob(dst, bodyBytes)) complete = false else blobs++
                for (cidText in local.referencedCids(rendered)) {
                    val bytes = local.cas.get(ContentId(cidText)) ?: continue
                    if (!putBlob(dst, bytes)) { complete = false; break }
                    blobs++
                }
                if (!complete) { conflicts++; continue }
                docs += rendered.filterKeys { it != "_attachments" }
            }
            if (docs.isNotEmpty()) {
                val reply = postJson("$dst/_bulk_docs", mapOf("docs" to docs, "new_edits" to false), expectList = true)
                val list = CouchDatabase.asList(reply?.get("results")) ?: emptyList()
                for (r in list) if ((r as? Map<*, *>)?.get("ok") == true) written++ else conflicts++
            }
            since = frames.last().sequence + 1
            saveCheckpoint(replId, since, dst)
        }
        return ReplicationReport("push", dst, start, since, read, written, blobs, conflicts)
    }

    // ── blobs ─────────────────────────────────────────────────────

    /** Bulk lane: returns how many blocks landed (each verified against its cid). */
    private suspend fun fetchBlobs(peer: String, cids: List<String>): Int {
        var landed = 0
        for (chunk in cids.chunked(bulkChunk)) {
            val r = http.call("POST", "$peer/_cas/_bulk", JsonSupport.stringify(mapOf("cids" to chunk)).encodeToByteArray(), "application/json")
            if (!r.ok) continue
            for ((cid, bytes) in borg.trikeshed.couch.CasBulkCodec.decode(r.body)) {
                val expect = runCatching { ContentId(cid) }.getOrNull() ?: continue
                if (ContentId.of(bytes) != expect) continue
                local.cas.put(bytes); landed++
            }
        }
        return landed
    }

    private suspend fun fetchBlob(peer: String, cid: ContentId): ByteArray? {
        val r = http.call("GET", "$peer/_cas/${cid.value}", null, null)
        if (!r.ok) return null
        if (ContentId.of(r.body) != cid) return null // peer lied or link corrupted: never land it
        local.cas.put(r.body)
        return r.body
    }

    private suspend fun putBlob(peer: String, bytes: ByteArray): Boolean =
        http.call("POST", "$peer/_cas", bytes, "application/octet-stream").ok

    // ── checkpoints ───────────────────────────────────────────────

    private fun checkpoint(replId: String): Long =
        (local.localGet(replId)?.get("last_seq") as? Number)?.toLong() ?: 0L

    private suspend fun saveCheckpoint(replId: String, seq: Long, peer: String) {
        local.localPut(replId, mapOf("last_seq" to seq, "peer" to peer))
        // 1.x writes the checkpoint on both ends; the far side is best-effort.
        runCatching { http.call("PUT", "$peer/_local/$replId", JsonSupport.stringify(mapOf("last_seq" to seq)).encodeToByteArray(), "application/json") }
    }

    // ── json helpers ──────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private suspend fun getJson(url: String): Map<String, Any?>? {
        val r = http.call("GET", url, null, null)
        if (!r.ok) return null
        return runCatching { JsonSupport.parse(r.text) as? Map<String, Any?> }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun postJson(url: String, body: Any?, expectList: Boolean = false): Map<String, Any?>? {
        val r = http.call("POST", url, JsonSupport.stringify(body).encodeToByteArray(), "application/json")
        if (!r.ok) return null
        val parsed = runCatching { JsonSupport.parse(r.text) }.getOrNull() ?: return null
        return if (expectList) mapOf("results" to (CouchDatabase.asList(parsed) ?: emptyList()))
        else parsed as? Map<String, Any?>
    }

    companion object {
        fun replicationId(direction: String, source: String, target: String): String =
            ContentId.of("$direction|$source|$target".encodeToByteArray()).hex.take(32)
    }
}
