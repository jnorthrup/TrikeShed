package borg.trikeshed.couch

import borg.trikeshed.couch.replicate.CouchReplicator
import borg.trikeshed.couch.replicate.HttpExchange
import borg.trikeshed.couch.replicate.HttpReply
import borg.trikeshed.couch.replicate.ReplicationReport
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.OroborosAttachmentRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two CAS-backed nodes wired together in-process through [CouchWireRouter] — the same code the
 * daemon mounts on its listener — replicate through the 1.x protocol with blobs as the payload.
 */
class CouchReplicationTest {

    @Test
    fun unavailableAndMalformedPeersAreFailuresNotEmptySuccess() = runTest {
        for (reply in listOf(HttpReply(503, byteArrayOf()), HttpReply(200, "{}".encodeToByteArray()))) {
            val node = Node("trikeshed")
            val replicator = CouchReplicator(node.db, HttpExchange { _, _, _, _ -> reply })
            assertFailsWith<IllegalStateException> { replicator.pull("http://peer/trikeshed") }
            assertNull(node.db.localGet(CouchReplicator.replicationId("pull", "http://peer/trikeshed", "trikeshed")))
        }
    }

    @Test
    fun missingBlobDoesNotAdvanceCheckpointAndRetryRepairsTheHole() = runTest {
        val a = Node("trikeshed")
        val b = Node("trikeshed")
        val bytes = "must survive a failed transfer".encodeToByteArray()
        val cid = ContentId.of(bytes)
        a.putFile("proof.txt", bytes)
        var unavailable = true
        val peer = a.exchange()
        val replicator = CouchReplicator(b.db, HttpExchange { method, url, body, type ->
            if (unavailable && (url.endsWith("/_cas/_bulk") || url.endsWith(cid.value))) HttpReply(503, byteArrayOf())
            else peer.call(method, url, body, type)
        })
        assertFailsWith<IllegalStateException> { replicator.pull("http://a/trikeshed") }
        assertNull(b.db.localGet(CouchReplicator.replicationId("pull", "http://a/trikeshed", "trikeshed")))
        assertNull(b.attachments.getAttachment(PREFIX + "proof.txt"))
        unavailable = false
        assertEquals(1, replicator.pull("http://a/trikeshed").docsWritten)
        assertContentEquals(bytes, b.attachments.getAttachment(PREFIX + "proof.txt")!!.second)
        assertEquals(0, replicator.pull("http://a/trikeshed").docsWritten)
    }

    @Test
    fun incompleteBulkAcknowledgementDoesNotCheckpointPush() = runTest {
        val a = Node("trikeshed")
        val b = Node("trikeshed")
        a.db.put("proof", mapOf("value" to 7), null)
        var omit = true
        val peer = b.exchange()
        val replicator = CouchReplicator(a.db, HttpExchange { method, url, body, type ->
            if (omit && url.endsWith("/_bulk_docs")) HttpReply(200, "[]".encodeToByteArray())
            else peer.call(method, url, body, type)
        })
        assertFailsWith<IllegalStateException> { replicator.push("http://b/trikeshed") }
        assertNull(a.db.localGet(CouchReplicator.replicationId("push", "trikeshed", "http://b/trikeshed")))
        omit = false
        assertEquals(1, replicator.push("http://b/trikeshed").docsWritten)
        assertEquals(7, (b.db.docJson("proof")!!["value"] as Number).toInt())
    }

    @Test
    fun asynchronousFailureSentinelNeverReportsSuccess() {
        assertEquals(false, ReplicationReport("pull", "peer", 0, 0, 0, 0, 0, -1).toMap()["ok"])
        // 2026-09-05: a POSITIVE conflict count is a report, not a failure — those are revisions the
        // receiving side declined because its head wins (revWins); nothing is missing on either side.
        // Before this, one divergent head failed every pass forever (two daemons absorbing one worktree).
        // An undelivered revision — a blob that never arrived — still throws and never reaches a report.
        assertEquals(true, ReplicationReport("pull", "peer", 0, 1, 1, 0, 0, 1).toMap()["ok"])
    }

    @Test
    fun pushAfterIncrementalPullDoesNotReinstallObsoleteHeads() = runTest {
        val a = Node("trikeshed")
        val b = Node("trikeshed")
        a.db.put("shared", mapOf("value" to 1), null)
        val puller = CouchReplicator(b.db, a.exchange())
        puller.pull("http://a/trikeshed")
        a.db.put("shared", mapOf("value" to 2), a.store.head.getRev("shared"))
        puller.pull("http://a/trikeshed")
        b.db.put("backchannel", mapOf("value" to "from-b"), null)
        val report = CouchReplicator(b.db, a.exchange()).push("http://a/trikeshed")
        assertEquals(0, report.conflicts)
        assertEquals(1, report.docsWritten)
        assertEquals(a.store.head.getRev("shared"), b.store.head.getRev("shared"))
        assertEquals(2, (a.db.docJson("shared")!!["value"] as Number).toInt())
        assertEquals("from-b", a.db.docJson("backchannel")!!["value"])
    }

    private class Node(name: String) {
        val cas: CasStore = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val db = Couch(name, store, cas)
        val router = CouchWireRouter(db, PREFIX)
        val attachments = CouchAttachmentGateway(store, cas)

        /** This node as a peer: `http://{name}/…` URLs are routed straight into its router. */
        fun exchange(): HttpExchange = HttpExchange { method, url, body, _ ->
            val path = url.removePrefix("http://").substringAfter('/', "").let { "/$it" }
            val reply = router.handle(method, path, body ?: ByteArray(0))
            if (reply == null) HttpReply(404, ByteArray(0)) else HttpReply(reply.status, reply.bytes)
        }

        fun putFile(path: String, bytes: ByteArray, contentType: String = "text/plain") {
            attachments.putAttachment(
                OroborosAttachmentRef(PREFIX + path, contentType, bytes.size.toLong(), ContentId.of(bytes), "test", "r1", 1L),
                bytes,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(reply: WireReply): Map<String, Any?> = JsonSupport.parse(reply.bytes.decodeToString()) as Map<String, Any?>

    @Test
    fun revisionIsTheBodyBlobAndAttachmentsHoistThroughTheRewrite() = runTest {
        val a = Node("trikeshed")
        a.db.ensureDesignDoc("docs/")
        a.putFile("docs/index.html", "<h1>forge</h1>".encodeToByteArray(), "text/html")
        a.putFile("docs/styles.css", "body{}".encodeToByteArray(), "text/css")

        // rev names the CBOR body blob in the CAS
        val rev = a.store.head.getRev(PREFIX + "docs/index.html")!!
        val cid = Couch.revToCid(rev)
        assertNotNull(cid)
        val body = CouchStoreFactory.documentFromBody(a.cas.get(cid)!!)
        assertEquals(PREFIX + "docs/index.html", body?.id)

        // document JSON carries the 1.x attachment stub
        val doc = json(a.router.handle("GET", "/trikeshed/$PREFIX" + "docs/index.html", ByteArray(0))!!)
        val stub = (doc["_attachments"] as Map<*, *>)["content"] as Map<*, *>
        assertEquals("text/html", stub["content_type"])
        assertEquals(true, stub["stub"])

        // vhost root and asset come out of the store via _design/forge.rewrites
        val root = a.router.handle("GET", "/", ByteArray(0))!!
        assertEquals(200, root.status)
        assertEquals("<h1>forge</h1>", root.bytes.decodeToString())
        val css = a.router.handle("GET", "/styles.css", ByteArray(0))!!
        assertEquals("body{}", css.bytes.decodeToString())
        assertEquals("text/css", css.contentType)
        val viaDesign = a.router.handle("GET", "/trikeshed/_design/forge/_rewrite/styles.css", ByteArray(0))!!
        assertEquals("body{}", viaDesign.bytes.decodeToString())
        assertNull(a.router.handle("GET", "/api/board", ByteArray(0)), "paths the store does not own decline")

        // raw blocks: _cas and the IPFS alias agree
        val hex = stub["digest"].toString().removePrefix("sha256-")
        val block = a.router.handle("GET", "/trikeshed/_cas/sha256:$hex", ByteArray(0))!!
        assertEquals("<h1>forge</h1>", block.bytes.decodeToString())
        val ipfs = a.router.handle("GET", "/api/v0/block/get?arg=sha256:$hex", ByteArray(0))!!
        assertContentEquals(block.bytes, ipfs.bytes)
    }

    @Test
    fun pullReplicatesDocumentsAndBlobsThenIsIdempotent() = runTest {
        val a = Node("trikeshed")
        val b = Node("trikeshed")
        a.db.ensureDesignDoc("docs/")
        a.putFile("docs/index.html", "<h1>v1</h1>".encodeToByteArray(), "text/html")
        a.putFile("build/live/classes/X.class", byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()), "application/java-vm")
        a.db.put("note", mapOf("kind" to "plain", "n" to 1), null)

        val puller = CouchReplicator(b.db, a.exchange())
        val r1 = puller.pull("http://a/trikeshed")
        assertEquals(4, r1.docsWritten, "ddoc + 2 attachments + plain doc")
        assertEquals(0, r1.conflicts)
        assertTrue(r1.blobsTransferred >= 6, "4 bodies + 2 attachment blobs, got ${r1.blobsTransferred}")

        // same revs, same bytes, app hoists on B
        assertEquals(a.store.head.getRev("note"), b.store.head.getRev("note"))
        assertEquals("<h1>v1</h1>", b.router.handle("GET", "/", ByteArray(0))!!.bytes.decodeToString())
        val cls = b.router.handle("GET", "/trikeshed/$PREFIX" + "build/live/classes/X.class/content", ByteArray(0))!!
        assertContentEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()), cls.bytes)

        // idempotent: nothing new to read past the checkpoint
        val r2 = puller.pull("http://a/trikeshed")
        assertEquals(0, r2.docsRead)
        assertEquals(r1.lastSeq, r2.lastSeq)

        // incremental: a changed file on A lands on B with the new rev; the old blob is still addressable
        a.putFile("docs/index.html", "<h1>v2</h1>".encodeToByteArray(), "text/html")
        val r3 = puller.pull("http://a/trikeshed")
        assertEquals(1, r3.docsWritten)
        assertEquals("<h1>v2</h1>", b.router.handle("GET", "/", ByteArray(0))!!.bytes.decodeToString())
        assertEquals(a.store.head.getRev(PREFIX + "docs/index.html"), b.store.head.getRev(PREFIX + "docs/index.html"))
    }

    @Test
    fun pushAndDeleteRoundTripWithWinnerRule() = runTest {
        val a = Node("trikeshed")
        val b = Node("trikeshed")
        a.db.put("k", mapOf("v" to "a1"), null)
        val pusher = CouchReplicator(a.db, b.exchange())
        val p1 = pusher.push("http://b/trikeshed")
        assertEquals(1, p1.docsWritten)
        assertEquals("a1", (b.db.docJson("k")!!)["v"])

        // delete on A propagates as a tombstone
        a.db.delete("k", a.store.head.getRev("k"))
        val p2 = pusher.push("http://b/trikeshed")
        assertEquals(1, p2.docsWritten)
        assertNull(b.db.docJson("k"))
        assertTrue(b.store.head.isDeleted("k"))

        // winner rule: a higher-generation foreign rev lands; a lower one is refused
        assertTrue(b.store.putReplicated(Document("w", listOf(Field("v", "x"))), "w", "3-sha256:${"0".repeat(64)}", false))
        assertEquals(false, b.store.putReplicated(Document("w", listOf(Field("v", "y"))), "w", "2-sha256:${"f".repeat(64)}", false))
        assertEquals("x", b.db.docJson("w")!!["v"])
        assertEquals(1, b.db.revsDiff(mapOf("w" to listOf("3-sha256:${"0".repeat(64)}", "9-sha256:${"1".repeat(64)}")))
            .let { Couch.asList((it["w"] as Map<*, *>)["missing"])!! }.size)
    }

    @Test
    fun changesAllDocsAndLocalShapes() = runTest {
        val a = Node("trikeshed")
        assertEquals(0L, a.db.updateSeq)
        a.db.put("b", mapOf("x" to 1), null)
        a.db.put("a", mapOf("x" to 2), null)
        assertEquals(2L, a.db.updateSeq)

        val all = json(a.router.handle("GET", "/trikeshed/_all_docs?include_docs=true", ByteArray(0))!!)
        assertEquals(listOf("a", "b"), Couch.asList(all["rows"])!!.map { (it as Map<*, *>)["id"] })

        val ch = json(a.router.handle("GET", "/trikeshed/_changes?since=1", ByteArray(0))!!)
        assertEquals(1, Couch.asList(ch["results"])!!.size)
        assertEquals(2L, (ch["last_seq"] as Number).toLong())
        val none = json(a.router.handle("GET", "/trikeshed/_changes?since=2", ByteArray(0))!!)
        assertEquals(0, Couch.asList(none["results"])!!.size)

        val put = a.router.handle("PUT", "/trikeshed/_local/ckpt", """{"last_seq":7}""".encodeToByteArray())!!
        assertEquals(201, put.status)
        assertEquals(7L, ((json(a.router.handle("GET", "/trikeshed/_local/ckpt", ByteArray(0))!!))["last_seq"] as Number).toLong())
        assertEquals(2L, a.db.updateSeq, "_local never enters _changes")

        val conflict = a.router.handle("PUT", "/trikeshed/a", """{"x":3}""".encodeToByteArray())!!
        assertEquals(409, conflict.status)
        val ok = a.router.handle("PUT", "/trikeshed/a?rev=${a.store.head.getRev("a")}", """{"x":3}""".encodeToByteArray())!!
        assertEquals(201, ok.status)
    }

    companion object {
        const val PREFIX = "projects/trikeshed/"
    }

    @Test
    fun aLosingRevisionIsDecidedFromItsStringAndFetchesNoBytes() = runTest {
        val a = Node("trikeshed")
        val b = Node("trikeshed")
        // the same id on both sides with different bodies: two heads, one winner under revWins
        a.db.put("shared", mapOf("v" to "a"), null)
        b.db.put("shared", mapOf("v" to "b"), null)
        val revA = a.store.head.getRev("shared")!!; val revB = b.store.head.getRev("shared")!!
        val casCalls = ArrayList<String>()
        val peer = a.exchange()
        val puller = CouchReplicator(b.db, HttpExchange { method, url, body, type ->
            if (url.contains("/_cas")) casCalls += url
            peer.call(method, url, body, type)
        })
        val r = puller.pull("http://a/trikeshed")
        if (revWins(revA, revB)) {
            assertEquals(1, r.docsWritten); assertEquals(0, r.conflicts)
            assertEquals(revA, b.store.head.getRev("shared"))
        } else {
            assertEquals(0, r.docsWritten); assertEquals(1, r.conflicts, "the loser is reported, not failed: $r")
            assertEquals(revB, b.store.head.getRev("shared"), "B keeps its winning head")
            assertTrue(casCalls.isEmpty(), "no blob was fetched for a revision the winner rule declines: $casCalls")
        }
        // and the pass completed: the checkpoint is past the divergence
        assertEquals(0, puller.pull("http://a/trikeshed").docsRead)
    }

    @Test
    fun anOversizedBlobIsRefusedByPushBeforeAnyByteIsSent() = runTest {
        val a = Node("trikeshed")
        val b = Node("trikeshed")
        val big = ByteArray(300) { 7 }
        a.putFile("huge.bin", big, "application/octet-stream")
        val sent = ArrayList<Int>()
        val peer = b.exchange()
        val pusher = CouchReplicator(a.db, HttpExchange { method, url, body, type ->
            if (url.endsWith("/_cas") && method == "POST") sent += body!!.size
            peer.call(method, url, body, type)
        }, maxPushBlobBytes = 280) // the document's own canonical body (~226 B) fits; the 300 B attachment does not
        val e = assertFailsWith<IllegalStateException> { pusher.push("http://b/trikeshed") }
        assertTrue(e.message!!.contains("huge.bin") && e.message!!.contains("300 bytes") && e.message!!.contains("push bound"),
            "the failure names the document, the blob and the bound: ${e.message}")
        assertTrue(sent.none { it > 280 }, "no oversized body was written toward the peer: $sent")
        assertNull(a.db.localGet(CouchReplicator.replicationId("push", "trikeshed", "http://b/trikeshed")), "the checkpoint holds")
    }
}
