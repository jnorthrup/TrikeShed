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
    private val batch: Int = 500,
    /** Upper bound of cids offered per `_cas/_bulk` exchange; the SERVER caps each reply by bytes. */
    private val bulkChunk: Int = 4096,
) {
    // ── pull: remote → local ───────────────────────────────────────

    suspend fun pull(source: String, sinceOverride: Long? = null): ReplicationReport {
        val src = source.trimEnd('/')
        val replId = replicationId("pull", src, local.name)
        val start = sinceOverride ?: checkpoint(replId)
        var since = start
        var read = 0; var written = 0; var blobs = 0; var conflicts = 0
        while (true) {
            val page = getJson("$src/_changes?since=$since&limit=$batch") ?: break
            val results = CouchDatabase.asList(page["results"]) ?: emptyList()
            if (results.isEmpty()) break
            read += results.size
            // Ask ourselves what we lack (local _revs_diff), then move only blobs.
            data class Row(val id: String, val rev: String, val deleted: Boolean)
            val rows = results.mapNotNull { r ->
                val m = r as? Map<*, *> ?: return@mapNotNull null
                val id = m["id"] as? String ?: return@mapNotNull null
                val rev = (CouchDatabase.asList(m["changes"])?.firstOrNull() as? Map<*, *>)?.get("rev") as? String ?: return@mapNotNull null
                Row(id, rev, m["deleted"] == true)
            }
            val missing = local.revsDiff(rows.groupBy({ it.id }, { it.rev }))
            val wantedRows = rows.filter { missing.containsKey(it.id) }
            // Stage 1: body blobs (the rev names them). Stage 2: whatever the bodies reference.
            blobs += fetchBlobs(src, wantedRows.filter { !it.deleted }
                .mapNotNull { CouchDatabase.revToCid(it.rev)?.value }
                .filter { local.cas.get(ContentId(it)) == null })
            val decoded = HashMap<String, borg.trikeshed.couch.Document?>()
            for (row in wantedRows) {
                if (row.deleted) continue
                decoded[row.id] = CouchDatabase.revToCid(row.rev)
                    ?.let { local.cas.get(it) ?: fetchBlob(src, it)?.also { _ -> blobs++ } }
                    ?.let { CouchStoreFactory.documentFromBody(it) }
            }
            blobs += fetchBlobs(src, decoded.values.filterNotNull()
                .flatMap { local.referencedCids(it) }
                .filter { local.cas.get(ContentId(it)) == null }.distinct())
            for (row in wantedRows) {
                if (row.deleted) {
                    if (local.store.putReplicated(null, row.id, row.rev, true)) written++ else conflicts++
                    continue
                }
                val doc = decoded[row.id]
                if (doc == null) { conflicts++; continue }
                // Every referenced blob must be local before the revision becomes visible.
                var complete = true
                for (cidText in local.referencedCids(doc)) {
                    val cid = ContentId(cidText)
                    if (local.cas.get(cid) != null) continue
                    if (fetchBlob(src, cid) == null) { complete = false; break }
                    blobs++
                }
                if (!complete) { conflicts++; continue }
                if (local.store.putReplicated(doc, row.id, row.rev, false)) written++ else conflicts++
            }
            val last = (page["last_seq"] as? Number)?.toLong() ?: break
            if (last <= since) break
            since = last
            local.localPut(replId, mapOf("last_seq" to since, "peer" to src))
        }
        if (since > start) saveCheckpoint(replId, since, src) // remote side once, not per page
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
            local.localPut(replId, mapOf("last_seq" to since, "peer" to dst))
        }
        if (since > start) saveCheckpoint(replId, since, dst)
        return ReplicationReport("push", dst, start, since, read, written, blobs, conflicts)
    }

    // ── blobs ─────────────────────────────────────────────────────

    /**
     * Bulk lane: offer every cid at once; the peer caps each reply by bytes, so repeat with
     * whatever is still missing until a round makes no progress. Exchange count scales with total
     * BYTES, not blob count — each HTX exchange has a fixed connect/poll cost that dominated
     * replication when tiny body blobs went 64 to a call. Every block is verified against its cid.
     */
    private suspend fun fetchBlobs(peer: String, cids: List<String>): Int {
        var landed = 0
        var want = cids.distinct()
        while (want.isNotEmpty()) {
            var progressed = 0
            for (chunk in want.chunked(bulkChunk)) {
                val r = http.call("POST", "$peer/_cas/_bulk", JsonSupport.stringify(mapOf("cids" to chunk)).encodeToByteArray(), "application/json")
                if (!r.ok) continue
                for ((cid, bytes) in borg.trikeshed.couch.CasBulkCodec.decode(r.body)) {
                    val expect = runCatching { ContentId(cid) }.getOrNull() ?: continue
                    if (ContentId.of(bytes) != expect) continue
                    local.cas.put(bytes); landed++; progressed++
                }
            }
            if (progressed == 0) break
            want = want.filter { local.cas.get(ContentId(it)) == null }
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
