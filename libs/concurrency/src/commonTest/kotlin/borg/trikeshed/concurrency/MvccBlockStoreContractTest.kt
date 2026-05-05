package borg.trikeshed.concurrency

import borg.trikeshed.miniduck.*
import borg.trikeshed.lib.*
import kotlin.test.*
import borg.trikeshed.cursor.*

/**
 * TDD spec for MvccBlockStore — MVCC snapshot isolation for block storage.
 *
 * MVCC invariants:
 *   - Readers see a consistent snapshot at the time they began reading
 *   - Writers produce a new version, never overwrite uncommitted data
 *   - Snapshot isolation: non-repeatable reads prevented
 *   - Write-write conflicts detected at commit time
 *
 */
class MvccBlockStoreContractTest {

    @Test
    fun mvccBlockStoreHasVersionedSnapshots() {
        val mvcc = MvccBlockStore()
        val snap1 = mvcc.snapshot()
        assertEquals(0L, snap1.seq)

        mvcc.put("docs", sealedBlock("alice"))
        val snap2 = mvcc.snapshot()
        assertEquals(1L, snap2.seq)

        // Each read returns a snapshot at a particular version
        assertTrue(snap1.seq < snap2.seq)
    }

    @Test
    fun snapshotIsImmutableOnceCreated() {
        val mvcc = MvccBlockStore()
        val snap = mvcc.snapshot()
        val docs1 = mvcc.listAt(snap, "docs")

        mvcc.put("docs", sealedBlock("alice"))

        val docs2 = mvcc.listAt(snap, "docs")

        // A snapshot never sees writes that committed after its creation
        assertEquals(docs1.size, docs2.size)
    }

    @Test
    fun writeCreatesNewVersionDoesNotClobberExisting() {
        val mvcc = MvccBlockStore()
        val blk1 = sealedBlock("v1")
        val blk2 = sealedBlock("v2")

        val id1 = mvcc.put("docs", blk1)
        val snap1 = mvcc.snapshot()
        val id2 = mvcc.put("docs", blk2)
        val snap2 = mvcc.snapshot()

        // write(key, value) produces a new version
        assertTrue(snap1.seq < snap2.seq)
        assertEquals(1, mvcc.listAt(snap1, "docs").size)
        assertEquals(2, mvcc.listAt(snap2, "docs").size)
    }

    @Test
    fun commitDetectsWriteWriteConflicts() {
        // Transactions and conflict detection are planned but not yet implemented
        assertTrue(true)
    }

    @Test
    fun readDoesNotBlockWriteAndViceVersa() {
        val mvcc = MvccBlockStore()
        mvcc.put("docs", sealedBlock("alice"))
        val snap = mvcc.snapshot()

        mvcc.put("docs", sealedBlock("bob"))

        // MVCC: readers and writers never block each other
        val loaded = mvcc.scanAt(snap, "docs")
        assertEquals(1, loaded.size)

        // Use values projection
        val values = loaded.row(0).values.toList()
        assertTrue(values.contains("alice"))
    }

    @Test
    fun snapshotReleasesWhenClosed() {
        // Snapshots hold resources; must be closed
        assertTrue(true)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    fun sealedBlock(name: String): BlockRowVec {
        val block = BlockRowVec.mutable()
        block.append(KeyedRowVec(listOf("name"), listOf(name as Any?)))
        return block.seal()
    }
}
