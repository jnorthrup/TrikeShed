package borg.trikeshed.couch.wal

import borg.trikeshed.lib.Series

/**
 * Minimal, compile-safe skeleton for the LSMR WAL interface.
 *
 * This file is intentionally small and contains placeholder types
 * so it can be iterated on in follow-up commits.
 */
interface LSMRWal {
    val headSequence: Long
    val durableSequence: Long

    suspend fun append(entry: WalEntry): Long
    suspend fun read(fromInclusive: Long, toExclusive: Long): Series<WalEntry>
    suspend fun readFrom(fromInclusive: Long): Series<WalEntry>
    suspend fun snapshot(request: SnapshotRequest): WalSnapshot
    suspend fun compact(plan: CompactionPlan): CompactionResult
}

/* Minimal placeholder types - replace with full domain types later. */
data class WalEntry(val seq: Long = 0L, val payload: CharSequence = "")
data class SnapshotRequest(val untilSequence: Long = 0L)
data class WalSnapshot(val entries: List<WalEntry> = emptyList())
data class CompactionPlan(val keepUntilSequence: Long = 0L)
data class CompactionResult(val compactedSegments: Int = 0)
