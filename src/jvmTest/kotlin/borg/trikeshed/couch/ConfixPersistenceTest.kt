package borg.trikeshed.couch

import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfixPersistenceTest {
    @Test
    fun revisionsRejectConflictsAndGuardDeletes() {
        val store = ConfixDocStoreFactory.createSequential()
        val created = assertNotNull(store.put("doc-1", "{\"name\":\"Alice\"}"))
        assertEquals("0-uuid", created.rev)

        assertNull(store.put("doc-1", "{\"name\":\"Mallory\"}", rev = "stale"))
        val updated = assertNotNull(store.put("doc-1", "{\"name\":\"Bob\"}", rev = created.rev))
        assertEquals("1-uuid", updated.rev)
        assertEquals(updated, store["doc-1"])

        assertFalse(store.delete("doc-1", created.rev))
        assertTrue(store.delete("doc-1", updated.rev))
        assertNull(store["doc-1"])
    }

    @Test
    fun prefixProjectionReturnsOnlyMatchingDocuments() {
        val store = ConfixDocStoreFactory.createSequential()
        store.put("user:1", "{\"name\":\"Bob\"}")
        store.put("user:2", "{\"name\":\"Carol\"}")
        store.put("job:1", "{\"name\":\"Build\"}")

        assertEquals(3, store.size)
        assertEquals(2, store.byIdPrefix("user:").size)
        assertEquals(1, store.byIdPrefix("job:").size)
    }
}
