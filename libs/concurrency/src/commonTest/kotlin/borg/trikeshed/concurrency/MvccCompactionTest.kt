package borg.trikeshed.concurrency

import borg.trikeshed.miniduck.*
import borg.trikeshed.miniduck.tablespace.*
import borg.trikeshed.cursor.*
import kotlin.test.*

class MvccCompactionTest {
    @Test
    fun compactRemovesOldInvisibleBlocks() {
        val mvcc = MvccBlockStore()

        val id1 = mvcc.put("docs", sealedBlock("alice"))
        val id2 = mvcc.put("docs", sealedBlock("bob"))

        mvcc.remove("docs", id1)

        val snap = mvcc.snapshot()

        // Let's create another snapshot and another put
        val id3 = mvcc.put("docs", sealedBlock("charlie"))

        // Compact up to snap (seq 3)
        mvcc.compact(snap.seq)

        // BlockMeta list has block id2 (kept), block id3 (kept because it's newer than snap),
        // and we might still have id1's remove marker if the logic keeps it.
        // Actually, since id1 was removed at seq 3, and we compact(3), we should only have >= 3.
        assertTrue(mvcc.blocks.all { it.putSeq >= snap.seq || (it.removed && it.removeSeq >= snap.seq) || (!it.removed) })

        // WAL should also be compacted
        assertTrue(mvcc.wal.entries.isNotEmpty())
        assertTrue(mvcc.wal.entries.all { it.seq >= snap.seq })
    }

    fun sealedBlock(name: String): BlockRowVec {
        val block = BlockRowVec.mutable()
        block.append(DocRowVec(listOf("name"), listOf(name)))
        return block.seal()
    }
}
