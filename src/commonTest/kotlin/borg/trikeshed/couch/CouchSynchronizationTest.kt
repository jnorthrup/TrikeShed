package borg.trikeshed.couch

import borg.trikeshed.context.Synchronization
import borg.trikeshed.couch.persistence.CouchCommitStore
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import kotlin.test.*

class CouchSynchronizationTest {
    @Test fun ownerSerializedMutationAndReplicationRetainOneSequenceInBothModes() {
        for (mode in Synchronization.entries) {
            for (store in s_[CouchStoreFactory.inMemory(mode), CouchStoreFactory.casBacked(CasStore.inMemory(), mode)].view) {
                assertTrue(store.put(Document("doc", listOf(Field("v", "first")))))
                val first = assertNotNull(store.head.getRev("doc"))
                assertFalse(store.put(Document("doc", listOf(Field("v", "stale"))), "0-stale"))
                assertTrue(store.put(Document("doc", listOf(Field("v", "second"))), first))
                assertFalse(store.putReplicated(Document("doc", emptyList()), "doc", first, false))
                assertTrue(store.putReplicated(Document("doc", listOf(Field("v", "replicated"))), "doc", "9-peer", false))
                assertTrue(store.delete("doc", "9-peer"))
                assertNull(store.get("doc"))
                assertEquals("10-deleted", store.head.getRev("doc"))
                val changes = store.changes.series()
                assertEquals(4, changes.size)
                assertEquals(listOf(0L, 1L, 2L, 3L), changes.view.map { it.sequence })
                store.close()
            }
        }
    }

    @Test fun commitStateLocalsAndCloseFollowTheSelectedOwnershipMode() {
        for (mode in Synchronization.entries) {
            val commits = CouchCommitStore(CasStore.inMemory(), StorageDurability.VOLATILE, synchronization = mode)
            assertEquals(mode, commits.synchronization)
            commits.commit(CouchCommittedFrame(0, "doc", "1-first", false, Document("doc", emptyList())))
            assertFailsWith<IllegalArgumentException> {
                commits.commit(CouchCommittedFrame(0, "doc", "1-repeat", false, Document("doc", emptyList())))
            }
            assertEquals("0-1", commits.localPut("peer", mapOf("seq" to 1L))["_rev"])
            assertEquals(1L, commits.localGet("peer")?.get("seq"))
            assertTrue(commits.localDelete("peer"))
            assertFalse(commits.localDelete("peer"))
            commits.close(); commits.close()
            assertFailsWith<IllegalStateException> { commits.localGet("peer") }
        }
    }
}
