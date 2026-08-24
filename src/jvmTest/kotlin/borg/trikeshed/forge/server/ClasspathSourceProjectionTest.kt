package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClasspathSourceProjectionTest {
    @Test
    fun sourceAttachmentMatesToExactRuntimeClassBlobThroughJdk25ClassfileApi() {
        val cas = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val database = CouchDatabase("test", store, cas)
        val sourceId = "projects/trikeshed/src/jvmTest/kotlin/borg/trikeshed/forge/server/ClasspathSourceProjectionTest.kt"
        val source = """
            package borg.trikeshed.forge.server
            class ClasspathSourceProjectionTest
        """.trimIndent().encodeToByteArray()
        val resource = "borg/trikeshed/forge/server/ClasspathSourceProjectionTest.class"
        val classBytes = javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
        assertNotNull(classBytes)
        attach(database, cas, sourceId, source, "text/x-kotlin")
        attach(
            database,
            cas,
            "build/live/classes/" + resource,
            classBytes,
            "application/java-vm",
        )

        val projected = ClasspathSourceProjection(database).project(sourceId)

        assertEquals(true, projected["mated"])
        assertEquals("java.lang.classfile (JDK 25)", projected["classfileApi"])
        @Suppress("UNCHECKED_CAST")
        val mates = projected["mates"] as List<Map<String, Any?>>
        assertEquals(1, mates.size)
        val mate = mates.single()
        assertEquals(true, mate["onClasspath"])
        assertEquals(true, mate["exactRuntimeBlob"])
        assertEquals(ContentId.of(classBytes).value, mate["blobCid"])
        @Suppress("UNCHECKED_CAST")
        val decompiler = mate["decompiler"] as Map<String, Any?>
        assertEquals("jdk25-classfile-pseudo", decompiler["projectionKind"])
        assertTrue(decompiler["pseudoSource"].toString().contains("ClasspathSourceProjectionTest"))
        @Suppress("UNCHECKED_CAST")
        val methods = decompiler["methods"] as List<Map<String, Any?>>
        assertTrue(methods.isNotEmpty())
        @Suppress("UNCHECKED_CAST")
        val instructions = methods.flatMap { it["instructions"] as List<Map<String, Any?>> }
        assertTrue(instructions.isNotEmpty())
        assertTrue(instructions.all { (it["offset"] as Int) >= 0 })
    }

    @Test
    fun hotspotAotSurfaceIsHonestAboutOpaqueArchiveBoundary() {
        val state = HotSpotAotBlobAccess.snapshot()
        assertEquals("opaque-hotspot-aot-cache", state["archiveProjection"])
        assertTrue(state["classfileProjection"].toString().contains("not this archive"))
        assertTrue(state.containsKey("mode"))
        assertTrue(state.containsKey("mxBeanRegistered"))
    }

    private fun attach(
        database: CouchDatabase,
        cas: CasStore,
        id: String,
        bytes: ByteArray,
        contentType: String,
    ) {
        val cid = cas.put(bytes)
        assertTrue(
            database.store.put(
                Document(
                    id,
                    listOf(
                        Field("contentType", contentType),
                        Field("length", bytes.size.toString()),
                        Field("contentId", cid.value),
                    ),
                ),
            ),
        )
    }
}
