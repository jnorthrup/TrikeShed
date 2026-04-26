package borg.trikeshed.miniduck.query

import borg.trikeshed.miniduck.*
import borg.trikeshed.lib.*

/**
 * Advanced cursor operations: GROUP BY aggregation and hash join.
 *
 * These extend MiniCursor with SQL-like transforms that produce new cursors.
 * All operations are pure — they don't modify the source cursors.
 *
 * Donor: DuckDB hash aggregate, Spark broadcastHashJoin.
 */

// ── GROUP BY ────────────────────────────────────────────────────────────────

/**
 * Group rows by a key column, applying one or more aggregation functions.
 *
 * Returns a new MiniCursor with one row per group. Output columns are:
 *   [groupKey, agg1, agg2, ...]
 *
 * Usage:
 *   cursor.groupBy("dept", Agg.count(), Agg.sum("salary"))
 */
fun MiniCursor.groupBy(keyColumn: String, vararg aggs: Agg): MiniCursor {
    if (size == 0) return emptyMiniCursor()

    // Phase 1: collect groups
    val groups = linkedMapOf<Any?, MutableList<Int>>()
    for (i in 0 until size) {
        val row = at(i)
        val key = row.getValue(keyColumn)
        groups.getOrPut(key) { mutableListOf() }.add(i)
    }

    // Phase 2: compute aggregations per group
    val outputKeys = mutableListOf(keyColumn)
    outputKeys.addAll(aggs.map { it.outputName })

    val rows = mutableListOf<DocRowVec>()
    for ((groupKey, indices) in groups) {
        val accumulators = aggs.map { it.createAccumulator() }

        for (idx in indices) {
            val row = at(idx)
            for ((j, agg) in aggs.withIndex()) {
                val colName = when (agg) {
                    is Agg.Count -> if (agg.column == "*") null else agg.column
                    is Agg.Sum -> agg.column
                    is Agg.Avg -> agg.column
                    is Agg.Min -> agg.column
                    is Agg.Max -> agg.column
                }
                val value = if (colName != null) row.getValue(colName) else null
                accumulators[j].add(value)
            }
        }

        val cells = mutableListOf<Any?>(groupKey)
        cells.addAll(accumulators.map { it.result() })
        rows.add(DocRowVec(keys = outputKeys, cells = cells))
    }

    return rows.size j { rows[it] }
}

// ── HASH JOIN ───────────────────────────────────────────────────────────────

/**
 * Hash join: combine rows from two cursors where [leftKey] matches [rightKey].
 *
 * Output rows have columns from both sides. The join key column from the right
 * side is omitted to avoid duplication.
 *
 * Null keys never match (SQL semantics: NULL != NULL).
 *
 * Usage:
 *   orders.hashJoin(users, "userId", "id")
 */
fun MiniCursor.hashJoin(
    right: MiniCursor,
    leftKey: String,
    rightKey: String,
): MiniCursor {
    if (size == 0 || right.size == 0) return emptyMiniCursor()

    // Phase 1: build hash table from right side
    val hashTable = mutableMapOf<Any?, MutableList<Int>>()
    for (i in 0 until right.size) {
        val key = right.at(i).getValue(rightKey)
        if (key != null) {
            hashTable.getOrPut(key) { mutableListOf() }.add(i)
        }
    }

    // Phase 2: probe with left side
    val resultRows = mutableListOf<DocRowVec>()
    for (i in 0 until size) {
        val leftRow = at(i) as? DocRowVec ?: continue
        val probeKey = leftRow.getValue(leftKey) ?: continue

        val matches = hashTable[probeKey] ?: continue
        for (matchIdx in matches) {
            val rightRow = right.at(matchIdx) as? DocRowVec ?: continue

            // Merge: left columns + right columns (excluding right join key)
            val mergedKeys = leftRow.keys + rightRow.keys.filter { it != rightKey }
            val mergedCells = leftRow.cells + rightRow.keys.indices
                .filter { rightRow.keys[it] != rightKey }
                .map { rightRow.cells[it] }

            resultRows.add(DocRowVec(keys = mergedKeys, cells = mergedCells))
        }
    }

    return resultRows.size j { resultRows[it] }
}

/** Infix alias for [hashJoin]. */
infix fun MiniCursor.join(
    other: Pair<MiniCursor, Pair<String, String>>,
): MiniCursor = hashJoin(other.first, other.second.first, other.second.second)

/**
 * Convenience: `cursor.join(other, leftKey, rightKey)`.
 */
fun MiniCursor.join(
    right: MiniCursor,
    leftKey: String,
    rightKey: String,
): MiniCursor = hashJoin(right, leftKey, rightKey)
