package borg.trikeshed.relaxfactory

import borg.trikeshed.couch.*
import borg.trikeshed.job.CasStore
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reify
import com.google.web.bindery.event.shared.SimpleEventBus
import com.google.web.bindery.requestfactory.shared.*
import com.google.web.bindery.requestfactory.vm.RequestFactorySource
import com.google.web.bindery.requestfactory.vm.impl.Deobfuscator
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Only the original JVM client's proxy token lookup needs this test metadata. */
class CouchGwtFactoryDeobfuscatorBuilderLite : Deobfuscator.Builder() {
    init {
        withRawTypeToken(RequestFactorySchema.proxyToken(CouchDocumentProxy::class.java), CouchDocumentProxy::class.java.name)
    }
}

interface CouchFixtureFactory : RequestFactory {
    fun couch(): CouchFixtureContext
}

@Service(RelaxFactoryDomain::class)
interface CouchFixtureContext : RequestContext {
    fun get(id: String): Request<CouchFixtureProxy>
    fun persist(document: CouchFixtureProxy): Request<CouchFixtureProxy>
    fun delete(document: CouchFixtureProxy): Request<Boolean>
    fun call(operation: String, document: CouchFixtureProxy, arguments: String): Request<String>
}

@ProxyFor(ConfixBean::class)
interface CouchFixtureProxy : EntityProxy {
    fun getId(): String?
    fun setId(value: String?)
    fun getRev(): String?
    fun setRev(value: String?)
    fun getType(): String?
    fun setType(value: String?)
    fun getQty(): Int?
    fun setQty(value: Int?)
}

class CouchFixtureFactoryDeobfuscatorBuilderLite : Deobfuscator.Builder() {
    init {
        withRawTypeToken(RequestFactorySchema.proxyToken(CouchFixtureProxy::class.java), CouchFixtureProxy::class.java.name)
    }
}

class GwtCouchRequestFactoryTest {
    class Result<T> : Receiver<T>() {
        var value: T? = null
        var failure: ServerFailure? = null
        var completed = false
        override fun onSuccess(response: T?) { value = response; completed = true }
        override fun onFailure(error: ServerFailure) { failure = error; completed = true }
    }

    class Transport(val server: GwtCouchRequestFactory) : RequestTransport {
        val requests = mutableListOf<String>()
        val responses = mutableListOf<String>()
        override fun send(payload: String, receiver: RequestTransport.TransportReceiver) {
            requests.add(payload)
            val response = server.process(payload)
            responses.add(response)
            receiver.onTransportSuccess(response)
        }
    }

    fun database(): Couch = CasStore.inMemory().let { Couch("rf", CouchStoreFactory.casBacked(it), it) }

    fun <T : RequestFactory> client(type: Class<T>, transport: Transport): T =
        RequestFactorySource.create(type).also { it.initialize(SimpleEventBus(), transport) }

    fun <T> result(request: Request<T>): Result<T> = Result<T>().also { request.fire(it); assertTrue(it.completed) }

    @Test
    fun originalClientPersistsUpdatesLoadsAndDeletesCanonicalCouchEntities() = runBlocking {
        val db = database()
        GwtCouchRequestFactory(db).use { server ->
            val wire = Transport(server)
            val rf = client(CouchGwtFactory::class.java, wire)
            val create = rf.couch()
            val document = create.create(CouchDocumentProxy::class.java)
            document.setId("item")
            document.setJson("""{"type":"widget","qty":3,"nested":{"label":"café"}}""")
            val saved = assertNotNull(result(create.persist(document)).value)
            val firstRev = assertNotNull(saved.getRev())
            assertEquals("item", saved.getId())
            assertEquals(firstRev, db.docJson("item")?.get("_rev"))
            assertTrue(firstRev.contains("sha256:"))
            assertEquals("café", (db.docJson("item")?.get("nested") as Map<*, *>)["label"])

            val original = reify(wire.requests.first()) as Map<*, *>
            assertEquals(CouchGwtFactory::class.java.name, original["F"])
            assertTrue(original["I"] is List<*>)
            assertTrue(original["O"] is List<*>)
            assertFalse(original.containsKey("operations"))
            val persistence = (reify(wire.responses.first()) as Map<*, *>)["O"] as List<*>
            assertEquals("PERSIST", (persistence.single() as Map<*, *>)["O"])

            val update = rf.couch()
            val edited = update.edit(saved)
            edited.setJson("""{"type":"widget","qty":7}""")
            val revised = assertNotNull(result(update.persist(edited)).value)
            assertNotEquals(firstRev, revised.getRev())
            assertFalse(db.docJson("item")!!.containsKey("nested"))
            val loaded = assertNotNull(result(rf.couch().get("item")).value)
            assertEquals(revised.getRev(), loaded.getRev())
            assertEquals(7.0, (reify(loaded.getJson()!!) as Map<*, *>)["qty"])
            assertEquals(2, (db.changes(0, 100, true)["results"] as List<*>).size)

            val deletion = rf.couch()
            assertEquals(true, result(deletion.delete(deletion.edit(loaded))).value)
            assertNull(db.docJson("item"))
            assertNull(result(rf.couch().get("item")).value)
            val operations = (reify(wire.responses[wire.responses.lastIndex - 1]) as Map<*, *>)["O"] as List<*>
            assertEquals("DELETE", (operations.single() as Map<*, *>)["O"])
            assertEquals(3, (db.changes(0, 100, true)["results"] as List<*>).size)
        }
    }

    @Test
    fun staleOriginalClientVersionFailsCouchCompareAndSwap() {
        val db = database()
        db.put("item", mapOf("qty" to 1), null)
        GwtCouchRequestFactory(db).use { server ->
            val wire = Transport(server)
            val rf = client(CouchGwtFactory::class.java, wire)
            val loaded = assertNotNull(result(rf.couch().get("item")).value)
            val revision = db.docJson("item")!!["_rev"] as String
            db.put("item", mapOf("qty" to 2), revision)
            val context = rf.couch()
            val stale = context.edit(loaded)
            stale.setJson("""{"qty":99}""")
            val response = result(context.persist(stale))
            assertNotNull(response.failure)
            assertEquals(2.0, db.docJson("item")!!["qty"])
            assertEquals(listOf(false), (reify(wire.responses.last()) as Map<*, *>)["S"])
        }
    }

    @Test
    fun storedViewsUseTheSameCouchDecoratorAndDesignDocument() {
        val db = database()
        db.put("_design/items", mapOf("views" to mapOf("qty" to mapOf("map" to mapOf("key" to "type", "value" to "qty"), "reduce" to "_sum"))), null)
        db.put("a", mapOf("type" to "widget", "qty" to 3), null)
        db.put("b", mapOf("type" to "widget", "qty" to 7), null)
        GwtCouchRequestFactory(db).use { server ->
            val rf = client(CouchGwtFactory::class.java, Transport(server))
            val response = result(rf.couch().storedView("items", "qty", "group=true"))
            val rows = (reify(assertNotNull(response.value)) as Map<*, *>)["rows"] as List<*>
            assertEquals("widget", (rows.single() as Map<*, *>)["key"])
            assertEquals(10.0, (rows.single() as Map<*, *>)["value"])
        }
    }

    @Test
    fun declaredProxyPropertiesAndRegisteredPolyglotOperationShareTheConfixReceiver() {
        val db = database()
        db.put("a", mapOf("type" to "widget", "qty" to 3), null)
        val bridge = ConfixDynInvoke()
        bridge.registerScript("increment", "js", "(doc, delta) => { doc.qty = doc.qty + delta; return { qty: doc.qty, type: doc.type }; }")
        GwtCouchRequestFactory(db, bridge, RequestFactorySchema(CouchFixtureFactory::class.java)).use { server ->
            val rf = client(CouchFixtureFactory::class.java, Transport(server))
            val loaded = assertNotNull(result(rf.couch().get("a")).value)
            assertEquals(3, loaded.getQty())
            assertEquals("widget", loaded.getType())
            val context = rf.couch()
            val edited = context.edit(loaded)
            edited.setType("part")
            val invocation = Result<String>()
            val persisted = Result<CouchFixtureProxy>()
            context.call("increment", edited, "[4]").to(invocation)
            context.persist(edited).to(persisted)
            context.fire()
            assertNull(invocation.failure)
            assertNull(persisted.failure)
            val script = reify(assertNotNull(invocation.value)) as Map<*, *>
            assertEquals(7.0, script["qty"])
            assertEquals("part", script["type"])
            assertEquals(7, assertNotNull(persisted.value).getQty())
            assertEquals(7.0, db.docJson("a")!!["qty"])
            assertEquals("part", db.docJson("a")!!["type"])
        }
    }

    @Test
    fun unregisteredFactoryAndProxyTokensCannotAdmitClasses() {
        GwtCouchRequestFactory(database()).use { server ->
            val failure = reify(server.process(jsonOf(mapOf("F" to "java.lang.Runtime")))) as Map<*, *>
            assertTrue(failure["F"] is Map<*, *>)
            val proxyFailure = reify(server.process(jsonOf(mapOf("F" to CouchGwtFactory::class.java.name, "O" to listOf(mapOf("T" to "java.lang.Runtime", "C" to 1)))))) as Map<*, *>
            assertTrue(proxyFailure["F"] is Map<*, *>)
        }
    }
}
