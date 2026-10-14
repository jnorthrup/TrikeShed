package borg.trikeshed.relaxfactory

import borg.trikeshed.couch.*
import borg.trikeshed.job.CasStore
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reify
import com.google.web.bindery.event.shared.SimpleEventBus
import com.google.web.bindery.requestfactory.server.ServiceLayer
import com.google.web.bindery.requestfactory.shared.*
import com.google.web.bindery.requestfactory.vm.RequestFactorySource
import com.google.web.bindery.requestfactory.vm.impl.Deobfuscator
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Date
import kotlin.test.*

interface CouchFixtureFactory : RequestFactory {
    fun couch(): CouchFixtureContext
}

@Service(RelaxFactoryDomain::class)
interface CouchFixtureContext : RequestContext {
    fun get(id: String): Request<CouchFixtureProxy>
    fun persist(document: CouchFixtureProxy): Request<CouchFixtureProxy>
    fun delete(document: CouchFixtureProxy): Request<Boolean>
    fun call(operation: String, document: CouchFixtureProxy, arguments: String): Request<String>
    fun adjust(document: CouchFixtureProxy, amount: Int): Request<CouchProfileProxy>
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
    fun getProfile(): CouchProfileProxy?
    fun setProfile(value: CouchProfileProxy?)
    fun getCounter(): Long?
    fun setCounter(value: Long?)
    fun getAmount(): BigDecimal?
    fun setAmount(value: BigDecimal?)
    fun getMagnitude(): BigInteger?
    fun setMagnitude(value: BigInteger?)
    fun getRecordedAt(): Date?
    fun setRecordedAt(value: Date?)
    fun getRelated(): List<CouchFixtureProxy>?
    fun setRelated(value: List<CouchFixtureProxy>?)
    fun getProfiles(): List<CouchProfileProxy>?
    fun setProfiles(value: List<CouchProfileProxy>?)
}

@ProxyFor(ConfixBean::class)
interface CouchProfileProxy : ValueProxy {
    fun getQty(): String?
    fun setQty(value: String?)
    fun getScores(): List<Int>?
    fun setScores(value: List<Int>?)
    fun getCodes(): Set<String>?
    fun setCodes(value: Set<String>?)
    fun getAttributes(): Map<String, Int>?
    fun setAttributes(value: Map<String, Int>?)
    fun getAmounts(): List<BigDecimal>?
    fun setAmounts(value: List<BigDecimal>?)
}

class CouchFixtureFactoryDeobfuscatorBuilderLite : Deobfuscator.Builder() {
    init {
        withRawTypeToken(RequestFactorySchema.proxyToken(CouchFixtureProxy::class.java), CouchFixtureProxy::class.java.name)
        withRawTypeToken(RequestFactorySchema.proxyToken(CouchProfileProxy::class.java), CouchProfileProxy::class.java.name)
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

    class Transport(val server: GwtCouchRequestFactory, val before: (String) -> Unit = {}) : RequestTransport {
        val requests = mutableListOf<String>()
        val responses = mutableListOf<String>()
        override fun send(payload: String, receiver: RequestTransport.TransportReceiver) {
            requests.add(payload)
            before(payload)
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
            assertEquals(2, (db.docJson("item")!!["qty"] as Number).toInt())
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
            assertEquals(7, (db.docJson("a")!!["qty"] as Number).toInt())
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

    @Test
    fun nestedValueProxyEditsAndGenericCollectionsPersistThroughTheOriginalClient() {
        val db = database()
        db.put("a", mapOf("qty" to 3, "profile" to mapOf("qty" to "three", "scores" to listOf(1, 2), "codes" to listOf("a", "b"), "attributes" to mapOf("rank" to 4))), null)
        GwtCouchRequestFactory(db, schema = RequestFactorySchema(CouchFixtureFactory::class.java)).use { server ->
            val rf = client(CouchFixtureFactory::class.java, Transport(server))
            val loaded = assertNotNull(result(rf.couch().get("a").with("profile")).value)
            val profile = assertNotNull(loaded.getProfile())
            assertEquals(3, loaded.getQty())
            assertEquals("three", profile.getQty())
            assertEquals(listOf(1, 2), profile.getScores())
            assertEquals(setOf("a", "b"), profile.getCodes())
            assertEquals(mapOf("rank" to 4), profile.getAttributes())
            val context = rf.couch()
            val parent = context.edit(loaded)
            val nested = context.edit(profile)
            nested.setQty("four")
            nested.setScores(listOf(4, 5))
            nested.setCodes(setOf("c"))
            nested.setAttributes(mapOf("rank" to 6))
            nested.setAmounts(listOf(BigDecimal("123456789012345678901234567890.0012300")))
            parent.setProfile(nested)
            val saved = assertNotNull(result(context.persist(parent).with("profile")).value)
            assertEquals("four", saved.getProfile()!!.getQty())
            assertEquals(listOf(4, 5), saved.getProfile()!!.getScores())
            assertEquals(listOf(BigDecimal("123456789012345678901234567890.0012300")), saved.getProfile()!!.getAmounts())
            val couch = db.docJson("a")!!["profile"] as Map<*, *>
            assertEquals("four", couch["qty"])
            assertEquals(listOf(4, 5), (couch["scores"] as List<*>).map { (it as Number).toInt() })
            assertEquals(6, ((couch["attributes"] as Map<*, *>)["rank"] as Number).toInt())
            assertEquals(listOf("123456789012345678901234567890.0012300"), couch["amounts"])
        }
    }

    @Test
    fun anotherProcessorsGlobalResolverCacheCannotAdmitAnUnregisteredSchema() {
        val db = database()
        db.put("a", mapOf("qty" to 3), null)
        var payload = ""
        GwtCouchRequestFactory(db, schema = RequestFactorySchema(CouchFixtureFactory::class.java)).use { server ->
            // Populate the real upstream process-global cache, then test another schema's outer gate.
            assertEquals(CouchFixtureFactory::class.java, ServiceLayer.create(server.decorator).resolveRequestFactory(CouchFixtureFactory::class.java.name))
            val wire = Transport(server)
            result(client(CouchFixtureFactory::class.java, wire).couch().get("a"))
            payload = wire.requests.single()
        }
        GwtCouchRequestFactory(db).use { server ->
            assertTrue((reify(server.process(payload)) as Map<*, *>)["F"] is Map<*, *>)
        }
    }

    @Test
    fun registeredContextMethodDispatchesTypedPolyglotArgumentsAndValueProxyResults() {
        val db = database()
        db.put("a", mapOf("qty" to 3), null)
        val schema = RequestFactorySchema(CouchFixtureFactory::class.java)
        val operation = schema.operations.entries.single { it.value.method.name == "adjust" }.key
        val bridge = ConfixDynInvoke()
        bridge.registerScript(operation, "js", "(doc, delta) => ({ qty: String(doc.qty + delta), scores: [doc.qty, doc.qty + delta], codes: ['script'], attributes: { total: doc.qty + delta } })")
        GwtCouchRequestFactory(db, bridge, schema).use { server ->
            val wire = Transport(server)
            val rf = client(CouchFixtureFactory::class.java, wire)
            val loaded = assertNotNull(result(rf.couch().get("a")).value)
            val reply = result(rf.couch().adjust(loaded, 4))
            assertNull(reply.failure)
            val value = assertNotNull(reply.value)
            assertEquals("7", value.getQty())
            assertEquals(listOf(3, 7), value.getScores())
            assertEquals(setOf("script"), value.getCodes())
            assertEquals(mapOf("total" to 7), value.getAttributes())
            val request = reify(wire.requests.last()) as Map<*, *>
            assertEquals(operation, ((request["I"] as List<*>).single() as Map<*, *>)["O"])
        }
    }

    @Test
    fun malformedProtocolMetadataReturnsNativeRequestFactoryFailures() {
        GwtCouchRequestFactory(database()).use { server ->
            val malformed = reify(server.process("{")) as Map<*, *>
            assertTrue(malformed["F"] is Map<*, *>)
            val id = com.google.gwt.user.server.Base64Utils.toBase64(jsonOf("missing").encodeToByteArray())
            for (invalid in listOf("bad", "!!!!", "AA=A", "AAAA=")) {
                val invalidVersion = jsonOf(mapOf("F" to CouchGwtFactory::class.java.name, "O" to listOf(mapOf("T" to RequestFactorySchema.proxyToken(CouchDocumentProxy::class.java), "S" to id, "V" to invalid))))
                assertTrue((reify(server.process(invalidVersion)) as Map<*, *>)["F"] is Map<*, *>)
            }
        }
    }

    @Test
    fun aScriptSeesNestedProxyEditsAndItsMutationsReachTheSameCouchCommit() {
        val db = database()
        db.put("a", mapOf("qty" to 3, "profile" to mapOf("qty" to "before", "scores" to listOf(1))), null)
        val schema = RequestFactorySchema(CouchFixtureFactory::class.java)
        val operation = schema.operations.entries.single { it.value.method.name == "adjust" }.key
        val bridge = ConfixDynInvoke()
        bridge.registerScript(operation, "js", "(doc, delta) => { if (doc.profile.qty !== 'edited') throw Error('stale nested receiver'); doc.profile.qty = 'script'; doc.profile.scores = [delta]; return doc.profile; }")
        GwtCouchRequestFactory(db, bridge, schema).use { server ->
            val rf = client(CouchFixtureFactory::class.java, Transport(server))
            val loaded = assertNotNull(result(rf.couch().get("a").with("profile")).value)
            val context = rf.couch()
            val parent = context.edit(loaded)
            val child = context.edit(loaded.getProfile()!!)
            child.setQty("edited")
            parent.setProfile(child)
            val script = Result<CouchProfileProxy>()
            val saved = Result<CouchFixtureProxy>()
            context.adjust(parent, 9).to(script)
            context.persist(parent).with("profile").to(saved)
            context.fire()
            assertNull(script.failure)
            assertNull(saved.failure)
            assertEquals("script", script.value!!.getQty())
            assertEquals(listOf(9), script.value!!.getScores())
            assertEquals("script", saved.value!!.getProfile()!!.getQty())
            val profile = db.docJson("a")!!["profile"] as Map<*, *>
            assertEquals("script", profile["qty"])
            assertEquals(listOf(9), (profile["scores"] as List<*>).map { (it as Number).toInt() })
        }
    }

    @Test
    fun declaredLongSurvivesOriginalProtocolAndCanonicalCouchCbor() {
        val db = database()
        GwtCouchRequestFactory(db, schema = RequestFactorySchema(CouchFixtureFactory::class.java)).use { server ->
            val rf = client(CouchFixtureFactory::class.java, Transport(server))
            val context = rf.couch()
            val document = context.create(CouchFixtureProxy::class.java)
            document.setId("counter")
            document.setCounter(Long.MAX_VALUE - 7)
            val saved = assertNotNull(result(context.persist(document)).value)
            assertEquals(Long.MAX_VALUE - 7, saved.getCounter())
            assertEquals(Long.MAX_VALUE - 7, (db.docJson("counter")!!["counter"] as Number).toLong())
            val loaded = assertNotNull(result(rf.couch().get("counter")).value)
            assertEquals(Long.MAX_VALUE - 7, loaded.getCounter())
            val update = rf.couch()
            val edited = update.edit(loaded)
            edited.setCounter(Long.MAX_VALUE - 8)
            assertEquals(Long.MAX_VALUE - 8, assertNotNull(result(update.persist(edited)).value).getCounter())
            assertEquals(Long.MAX_VALUE - 8, (db.docJson("counter")!!["counter"] as Number).toLong())
        }
    }

    @Test
    fun declaredArbitraryPrecisionAndDateScalarsRoundtripWithoutCborNumberDemotion() {
        val db = database()
        val amount = BigDecimal("123456789012345678901234567890.0012300")
        val magnitude = BigInteger("12345678901234567890123456789012345678901234567890")
        val date = Date(1_700_000_000_123)
        GwtCouchRequestFactory(db, schema = RequestFactorySchema(CouchFixtureFactory::class.java)).use { server ->
            val rf = client(CouchFixtureFactory::class.java, Transport(server))
            val context = rf.couch()
            val document = context.create(CouchFixtureProxy::class.java)
            document.setId("scalars")
            document.setAmount(amount)
            document.setMagnitude(magnitude)
            document.setRecordedAt(date)
            val saved = assertNotNull(result(context.persist(document)).value)
            assertEquals(amount, saved.getAmount())
            assertEquals(magnitude, saved.getMagnitude())
            assertEquals(date, saved.getRecordedAt())
            val loaded = assertNotNull(result(rf.couch().get("scalars")).value)
            assertEquals(amount, loaded.getAmount())
            assertEquals(magnitude, loaded.getMagnitude())
            assertEquals(date, loaded.getRecordedAt())
            val couch = db.docJson("scalars")!!
            assertEquals(amount.toString(), couch["amount"])
            assertEquals(magnitude.toString(), couch["magnitude"])
            assertEquals(date.time, (couch["recordedAt"] as Number).toLong())
        }
    }

    @Test
    fun aDeadEntityDecodeFailureDoesNotChangeTheFollowingInvocationsMetadata() {
        val db = database()
        db.put("doomed", mapOf("qty" to 1), null)
        db.put("live", mapOf("qty" to 2), null)
        GwtCouchRequestFactory(db).use { server ->
            var remove = false
            val wire = Transport(server) {
                if (remove) {
                    db.delete("doomed", db.docJson("doomed")!!["_rev"] as String)
                    remove = false
                }
            }
            val rf = client(CouchGwtFactory::class.java, wire)
            val doomed = assertNotNull(result(rf.couch().get("doomed")).value)
            val context = rf.couch()
            val failed = Result<CouchDocumentProxy>()
            val succeeded = Result<CouchDocumentProxy>()
            context.persist(context.edit(doomed)).to(failed)
            context.get("live").to(succeeded)
            remove = true
            context.fire()
            assertNotNull(failed.failure)
            assertNull(succeeded.failure)
            assertEquals("live", succeeded.value!!.getId())
            assertEquals(listOf(false, true), (reify(wire.responses.last()) as Map<*, *>)["S"])
        }
    }

    @Test
    fun namedNativeCallReceivesExactIndexedArgumentNumbers() {
        val db = database()
        db.put("a", mapOf("qty" to 1), null)
        val bridge = ConfixDynInvoke()
        var received: Any? = null
        bridge.register("echo") { args -> args[1].also { received = it } }
        GwtCouchRequestFactory(db, bridge).use { server ->
            val rf = client(CouchGwtFactory::class.java, Transport(server))
            val document = assertNotNull(result(rf.couch().get("a")).value)
            assertEquals(Long.MAX_VALUE.toString(), result(rf.couch().call("echo", document, "[${Long.MAX_VALUE}]")).value)
            assertEquals(Long.MAX_VALUE, received)
            val decimal = BigDecimal("123456789012345678901234567890.0012300")
            assertEquals(jsonOf(decimal.toString()), result(rf.couch().call("echo", document, "[$decimal]")).value)
            assertEquals(decimal, received)
        }
    }

    @Test
    fun reorderedEntityReferencesRetainTheChildsCouchIdentityForPersistence() {
        val db = database()
        db.put("a", mapOf("qty" to 1), null)
        db.put("b", mapOf("qty" to 2), null)
        db.put("parent", mapOf("related" to listOf(db.docJson("a")!!, db.docJson("b")!!)), null)
        val bridge = ConfixDynInvoke()
        bridge.register("reverse") { args ->
            val document = args[0] as ConfixBean
            val related = bridge.get(document, "related", List::class.java) as List<*>
            bridge.set(document, "related", related.reversed())
            true
        }
        GwtCouchRequestFactory(db, bridge, RequestFactorySchema(CouchFixtureFactory::class.java)).use { server ->
            val wire = Transport(server)
            val rf = client(CouchFixtureFactory::class.java, wire)
            val loaded = assertNotNull(result(rf.couch().get("parent").with("related")).value)
            val context = rf.couch()
            val parent = context.edit(loaded)
            val child = context.edit(loaded.getRelated()!!.first())
            child.setQty(11)
            parent.setRelated(listOf(child, context.edit(loaded.getRelated()!![1])))
            val reordered = Result<String>()
            val savedChild = Result<CouchFixtureProxy>()
            val savedParent = Result<CouchFixtureProxy>()
            context.call("reverse", parent, "[]").to(reordered)
            context.persist(child).to(savedChild)
            context.persist(parent).with("related").to(savedParent)
            context.fire()
            assertNull(reordered.failure)
            assertNull(savedChild.failure)
            assertNull(savedParent.failure)
            assertEquals("a", savedChild.value!!.getId())
            assertEquals(listOf("b", "a"), savedParent.value!!.getRelated()!!.map { it.getId() })
            assertEquals(11, (db.docJson("a")!!["qty"] as Number).toInt())
            assertEquals(2, (db.docJson("b")!!["qty"] as Number).toInt())
            assertEquals(listOf(true, true, true), (reify(wire.responses.last()) as Map<*, *>)["S"])
        }
    }

    @Test
    fun modifyingOneRepeatedValueProxyListSlotDoesNotOverwriteItsSibling() {
        val db = database()
        val schema = RequestFactorySchema(CouchFixtureFactory::class.java)
        val operation = schema.operations.entries.single { it.value.method.name == "adjust" }.key
        val bridge = ConfixDynInvoke()
        bridge.registerScript(operation, "js", "(doc, ignored) => { doc.profiles[0].qty = 'after'; return doc.profiles[0]; }")
        GwtCouchRequestFactory(db, bridge, schema).use { server ->
            val rf = client(CouchFixtureFactory::class.java, Transport(server))
            val create = rf.couch()
            val parent = create.create(CouchFixtureProxy::class.java)
            val profile = create.create(CouchProfileProxy::class.java)
            parent.setId("parent")
            profile.setQty("before")
            parent.setProfiles(listOf(profile, profile))
            val loaded = assertNotNull(result(create.persist(parent).with("profiles")).value)
            val update = rf.couch()
            val edited = update.edit(loaded)
            val script = Result<CouchProfileProxy>()
            val saved = Result<CouchFixtureProxy>()
            update.adjust(edited, 0).to(script)
            update.persist(edited).with("profiles").to(saved)
            update.fire()
            assertNull(script.failure)
            assertNull(saved.failure)
            assertEquals(listOf("after", "before"), saved.value!!.getProfiles()!!.map { it.getQty() })
            assertEquals(listOf("after", "before"), (db.docJson("parent")!!["profiles"] as List<*>).map { (it as Map<*, *>)["qty"] })
        }
    }
}
