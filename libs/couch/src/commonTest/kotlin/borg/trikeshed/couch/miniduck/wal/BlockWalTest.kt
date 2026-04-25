package borg.trikeshed.couch.miniduck.tablespace

import borg.trikeshed.couch.miniduck.*
import borg.trikeshed.lib.*
import kotlin.test.*

/**
 * RED test: WAL for block-store operations.
 *
 * Every put/remove first appends to the WAL. On crash, replay the WAL
 * to reconstruct the BlockStore state. Sequence numbers are monotonic
 * and gap-free — they're the MVCC clock.
 *
 * Donor patterns: LSMRWal skeleton, CouchDB _changes feed, Raft log.
 */
class BlockWalTest {

    // ── Sequence monotonicity ────────────────────────────────────────────

    @Test
    fun appendAssignsMonotonicSequence() {
        val wal = InMemoryBlockWal()
        val s1 = wal.append(WalOp.Put("docs", "blk-0", buildBlock("alice")))
        val s2 = wal.append(WalOp.Put("docs", "blk-1", buildBlock("bob")))
        val s3 = wal.append(WalOp.Put("docs", "blk-2", buildBlock("charlie")))
        assertTrue(s1 < s2)
        assertTrue(s2 < s3)
    }

    @Test
    fun sequenceStartsAtOne() {
        val wal = InMemoryBlockWal()
        val s = wal.append(WalOp.Put("docs", "blk-0", buildBlock("x")))
        assertEquals(1L, s)
    }

    // ── Read range ───────────────────────────────────────────────────────

    @Test
    fun readRangeReturnsEntriesInclusiveExclusive() {
        val wal = InMemoryBlockWal()
        wal.append(WalOp.Put("docs", "blk-0", buildBlock("a")))
        wal.append(WalOp.Put("docs", "blk-1", buildBlock("b")))
        wal.append(WalOp.Put("docs", "blk-2", buildBlock("c")))
        wal.append(WalOp.Put("docs", "blk-3", buildBlock("d")))

        // read [2, 4) → sequences 2, 3
        val entries = wal.readRange(2L, 4L)
        assertEquals(2, entries.size)
        assertEquals(2L, entries[0].seq)
        assertEquals(3L, entries[1].seq)
    }

    @Test
    fun readRangeEmptyWhenNoEntries() {
        val wal = InMemoryBlockWal()
        assertTrue(wal.readRange(1L, 100L).isEmpty())
    }

    @Test
    fun readFromReturnsAllEntriesFromSequence() {
        val wal = InMemoryBlockWal()
        wal.append(WalOp.Put("docs", "blk-0", buildBlock("a")))
        wal.append(WalOp.Put("docs", "blk-1", buildBlock("b")))
        wal.append(WalOp.Put("docs", "blk-2", buildBlock("c")))

        val from2 = wal.readFrom(2L)
        assertEquals(2, from2.size)
        assertEquals(2L, from2[0].seq)
        assertEquals(3L, from2[1].seq)
    }

    // ── Replay to reconstruct BlockStore ─────────────────────────────────

    @Test
    fun replayReconstructsBlockStore() {
        val wal = InMemoryBlockWal()
        wal.append(WalOp.Put("docs", "blk-0", buildBlock("alice")))
        wal.append(WalOp.Put("docs", "blk-1", buildBlock("bob")))
        wal.append(WalOp.Remove("docs", "blk-0"))
        wal.append(WalOp.Put("docs", "blk-2", buildBlock("charlie")))

        val store = InMemoryBlockStore()
        wal.replay(store)

        // blk-0 was removed, blk-1 and blk-2 should exist
        assertNull(store.get("docs", "blk-0"))
        assertNotNull(store.get("docs", "blk-1"))
        assertNotNull(store.get("docs", "blk-2"))
        assertEquals(2, store.list("docs").size)
    }

    @Test
    fun replayIdempotent() {
        val wal = InMemoryBlockWal()
        wal.append(WalOp.Put("docs", "blk-0", buildBlock("x")))

        val store = InMemoryBlockStore()
        wal.replay(store)
        wal.replay(store) // second replay should be idempotent

        assertEquals(1, store.list("docs").size)
    }

    // ── Compaction ───────────────────────────────────────────────────────

    @Test
    fun compactTrimsOldEntries() {
        val wal = InMemoryBlockWal()
        wal.append(WalOp.Put("docs", "blk-0", buildBlock("a")))
        wal.append(WalOp.Put("docs", "blk-1", buildBlock("b")))
        wal.append(WalOp.Put("docs", "blk-2", buildBlock("c")))
        wal.append(WalOp.Put("docs", "blk-3", buildBlock("d")))

        // Compact: keep entries from seq 3 onward
        wal.compact(keepFromSeq = 3L)

        val remaining = wal.readFrom(1L)
        assertEquals(2, remaining.size)
        assertEquals(3L, remaining[0].seq)
        assertEquals(4L, remaining[1].seq)
    }

    @Test
    fun compactWithKeepFromOneKeepsAll() {
        val wal = InMemoryBlockWal()
        wal.append(WalOp.Put("docs", "blk-0", buildBlock("a")))
        wal.append(WalOp.Put("docs", "blk-1", buildBlock("b")))

        wal.compact(keepFromSeq = 1L)

        assertEquals(2, wal.readFrom(1L).size)
    }

    // ── Snapshot ─────────────────────────────────────────────────────────

    @Test
    fun snapshotCapturesStateAtSequence() {
        val wal = InMemoryBlockWal()
        wal.append(WalOp.Put("docs", "blk-0", buildBlock("a")))
        wal.append(WalOp.Put("docs", "blk-1", buildBlock("b")))
        val snapSeq = wal.headSequence
        wal.append(WalOp.Put("docs", "blk-2", buildBlock("c")))

        // Snapshot at snapSeq should not include blk-2
        val store = InMemoryBlockStore()
        wal.replayTo(store, snapSeq)

        assertNotNull(store.get("docs", "blk-0"))
        assertNotNull(store.get("docs", "blk-1"))
        assertNull(store.get("docs", "blk-2"))
    }

    // ── Head/durable sequence ────────────────────────────────────────────

    @Test
    fun headSequenceTracksLastAppend() {
        val wal = InMemoryBlockWal()
        assertEquals(0L, wal.headSequence)
        wal.append(WalOp.Put("docs", "blk-0", buildBlock("a")))
        assertEquals(1L, wal.headSequence)
        wal.append(WalOp.Put("docs", "blk-1", buildBlock("b")))
        assertEquals(2L, wal.headSequence)
    }

    // ── Collection isolation ─────────────────────────────────────────────

    @Test
    fun replayCollectionIsolation() {
        val wal = InMemoryBlockWal()
        wal.append(WalOp.Put("users", "blk-0", buildBlock("alice")))
        wal.append(WalOp.Put("orders", "blk-0", buildBlock("order-1")))

        val store = InMemoryBlockStore()
        wal.replay(store)

        assertEquals(1, store.list("users").size)
        assertEquals(1, store.list("orders").size)
        assertNull(store.get("users", "blk-1"))
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun buildBlock(name: String): BlockRowVec {
        val block = BlockRowVec.mutable()
        block.append(DocRowVec(listOf("name"), listOf(name)))
        return block.seal()
    }
}
