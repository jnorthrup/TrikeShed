package borg.trikeshed.couch.isam

import borg.trikeshed.couch.*
import borg.trikeshed.couch.persistence.CouchCommitStore
import borg.trikeshed.isam.*
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.*

class ConfixIsamCouchTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path
    private val files = JvmFileOperations()
    private fun cas() = FileCasStore(files, directory.resolve("cas").toString())
    private fun open(store: CasStore = cas(), fileOps: FileOperations = files, dictionary: ContentId? = null) =
        ConfixIsamCursorBridge(directory.resolve("dense").toString(), fileOps, store,
            UringIsamOperations(leafBytes = 256, leafCodec = CasLeafCodec(store, { ZstdDictionaryCodec(it) }, dictionary)))

    @Test fun couchWritesRevisionsReplicationAndCheckpointsRecoverFromDenseStorage() = runBlocking {
        val cas = cas()
        val dictionary = cas.put("document evidence quotation patient legal review nested field".encodeToByteArray())
        lateinit var firstRevision: String
        lateinit var finalRevision: String
        lateinit var checkpoint: Map<String, Any?>
        lateinit var changes: Map<String, Any?>
        open(cas, dictionary = dictionary).use { storage ->
            val couch = storage.couch("dense")
            firstRevision = couch.put("case", mapOf("nested" to mapOf("患者" to listOf("医", "review"))), null)["rev"] as String
            assertEquals("conflict", couch.put("case", mapOf("value" to "stale"), null)["error"])
            finalRevision = couch.put("case", mapOf("value" to "updated"), firstRevision)["rev"] as String
            val deleted = couch.put("gone", mapOf("value" to 1L), null)["rev"] as String
            assertEquals(true, couch.delete("gone", deleted)["ok"])
            val replicated = mapOf("_id" to "peer", "_rev" to "7-foreign", "value" to "retained")
            assertEquals(true, couch.bulkDocs(listOf(replicated), false).single()["ok"])
            assertEquals(true, couch.bulkDocs(listOf(replicated), false).single()["ok"])
            assertEquals(5L, couch.updateSeq)
            couch.localPut("pull-peer", mapOf("last_seq" to 29L))
            checkpoint = assertNotNull(couch.localGet("pull-peer"))
            changes = couch.changes(0, includeDocs = true)
            assertEquals(5, storage.revisions.size)
            assertSame(couch.store, storage.couch("dense").store, "One ingress owns this destination")
            assertFailsWith<IllegalStateException> {
                storage.put(99, "bypass", "1-bypass", "{}".encodeToByteArray(), Syntax.JSON)
            }
        }
        // A fresh CAS and storage owner reads files using each historical leaf's dictionary identity.
        open().use { storage ->
            val couch = storage.couch("dense")
            assertEquals("updated", couch.docJson("case")!!["value"])
            assertEquals(finalRevision, couch.docJson("case")!!["_rev"])
            assertNull(couch.docJson("gone"))
            assertTrue(couch.store.head.isDeleted("gone"))
            assertEquals("7-foreign", couch.docJson("peer")!!["_rev"])
            assertEquals(changes, couch.changes(0, includeDocs = true))
            assertEquals(checkpoint, couch.localGet("pull-peer"))
            assertEquals(listOf("医", "review"), storage.document("case", firstRevision)!!.value("nested", "患者"))
            assertEquals(true, couch.put("next", mapOf("value" to 2L), null)["ok"])
            assertEquals(6L, couch.updateSeq)
            couch.localDelete("pull-peer")
        }
        open().use { storage ->
            val couch = storage.couch("dense")
            assertEquals(2L, couch.docJson("next")!!["value"])
            assertNull(couch.localGet("pull-peer"))
            assertEquals(6L, couch.updateSeq)
        }
    }

    @Test fun explicitConversionCanBeOpenedAsCouchWithoutChangingItsSource() {
        val cas = cas()
        val source = directory.resolve("source").toString()
        val body = Document("record", listOf(Field("value", "retained")))
        CouchCommitStore(cas, StorageDurability.DURABLE, files, source).use { owner ->
            owner.commit(CouchCommittedFrame(4, "record", "3-original", false, body))
            owner.commit(CouchCommittedFrame(9, "gone", "2-deleted", true, null))
            owner.localPut("source-only", mapOf("last_seq" to 10L))
        }
        val head = files.readAllBytes("$source/HEAD")
        CouchCommitStore(cas, StorageDurability.DURABLE, files, source).use { owner ->
            open(cas).use { storage ->
                storage.importFrames(owner.recoveredFrames)
                val couch = storage.couch("destination")
                assertEquals("retained", couch.docJson("record")!!["value"])
                assertEquals("3-original", couch.docJson("record")!!["_rev"])
                assertEquals(10L, couch.updateSeq)
                assertNull(couch.localGet("source-only"), "A new destination must not inherit another peer's checkpoint")
                assertTrue(couch.store.put(Document("next", emptyList())))
                assertEquals(11L, couch.updateSeq)
            }
        }
        assertContentEquals(head, files.readAllBytes("$source/HEAD"))
    }

    @Test fun failedDensePublicationDoesNotBecomeAVisibleCouchRevision() {
        var fail = false
        val faulty = object : FileOperations by files {
            override fun writeAtomically(filename: String, bytes: ByteArray) {
                if (fail && filename.endsWith("/HEAD")) error("injected root publication failure")
                files.writeAtomically(filename, bytes)
            }
        }
        open(fileOps = faulty).use { storage ->
            val couch = storage.couch("dense")
            val rev = couch.put("a", mapOf("value" to "old"), null)["rev"] as String
            fail = true
            assertFails { couch.put("a", mapOf("value" to "new"), rev) }
            assertEquals("old", couch.docJson("a")!!["value"])
            assertEquals(rev, couch.docJson("a")!!["_rev"])
            assertEquals(1L, couch.updateSeq)
            assertFails { couch.localPut("checkpoint", mapOf("last_seq" to 2L)) }
        }
        open().use { storage ->
            val couch = storage.couch("dense")
            assertEquals("old", couch.docJson("a")!!["value"])
            assertEquals(true, couch.put("a", mapOf("value" to "new"), couch.docJson("a")!!["_rev"] as String)["ok"])
        }
    }

    @Test fun denseCouchValidatesBodiesReferencesAndGeometryBeforeServing() {
        open().use { storage ->
            val couch = storage.couch("dense")
            val absent = ContentId.of("absent".encodeToByteArray())
            assertFails { couch.put("attachment", mapOf("contentId" to absent.value, "contentType" to "text/plain"), null) }
            assertFails { couch.store.put(Document("ambiguous", listOf(Field("v", 1), Field("v", 2)))) }
            assertEquals(0L, couch.updateSeq)
            assertEquals(0, storage.revisions.size)
            couch.put("a", mapOf("v" to "verified"), null)
        }
        val entry = open().use { assertNotNull(it.revision("a")) }
        val data = directory.resolve("dense.store").resolve(entry.dataFile!!).toString()
        val corrupt = files.readAllBytes(data).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        files.writeAtomically(data, corrupt)
        open().use { storage -> assertFails { storage.couch("dense") } }
        assertContentEquals(corrupt, files.readAllBytes(data))
    }

    @Test fun arbitraryConfixProjectionIsNotSilentlyReinterpretedAsCouch() {
        open().use { storage ->
            storage.put(0, "a", "1-a", "{\"_id\":\"a\"}".encodeToByteArray(), Syntax.JSON)
            assertFails { storage.couch("dense") }
            assertNotNull(storage.document("a"))
        }
    }

    @Test fun existingWireRouterServesDenseDocumentsAttachmentsAndChangesAfterReopen() = runBlocking {
        val payload = "Synthetic retained quotation — 医".encodeToByteArray()
        open().use { storage ->
            val router = CouchWireRouter(storage.couch("dense"), "")
            assertEquals(201, router.handle("POST", "/dense/_cas", payload)!!.status)
            val body = borg.trikeshed.parse.jsonOf(mapOf("contentId" to ContentId.of(payload).value,
                "contentType" to "text/plain", "length" to payload.size)).encodeToByteArray()
            assertEquals(201, router.handle("PUT", "/dense/source", body)!!.status)
            assertEquals(409, router.handle("PUT", "/dense/source", body)!!.status)
        }
        open().use { storage ->
            val router = CouchWireRouter(storage.couch("dense"), "")
            val attachment = assertNotNull(router.handle("GET", "/dense/source/content", byteArrayOf()))
            assertEquals(200, attachment.status)
            assertContentEquals(payload, attachment.bytes)
            val changes = assertNotNull(router.handle("GET", "/dense/_changes?since=0&include_docs=true", byteArrayOf()))
            assertEquals(200, changes.status)
            val data = borg.trikeshed.parse.reify(changes.bytes) as Map<*, *>
            val row = (data["results"] as List<*>).single() as Map<*, *>
            assertEquals("source", row["id"])
            assertEquals(ContentId.of(payload).value, (row["doc"] as Map<*, *>)["contentId"])
        }
    }
}
