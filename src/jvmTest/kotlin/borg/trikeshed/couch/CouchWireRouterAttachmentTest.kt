package borg.trikeshed.couch

import borg.trikeshed.job.CasStore
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Attachment GET over the daemon router — the gap-analysis critical path ("attachment GET route",
 * docs/oroboros-gap-analysis-2026-08-23.md) and the guide's known-bug row. An attachment document
 * carries `contentId`; its bytes ARE a CAS blob, so serving is a read through the store:
 * `GET /{db}/{id…}/content[?rev]` resolves the (rev's) canonical body blob, then the blob it names.
 */
class CouchWireRouterAttachmentTest {

    private class Node {
        val cas: CasStore = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val db = CouchDatabase("trike", store, cas)
        val router = CouchWireRouter(db, "projects/trikeshed/")

        suspend fun raw(method: String, path: String, body: ByteArray = ByteArray(0)): WireReply =
            router.handle(method, path, body) ?: error("router declined $path")

        @Suppress("UNCHECKED_CAST")
        suspend fun json(method: String, path: String, body: ByteArray = ByteArray(0)): Pair<Map<String, Any?>, Int> {
            val reply = raw(method, path, body)
            return JsonSupport.parse(reply.bytes.decodeToString()) as Map<String, Any?> to reply.status
        }

        /** The wire flow the absorber uses: bytes → `_cas` block, then the path doc naming its cid. */
        suspend fun putAttachment(id: String, bytes: ByteArray, contentType: String, rev: String? = null): Pair<String, String> {
            val (blk, blkStatus) = json("POST", "/trike/_cas", bytes)
            assertEquals(201, blkStatus)
            val cid = blk["cid"] as String
            val doc = """{"contentId":"$cid","contentType":"$contentType","length":${bytes.size}}"""
            val (put, putStatus) = json("PUT", "/trike/$id" + (rev?.let { "?rev=$it" } ?: ""), doc.encodeToByteArray())
            assertEquals(201, putStatus, put.toString())
            return cid to put["rev"] as String
        }
    }

    /** Every byte value once — a text decode would mangle this; the GET must be binary-safe. */
    private fun allBytes() = ByteArray(256) { it.toByte() }

    @Test
    fun attachmentGetRoundTripsBytesAndContentType(): Unit = runBlocking {
        val n = Node()
        val bytes = allBytes()
        n.putAttachment("docs/hello.bin", bytes, "application/octet-stream")
        val got = n.raw("GET", "/trike/docs/hello.bin/content")
        assertEquals(200, got.status)
        assertEquals("application/octet-stream", got.contentType)
        assertContentEquals(bytes, got.bytes)
    }

    @Test
    fun docGetRendersTheAttachmentStub(): Unit = runBlocking {
        val n = Node()
        val bytes = "hello, stub".encodeToByteArray()
        val (cid, _) = n.putAttachment("docs/hello.txt", bytes, "text/plain")
        val (doc, status) = n.json("GET", "/trike/docs/hello.txt")
        assertEquals(200, status)
        val att = (doc["_attachments"] as Map<*, *>)["content"] as Map<*, *>
        assertEquals("text/plain", att["content_type"])
        // length arrived as a JSON number in the PUT body; the stub must not zero it
        assertEquals(bytes.size.toLong(), (att["length"] as Number).toLong())
        assertEquals("sha256-" + cid.removePrefix("sha256:"), att["digest"])
        assertEquals(cid, att["cid"])
        assertEquals(true, att["stub"])
    }

    @Test
    fun revParamReadsThePriorRevisionThroughTheCas(): Unit = runBlocking {
        val n = Node()
        val v1 = "version one".encodeToByteArray()
        val v2 = "version two, longer".encodeToByteArray()
        val (_, rev1) = n.putAttachment("docs/page.txt", v1, "text/plain")
        val (_, rev2) = n.putAttachment("docs/page.txt", v2, "text/plain", rev = rev1)
        assertContentEquals(v2, n.raw("GET", "/trike/docs/page.txt/content").bytes)
        assertContentEquals(v2, n.raw("GET", "/trike/docs/page.txt/content?rev=$rev2").bytes)
        val old = n.raw("GET", "/trike/docs/page.txt/content?rev=$rev1")
        assertEquals(200, old.status)
        assertEquals("text/plain", old.contentType)
        assertContentEquals(v1, old.bytes)
        // a rev nobody minted → the router's standard not_found shape
        val (err, errStatus) = n.json("GET", "/trike/docs/page.txt/content?rev=9-deadbeef")
        assertEquals(404, errStatus)
        assertEquals("not_found", err["error"])
    }

    @Test
    fun missingAttachmentAnswersTheRouterNotFoundShape(): Unit = runBlocking {
        val n = Node()
        // no such document at all
        val (absent, absentStatus) = n.json("GET", "/trike/docs/absent.txt/content")
        assertEquals(404, absentStatus)
        assertEquals(mapOf("error" to "not_found", "reason" to "missing"), absent)
        // a live document with no attachment: /content must not invent bytes
        val (put, putStatus) = n.json("PUT", "/trike/plain", """{"x":1}""".encodeToByteArray())
        assertEquals(201, putStatus, put.toString())
        val (plain, plainStatus) = n.json("GET", "/trike/plain/content")
        assertEquals(404, plainStatus)
        assertEquals("not_found", plain["error"])
    }
}
