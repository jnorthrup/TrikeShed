package borg.trikeshed.miniduck.lsmr

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

/**
 * LSMR entry: key j (value j (seq j deleted))
 *
 * Stays as a product of Join — named access via extension properties.
 * Sorting and merging operate only on .key and .seq; no reflection.
 */
typealias LsmrEntry = Join<String, Join<String, Join<Long, Boolean>>>

fun lsmrEntry(key: String, value: String, seq: Long, deleted: Boolean = false): LsmrEntry =
    key j (value j (seq j deleted))

val LsmrEntry.key: String get() = a
val LsmrEntry.value: String get() = b.a
val LsmrEntry.seq: Long get() = b.b.a
val LsmrEntry.deleted: Boolean get() = b.b.b

private val entryKeyOrder: Comparator<LsmrEntry> =
    compareBy<LsmrEntry> { it.key }.thenByDescending { it.seq }

/**
 * LSMR merge tree: L0 (memory) → L1 (sorted sealed runs) → L2 (merged runs).
 *
 * Invariants:
 *  - L0 is mutable; flush seals it into a sorted L1 run.
 *  - merge() collapses all L1 runs into a single L2 run, resolving
 *    duplicates (latest seq wins) and suppressing tombstones.
 *  - scan() merges across all levels lazily, newest seq per key wins.
 */
class LsmrMergeTree {
    private val l0: MutableList<LsmrEntry> = mutableListOf()
    private val l1Runs: MutableList<List<LsmrEntry>> = mutableListOf()
    private val l2Runs: MutableList<List<LsmrEntry>> = mutableListOf()

    fun put(key: String, value: String, seq: Long) {
        l0.add(lsmrEntry(key, value, seq))
    }

    fun delete(key: String, seq: Long) {
        l0.add(lsmrEntry(key, "", seq, deleted = true))
    }

    /** Seal L0 into a sorted immutable L1 run. No-op if L0 is empty. */
    fun flush() {
        if (l0.isEmpty()) return
        l1Runs.add(l0.sortedWith(entryKeyOrder))
        l0.clear()
    }

    /**
     * Merge all L1 runs into one L2 run.
     * Keeps newest seq per key; removes tombstones with no surviving value.
     */
    fun merge() {
        if (l1Runs.isEmpty()) return
        val merged = mergeRuns(l1Runs)
        l2Runs.add(merged)
        l1Runs.clear()
    }

    /**
     * Scan all levels: L0, L1, L2. Merges lazily — latest seq per key wins;
     * deleted entries are suppressed.
     */
    fun scan(): Sequence<LsmrEntry> {
        val allRuns = buildList {
            if (l0.isNotEmpty()) add(l0.sortedWith(entryKeyOrder))
            addAll(l1Runs)
            addAll(l2Runs)
        }
        return mergeRuns(allRuns).asSequence()
    }

    fun level0Size(): Int = l0.size
    fun level1Size(): Int = l1Runs.sumOf { it.size }
    fun level1RunCount(): Int = l1Runs.size
    fun level2RunCount(): Int = l2Runs.size
    fun level2Size(): Int = l2Runs.sumOf { it.size }

    /** Merge a list of sorted runs: newest seq per key wins; tombstones suppressed. */
    private fun mergeRuns(runs: List<List<LsmrEntry>>): List<LsmrEntry> {
        // Collect all entries, pick highest seq per key, then filter tombstones.
        val byKey = mutableMapOf<String, LsmrEntry>()
        for (run in runs) {
            for (entry in run) {
                val existing = byKey[entry.key]
                if (existing == null || entry.seq > existing.seq) {
                    byKey[entry.key] = entry
                }
            }
        }
        return byKey.values
            .filter { !it.deleted }
            .sortedBy { it.key }
    }
}
