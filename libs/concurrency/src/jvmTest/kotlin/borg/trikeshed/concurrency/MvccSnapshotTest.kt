package borg.trikeshed.miniduck.mvcc

import borg.trikeshed.miniduck.*
import borg.trikeshed.miniduck.tablespace.*
import borg.trikeshed.lib.*
import kotlin.test.*

/**
 * RED test: MVCC snapshot isolation over WAL-backed BlockStore.
 *
 * Reads at a snapshot sequence see only blocks that were sealed at or before that sequence.
 * Writers don't block readers — a reader at seq 5 sees the world as it was at seq 5,
 * even if writers have advanced to seq 50.
 *
 * This is the multi-version concurrency model:
 *   - Each BlockStore.put assigns a monotonically increasing sequence
 *   - A snapshot captures the sequence at creation time
 *   - readAt(snapshot) filters blocks by their put sequence
 *
 * Donor: CouchDB MVCC (_rev), PostgreSQL MVCC (xmin/xmax), Raft snapshot.
 */
class MvccSnapshotTest {

    // ── Snapshot sees only blocks at or before its sequence ──────────────

    @Test
    fun snapshotSeesOnlyPriorBlocks() {
        val mvcc = MvccBlockStore()

        val blk0 = sealedBlock("alice")
        val blk1 = sealedBlock("bob")
        val blk2 = sealedBlock("charlie")

        val seq0 = mvcc.put("docs", blk0) // seq 1
        val seq1 = mvcc.put("docs", blk1) // seq 2
        val snap = mvcc.snapshot()         // snap at seq 2
        val seq2 = mvcc.put("docs", blk2) // seq 3

        // At snapshot, only blk0 and blk1 visible
        val docs = mvcc.listAt(snap, "docs")
        assertEquals(2, docs.size)
        assertTrue(docs.contains(seq0))
        assertTrue(docs.contains(seq1))
        assertFalse(docs.contains(seq2))
    }

    @Test
    fun laterSnapshotSeesAllBlocks() {
        val mvcc = MvccBlockStore()
        mvcc.put("docs", sealedBlock("a"))
        mvcc.put("docs", sealedBlock("b"))
        mvcc.put("docs", sealedBlock("c"))

        val snap = mvcc.snapshot() // snap at seq 3
        assertEquals(3, mvcc.listAt(snap, "docs").size)
    }

    // ── Read at snapshot returns correct block ────────────────────────────

    @Test
    fun readAtSnapshotReturnsCorrectBlock() {
        val mvcc = MvccBlockStore()
        val blk = sealedBlock("alice")
        val blockId = mvcc.put("docs", blk)

        val snap = mvcc.snapshot()
        val loaded = mvcc.getAt(snap, "docs", blockId)
        assertNotNull(loaded)
        assertEquals(1, loaded.rowCount)
    }

    @Test
    fun readAtSnapshotReturnsNullForFutureBlock() {
        val mvcc = MvccBlockStore()
        val blk0 = sealedBlock("alice")
        val blockId0 = mvcc.put("docs", blk0)
        val snap = mvcc.snapshot()
        val blk1 = sealedBlock("bob")
        val blockId1 = mvcc.put("docs", blk1)

        // blk1 was put after the snapshot
        assertNull(mvcc.getAt(snap, "docs", blockId1))
        // blk0 was put before the snapshot
        assertNotNull(mvcc.getAt(snap, "docs", blockId0))
    }

    // ── Remove visibility at snapshot ─────────────────────────────────────

    @Test
    fun removeNotVisibleAtPriorSnapshot() {
        val mvcc = MvccBlockStore()
        val blk = sealedBlock("alice")
        val blockId = mvcc.put("docs", blk)

        val snapBeforeRemove = mvcc.snapshot()
        mvcc.remove("docs", blockId)

        // At the snapshot taken before remove, block is still visible
        assertNotNull(mvcc.getAt(snapBeforeRemove, "docs", blockId))

        // At a new snapshot after remove, block is gone
        val snapAfterRemove = mvcc.snapshot()
        assertNull(mvcc.getAt(snapAfterRemove, "docs", blockId))
    }

    // ── Concurrent snapshots are independent ─────────────────────────────

    @Test
    fun concurrentSnapshotsAreIndependent() {
        val mvcc = MvccBlockStore()
        val blk0 = sealedBlock("a")
        val blk1 = sealedBlock("b")
        val blk2 = sealedBlock("c")

        mvcc.put("docs", blk0)
        val snap1 = mvcc.snapshot()          // sees only "a"
        mvcc.put("docs", blk1)
        val snap2 = mvcc.snapshot()          // sees "a" and "b"
        mvcc.put("docs", blk2)

        assertEquals(1, mvcc.listAt(snap1, "docs").size)
        assertEquals(2, mvcc.listAt(snap2, "docs").size)
        assertEquals(3, mvcc.listAt(mvcc.snapshot(), "docs").size)
    }

    // ── Collection isolation at snapshot ──────────────────────────────────

    @Test
    fun snapshotCollectionIsolation() {
        val mvcc = MvccBlockStore()
        mvcc.put("users", sealedBlock("alice"))
        mvcc.put("orders", sealedBlock("order-1"))

        val snap = mvcc.snapshot()
        assertEquals(1, mvcc.listAt(snap, "users").size)
        assertEquals(1, mvcc.listAt(snap, "orders").size)
        assertEquals(0, mvcc.listAt(snap, "nonexistent").size)
    }

    // ── Overwrite (put same collection, different block) ──────────────────

    @Test
    fun overwriteBlockAtSameCollection() {
        val mvcc = MvccBlockStore()
        val blk1 = sealedBlock("v1")
        val blk2 = sealedBlock("v2")

        val id1 = mvcc.put("docs", blk1)
        val snap1 = mvcc.snapshot()
        val id2 = mvcc.put("docs", blk2)
        val snap2 = mvcc.snapshot()

        // Both blocks exist, but at different snapshots
        assertEquals(1, mvcc.listAt(snap1, "docs").size)
        assertEquals(2, mvcc.listAt(snap2, "docs").size)
    }

    // ── Scan cursor at snapshot ───────────────────────────────────────────

    @Test
    fun scanAtSnapshotReturnsCursor() {
        val mvcc = MvccBlockStore()
        mvcc.put("docs", sealedBlock("alice"))
        mvcc.put("docs", sealedBlock("bob"))

        val snap = mvcc.snapshot()
        val cursor = mvcc.scanAt(snap, "docs")
        assertEquals(2, cursor.size)

        val names = (0 until cursor.size).map { i ->
            val row = cursor.at(i)
            row.getValue("name")
        }.toSet()
        assertEquals(setOf("alice", "bob"), names)
    }

    @Test
    fun scanAtSnapshotExcludesLaterBlocks() {
        val mvcc = MvccBlockStore()
        mvcc.put("docs", sealedBlock("alice"))
        val snap = mvcc.snapshot()
        mvcc.put("docs", sealedBlock("bob"))

        val cursor = mvcc.scanAt(snap, "docs")
        assertEquals(1, cursor.size)
        assertEquals("alice", cursor.at(0).getValue("name"))
    }

    // ── Helpers ──────────────────────────────────────────────────────────

   fun sealedBlock(name: String): BlockRowVec {
        val block = BlockRowVec.mutable()
        block.append(DocRowVec(listOf("name"), listOf(name)))
        return block.seal()
    }
}
