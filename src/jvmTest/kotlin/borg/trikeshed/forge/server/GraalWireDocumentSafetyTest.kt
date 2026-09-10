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

private class GraalWireConstantSubject {
    fun value(): String = "sk-test-GRAAL-BYTECODE-CANARY-123456789"
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
    fun sensitiveIdHidesOpaquePayloadAcrossDocumentSheetAndContent() = runTest {
        val database = database("trikeshed")
        val id = "keymux/provider"
        val opaque = "q73m2p9v6c8n4t5w"
        val unrelatedPayload = "opaque-provider-material"
        val cid = attach(database, id, opaque.encodeToByteArray(), "text/plain")
        val attached = assertNotNull(database.store.get(id))
        assertTrue(database.store.put(attached.copy(fields = attached.fields + listOf(
            Field("provider", "test-provider"),
            Field("value", opaque),
            Field("payload", mapOf("label" to unrelatedPayload)),
            Field("label", listOf(mapOf("value" to opaque))),
        )), database.store.head.getRev(id)))
        val wire = GraalWire(JvmVitals(), database.store, null, this, database)

        for (route in listOf("doc", "sheet")) {
            val response = wire.route("GET", "/api/graal/$route?id=keymux%2Fprovider", "", null)!!
            assertEquals(200, response.status, route)
            assertFalse(response.body.contains(opaque), "$route must hide opaque credential values")
            assertFalse(response.body.contains(unrelatedPayload), "$route must hide the whole payload")
            assertFalse(response.body.contains(cid.value), "$route must hide the attachment address")
            assertTrue(response.body.contains("[redacted]"), route)
        }
        val content = wire.route("GET", "/api/graal/content?id=keymux%2Fprovider", "", null)!!
        assertEquals(403, content.status)
        assertEquals(null, content.bytes)
        assertFalse(content.body.contains(opaque))
    }

    @Test
    fun nestedCredentialFieldBlocksNeutralDocumentPreview() = runTest {
        val database = database("trikeshed")
        val id = "settings/provider"
        val opaque = "w49n7q2m5c3t8p6v"
        val cid = attach(database, id, opaque.encodeToByteArray(), "text/plain")
        val attached = assertNotNull(database.store.get(id))
        assertTrue(database.store.put(attached.copy(fields = attached.fields + Field(
            "configuration", listOf(mapOf("authentication" to mapOf("clientSecret" to opaque))),
        )), database.store.head.getRev(id)))
        val wire = GraalWire(JvmVitals(), database.store, null, this, database)

        val response = wire.route("GET", "/api/graal/doc?id=$id", "", null)!!
        assertEquals(200, response.status)
        assertFalse(response.body.contains(opaque))
        assertFalse(response.body.contains(cid.value))
        val body = JsonSupport.parse(response.body) as Map<*, *>
        assertEquals(true, (body["_graal"] as Map<*, *>)["previewBlocked"])
        val content = wire.route("GET", "/api/graal/content?id=$id", "", null)!!
        assertEquals(403, content.status)
        assertEquals(null, content.bytes)
    }

    @Test
    fun sensitiveAttachmentsCannotEscapeThroughSourceAliasesOrClassProjection() = runTest {
        val database = database("trikeshed")
        val opaque = "c8n2v5w7p4m6q9t3"
        val sourceId = "keymux/ProviderFixture.kt"
        val sourceCid = attach(database, sourceId, "val value = \"$opaque\"".encodeToByteArray(), "text/plain")
        val resource = GraalWireDocumentSafetySubject::class.java.name.replace('.', '/') + ".class"
        val classBytes = assertNotNull(javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() })
        val classId = "keymux/ProviderFixture.class"
        val classCid = attach(database, classId, classBytes, "application/java-vm")
        val wire = GraalWire(JvmVitals(), database.store, null, this, database)

        for (source in listOf(sourceId, "ProviderFixture.kt")) {
            val response = wire.route("GET", "/api/graal/decompile?source=$source", "", null)!!
            assertEquals(403, response.status, "resolved source $source must retain its sensitive classification")
            assertFalse(response.body.contains(opaque))
            assertFalse(response.body.contains(sourceCid.value))
        }
        val projection = wire.route("GET", "/api/graal/classfile?id=$classId", "", null)!!
        assertEquals(403, projection.status)
        assertFalse(projection.body.contains(classCid.value))
        assertFalse(projection.body.contains("GraalWireDocumentSafetySubject"))
    }

    @Test
    fun neutralTextAttachmentWithCredentialContentBlocksRawAndSourcePreview() = runTest {
        val database = database("trikeshed")
        val id = "dropzone/Notes.kt"
        val canary = "sk-test-GRAAL-ATTACHMENT-CANARY-123456789"
        val cid = attach(database, id, "val value = \"$canary\"".encodeToByteArray(), "text/plain")
        val wire = GraalWire(JvmVitals(), database.store, null, this, database)

        for (path in listOf(
            "/api/graal/content?id=$id",
            "/api/graal/decompile?source=$id",
            "/api/graal/sheet?id=$id",
        )) {
            val response = wire.route("GET", path, "", null)!!
            assertEquals(403, response.status, path)
            assertEquals(null, response.bytes, path)
            assertFalse(response.body.contains(canary), path)
            assertFalse(response.body.contains(cid.value), path)
        }
    }

    @Test
    fun neutralClassProjectionCannotExposeCredentialConstant() = runTest {
        val database = database("trikeshed")
        val resource = GraalWireConstantSubject::class.java.name.replace('.', '/') + ".class"
        val classBytes = assertNotNull(javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() })
        val id = "dropzone/GraalWireConstantSubject.class"
        val cid = attach(database, id, classBytes, "application/java-vm")
        val wire = GraalWire(JvmVitals(), database.store, null, this, database)

        val projection = wire.route("GET", "/api/graal/classfile?id=$id", "", null)!!
        assertFalse(projection.body.contains(GraalWireConstantSubject().value()))
        // The current structural projection omits string constants entirely; that safe
        // projection may remain available without requiring a blocked response.
        assertTrue(projection.status == 200 || projection.status == 403)
        if (projection.status == 403) assertFalse(projection.body.contains(cid.value))
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
