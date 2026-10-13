package borg.trikeshed.relaxfactory

import borg.trikeshed.couch.*
import borg.trikeshed.forge.server.CouchWire
import borg.trikeshed.htx.*
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.reify
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import com.google.web.bindery.event.shared.SimpleEventBus
import com.google.web.bindery.requestfactory.shared.*
import com.google.web.bindery.requestfactory.vm.RequestFactorySource
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.*

/** The original RF JVM client reaches the processor through the daemon's actual HTX wire. */
class GwtRequestFactorySocketTest {
    class Result<T> : Receiver<T>() {
        val receipt = CompletableDeferred<T?>()
        override fun onSuccess(response: T?) { receipt.complete(response) }
        override fun onFailure(error: ServerFailure) { receipt.completeExceptionally(IllegalStateException(error.message)) }
    }

    class Transport(
        val scope: CoroutineScope,
        val client: HtxClientReactorElement,
        val base: String,
        var mount: String,
    ) : RequestTransport {
        override fun send(payload: String, receiver: RequestTransport.TransportReceiver) {
            scope.launch {
                try {
                    val response = client.request(parseHtxRequest(base + mount, HtxMethod.POST, body = ByteSeries(payload))
                        .withHeader("Content-Type", "application/json; charset=utf-8"))
                    if (response.status == 200) receiver.onTransportSuccess(response.body.asString())
                    else receiver.onTransportFailure(ServerFailure("RequestFactory HTTP ${response.status}"))
                } catch (failure: CancellationException) {
                    receiver.onTransportFailure(ServerFailure("RequestFactory transport cancelled"))
                    throw failure
                } catch (failure: Exception) {
                    receiver.onTransportFailure(ServerFailure(failure.message))
                }
            }
        }
    }

    suspend fun <T> result(request: Request<T>): T? = Result<T>().let { receiver ->
        request.fire(receiver)
        withTimeout(15_000) { receiver.receipt.await() }
    }

    @Test
    fun originalProtocolAndLiveConfixScriptRunThroughTheHtxListener() = runBlocking(Dispatchers.IO) {
        val cas = CasStore.inMemory()
        val db = Couch("rf", CouchStoreFactory.casBacked(cas), cas)
        db.put("_design/items", mapOf("views" to mapOf("qty" to mapOf(
            "map" to mapOf("key" to "type", "value" to "qty"), "reduce" to "_sum"))), null)
        val report = CouchReportReactorElement(parentJob = coroutineContext[Job]).also { it.open() }
        val bridge = ConfixDynInvoke().also {
            it.registerScript("increment", "js", "(doc, amount) => { doc.qty += amount; return {qty: doc.qty}; }")
        }
        val processor = GwtCouchRequestFactory(db, bridge, viewServer = ViewServer(report))
        val gate = RequestFactoryReactorElement({ processor.process(it) }, { processor.close() }, coroutineContext[Job]).also { it.open() }
        val router = CouchWireRouter(db, "projects/", report = report, requestFactoryHandler = gate)
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + owner + CoroutineExceptionHandler { _, _ -> })
        val wire = CouchWire(router, null, scope, requestFactory = gate)
        val port = ServerSocket(0).use { it.localPort }
        val state = Files.createTempDirectory("rf-htx-").toFile()
        val server = scope.launch { JvmKanbanServer(rawRoutes = listOf(wire::route), stateDir = state).run(port, null) }
        val reactor = HtxReactorElement(channelOperations = JvmChannelOperations(), parentJob = coroutineContext[Job]).also { it.open() }
        val client = openHtxClientReactorElement(routeService = reactor)
        val base = "http://127.0.0.1:$port"
        try {
            withTimeout(15_000) {
                while (true) {
                    val response = try { client.request(parseHtxRequest("$base/rf")) } catch (failure: Exception) { null }
                    if (response?.status == 200) break
                    delay(20)
                }
            }
            val transport = Transport(scope, client, base, "/rf/_requestfactory")
            val rf = RequestFactorySource.create(CouchGwtFactory::class.java).also { it.initialize(SimpleEventBus(), transport) }
            val create = rf.couch()
            val document = create.create(CouchDocumentProxy::class.java)
            document.setId("item")
            document.setJson("""{"type":"widget","qty":3,"nested":{"label":"café"}}""")
            val saved = assertNotNull(result(create.persist(document)))
            assertEquals(db.docJson("item")!!["_rev"], saved.getRev())
            assertNotNull(Couch.revToCid(assertNotNull(saved.getRev())))

            // The original mount and the quasi-RelaxFactory mount reach the same processor.
            for (mount in s_["/gwtRequest", "/rf/_relax"].view) {
                transport.mount = mount
                assertEquals(saved.getRev(), assertNotNull(result(rf.couch().get("item"))).getRev())
            }

            val context = rf.couch()
            val edited = context.edit(saved)
            val invoked = Result<String>()
            val persisted = Result<CouchDocumentProxy>()
            context.call("increment", edited, "[5]").to(invoked)
            context.persist(edited).to(persisted)
            context.fire()
            val script = reify(withTimeout(15_000) { invoked.receipt.await() }!!) as Map<*, *>
            val revised = assertNotNull(withTimeout(15_000) { persisted.receipt.await() })
            assertEquals(8.0, script["qty"])
            assertEquals(8.0, db.docJson("item")!!["qty"])
            assertNotEquals(saved.getRev(), revised.getRev())
            assertTrue(bridge.callRoot.invoke.hits > 0, "RF calls did not execute the specialized Truffle node")

            val view = reify(assertNotNull(result(rf.couch().storedView("items", "qty", "")))) as Map<*, *>
            val row = (view["rows"] as List<*>).single() as Map<*, *>
            assertNull(row["key"])
            assertEquals(8.0, row["value"])
            assertTrue(report.reportState.value.reductions > 0)
            val changes = client.request(parseHtxRequest("$base/rf/_changes?include_docs=true"))
            assertEquals(3, ((reify(changes.body.asString()) as Map<*, *>)["results"] as List<*>).size)

            val wrongType = client.request(parseHtxRequest("$base/gwtRequest", HtxMethod.POST,
                body = ByteSeries("""{"F":"${CouchGwtFactory::class.java.name}"}""")).withHeader("Content-Type", "text/plain"))
            assertEquals(415, wrongType.status)
        } finally {
            withContext(NonCancellable) {
                gate.drain()
                client.close()
                reactor.close()
                server.cancelAndJoin()
                owner.cancelAndJoin()
                report.close()
                state.deleteRecursively()
            }
        }
        assertTrue(processor.closed)
        assertTrue(bridge.closed)
    }
}
