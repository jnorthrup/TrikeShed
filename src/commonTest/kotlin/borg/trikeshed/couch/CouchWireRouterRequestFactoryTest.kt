package borg.trikeshed.couch

import borg.trikeshed.job.CasStore
import borg.trikeshed.relaxfactory.RequestFactoryHandler
import borg.trikeshed.relaxfactory.RequestFactoryReactorElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CouchWireRouterRequestFactoryTest {
    fun database(): Couch {
        val cas = CasStore.inMemory()
        return Couch("trike", CouchStoreFactory.casBacked(cas), cas)
    }

    @Test
    fun dedicatedAndLegacyMountsPreserveTheOriginalProtocolPayloadAndResponse() = runTest {
        val payload = """{ "F": "factory", "I": [], "O": [] }"""
        val response = """{ "E": {"M":"protocol failure"} }"""
        val received = mutableListOf<String>()
        val router = CouchWireRouter(database(), "", requestFactoryHandler = object : RequestFactoryHandler {
            override suspend fun processRequest(payload: String): String {
                received += payload
                return response
            }
        })
        for (path in listOf("/gwtRequest", "/trike/_requestfactory", "/trike/_relax", "/trike")) {
            val reply = router.handle("POST", path, payload.encodeToByteArray(), "application/json; charset=UTF-8")!!
            assertEquals(200, reply.status)
            assertEquals("application/json; charset=utf-8", reply.contentType)
            assertContentEquals(response.encodeToByteArray(), reply.bytes)
        }
        assertEquals(List(4) { payload }, received)
    }

    @Test
    fun contextLookupUsesTheOwnedProcessorChannel() = runTest {
        val element = RequestFactoryReactorElement(processor = { "{\"reply\":$it}" })
        element.open()
        val reply = withContext(element) {
            CouchWireRouter(database(), "").handle("POST", "/trike/_requestfactory", "{}".encodeToByteArray())!!
        }
        assertEquals("{\"reply\":{}}", reply.bytes.decodeToString())
        element.drain()
    }

    @Test
    fun missingProcessorAndHttpContractFailuresRemainExplicit() = runTest {
        val router = CouchWireRouter(database(), "")
        val payload = """{"F":"factory","I":[],"O":[]}""".encodeToByteArray()
        assertEquals(501, router.handle("POST", "/trike/_requestfactory", payload)!!.status)
        assertEquals(501, router.handle("POST", "/trike/_relax", payload)!!.status)
        assertEquals(405, router.handle("GET", "/gwtRequest", payload)!!.status)
        assertEquals(415, router.handle("POST", "/gwtRequest", payload, "text/plain")!!.status)
        assertEquals(400, router.handle("POST", "/gwtRequest", "{".encodeToByteArray())!!.status)
        assertEquals(400, router.handle("POST", "/gwtRequest", byteArrayOf(0xff.toByte()))!!.status)
    }

    @Test
    fun couchOperationsAndDocumentsWithIoFieldsKeepTheirExistingBehavior() = runTest {
        val db = database()
        val router = CouchWireRouter(db, "")
        val document = """{"_id":"doc","I":[],"O":[]}""".encodeToByteArray()
        assertEquals(201, router.handle("POST", "/trike", document)!!.status)
        val envelope = """{"operations":[{"op":"get","id":"doc"}]}""".encodeToByteArray()
        val reply = router.handle("POST", "/trike/_relax", envelope)!!
        assertEquals(200, reply.status)
        assertEquals(true, (borg.trikeshed.parse.reify(reply.bytes) as Map<*, *>)["ok"])
    }

    @Test
    fun processorExceptionsAreHttpFailuresAndCancellationPropagates() = runTest {
        val handler = object : RequestFactoryHandler {
            override suspend fun processRequest(payload: String): String {
                if (payload.contains("cancel")) throw CancellationException("caller canceled")
                error("processor failed")
            }
        }
        val router = CouchWireRouter(database(), "", requestFactoryHandler = handler)
        assertEquals(500, router.handle("POST", "/gwtRequest", "{}".encodeToByteArray())!!.status)
        val failure = runCatching { router.handle("POST", "/gwtRequest", "{\"cancel\":true}".encodeToByteArray()) }
        assertIs<CancellationException>(failure.exceptionOrNull())
    }
}
