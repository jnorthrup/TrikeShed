package borg.trikeshed.forge.server

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.CouchWireRouter
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.graal.vitals.JvmVitals
import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class GraalWireDocumentSafetySubject {
    fun touch(): String = "ok"
}

class GraalWireDocumentSafetyTest {
    @Test
    fun graalDocRedactsCredentialFieldsAndBlocksContentPreview() = runTest {
        val database = database("trikeshed")
        val canary = "sk-test-GRAAL-CANARY-DO-NOT-LEAK-123456"
        val bytes = "OPENAI_API_KEY=$canary\n".encodeToByteArray()
        val cid = attach(database, "credentials/openai.env", bytes, "text/plain")
        assertTrue(database.store.put(Document(
            "credentials/openai.env",
            listOf(
                Field("provider", "openai"),
                Field("apiKey", canary),
                Field("contentType", "text/plain"),
                Field("length", bytes.size.toString()),
                Field("contentId", cid.value),
            ),
        ), database.store.head.getRev("credentials/openai.env")))

        val wire = GraalWire(JvmVitals(), database.store, null, this, database)
        val response = wire.route("GET", "/api/graal/doc?id=credentials%2Fopenai.env", "", null)!!

        assertEquals(200, response.status)
        assertFalse(response.body.contains(canary), "synthetic key must not serialize")
        assertFalse(response.body.contains(cid.value), "content cid must not create a preview bypass")
        assertTrue(response.body.contains("[redacted]"))
        @Suppress("UNCHECKED_CAST")
        val body = JsonSupport.parse(response.body) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val graal = body["_graal"] as Map<String, Any?>
        assertEquals(true, graal["redacted"])
        assertEquals(true, graal["previewBlocked"])
        @Suppress("UNCHECKED_CAST")
        val attachments = body["_attachments"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val content = attachments["content"] as Map<String, Any?>
        assertEquals(true, content["previewBlocked"])
        assertEquals(null, content["cid"])

        val blocked = wire.route("GET", "/api/graal/content?id=credentials%2Fopenai.env", "", null)!!
        assertEquals(403, blocked.status)
        assertFalse(blocked.body.contains(canary), "blocked content response must not serialize bytes")
        assertFalse(blocked.body.contains(cid.value), "blocked content response must not serialize cid")
    }

    @Test
    fun projectDbClassRowsResolveAndDeletedAttachmentsStayOutOfMap() = runTest {
        val primary = database("trikeshed")
        val project = database("projects")
        val registry = ProjectDbRegistry("trikeshed")
        registry.register(ProjectDb(
            "projects",
            "/tmp/projects",
            "git",
            project,
            project.store,
            CouchAttachmentGateway(project.store, project.cas),
            CouchWireRouter(project, "trikeshed/"),
        ))

        val resource = GraalWireDocumentSafetySubject::class.java.name.replace('.', '/') + ".class"
        val classBytes = assertNotNull(
            javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() },
            "compiled test fixture class missing: $resource",
        )
        val docId = "trikeshed/build/live/classes/$resource"
        val displayId = "projects/$docId"
        attach(project, docId, classBytes, "application/java-vm")
        assertTrue(project.store.put(Document(
            "trikeshed/build/live/classes/deleted.class",
            listOf(Field("deleted", "true"), Field("revision", "drop")),
        )))

        val wire = GraalWire(JvmVitals(), primary.store, null, this, primary, projectDbs = registry)
        val projection = wire.route("GET", "/api/graal/classfile?id=$displayId", "", null)!!
        assertEquals(200, projection.status)
        assertTrue(projection.body.contains("GraalWireDocumentSafetySubject"))
        assertTrue(projection.body.contains("\"database\":\"projects\""))
        assertTrue(projection.body.contains("\"id\":\"$displayId\""))

        val content = wire.route("GET", "/api/graal/content?id=$displayId", "", null)!!
        assertEquals(200, content.status)
        assertEquals("application/java-vm", content.contentType)
        assertTrue(content.bytes?.take(4) == listOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))

        val deleted = wire.route("GET", "/api/graal/classfile?id=projects/trikeshed/build/live/classes/deleted.class", "", null)!!
        assertEquals(410, deleted.status)
        assertTrue(deleted.body.contains("class_blob_deleted"))

        val map = wire.route("GET", "/api/graal/map", "", null)!!
        assertTrue(map.body.contains(displayId))
        assertFalse(map.body.contains("projects/trikeshed/build/live/classes/deleted.class"))
    }

    private fun database(name: String): Couch {
        val cas = borg.trikeshed.job.CasStore.inMemory()
        return Couch(name, CouchStoreFactory.casBacked(cas), cas)
    }

    private fun attach(database: Couch, id: String, bytes: ByteArray, contentType: String): ContentId {
        val cid = database.cas.put(bytes)
        assertTrue(database.store.put(Document(
            id,
            listOf(
                Field("contentType", contentType),
                Field("length", bytes.size.toString()),
                Field("contentId", cid.value),
            ),
        ), database.store.head.getRev(id)))
        return cid
    }
}
