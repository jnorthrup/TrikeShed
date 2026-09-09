package borg.trikeshed.forge.server

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.graal.vitals.JvmVitals
import borg.trikeshed.job.CasStore
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SheetClassSubject(val value: Int) {
    fun increment(): Int = value + 1
}

class GraalClassfileSheetTest {
    @Test
    fun classAndSourceAttachmentsProduceNestedStructuralSheets() = runTest {
        val cas = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val database = Couch("test", store, cas)
        val resource = "borg/trikeshed/forge/server/SheetClassSubject.class"
        val id = "projects/trikeshed/build/live/classes/$resource"
        val sourceId = "projects/trikeshed/src/jvmTest/kotlin/borg/trikeshed/forge/server/GraalClassfileSheetTest.kt"
        fun attach(key: String, bytes: ByteArray) {
            val cid = cas.put(bytes)
            assertTrue(store.put(Document(key, listOf(Field("contentId", cid.value),
                Field("length", bytes.size.toString()), Field("contentType", "application/octet-stream")))))
        }
        attach(id, javaClass.classLoader.getResourceAsStream(resource)!!.use { it.readBytes() })
        attach(sourceId, "package borg.trikeshed.forge.server\nclass SheetClassSubject".encodeToByteArray())
        val wire = GraalWire(JvmVitals(), store, null, this, database)
        val response = wire.route("GET", "/api/graal/sheet?id=$id", "", null)!!
        assertEquals(200, response.status)
        @Suppress("UNCHECKED_CAST")
        val sheets = JsonSupport.parse(response.body) as List<Map<String, Any?>>
        val ids = sheets.map { it["id"] }
        assertTrue("$id/classFile" in ids)
        assertTrue("$id/fields" in ids)
        assertTrue("$id/methods" in ids)
        assertTrue("$id/pointcuts" in ids)
        assertTrue(ids.any { it.toString().endsWith("/instructions") })
        assertTrue(response.body.contains("methodDescriptor"))
        val root = sheets.first()["rows"] as List<*>
        assertTrue(root.any { it == listOf("exactRuntimeBlob", true) })
        assertTrue(sheets.drop(1).all { it["parent"] in ids })

        val source = wire.route("GET", "/api/graal/sheet?id=$sourceId", "", null)!!
        assertEquals(200, source.status)
        assertTrue(source.body.contains("$sourceId/mates"))
        assertTrue(source.body.contains("exactRuntimeBlob"))

        attach("bad.class", byteArrayOf(0, 1))
        assertEquals(422, wire.route("GET", "/api/graal/sheet?id=bad.class", "", null)?.status)
        assertEquals(404, wire.route("GET", "/api/graal/sheet?id=missing.class", "", null)?.status)
    }
}
