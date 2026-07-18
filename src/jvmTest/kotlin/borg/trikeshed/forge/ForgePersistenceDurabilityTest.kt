package borg.trikeshed.forge

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ForgePersistenceDurabilityTest {

    @Test fun mutationProducesImmediateWalEntry() {
        val store = InMemoryForgePersistenceStore()
        val persistence = ForgePersistenceCoordinator(store)
        val first = workspaceSnapshot(1)

        val sizeAfterFirst = persistence.recordMutation(first, walEntry(1, first))
        assertEquals(1, sizeAfterFirst)
        val envelopeAfterFirst = decodePersistenceEnvelope(store.readLocalStorage())
        assertNotNull(envelopeAfterFirst)
        assertEquals(1, envelopeAfterFirst.wal.size)
        assertEquals("n-1", envelopeAfterFirst.wal.first().snapshot["label"]?.jsonPrimitive?.content)

        repeat(99) { index ->
            val snapshot = workspaceSnapshot(index + 2)
            persistence.recordMutation(snapshot, walEntry(index + 2, snapshot))
        }
        val envelopeAfterHundred = decodePersistenceEnvelope(store.readLocalStorage())
        assertNotNull(envelopeAfterHundred)
        assertEquals(100, envelopeAfterHundred.wal.size)
        assertEquals(listOf("n-1", "n-2", "n-3"), envelopeAfterHundred.wal.take(3).map { it.snapshot["label"]!!.jsonPrimitive.content })
        assertEquals("n-100", envelopeAfterHundred.wal.last().snapshot["label"]!!.jsonPrimitive.content)
    }

    @Test fun indexedDbUnavailableDegrades() = runTest {
        val store = InMemoryForgePersistenceStore(failIndexedDb = true)
        val persistence = ForgePersistenceCoordinator(store)
        var snapshot = workspaceSnapshot(0)

        repeat(101) { index ->
            snapshot = workspaceSnapshot(index + 1)
            persistence.recordMutation(snapshot, walEntry(index + 1, snapshot))
        }

        val persisted = persistence.persistSnapshot(snapshot)
        assertEquals("LocalStorageOnly", persistence.mode)
        assertTrue(persistence.warning.contains("localStorage") || persistence.warning.contains("fallback"))
        assertTrue(persisted.snapshot["label"]!!.jsonPrimitive.content == "n-101")

        val loaded = persistence.loadLatest(workspaceSnapshot(-1))
        assertEquals("n-101", loaded["label"]!!.jsonPrimitive.content)
        val envelope = decodePersistenceEnvelope(store.readLocalStorage())
        assertNotNull(envelope)
        assertEquals(1, envelope.wal.size)
    }

    @Test fun loadsOldSnapshot() = runTest {
        val store = InMemoryForgePersistenceStore()
        val old = buildJsonObject {
            put("snapshot", workspaceSnapshot(7))
            put("source", "indexeddb")
            put("version", 1)
        }
        store.writeLocalStorage(old.toString())
        val persistence = ForgePersistenceCoordinator(store)

        val loaded = persistence.loadLatest(workspaceSnapshot(0))
        assertEquals("n-7", loaded["label"]!!.jsonPrimitive.content)
        assertEquals(7, loaded["counter"]!!.jsonPrimitive.intOrNull)
        assertEquals("", decodePersistenceEnvelope(store.readLocalStorage())?.warning)
    }

    @Test fun walRotates() = runTest {
        val store = InMemoryForgePersistenceStore()
        val persistence = ForgePersistenceCoordinator(store)
        var snapshot = workspaceSnapshot(0)

        repeat(250) { index ->
            snapshot = workspaceSnapshot(index + 1)
            persistence.recordMutation(snapshot, walEntry(index + 1, snapshot))
        }

        val envelope = decodePersistenceEnvelope(store.readLocalStorage())
        assertNotNull(envelope)
        assertTrue(envelope.wal.size <= 100)
        assertEquals("n-250", envelope.wal.last().snapshot["label"]!!.jsonPrimitive.content)

        val loaded = persistence.loadLatest(workspaceSnapshot(-1))
        assertEquals("n-250", loaded["label"]!!.jsonPrimitive.content)
        assertEquals(250, loaded["counter"]!!.jsonPrimitive.intOrNull)
    }


    private fun workspaceSnapshot(counter: Int) = buildJsonObject {
        put("counter", counter)
        put("label", "n-$counter")
        put("title", "Workspace $counter")
    }


    private fun walEntry(counter: Int, snapshot: kotlinx.serialization.json.JsonObject) = ForgeWalEntry(
        id = "mut-$counter",
        kind = "workspace-mutation",
        label = "n-$counter",
        source = "forge",
        timestampMs = counter.toLong(),
        snapshot = snapshot,
    )
}
