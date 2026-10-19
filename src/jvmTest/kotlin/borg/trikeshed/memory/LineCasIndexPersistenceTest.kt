package borg.trikeshed.memory

import borg.trikeshed.cas.LineCas
import borg.trikeshed.cas.LineCasIndex
import borg.trikeshed.cas.LineCasSpineIsam
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R2 gate — the inverted index is persisted, so rings and sessions don't re-derive
 * the world each boot:
 *
 * 1. `LineCasIndex.snapshot` → `restore` round-trips EXACTLY (doc count, content
 *    keys, and graded link matches are identical; per-line codes survive).
 * 2. The index persists as an appendable ISAM cursor of its lines ([LineCasSpineIsam]): each ingest appends only
 *    the new spine's rows, and a FRESH cursor over the same rows reproduces the live index — boot without cold
 *    derivation and without rewriting the index per ingest.
 * 3. `MemoryStore.onIndexIngest` hands each absorbed spine to the cursor, and `restoreIndex` adopts the restored
 *    continent so `linkSearch` answers across the boot boundary.
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
    fun spineRowsLandInTheCursorAndRestoreAcrossABoot() {
        val files = borg.trikeshed.userspace.nio.file.spi.JvmFileOperations()
        val dir = files.createTempDir("linecas-isam")
        val cas = CasStore.inMemory()
        val live = LineCasIndex()
        val cursor = LineCasSpineIsam(files.resolvePath(dir, "linecas.isam"), files)
        for (text in listOf(textA, textB)) LineCas.spineInto(cas, text).also { live.ingestSpine(it); cursor.append(it) }

        // a fresh cursor over the same rows (a new boot) restores the continent — no re-derivation
        val restored = assertNotNull(LineCasSpineIsam(files.resolvePath(dir, "linecas.isam"), files).restore())
        assertEquals(live.snapshot(), restored.snapshot(), "the rows restore every spine exactly")

        // no rows yet → no restore (the honest empty case)
        assertNull(LineCasSpineIsam(files.resolvePath(dir, "absent.isam"), files).restore())
        files.deleteRecursively(dir)
    }

    @Test
    fun memoryStoreAppendsEachSpineAndRestoresAcrossABoot() {
        val files = borg.trikeshed.userspace.nio.file.spi.JvmFileOperations()
        val dir = files.createTempDir("linecas-isam")
        val cas = CasStore.inMemory()
        val couch = CouchStoreFactory.casBacked(cas)
        val store = MemoryStore(cas, couch)
        val cursor = LineCasSpineIsam(files.resolvePath(dir, "linecas.isam"), files)
        store.onIndexIngest = cursor::append

        store.put(memoryFile("/memories/a.md", "alpha", textA), kind = "note")
        store.put(memoryFile("/memories/b.md", "beta", textB), kind = "note")
        assertEquals(2, store.lineIndex.documentCount)

        // boot two: a fresh store adopts the appended continent
        val store2 = MemoryStore(CasStore.inMemory(), CouchStoreFactory.casBacked(cas))
        val restored = LineCasSpineIsam(files.resolvePath(dir, "linecas.isam"), files).restore()
        assertNotNull(restored)
        store2.restoreIndex(restored)

        // the cross-file structural query answers the same on both boots
        val liveHits = store.linkSearch(textB, minGrade = borg.trikeshed.cas.MatchGrade.CONTENT_ONLY)
        val bootHits = store2.linkSearch(textB, minGrade = borg.trikeshed.cas.MatchGrade.CONTENT_ONLY)
        assertEquals(liveHits.size, bootHits.size, "linkSearch answers identically across the boot boundary")
        assertTrue(bootHits.size > 0, "the restored continent actually matches")
        files.deleteRecursively(dir)
    }
}
