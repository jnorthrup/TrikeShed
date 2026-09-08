package borg.trikeshed.couch.replicate

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.revWins
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.json.JsonSupport

/** One HTTP round trip. The daemon binds this to HtxElement; tests bind it to another [Couch] in-process. */
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
        // A report exists only when nothing is undelivered; `conflicts >= 0` are losers the winner rule
        // declined on the receiving side, so such a report is ok. `conflicts == -1` is CouchWire's
        // asynchronous-failure sentinel (the run threw; there is no count), and that is not ok.
        "ok" to (conflicts >= 0), "direction" to direction, "peer" to peer, "start_seq" to startSeq, "last_seq" to lastSeq,
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
 *
 * Two outcomes are kept apart, because they mean opposite things for the checkpoint:
 *
 *  - **undelivered** — a revision the peer offered that could not be landed (its body blob or a
 *    referenced blob did not arrive, or did not hash). That is a HOLE: the pass fails and the
 *    checkpoint stays put, so a retry offers the same revision again (see
 *    `missingBlobDoesNotAdvanceCheckpointAndRetryRepairsTheHole`).
 *  - **conflicts** — a revision the receiving side DECLINED because its own head wins under the
 *    1.x rule (`revWins`: higher generation, then greater hash). Nothing is missing; the winner is
 *    already in place on both sides, and offering the loser again can never change that. These are
 *    reported in [ReplicationReport.conflicts] and the checkpoint advances past them.
 *
 * Until 2026-09-05 the two were one counter, and one divergent head on the first page failed
 * every pass forever: two daemons that had each absorbed the same worktree disagreed on 252
 * `.git` attachment revisions (identical bytes, but the attachment body carries the absorber's wall-clock
 * `sequence`), so neither could ever reach the documents behind them.
 */
class CouchReplicator(
    private val local: Couch,
    private val http: HttpExchange,
    private val batch: Int = 500,
    /** Upper bound of cids offered per `_cas/_bulk` exchange; the SERVER caps each reply by bytes. */
    private val bulkChunk: Int = 4096,
    /**
     * The largest blob push will offer a peer in one `POST _cas`. A listener reassembles a request in
     * memory and answers 413 then closes above its cap (`JvmKanbanServer.maxRequestBatch`, 4 MiB on the
     * daemon); writing past that is a broken pipe that surfaced as "HTX reactor write failed for fd=N"
     * (2026-08-29, and again 2026-09-05 on a 30.7 MiB `.git/lost-found` object at sequence 344). A blob
     * over this bound is refused here, named, and counted undelivered — no bytes are sent, the failure
     * is immediate, and the checkpoint holds. Blobs that size cross today only by PULL, whose `_cas`
     * GET is bounded by the server; a chunked upload lane is the open design item.
     */
    private val maxPushBlobBytes: Int = DEFAULT_MAX_PUSH_BLOB_BYTES,
) {
    // ── pull: remote → local ───────────────────────────────────────

    suspend fun pull(source: String, sinceOverride: Long? = null): ReplicationReport {
        val src = source.trimEnd('/')
        val replId = replicationId("pull", src, local.name)
        val start = sinceOverride ?: checkpoint(replId)
        var since = start
        var read = 0; var written = 0; var blobs = 0; var conflicts = 0; var undelivered = 0
        while (true) {
            val page = getJson("$src/_changes?since=$since&limit=$batch")
            val results = Couch.asList(page["results"]) ?: error("Replication changes response has no results array")
            if (results.isEmpty()) break
            read += results.size
            // Ask ourselves what we lack (local _revs_diff), then move only blobs.
            data class Row(val id: String, val rev: String, val deleted: Boolean)
            val rows = results.map { r ->
                val m = r as? Map<*, *> ?: error("Invalid replication change row")
                val id = m["id"] as? String ?: error("Replication change has no document id")
                val rev = (Couch.asList(m["changes"])?.firstOrNull() as? Map<*, *>)?.get("rev") as? String ?: error("Replication change has no revision")
                Row(id, rev, m["deleted"] == true)
            }
            val missing = local.revsDiff(rows.groupBy({ it.id }, { it.rev }))
            // The winner rule needs only the revision strings. Deciding it BEFORE any blob moves is what
            // makes a divergent store cheap to reconcile: on 2026-09-05 two daemons that had absorbed one
            // worktree disagreed on ~4,500 `.git` revisions, and fetching each loser's body first was a
            // full HTX exchange per document that the receiving side then declined.
            val (wantedRows, losers) = rows.filter { missingRevision(missing, it.id, it.rev) }
                .partition { r -> local.store.head.getRev(r.id)?.let { revWins(r.rev, it) } ?: true }
            conflicts += losers.size
            // Stage 1: body blobs (the rev names them). Stage 2: whatever the bodies reference.
            blobs += fetchBlobs(src, wantedRows.filter { !it.deleted }
                .mapNotNull { Couch.revToCid(it.rev)?.value }
                .filter { local.cas.get(ContentId(it)) == null })
            val decoded = HashMap<Row, borg.trikeshed.couch.Document?>()
            for (row in wantedRows) {
                if (row.deleted) continue
                decoded[row] = Couch.revToCid(row.rev)
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
                val doc = decoded[row]
                if (doc == null) { undelivered++; continue } // body blob never arrived: a hole
                check(doc.id == row.id) { "Replication body does not belong to the offered document" }
                // Every referenced blob must be local before the revision becomes visible.
                var complete = true
                for (cidText in local.referencedCids(doc)) {
                    val cid = ContentId(cidText)
                    if (local.cas.get(cid) != null) continue
                    if (fetchBlob(src, cid) == null) { complete = false; break }
                    blobs++
                }
                if (!complete) { undelivered++; continue }
                // Declined = our head wins (revWins); the document is complete on both sides.
                if (local.store.putReplicated(doc, row.id, row.rev, false)) written++ else conflicts++
            }
            // Partial landings are safe to retry through revsDiff. Never checkpoint past a HOLE —
            // but a declined loser is not a hole, and holding the checkpoint for it holds it forever.
            check(undelivered == 0) { "Replication pull incomplete: $undelivered revision(s) not delivered; checkpoint retained" }
            val last = (page["last_seq"] as? Number)?.toLong() ?: error("Replication changes response has no last_seq")
            check(last > since) { "Replication changes sequence did not advance" }
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
        var read = 0; var written = 0; var blobs = 0; var conflicts = 0; var undelivered = 0
        while (true) {
            val frames = local.framesSince(since).take(batch)
            if (frames.isEmpty()) break
            read += frames.size
            // The store exposes a head-only rev tree. Its historical log is not
            // a request to reinstall obsolete heads on a peer that already advanced.
            val current = frames.filter { local.store.head.getRev(it.docId) == it.rev }
            val offered = current.groupBy({ it.docId }, { it.rev })
            val diff = postJson("$dst/_revs_diff", offered)
            val docs = mutableListOf<Map<String, Any?>>()
            for (f in current) {
                if (!missingRevision(diff, f.docId, f.rev)) continue
                if (f.deleted) { docs += mapOf("_id" to f.docId, "_rev" to f.rev, "_deleted" to true); continue }
                val doc = f.doc ?: error("Replication source revision has no document: ${f.docId}")
                val rendered = local.render(doc, f.rev)
                // Ship blobs first: the body the rev names, then anything the body references.
                var complete = true
                val bodyCid = Couch.revToCid(f.rev)
                val bodyBytes = bodyCid?.let { local.cas.get(it) } ?: CouchStoreFactory.canonicalBody(doc)
                if (!putBlob(dst, bodyBytes, f.docId)) complete = false else blobs++
                if (complete) for (cidText in local.referencedCids(rendered)) {
                    val bytes = local.cas.get(ContentId(cidText)) ?: error("Replication source blob missing: $cidText")
                    if (!putBlob(dst, bytes, f.docId)) { complete = false; break }
                    blobs++
                }
                if (!complete) { undelivered++; continue } // the peer never got the bytes: a hole
                docs += rendered.filterKeys { it != "_attachments" }
            }
            if (docs.isNotEmpty()) {
                val reply = postJson("$dst/_bulk_docs", mapOf("docs" to docs, "new_edits" to false), expectList = true)
                val list = Couch.asList(reply["results"]) ?: error("Replication bulk response has no results array")
                check(list.size == docs.size) { "Replication bulk response omitted acknowledgements" }
                for ((index, r) in list.withIndex()) {
                    val ack = r as? Map<*, *>
                    // `new_edits=false` on a head-only peer: ok=false means its head won (revWins), the
                    // same declined-loser outcome pull sees from putReplicated — complete, not a hole.
                    if (ack?.get("ok") == true && ack["id"] == docs[index]["_id"] && ack["rev"] == docs[index]["_rev"]) written++ else conflicts++
                }
            }
            check(undelivered == 0) {
                "Replication push incomplete: $undelivered revision(s) not delivered; checkpoint retained" +
                    (firstUndelivered?.let { " — first: $it" } ?: "")
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

    /** Why the first undelivered push revision could not land, for the failure message. */
    private var firstUndelivered: String? = null

    private suspend fun putBlob(peer: String, bytes: ByteArray, docId: String): Boolean {
        if (bytes.size > maxPushBlobBytes) {
            if (firstUndelivered == null) firstUndelivered =
                "$docId: blob ${ContentId.of(bytes).value} is ${bytes.size} bytes, over the $maxPushBlobBytes-byte push bound (the peer's request cap); it crosses by pull, not push"
            return false
        }
        // A peer that closes mid-request (413 and close) breaks the pipe under the HTX write; that is
        // this document's failure, not the pass's — name it and count it, do not throw out of the loop.
        val reply = runCatching { http.call("POST", "$peer/_cas", bytes, "application/octet-stream") }
            .getOrElse { e -> if (firstUndelivered == null) firstUndelivered = "$docId: ${e.message ?: e::class.simpleName}"; return false }
        if (!reply.ok && firstUndelivered == null) firstUndelivered = "$docId: peer answered ${reply.status} to a ${bytes.size}-byte blob"
        return reply.ok
    }

    // ── checkpoints ───────────────────────────────────────────────

    private fun checkpoint(replId: String): Long =
        (local.localGet(replId)?.get("last_seq") as? Number)?.toLong() ?: 0L

    private suspend fun saveCheckpoint(replId: String, seq: Long, peer: String) {
        local.localPut(replId, mapOf("last_seq" to seq, "peer" to peer))
        // 1.x writes the checkpoint on both ends; the far side is best-effort.
        runCatching { http.call("PUT", "$peer/_local/$replId", JsonSupport.stringify(mapOf("last_seq" to seq)).encodeToByteArray(), "application/json") }
    }

    // ── json helpers ──────────────────────────────────────────────

    private fun missingRevision(diff: Map<String, Any?>, id: String, rev: String): Boolean {
        if (!diff.containsKey(id)) return false
        val entry = diff[id] as? Map<*, *> ?: error("Invalid replication revision difference")
        val missing = Couch.asList(entry["missing"]) ?: error("Replication revision difference has no missing array")
        check(missing.all { it is String }) { "Invalid missing revision" }
        return rev in missing
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun getJson(url: String): Map<String, Any?> {
        val r = http.call("GET", url, null, null)
        check(r.ok) { "Replication GET failed: HTTP ${r.status}" }
        return JsonSupport.parse(r.text) as? Map<String, Any?> ?: error("Replication GET returned a non-object response")
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun postJson(url: String, body: Any?, expectList: Boolean = false): Map<String, Any?> {
        val r = http.call("POST", url, JsonSupport.stringify(body).encodeToByteArray(), "application/json")
        check(r.ok) { "Replication POST failed: HTTP ${r.status}" }
        val parsed = JsonSupport.parse(r.text)
        return if (expectList) mapOf("results" to (Couch.asList(parsed) ?: error("Replication POST returned a non-array response")))
        else parsed as? Map<String, Any?> ?: error("Replication POST returned a non-object response")
    }

    companion object {
        /** 4 MiB less headers' worth of headroom: the daemon listener's cap is `maxRequestBatch = 4096` KiB. */
        const val DEFAULT_MAX_PUSH_BLOB_BYTES: Int = 4 * 1024 * 1024 - 64 * 1024

        fun replicationId(direction: String, source: String, target: String): String =
            ContentId.of("$direction|$source|$target".encodeToByteArray()).hex.take(32)
    }
}
