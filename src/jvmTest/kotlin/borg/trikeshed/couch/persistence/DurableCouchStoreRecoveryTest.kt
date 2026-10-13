package borg.trikeshed.couch.persistence

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.CouchCommittedFrame
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import borg.trikeshed.util.oroboros.FileCasStore
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Durable-heads gate (funding-pack E02): a durableCasBacked store recovers
 * heads, revisions, and deletions from the commit chain alone — the memory
 * projection is rebuilt, never trusted.
 */
class DurableCouchStoreRecoveryTest {

    private fun doc(id: String, vararg fields: Pair<String, Any>) =
        Document(id, fields.map { (k, v) -> Field(k, v) })

    @Test
    fun recoveredBodiesRemainIsolatedFromCallerMutation() {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("couch-recovery-isolation-").toString()
        try {
            val cas = FileCasStore(files, files.resolvePath(root, "cas"))
            val directory = files.resolvePath(root, "couch")
            val bytes = byteArrayOf(1, 2)
            val nested = mutableMapOf<String, Any>("values" to mutableListOf("retained"), "bytes" to bytes)
            CouchCommitStore(cas, StorageDurability.DURABLE, files, directory).use { owner ->
                owner.commit(CouchCommittedFrame(0, "a", "1-retained", false, doc("a", "nested" to nested)))
                nested["values"] = listOf("changed after commit")
                bytes[0] = 9
            }
            CouchCommitStore(cas, StorageDurability.DURABLE, files, directory).use { owner ->
                val frames = owner.recoveredFrames
                assertEquals(1, frames.size)
                val first = frames[0]
                assertEquals("1-retained", first.rev)
                @Suppress("UNCHECKED_CAST")
                val firstNested = first.doc!!.fields.single().value as MutableMap<String, Any>
                assertEquals(listOf("retained"), firstNested["values"])
                assertContentEquals(byteArrayOf(1, 2), firstNested["bytes"] as ByteArray)
                firstNested["values"] = listOf("changed after recovery")
                (firstNested["bytes"] as ByteArray)[0] = 8
                val secondNested = frames[0].doc!!.fields.single().value as Map<*, *>
                assertEquals(listOf("retained"), secondNested["values"])
                assertContentEquals(byteArrayOf(1, 2), secondNested["bytes"] as ByteArray)
            }
        } finally { files.deleteRecursively(root) }
    }

    @Test
    fun recoveryStillRejectsInvalidBodiesWithoutChangingTheRootOrLeakingItsLease() {
        val files = JvmFileOperations()
        val root = Files.createTempDirectory("couch-recovery-validation-").toString()
        try {
            val cas = FileCasStore(files, files.resolvePath(root, "cas"))
            val directory = files.resolvePath(root, "couch")
            CouchCommitStore(cas, StorageDurability.DURABLE, files, directory).close()
            val head = files.resolvePath(directory, "HEAD")
            val empty = files.readAllBytes(head)
            val valid = CanonicalCbor.encodeMap(mapOf("_id" to "a", "value" to "retained"))
            val invalid = listOf(
                CanonicalCbor.encodeMap(mapOf("_id" to "different")),
                valid + byteArrayOf(0),
                CanonicalCbor.encodeMap(mapOf("_id" to "a", "contentType" to "text/plain",
                    "contentId" to ContentId.of(byteArrayOf(99)).value)),
            )
            for (body in invalid) {
                val event = cas.put(CanonicalCbor.encodeMap(mapOf(
                    "format" to "couch-commit-v1", "index" to 1L, "previous" to null,
                    "kind" to "frame", "sequence" to 0L, "id" to "a", "rev" to "1-retained",
                    "deleted" to false, "body" to cas.put(body).value,
                )))
                val published = CanonicalCbor.encodeMap(mapOf("format" to "couch-root-v1", "head" to event.value, "count" to 1L))
                files.writeAtomically(head, published)
                assertFailsWith<IllegalArgumentException> {
                    CouchCommitStore(cas, StorageDurability.DURABLE, files, directory)
                }
                assertContentEquals(published, files.readAllBytes(head))
                files.writeAtomically(head, empty)
                CouchCommitStore(cas, StorageDurability.DURABLE, files, directory).close()
            }
        } finally { files.deleteRecursively(root) }
    }

    @Test
    fun headsRevisionsAndDeletionsSurviveReopen() {
        val fileOps = JvmFileOperations()
        val root = Files.createTempDirectory("couch-durable-test").toString()
        val casRoot = fileOps.resolvePath(root, "cas")
        val couchDir = fileOps.resolvePath(root, "couch")
        assertEquals(StorageDurability.DURABLE, fileOps.durability)

        // First owner: insert, update, delete.
        run {
            val cas = FileCasStore(fileOps, casRoot)
            val commits = CouchCommitStore(cas, StorageDurability.DURABLE, fileOps, couchDir)
            val store = CouchStoreFactory.durableCasBacked(cas, commits)
            assertTrue(store.put(doc("a", "v" to "one")))
            val revA1 = store.head.getRev("a")
            assertTrue(store.put(doc("a", "v" to "two"), expectedRev = revA1))
            assertTrue(store.put(doc("b", "v" to "gone")))
            assertTrue(store.delete("b", expectedRev = store.head.getRev("b")))
            commits.close()
        }

        // Second owner: same CAS, same couch dir — full recovery.
        val cas2 = FileCasStore(fileOps, casRoot)
        val commits2 = CouchCommitStore(cas2, StorageDurability.DURABLE, fileOps, couchDir)
        val store2 = CouchStoreFactory.durableCasBacked(cas2, commits2)

        val recovered = store2.get("a")
        assertNotNull(recovered)
        assertEquals("two", recovered.fields.single { it.name == "v" }.value)
        assertTrue(store2.head.getRev("a")!!.startsWith("2-"), "revision generation recovers")

        assertNull(store2.get("b"), "deletion recovers: b reads as missing")
        assertTrue(store2.head.isDeleted("b"), "deletion tombstone recovers into head")

        // The ingress resumes after the recovered sequence — new commits chain on.
        assertTrue(store2.put(doc("c", "v" to "fresh")))
        assertNotNull(store2.get("c"))

        // Third owner sees the post-recovery write too.
        commits2.close()
        val cas3 = FileCasStore(fileOps, casRoot)
        val commits3 = CouchCommitStore(cas3, StorageDurability.DURABLE, fileOps, couchDir)
        val store3 = CouchStoreFactory.durableCasBacked(cas3, commits3)
        assertNotNull(store3.get("c"))
        assertEquals("two", store3.get("a")!!.fields.single { it.name == "v" }.value)
        commits3.close()

        fileOps.deleteRecursively(root)
    }

    @Test
    fun checkpointsSurviveReopen() {
        val fileOps = JvmFileOperations()
        val root = Files.createTempDirectory("couch-checkpoint-test").toString()
        val casRoot = fileOps.resolvePath(root, "cas")
        val couchDir = fileOps.resolvePath(root, "couch")

        run {
            val cas = FileCasStore(fileOps, casRoot)
            val commits = CouchCommitStore(cas, StorageDurability.DURABLE, fileOps, couchDir)
            val store = CouchStoreFactory.durableCasBacked(cas, commits)
            val couch = borg.trikeshed.couch.Couch("t", store, cas, localBacking = commits.asLocalBacking())
            couch.localPut("pull-peer-t", mapOf("last_seq" to 341L, "peer" to "http://peer"))
            commits.close()
        }

        val cas2 = FileCasStore(fileOps, casRoot)
        val commits2 = CouchCommitStore(cas2, StorageDurability.DURABLE, fileOps, couchDir)
        val store2 = CouchStoreFactory.durableCasBacked(cas2, commits2)
        val couch2 = borg.trikeshed.couch.Couch("t", store2, cas2, localBacking = commits2.asLocalBacking())
        val checkpoint = couch2.localGet("pull-peer-t")
        assertNotNull(checkpoint, "replication checkpoint recovers with the commit chain")
        assertEquals(341L, (checkpoint["last_seq"] as Number).toLong())

        // In-memory default stays exactly that: no backing, no recovery.
        val volatileCouch = borg.trikeshed.couch.Couch("v", CouchStoreFactory.inMemory(), cas2)
        assertNull(volatileCouch.localGet("pull-peer-t"))
        commits2.close()
        fileOps.deleteRecursively(root)
    }

    @Test
    fun exclusiveLeaseRefusesASecondWriter() {
        val fileOps = JvmFileOperations()
        val root = Files.createTempDirectory("couch-lease-test").toString()
        val casRoot = fileOps.resolvePath(root, "cas")
        val couchDir = fileOps.resolvePath(root, "couch")
        val cas = FileCasStore(fileOps, casRoot)
        val first = CouchCommitStore(cas, StorageDurability.DURABLE, fileOps, couchDir)
        var refused = false
        try {
            CouchCommitStore(cas, StorageDurability.DURABLE, fileOps, couchDir)
        } catch (expected: IllegalStateException) {
            refused = true
        }
        assertTrue(refused, "a second writer on the same directory is refused by the lease")
        first.close()
        // After the owner closes, the lease frees.
        CouchCommitStore(cas, StorageDurability.DURABLE, fileOps, couchDir).close()
        fileOps.deleteRecursively(root)
    }
}
