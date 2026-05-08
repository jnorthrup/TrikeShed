package borg.trikeshed.couch.btrfs

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.couch.wal.CompactionPlan
import borg.trikeshed.couch.wal.CompactionResult
import borg.trikeshed.couch.wal.LSMRWal
import borg.trikeshed.couch.wal.SnapshotRequest
import borg.trikeshed.couch.wal.WalEntry
import borg.trikeshed.couch.wal.WalSnapshot
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

class BtrfsWal(private val element: BtrfsSandboxElement) : AsyncContextElement(), LSMRWal {
    companion object Key : AsyncContextKey<BtrfsWal>()
    override val key: AsyncContextKey<BtrfsWal> get() = Key

    private var nextSeq = 1L

    override val headSequence: Long get() = nextSeq - 1
    override val durableSequence: Long get() = headSequence

    override suspend fun append(entry: WalEntry): Long {
        requireState(ElementState.OPEN)
        val seq = if (entry.seq <= 0L) nextSeq++ else entry.seq
        element.btree.put(seq, entry.payload)
        return seq
    }

    override suspend fun read(fromInclusive: Long, toExclusive: Long): Series<WalEntry> {
        requireState(ElementState.OPEN)
        val entries = mutableListOf<WalEntry>()
        for (i in fromInclusive until toExclusive) {
            val v = element.btree.get(i)
            if (v != null) {
                entries.add(WalEntry(i, v))
            }
        }
        return entries.size j { i -> entries[i] }
    }

    override suspend fun readFrom(fromInclusive: Long): Series<WalEntry> {
        return read(fromInclusive, headSequence + 1)
    }

    override suspend fun snapshot(request: SnapshotRequest): WalSnapshot {
        val series = read(1L, request.untilSequence + 1)
        val list = mutableListOf<WalEntry>()
        for (i in 0 until series.a) {
            list.add(series.b(i))
        }
        return WalSnapshot(list)
    }

    override suspend fun compact(plan: CompactionPlan): CompactionResult {
        return CompactionResult(1)
    }
}
