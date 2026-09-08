package borg.trikeshed.forge.server

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.graal.vitals.JvmVitals
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private interface ClassfileBlobPrimary {
    fun label(): String
}

private interface ClassfileBlobSecondary

private class ClassfileBlobSubject : ClassfileBlobPrimary, ClassfileBlobSecondary {
    override fun label(): String = "subject"
    fun overload(value: Int): Int = value * 2
    fun overload(value: String): String = value.reversed()
    fun touch(values: MutableList<String>): String {
        values.add(label())
        return values.last()
    }
}

class ClassfileBlobProjectionTest {
    @Test
    fun selectedClassAttachmentProjectsClassfileApiAndRuntimeBoundary() {
        val cas = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val database = Couch("test", store, cas)
        val resource = ClassfileBlobSubject::class.java.name.replace('.', '/') + ".class"
        val classBytes = javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
        assertNotNull(classBytes, "compiled test fixture class missing: $resource")
        val id = "projects/trikeshed/build/live/classes/$resource"
        attach(database, cas, id, classBytes, "application/java-vm")

        val projection = ClassfileBlobProjection(database, JvmVitals()).project(id)

        assertEquals(null, projection["error"])
        assertEquals("java.lang.classfile (JDK 25)", projection["classfileApi"])
        assertEquals(ContentId.of(classBytes).value, projection["blobCid"])
        assertEquals(resource, projection["classpathResource"])
        assertEquals(true, projection["onClasspath"])
        assertEquals(true, projection["exactRuntimeBlob"])

        @Suppress("UNCHECKED_CAST")
        val interfaces = projection["interfaces"] as List<String>
        assertTrue(interfaces.contains("borg.trikeshed.forge.server.ClassfileBlobPrimary"))
        assertTrue(interfaces.contains("borg.trikeshed.forge.server.ClassfileBlobSecondary"))

        @Suppress("UNCHECKED_CAST")
        val methods = projection["methods"] as List<Map<String, Any?>>
        val overloadDescriptors = methods
            .filter { it["name"] == "overload" }
            .map { it["descriptor"] }
            .toSet()
        assertTrue(overloadDescriptors.contains("(I)I"))
        assertTrue(overloadDescriptors.contains("(Ljava/lang/String;)Ljava/lang/String;"))

        @Suppress("UNCHECKED_CAST")
        val classFile = projection["classFile"] as Map<String, Any?>
        assertEquals(2, classFile["interfaceCount"])
        assertTrue((classFile["instructionCount"] as Int) > 0)

        @Suppress("UNCHECKED_CAST")
        val pointcuts = projection["pointcuts"] as List<Map<String, Any?>>
        assertTrue(pointcuts.any { pointcut ->
            @Suppress("UNCHECKED_CAST")
            val symbol = pointcut["symbol"] as Map<String, Any?>
            symbol["methodName"] == "touch" && symbol["methodDescriptor"] == "(Ljava/util/List;)Ljava/lang/String;"
        })

        @Suppress("UNCHECKED_CAST")
        val runtime = projection["runtimeObservation"] as Map<String, Any?>
        assertEquals(true, runtime["available"])
        @Suppress("UNCHECKED_CAST")
        val methodCounters = runtime["methodCounters"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val instructionCounters = runtime["instructionCounters"] as Map<String, Any?>
        assertEquals(false, methodCounters["available"])
        assertEquals(false, instructionCounters["available"])
        assertTrue(methodCounters["reason"].toString().contains("descriptor"))
        assertTrue(instructionCounters["reason"].toString().contains("bytecode-offset"))
    }

    private fun attach(
        database: Couch,
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
