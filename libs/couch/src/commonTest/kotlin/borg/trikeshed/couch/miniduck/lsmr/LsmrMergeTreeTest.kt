package borg.trikeshed.couch.miniduck.lsmr

import borg.trikeshed.couch.miniduck.*
import borg.trikeshed.lib.*
import kotlin.test.*

/**
 * RED test: LSMR merge tree — multi-level sorted runs with compaction.
 *
 * Data flows: write → L0 (memory) → flush → L1 (disk runs) → merge → L2 (merged).
 * Compaction reduces read amplification by merging overlapping runs.
 * Each run is a sorted sequence of (key, seq, value, deleted) entries.
 * Merge keeps the newest seq for each key; tombstones (deleted=true) suppress older entries.
 *
 * Donor: LSMR database, B-tree/LSM hybrid, CouchDB B+tree compaction.
 */
class LsmrMergeTreeTest {

    // ── Basic insert and scan ────────────────────────────────────────────

    @Test
    fun insertAndScanReturnsAllEntries() {
        val tree = LsmrMergeTree()
        tree.put("a", "1", seq = 1)
        tree.put("b", "2", seq = 2)
        tree.put("c", "3", seq = 3)

        val results = tree.scan().toList()
        assertEquals(3, results.size)
        assertEquals("a", results[0].key)
        assertEquals("b", results[1].key)
        assertEquals("c", results[2].key)
    }

    @Test
    fun scanReturnsEntriesSortedByKey() {
        val tree = LsmrMergeTree()
        tree.put("c", "3", seq = 1)
        tree.put("a", "1", seq = 2)
        tree.put("b", "2", seq = 3)

        val results = tree.scan().toList()
        assertEquals("a", results[0].key)
        assertEquals("b", results[1].key)
        assertEquals("c", results[2].key)
    }

    // ── Flush from L0 to L1 ─────────────────────────────────────────────

    @Test
    fun flushMovesEntriesFromL0ToL1() {
        val tree = LsmrMergeTree()
        tree.put("x", "10", seq = 1)
        tree.put("y", "20", seq = 2)

        assertEquals(2, tree.level0Size())
        assertEquals(0, tree.level1Size())

        tree.flush()

        assertEquals(0, tree.level0Size())
        assertEquals(1, tree.level1RunCount())
        assertEquals(2, tree.level1Size())
    }

    @Test
    fun scanReadsAcrossLevels() {
        val tree = LsmrMergeTree()
        tree.put("a", "1", seq = 1)
        tree.flush() // a is now in L1
        tree.put("b", "2", seq = 2) // b is in L0

        val results = tree.scan().toList()
        assertEquals(2, results.size)
        assertEquals("a", results[0].key)
        assertEquals("b", results[1].key)
    }

    // ── Merge L1 runs into L2 ───────────────────────────────────────────

    @Test
    fun mergeCombinesL1RunsIntoL2() {
        val tree = LsmrMergeTree()
        // First flush → L1 run 1
        tree.put("a", "1", seq = 1)
        tree.put("b", "2", seq = 2)
        tree.flush()

        // Second flush → L1 run 2
        tree.put("c", "3", seq = 3)
        tree.put("d", "4", seq = 4)
        tree.flush()

        assertEquals(2, tree.level1RunCount())

        tree.merge()

        // All L1 runs merged into one L2 run
        assertEquals(0, tree.level1RunCount())
        assertEquals(1, tree.level2RunCount())
        assertEquals(4, tree.level2Size())
    }

    @Test
    fun mergeKeepsNewestSeqForDuplicateKeys() {
        val tree = LsmrMergeTree()
        tree.put("a", "old", seq = 1)
        tree.flush()

        tree.put("a", "new", seq = 2)
        tree.flush()

        tree.merge()

        val results = tree.scan().toList()
        assertEquals(1, results.size)
        assertEquals("new", results[0].value)
        assertEquals(2L, results[0].seq)
    }

    // ── Tombstone (delete) ───────────────────────────────────────────────

    @Test
    fun tombstoneHidesDeletedEntryInScan() {
        val tree = LsmrMergeTree()
        tree.put("a", "1", seq = 1)
        tree.put("b", "2", seq = 2)
        tree.flush()

        tree.delete("a", seq = 3) // tombstone
        tree.flush()
        tree.merge()

        val results = tree.scan().toList()
        assertEquals(1, results.size)
        assertEquals("b", results[0].key)
    }

    @Test
    fun tombstoneRemovedAfterCompactionPastSnapshot() {
        val tree = LsmrMergeTree()
        tree.put("a", "1", seq = 1)
        tree.delete("a", seq = 2)
        tree.flush()
        tree.merge()

        // After merge past the tombstone, "a" should not appear at all
        val results = tree.scan().toList()
        assertTrue(results.none { it.key == "a" })
    }

    // ── Full compaction cycle ────────────────────────────────────────────

    @Test
    fun fullCompactionCycle() {
        val tree = LsmrMergeTree()

        // Write 10 entries
        for (i in 0 until 10) {
            tree.put("key-$i", "value-$i", seq = i.toLong() + 1)
        }
        tree.flush()

        // Overwrite some
        tree.put("key-3", "updated-3", seq = 11)
        tree.put("key-7", "updated-7", seq = 12)
        tree.flush()

        // Delete one
        tree.delete("key-5", seq = 13)
        tree.flush()

        // Merge all L1 runs → L2
        tree.merge()

        val results = tree.scan().toList()
        // 10 original - 1 deleted + 0 new = 9 entries
        assertEquals(9, results.size)
        // key-3 and key-7 should have updated values
        val key3 = results.first { it.key == "key-3" }
        assertEquals("updated-3", key3.value)
        // key-5 should be gone
        assertTrue(results.none { it.key == "key-5" })
    }

    // ── Sequence ordering within merge ───────────────────────────────────

    @Test
    fun mergePreservesSequenceOrdering() {
        val tree = LsmrMergeTree()
        // Interleaved writes across flushes
        tree.put("a", "v1", seq = 1)
        tree.put("b", "v1", seq = 2)
        tree.flush()

        tree.put("a", "v2", seq = 3)
        tree.put("c", "v1", seq = 4)
        tree.flush()

        tree.put("b", "v2", seq = 5)
        tree.flush()

        tree.merge()

        val results = tree.scan().toList()
        assertEquals(3, results.size)
        // All should have their latest values
        val map = results.associate { it.key to it.value }
        assertEquals("v2", map["a"])
        assertEquals("v2", map["b"])
        assertEquals("v1", map["c"])
    }

    // ── Empty tree ───────────────────────────────────────────────────────

    @Test
    fun scanEmptyTreeReturnsNothing() {
        val tree = LsmrMergeTree()
        assertTrue(tree.scan().toList().isEmpty())
    }

    @Test
    fun flushEmptyL0IsNoop() {
        val tree = LsmrMergeTree()
        tree.flush()
        assertEquals(0, tree.level1RunCount())
    }
}
