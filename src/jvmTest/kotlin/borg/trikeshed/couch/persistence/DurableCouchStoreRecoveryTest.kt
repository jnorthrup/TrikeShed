package borg.trikeshed.couch.persistence

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.couch.Document
import borg.trikeshed.couch.Field
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import borg.trikeshed.util.oroboros.FileCasStore
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Durable-heads gate (funding-pack E02): a durableCasBacked store recovers
 * heads, revisions, and deletions from the commit chain alone — the memory
 * projection is rebuilt, never trusted.
 */
class DurableCouchStoreRecoveryTest {

    private fun doc(id: String, vararg fields: Pair<String, Any>) =
        Document(id, fields.map { (k, v) -> Field(k, v) })

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
