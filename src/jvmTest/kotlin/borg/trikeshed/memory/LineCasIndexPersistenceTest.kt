package borg.trikeshed.memory

import borg.trikeshed.cas.LineCas
import borg.trikeshed.cas.LineCasIndex
import borg.trikeshed.cas.LineCasIndexPersistence
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R2 gate — the inverted index is persisted, so rings and sessions don't re-derive
 * the world each boot:
 *
 * 1. `LineCasIndex.snapshot` → `restore` round-trips EXACTLY (doc count, content
 *    keys, and graded link matches are identical; per-line codes survive).
 * 2. The persistence seam lands a content-addressed snapshot document in the store,
 *    and a FRESH store + `restore` reproduces the live index — boot without
 *    cold derivation.
 * 3. `MemoryStore.onIndexIngest` trails the live index: every `put` lands the
 *    snapshot, and `restoreIndex` adopts the restored continent so `linkSearch`
 *    answers across the boot boundary.
 */
class LineCasIndexPersistenceTest {

    private val textA = """
        val x = 1
        fun main() {
            println(x)
        }
        val y = x + 1
    """.trimIndent()
    private val textB = """
        val x = 1
        fun other() {
            println(x)
        }
        val z = x - 1
    """.trimIndent()

    private fun indexed(store: MemoryStore = MemoryStore(CasStore.inMemory(), CouchStoreFactory.inMemory())): LineCasIndex {
        store.lineIndex.ingestSpine(LineCas.spineInto(CasStore.inMemory(), textA))
        store.lineIndex.ingestSpine(LineCas.spineInto(CasStore.inMemory(), textB))
        return store.lineIndex
    }

    @Test
    fun snapshotRestoreRoundTripsExactly() {
        val live = indexed()
        val restored = LineCasIndex.restore(live.snapshot())
        assertEquals(live.documentCount, restored.documentCount, "doc count survives the round trip")
        assertEquals(live.contentKeyCount, restored.contentKeyCount, "content keys survive the round trip")

        // graded link matching agrees between live and restored
        val probe = LineCas.spine(textB)
        for (i in 0 until probe.size) {
            val a = live.linkMatch(probe[i], minGrade = borg.trikeshed.cas.MatchGrade.CONTENT_ONLY)
            val b = restored.linkMatch(probe[i], minGrade = borg.trikeshed.cas.MatchGrade.CONTENT_ONLY)
            assertEquals(a.size, b.size, "probe line $i: hit count matches")
            for (k in 0 until a.size) {
                assertEquals(a[k].grade, b[k].grade, "probe line $i hit $k: grade preserved")
                assertEquals(a[k].node.code, b[k].node.code, "probe line $i hit $k: coordinate preserved")
            }
        }
    }

    @Test
    fun snapshotIsDeterministic() {
        val a = indexed()
        val b = indexed()
        assertEquals(a.snapshot(), b.snapshot(), "identical corpus → identical snapshot text")
    }

    @Test
    fun persistenceLandsADocAndRestoresAcrossABoot() {
        val cas = CasStore.inMemory()
        val couch = CouchStoreFactory.casBacked(cas)
        val live = LineCasIndex()
        val spA = LineCas.spineInto(cas, textA)
        val spB = LineCas.spineInto(cas, textB)
        live.ingestSpine(spA)
        live.ingestSpine(spB)

        val cid = LineCasIndexPersistence.write(couch, cas, live)
        assertTrue(LineCasIndexPersistence.exists(couch), "snapshot doc is present")
        val bytes = assertNotNull(LineCasIndexPersistence.read(couch, cas))
        assertEquals(cid.value, borg.trikeshed.job.ContentId.of(bytes).value, "the doc references the exact blob")

        // a FRESH store (a new boot) restores the continent — no re-derivation
        val restored = assertNotNull(LineCasIndexPersistence.restore(couch, cas))
        assertEquals(2, restored.documentCount, "both spines restored")
        assertEquals(live.contentKeyCount, restored.contentKeyCount)

        // no snapshot yet → no restore (the honest empty case)
        val emptyCas = CasStore.inMemory()
        val emptyCouch = CouchStoreFactory.casBacked(emptyCas)
        assertFalse(LineCasIndexPersistence.exists(emptyCouch))
        assertNull(LineCasIndexPersistence.restore(emptyCouch, emptyCas))
    }

    @Test
    fun memoryStoreTrailsTheIndexAndRestoresAcrossABoot() {
        val cas = CasStore.inMemory()
        val couch = CouchStoreFactory.casBacked(cas)
        val store = MemoryStore(cas, couch)
        store.onIndexIngest = { idx -> LineCasIndexPersistence.write(couch, cas, idx) }

        store.put(memoryFile("/memories/a.md", "alpha", textA), kind = "note")
        store.put(memoryFile("/memories/b.md", "beta", textB), kind = "note")
        assertTrue(LineCasIndexPersistence.exists(couch), "the snapshot trailed the ingest")
        assertEquals(2, store.lineIndex.documentCount)

        // boot two: a fresh store adopts the persisted continent
        val store2 = MemoryStore(CasStore.inMemory(), CouchStoreFactory.casBacked(cas))
        val restored = LineCasIndexPersistence.restore(couch, cas)
        assertNotNull(restored)
        store2.restoreIndex(restored)

        // the cross-file structural query answers the same on both boots
        val liveHits = store.linkSearch(textB, minGrade = borg.trikeshed.cas.MatchGrade.CONTENT_ONLY)
        val bootHits = store2.linkSearch(textB, minGrade = borg.trikeshed.cas.MatchGrade.CONTENT_ONLY)
        assertEquals(liveHits.size, bootHits.size, "linkSearch answers identically across the boot boundary")
        assertTrue(bootHits.size > 0, "the restored continent actually matches")
    }
}
