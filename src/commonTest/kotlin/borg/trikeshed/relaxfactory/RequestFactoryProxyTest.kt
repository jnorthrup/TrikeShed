package borg.trikeshed.relaxfactory

import borg.trikeshed.couch.ConfixDocStoreFactory
import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.CouchWireRouter
import borg.trikeshed.couch.replicate.CouchReplicator
import borg.trikeshed.couch.replicate.HttpExchange
import borg.trikeshed.couch.replicate.HttpReply
import borg.trikeshed.job.CasStore
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The RequestFactory as the distributed design uses it: one commonMain proxy addressing state in
 * this process and state on a peer through the same envelope, over the same database the couch
 * transport serves.
 *
 * What each test is for:
 *  - [envelopePutMintsTheRevisionTheRouteWouldAndEntersChanges] — the "exact matching" claim. An
 *    envelope put is not a side door: same CAS-derived revision, same committed frame, so it
 *    replicates. This is what was broken while the factory had a store of its own.
 *  - [oneProxyCodeAddressesLocalAndRemoteState] — the proxy is transport-agnostic.
 *  - [proxyDrivesM2mSyncThroughTheEnvelope] / [proxyMovesIpfsBlocksThroughTheEnvelope] — the sync
 *    lanes are reachable from a client that only speaks RequestFactory.
 *  - [envelopeQueryAndViewRouteReturnTheSameRows] — one view engine, two askers.
 *  - [laneOperationsRefuseOnADocumentOnlyStore] — the honest failure, not a silent one.
 */
class RequestFactoryProxyTest {

    private class Node(val name: String = "trikeshed") {
        val cas: CasStore = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val db = CouchDatabase(name, store, cas)
        val router = CouchWireRouter(db, PREFIX)
        fun exchange(): HttpExchange = exchangeFor(router)
    }

    companion object {
        const val PREFIX = "projects/trikeshed/"

        /** `http://peer/{db}/…` straight into a router — the daemon's mount, minus the socket. */
        fun exchangeFor(router: CouchWireRouter): HttpExchange = HttpExchange { method, url, body, _ ->
            val path = url.removePrefix("http://").substringAfter('/', "").let { "/$it" }
            val reply = router.handle(method, path, body ?: ByteArray(0))
            if (reply == null) HttpReply(404, ByteArray(0)) else HttpReply(reply.status, reply.bytes)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(bytes: ByteArray): Map<String, Any?> = JsonSupport.parse(bytes.decodeToString()) as Map<String, Any?>

    // ── the exact-matching claim ──────────────────────────────────

    @Test
    fun envelopePutMintsTheRevisionTheRouteWouldAndEntersChanges() = runTest {
        val a = Node()
        val proxy = RequestFactoryProxy(RelaxTransport.local(a.db))

        val put = proxy.put(mapOf("type" to "widget", "qty" to 2), id = "w1")
        assertTrue(put.ok, "put failed: $put")
        val rev = assertNotNull(put.rev)

        // The revision names a CAS blob — the property replication depends on. A UUID rev, which is
        // what the factory used to mint against its own store, yields null here and no peer could
        // ever ask for the body.
        assertNotNull(CouchDatabase.revToCid(rev), "rev '$rev' does not name a CAS blob")
        assertEquals(rev, a.store.head.getRev("w1"), "envelope and store disagree on the head rev")

        // The same revision the REST route reports, and the same document body.
        val viaRoute = json(a.router.handle("GET", "/trikeshed/w1", ByteArray(0))!!.bytes)
        assertEquals(rev, viaRoute["_rev"])
        assertEquals(viaRoute, proxy.get("w1").doc, "envelope `get` and GET /{db}/{id} disagree")

        // It is in _changes, so it is replicable.
        val changes = proxy.changes(since = 0L)
        assertTrue(changes.ok)
        assertEquals(listOf("w1"), changes.results.map { it["id"] })
        assertEquals(1L, a.db.updateSeq)

        // Conflict discipline survives the envelope: no rev is a conflict, the right rev is a new rev.
        val stale = proxy.put(mapOf("qty" to 3), id = "w1")
        assertTrue(!stale.ok && stale.error == "conflict", "expected conflict, got $stale")
        val fresh = proxy.put(mapOf("qty" to 3), id = "w1", rev = rev)
        assertTrue(fresh.ok)
        assertTrue(fresh.rev != rev)
    }

    @Test
    fun batchReportsPerOperationAndAPartialFailureCostsOnlyItsOwnReceipt() = runTest {
        val a = Node()
        val proxy = RequestFactoryProxy(RelaxTransport.local(a.db))

        val batch = proxy.submit(
            RelaxOp.Put(mapOf("n" to 1), id = "one"),
            RelaxOp.Get("absent"),
            RelaxOp.Put(mapOf("n" to 2), id = "two"),
        )
        assertEquals(3, batch.size)
        assertTrue(!batch.ok, "a batch with a failed operation is not ok")
        assertTrue(batch[0].ok)
        assertEquals("not_found", batch[1].error)
        assertTrue(batch[2].ok)
        // The neighbours of the failure still landed.
        assertEquals(1L, (a.db.docJson("one")!!["n"] as Number).toLong())
        assertEquals(2L, (a.db.docJson("two")!!["n"] as Number).toLong())
        assertEquals(listOf("not_found"), batch.failures.map { it.error })
    }

    // ── one proxy, two transports ─────────────────────────────────

    /** Written once, run against both bindings; whatever it returns must not depend on which. */
    private suspend fun exercise(p: RequestFactoryProxy): List<Any?> {
        val w = p.put(mapOf("type" to "widget", "qty" to 2), id = "w")
        val g = p.put(mapOf("type" to "gadget", "qty" to 7), id = "g")
        val hashed = p.put(mapOf("type" to "anon"))
        val listed = p.list("").rows.mapNotNull { it["id"] as? String }.sorted()
        val fetched = p.get("g").doc?.get("type")
        return listOf(w.rev, g.rev, hashed.id, listed, fetched)
    }

    @Test
    fun oneProxyCodeAddressesLocalAndRemoteState() = runTest {
        val here = Node()
        val there = Node()

        // Server-side state: the store in this process, no wire.
        val local = exercise(RequestFactoryProxy(RelaxTransport.local(here.db)))
        // Client-side state: a peer's _relax mount, over the transport the daemon binds to HTX.
        val remote = exercise(RequestFactoryProxy(RelaxTransport.http(there.exchange(), "http://there/trikeshed")))

        assertEquals(local, remote, "the same proxy code produced different results per transport")
        // Content-addressed ids are stable across nodes — the same document is the same id anywhere.
        assertNotNull(local[2])
        assertEquals(here.store.head.getRev("w"), there.store.head.getRev("w"))
    }

    @Test
    fun theEnvelopeAnswersOnBothItsMountsIdentically() = runTest {
        val a = Node()
        a.db.put("k", mapOf("v" to 1), null)
        val envelope = """{"operations":[{"op":"get","id":"k"}]}""".encodeToByteArray()

        // The dedicated endpoint and the sniffed POST on the database root are one route.
        val viaRelax = a.router.handle("POST", "/trikeshed/_relax", envelope)!!
        val viaRoot = a.router.handle("POST", "/trikeshed", envelope)!!
        assertEquals(200, viaRelax.status)
        assertEquals(200, viaRoot.status)
        assertContentEquals(viaRelax.bytes, viaRoot.bytes)

        // A bare document still puts, and is not mistaken for an envelope.
        val bare = a.router.handle("POST", "/trikeshed", """{"_id":"plain","operations":"a string"}""".encodeToByteArray())!!
        assertEquals(201, bare.status)
        assertEquals("a string", a.db.docJson("plain")!!["operations"])
    }

    // ── sync lanes through the envelope ───────────────────────────

    @Test
    fun proxyDrivesM2mSyncThroughTheEnvelope() = runTest {
        val a = Node()
        val b = Node()
        a.db.put("note", mapOf("kind" to "plain", "n" to 1), null)
        a.db.put("other", mapOf("kind" to "plain", "n" to 2), null)

        // B mounted with a replicator aimed at A — the daemon's wiring, in-process.
        val bRouter = CouchWireRouter(b.db, PREFIX, replicator = CouchReplicator(b.db, a.exchange()))
        val bProxy = RequestFactoryProxy(RelaxTransport.http(exchangeFor(bRouter), "http://b/trikeshed"))

        val report = bProxy.replicate("pull", "http://a/trikeshed")
        assertTrue(report.ok, "replicate failed: $report")
        assertEquals(2L, (report.fields["docs_written"] as Number).toLong())
        assertEquals(1L, (b.db.docJson("note")!!["n"] as Number).toLong())
        assertEquals(a.store.head.getRev("note"), b.store.head.getRev("note"))

        // Idempotent from the checkpoint the replicator wrote, and readable back as a checkpoint.
        val again = bProxy.replicate("pull", "http://a/trikeshed")
        assertEquals(0L, (again.fields["docs_read"] as Number).toLong())
        val checkpoint = bProxy.localGet(report.fields["_local_id"] as String)
        assertTrue(checkpoint.ok, "the replication checkpoint should be readable through the envelope")

        // revs_diff is the question a peer asks before moving anything; ask it through the envelope.
        val diff = bProxy.revsDiff(mapOf("note" to listOf("9-sha256:${"0".repeat(64)}")))
        assertTrue(diff.ok)
        assertTrue(diff.diff.containsKey("note"), "an unknown revision must come back missing")
        assertTrue(bProxy.revsDiff(mapOf("note" to listOf(b.store.head.getRev("note")!!))).diff.isEmpty())
    }

    @Test
    fun proxyMovesIpfsBlocksThroughTheEnvelope() = runTest {
        val a = Node()
        val proxy = RequestFactoryProxy(RelaxTransport.http(a.exchange(), "http://a/trikeshed"))
        val payload = "<h1>forge</h1>".encodeToByteArray()

        val cid = assertNotNull(proxy.blockPut(payload), "block_put returned no cid")
        assertContentEquals(payload, proxy.blockGet(cid), "block_get did not round-trip")

        // The same block, addressed the two other ways this node serves it: the couch CAS lane and
        // the IPFS alias. One store, three spellings.
        assertContentEquals(payload, a.router.handle("GET", "/trikeshed/_cas/$cid", ByteArray(0))!!.bytes)
        assertContentEquals(payload, a.router.handle("GET", "/api/v0/block/get?arg=$cid", ByteArray(0))!!.bytes)

        assertNull(proxy.blockGet("sha256:${"0".repeat(64)}"), "a block this node lacks reads as null")
    }

    // ── one view engine ───────────────────────────────────────────

    @Test
    fun envelopeQueryAndViewRouteReturnTheSameRows() = runTest {
        val a = Node()
        a.db.put("a", mapOf("type" to "widget", "qty" to 2), null)
        a.db.put("b", mapOf("type" to "widget", "qty" to 5), null)
        a.db.put("c", mapOf("type" to "gadget", "qty" to 7), null)
        a.db.put("_design/rf", mapOf("views" to mapOf("by_type" to mapOf("map" to mapOf("key" to "type", "value" to "qty")))), null)

        val route = json(a.router.handle("GET", "/trikeshed/_design/rf/_view/by_type", ByteArray(0))!!.bytes)
        val routeRows = CouchDatabase.asList(route["rows"])!!.map { it as Map<*, *> }

        val envelope = RequestFactoryProxy(RelaxTransport.local(a.db))
            .query(mapOf("ddoc" to "_design/rf", "name" to "by_type", "key" to "type", "value" to "qty"))
        assertTrue(envelope.ok, "query failed: $envelope")

        val shape = { rows: List<Map<*, *>> -> rows.map { Triple(it["id"], it["key"], it["value"].toString()) } }
        assertEquals(shape(routeRows), shape(envelope.rows), "the two askers disagree on the same view")
        assertEquals(3L, (envelope.fields["total_rows"] as Number).toLong())
        // The design doc is not its own data on either side.
        assertTrue(routeRows.none { it["id"] == "_design/rf" })
        assertTrue(envelope.rows.none { it["id"] == "_design/rf" })
        // Every query carries a replayable proof of the run.
        assertTrue(!envelope.proofCid.isNullOrEmpty(), "query receipt carries no proofCid")
    }

    @Test
    fun theEnvelopeCanAddressAStoredViewNotJustAnInlineOne() = runTest {
        val a = Node()
        a.db.put("a", mapOf("type" to "widget", "qty" to 2), null)
        a.db.put("b", mapOf("type" to "widget", "qty" to 5), null)
        a.db.put("c", mapOf("type" to "gadget", "qty" to 7), null)
        a.db.put(
            "_design/rf",
            mapOf("views" to mapOf("by_type" to mapOf(
                "map" to mapOf("key" to "type", "value" to "qty"),
                "reduce" to "_sum",
            ))),
            null,
        )

        // The route's answer for a stored view...
        val route = json(a.router.handle("GET", "/trikeshed/_design/rf/_view/by_type?group=true", ByteArray(0))!!.bytes)
        val routeRows = CouchDatabase.asList(route["rows"])!!.map { it as Map<*, *> }

        // ...and the envelope's, for the same stored view. `query` could only ever take an inline
        // spec, so this was the one report the route could serve and a proxy client could not.
        val proxy = RequestFactoryProxy(RelaxTransport.local(a.db))
        val receipt = proxy.view("_design/rf", "by_type", mapOf("group" to true))
        assertTrue(receipt.ok, "stored view failed: $receipt")
        assertEquals("_design/rf/by_type", receipt.fields["view"])

        val shape = { rows: List<Map<*, *>> -> rows.map { it["key"] to it["value"].toString() } }
        assertEquals(shape(routeRows), shape(receipt.rows), "the two askers disagree on one stored view")

        // ddoc may be spelled with or without the _design/ prefix
        assertEquals(shape(receipt.rows), shape(proxy.view("rf", "by_type", mapOf("group" to true)).rows))
        // and a missing view refuses per-operation rather than failing the batch
        val missing = proxy.view("_design/rf", "nope")
        assertTrue(!missing.ok && missing.error == "not_found", "expected a refusal, got $missing")
    }

    // ── honesty about what a document-only store cannot do ────────

    @Test
    fun laneOperationsRefuseOnADocumentOnlyStore() = runTest {
        val proxy = RequestFactoryProxy(
            RelaxTransport.local(CouchRequestFactory.forConfixStore(ConfixDocStoreFactory.createSequential())),
        )
        // Documents work.
        assertTrue(proxy.put(mapOf("v" to 1), id = "d").ok)
        assertEquals(1L, (proxy.get("d").doc!!["v"] as Number).toLong())

        // The lanes say so rather than answering with something else's truth.
        for (receipt in listOf(
            proxy.changes(),
            proxy.revsDiff(mapOf("d" to listOf("1-x"))),
            proxy.replicate("pull", "http://peer/db"),
            proxy.submit(RelaxOp.BlockGet("sha256:${"0".repeat(64)}")).first,
        )) {
            assertTrue(!receipt.ok, "expected refusal, got $receipt")
            assertEquals("not_implemented", receipt.error, "wrong refusal: $receipt")
        }
    }
}
