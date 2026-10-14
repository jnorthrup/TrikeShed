package borg.trikeshed.relaxfactory

import borg.trikeshed.couch.*
import borg.trikeshed.forge.server.CouchWire
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.reify
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.*

class HtxRequestFactoryClientTest {
    @Test
    fun stubEntryPointAndOriginalClientReviveCouchOverTheHtxSocket() = runBlocking(Dispatchers.IO) {
        val cas = CasStore.inMemory()
        val db = Couch("rf", CouchStoreFactory.casBacked(cas), cas)
        db.put("_design/items", mapOf("views" to mapOf("qty" to mapOf(
            "map" to mapOf("key" to "type", "value" to "qty"), "reduce" to "_sum"))), null)
        val processor = GwtCouchRequestFactory(db)
        val gate = RequestFactoryReactorElement({ processor.process(it) }, { processor.close() }, coroutineContext[Job]).also { it.open() }
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner + CoroutineExceptionHandler { _, _ -> })
        val wire = CouchWire(CouchWireRouter(db, "projects/", requestFactoryHandler = gate), null, scope, requestFactory = gate)
        val port = ServerSocket(0).use { it.localPort }
        val state = Files.createTempDirectory("rxf-client-").toFile()
        val server = scope.launch { JvmKanbanServer(rawRoutes = listOf(wire::route), stateDir = state).run(port, null) }
        val endpoint = "http://127.0.0.1:$port/rf/_requestfactory"
        try {
            withTimeout(15_000) {
                while (true) {
                    val ready = try { HtxRequestFactoryClient.run(arrayOf(endpoint, "find", "missing")) == "null" }
                        catch (failure: CancellationException) { throw failure }
                        catch (failure: Exception) { false }
                    if (ready) break
                    delay(20)
                }
            }
            val children = coroutineContext[Job]!!.children.toSet()
            // This is the same public main called by RxfRestStub's Java bootstrap.
            HtxRequestFactoryClient.main(arrayOf(endpoint, "persist", "item", """{"type":"widget","qty":3,"counter":9223372036854775807}"""))
            val first = db.docJson("item")!!["_rev"] as String
            assertNotNull(Couch.revToCid(first))
            assertEquals(Long.MAX_VALUE, db.docJson("item")!!["counter"])
            val loaded = HtxRequestFactoryClient.run(arrayOf("http://127.0.0.1:$port/gwtRequest", "find", "item"))
            assertContains(loaded, Long.MAX_VALUE.toString())
            assertEquals(first, (reify(loaded) as Map<*, *>)["_rev"])

            val updated = reify(HtxRequestFactoryClient.run(arrayOf(endpoint, "persist", "item", """{"type":"widget","qty":8}""", first))) as Map<*, *>
            assertEquals(true, updated["ok"])
            val second = updated["rev"] as String
            assertNotEquals(first, second)
            assertFailsWith<IllegalArgumentException> {
                HtxRequestFactoryClient.run(arrayOf(endpoint, "persist", "item", """{"qty":100}""", first))
            }
            assertEquals(second, db.docJson("item")!!["_rev"])
            val view = reify(HtxRequestFactoryClient.run(arrayOf(endpoint, "view", "items", "qty"))) as Map<*, *>
            assertEquals(8.0, ((view["rows"] as List<*>).single() as Map<*, *>)["value"])
            assertEquals(true, (reify(HtxRequestFactoryClient.run(arrayOf(endpoint, "delete", "item", second))) as Map<*, *>)["ok"])
            assertNull(db.docJson("item"))
            assertEquals("null", HtxRequestFactoryClient.run(arrayOf(endpoint, "find", "item")))
            assertEquals(children, coroutineContext[Job]!!.children.toSet(), "Client commands left owned work open")
        } finally {
            withContext(NonCancellable) {
                gate.drain()
                server.cancelAndJoin()
                owner.cancelAndJoin()
                state.deleteRecursively()
            }
        }
        assertTrue(processor.closed)
    }
}
