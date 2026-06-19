package borg.trikeshed.miniduck.lsmr

/**
 * A single entry in the LSMR merge tree.
 *
 * @param key     the sort key
 * @param value   the stored value (null if deleted)
 * @param seq     the WAL sequence number (higher = newer)
 * @param deleted true if this is a tombstone (delete marker)
 */
data class LsmrEntry(
    val key: String,
    val value: String?,
    val seq: Long,
    val deleted: Boolean = false,
) : Comparable<LsmrEntry> {
    override fun compareTo(other: LsmrEntry): Int = key.compareTo(other.key)
}

/**
 * A sorted run — a contiguous sequence of entries sorted by key.
 * Runs are immutable once sealed.
 */
data class SortedRun(
    val entries: List<LsmrEntry>,
    val isSealed: Boolean = true,
) {
    /** Merge two sorted runs, keeping the newest seq for each key. Tombstones suppress older entries. */
    fun mergeWith(other: SortedRun): SortedRun {
        val merged = mutableListOf<LsmrEntry>()
        var i = 0
        var j = 0

        while (i < entries.size && j < other.entries.size) {
            val a = entries[i]
            val b = other.entries[j]
            val cmp = a.key.compareTo(b.key)

            when {
                cmp < 0 -> { merged.add(a); i++ }
                cmp > 0 -> { merged.add(b); j++ }
                else -> {
                    // Same key — keep the one with higher seq
                    merged.add(if (a.seq >= b.seq) a else b)
                    i++; j++
                }
            }
        }
        while (i < entries.size) { merged.add(entries[i]); i++ }
        while (j < other.entries.size) { merged.add(other.entries[j]); j++ }

        return SortedRun(merged, isSealed = true)
    }

    /** Deduplicate within a single run: keep the newest seq for each key. */
    fun deduplicate(): SortedRun {
        if (entries.isEmpty()) return this
        // Sort by key, then by seq descending (newest first)
        val sorted = entries.sortedWith(compareBy<LsmrEntry> { it.key }.thenByDescending { it.seq })
        val deduped = mutableListOf<LsmrEntry>()
        var lastKey: String? = null
        for (entry in sorted) {
            if (entry.key != lastKey) {
                deduped.add(entry)
                lastKey = entry.key
            }
        }
        return SortedRun(deduped, isSealed = true)
    }

    companion object {
        fun fromUnsorted(entries: List<LsmrEntry>): SortedRun =
            SortedRun(entries.sortedBy { it.key }, isSealed = true)

        fun empty(): SortedRun = SortedRun(emptyList(), isSealed = true)
    }
}

/**
 * LSMR merge tree — multi-level sorted runs with compaction.
 *
 * Data flows: write → L0 (memory) → flush → L1 (disk runs) → merge → L2 (merged).
 * Compaction reduces read amplification by merging overlapping runs.
 *
 * Donor: LSMR database, B-tree/LSM hybrid, CouchDB B+tree compaction.
 */
class LsmrMergeTree {

   val l0Buffer = mutableListOf<LsmrEntry>()
   val l1Runs = mutableListOf<SortedRun>()
   val l2Runs = mutableListOf<SortedRun>()

    // ── Write path ───────────────────────────────────────────────────────

    /** Insert a key-value pair with a sequence number into L0. */
    fun put(key: String, value: String, seq: Long) {
        l0Buffer.add(LsmrEntry(key, value, seq, deleted = false))
    }

    /** Mark a key as deleted (tombstone) in L0. */
    fun delete(key: String, seq: Long) {
        l0Buffer.add(LsmrEntry(key, null, seq, deleted = true))
    }

    // ── Flush L0 → L1 ────────────────────────────────────────────────────

    /** Flush the L0 buffer into a new sorted run in L1. Noop if L0 is empty. */
    fun flush() {
        if (l0Buffer.isEmpty()) return
        val run = SortedRun.fromUnsorted(l0Buffer.toList())
        l1Runs.add(run)
        l0Buffer.clear()
    }

    // ── Merge L1 → L2 ────────────────────────────────────────────────────

    /** Merge all L1 runs into a single L2 run. Deduplicates by key, keeping newest seq. */
    fun merge() {
        if (l1Runs.isEmpty()) return

        // First: deduplicate within each run (keep newest seq per key)
        val dedupedRuns = l1Runs.map { it.deduplicate() }

        var result = dedupedRuns.first()
        for (i in 1 until dedupedRuns.size) {
            result = result.mergeWith(dedupedRuns[i])
        }
        // Remove tombstones — they've served their purpose
        val cleaned = SortedRun(result.entries.filter { !it.deleted }, isSealed = true)
        l2Runs.add(cleaned)
        l1Runs.clear()
    }

    // ── Scan ─────────────────────────────────────────────────────────────

    /**
     * Scan all entries across L0, L1, and L2, sorted by key.
     * For duplicate keys, the entry with the highest seq wins.
     * Tombstones (deleted=true) suppress the key from results.
     */
    fun scan(): Sequence<LsmrEntry> = sequence {
        // Collect all entries from all levels
        val allEntries = mutableListOf<LsmrEntry>()
        allEntries.addAll(l0Buffer)
        for (run in l1Runs) allEntries.addAll(run.entries)
        for (run in l2Runs) allEntries.addAll(run.entries)

        if (allEntries.isEmpty()) return@sequence

        // Sort by key, then by seq descending (newest first)
        allEntries.sortWith(compareBy<LsmrEntry> { it.key }.thenByDescending { it.seq })

        // Deduplicate: keep first (newest) for each key, skip tombstones
        var lastKey: String? = null
        for (entry in allEntries) {
            if (entry.key == lastKey) continue
            lastKey = entry.key
            if (!entry.deleted) {
                yield(entry)
            }
        }
    }

    // ── Level sizes (for testing) ─────────────────────────────────────────

    fun level0Size(): Int = l0Buffer.size
    fun level1RunCount(): Int = l1Runs.size
    fun level1Size(): Int = l1Runs.sumOf { it.entries.size }
    fun level2RunCount(): Int = l2Runs.size
    fun level2Size(): Int = l2Runs.sumOf { it.entries.size }
}
