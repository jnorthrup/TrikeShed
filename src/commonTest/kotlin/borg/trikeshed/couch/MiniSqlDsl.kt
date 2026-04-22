@file:Suppress("FunctionName")

package borg.trikeshed.couch

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*


/**
 * Functional SQL DSL using Series/Join composition.
 *
 * This is a JOIN-CENTRIC and SERIES-CENTRIC approach where queries are
 * composed by combining Series (lazy sequences) and Join operations.
 *
 * Instead of mutable builders, queries are pure functional transformations
 * that compose via the Series algebra.
 *
 * Key principles:
 * 1. Lazy evaluation - operations compose without materializing intermediate results
 * 2. Join-centric - queries are built from Join operations on Series
 * 3. Series-based - all operations work on the Series abstraction
 * 4. Functional - predicates are functions, not builder methods
 *
 * Example:
 * ```
 * val results = users
 *   .filter { it["age"] as Int > 30 }
 *   .sortBy { it["age"] as Int }
 *   .take(5)
 *   .select("name", "age")
 * ```
 *
 * Or with joins:
 * ```
 * val results = users
 *   .innerJoin(orders, on = { u, o -> u["id"] == o["user_id"] })
 *   .filter { (_, o) -> (o["total"] as Double) > 100.0 }
 *   .select("name", "total")
 * ```
 */
interface MiniSqlDsl {
    /**
     * Query a table as a Cursor (Series of RowVec).
     * Returns all rows by default as a lazy Series.
     */
    suspend fun query(table: String): Cursor
}

/**
 * Represents a join condition between two tables.
 * This is a functional predicate rather than a builder method.
 */
typealias JoinCondition = (RowAccessor, RowAccessor) -> Boolean

/**
 * Represents a filter predicate on a single table.
 * This is a functional predicate that operates on row accessors.
 */
typealias RowPredicate = (RowAccessor) -> Boolean

/**
 * Type alias for row accessor (map of column name to value).
 */
typealias RowAccessor = Map<String, Any?>

/**
 * Creates a RowAccessor from a Join (pair) of values.
 */
fun joinToRowAccessor(left: RowAccessor?, right: RowAccessor?): RowAccessor {
    val result = mutableMapOf<String, Any?>()
    left?.let { result.putAll(it) }
    right?.let { result.putAll(it) }
    return result
}

/**
 * Creates a RowAccessor from a RowVec (Series2<Any?, () -> ColumnMeta>).
 */
fun RowVec.toRowAccessor(): RowAccessor {
    val map = mutableMapOf<String, Any?>()
    for (i in 0 until size) {
        val pair = this[i] as Join<Any?, () -> ColumnMeta>
        val value = pair.a
        val metaProvider = pair.b as (() -> borg.trikeshed.cursor.ColumnMeta)
        val meta: borg.trikeshed.cursor.ColumnMeta = metaProvider()
        map[meta.name] = value
    }
    return map
}

/**
 * Join type enumeration for SQL-like join semantics.
 */
enum class JoinType {
    /** Inner join - only matching rows */
    INNER,
    /** Left join - all left rows, matching right rows */
    LEFT,
    /** Right join - all right rows, matching left rows */
    RIGHT,
    /** Full outer join - all rows from both sides */
    FULL
}

/**
 * Lazy filter operation on Cursor using Series composition.
 *
 * This creates a new Series that lazily filters rows without materializing.
 */
fun Cursor.filter(predicate: RowPredicate): Cursor = object : Series<RowVec> {
    override val a: Int
        get() = this@filter.size

    override val b: (Int) -> RowVec = { idx ->
        val row = this@filter[idx]
        val accessor = row.toRowAccessor()
        if (predicate(accessor)) {
            row
        } else {
            // Return an empty row for non-matching rows
            // Build explicitly to help the compiler with generics
            val nullElem: Join<Any?, () -> ColumnMeta> = Join(null as Any?, { ColumnMeta("_", SeqTypeMemento) })
            val nullRow: RowVec = Join(0, { _ -> nullElem })
            nullRow
        }
    }
}.collect { it.size > 0 }

/**
 * Collect a Series by applying a predicate to filter elements.
 * This creates a new Series with only the matching elements.
 */
fun <T> Series<T>.collect(predicate: (T) -> Boolean): Series<T> {
    // First pass: count matching elements
    val matchingIndices = mutableListOf<Int>()
    for (i in 0 until size) {
        if (predicate(this[i])) {
            matchingIndices.add(i)
        }
    }

    // Second pass: create a new Series with only matching elements
    return matchingIndices.size j { idx -> this[matchingIndices[idx]] }
}

/**
 * Lazy sort operation on Cursor using Series composition.
 *
 * This creates a new Series with sorted indices.
 */
fun Cursor.sortBy(comparator: Comparator<RowAccessor>): Cursor {
    // Create indices and sort them
    val indices = (0 until size).toList().sortedWith { a, b ->
        val rowA = this[a].toRowAccessor()
        val rowB = this[b].toRowAccessor()
        comparator.compare(rowA, rowB)
    }

    // Create a new Series with sorted indices
    return indices.size j { idx -> this[indices[idx]] }
}

/**
 * Sort by a single key in ascending order.
 */
fun <T : Comparable<T>> Cursor.sortByAsc(keyExtractor: (RowAccessor) -> T): Cursor =
    sortBy(compareBy(keyExtractor))

/**
 * Sort by a single key in descending order.
 */
fun <T : Comparable<T>> Cursor.sortByDesc(keyExtractor: (RowAccessor) -> T): Cursor =
    sortBy(compareByDescending(keyExtractor))

/**
 * Sort specification for multi-column sorting.
 */
data class SortSpec<T : Comparable<T>>(
    val keyExtractor: (RowAccessor) -> T,
    val direction: SortDirection = SortDirection.ASC
)

enum class SortDirection { ASC, DESC }

/**
 * Sort by multiple keys.
 */
fun Cursor.sortBy(vararg specs: SortSpec<*>): Cursor {
    val indices = (0 until size).toList().sortedWith { a, b ->
        val rowA = this[a].toRowAccessor()
        val rowB = this[b].toRowAccessor()

        for (spec in specs) {
            @Suppress("UNCHECKED_CAST")
            val keyA = spec.keyExtractor(rowA) as Comparable<Any>
            @Suppress("UNCHECKED_CAST")
            val keyB = spec.keyExtractor(rowB) as Comparable<Any>
            val cmp = keyA.compareTo(keyB)
            if (cmp != 0) {
                return@sortedWith if (spec.direction == SortDirection.DESC) -cmp else cmp
            }
        }
        0
    }

    return indices.size j { idx -> this[indices[idx]] }
}

/**
 * Take first n elements from a Cursor.
 * This is lazy and creates a view into the original Series.
 */
fun Cursor.take(n: Int): Cursor = if (size <= n) this else n j { this[it] }

/**
 * Drop first n elements from a Cursor.
 * This is lazy and creates a view into the original Series.
 */
fun Cursor.drop(n: Int): Cursor = if (size <= n) {
    emptySeries()
} else {
    (size - n) j { this[n + it] }
}

/**
 * Select specific columns by name from a Cursor.
 * This creates a new Cursor with only the specified columns.
 */
fun Cursor.select(vararg columnNames: String): Cursor {
    if (columnNames.isEmpty()) return this

    // Get column indices from metadata
    val meta = this.meta
    val columnIndices = columnNames.map { name ->
        var found = -1
        for (i in 0 until meta.size) {
            if (meta[i].name == name) {
                found = i
                break
            }
        }
        if (found == -1) throw IllegalArgumentException("Column '$name' not found")
        found
    }

    // Create a new Cursor with only selected columns
    return size j { rowIdx ->
        val row = this[rowIdx]
        columnIndices.size j { colIdx -> row[columnIndices[colIdx]] }
    }
}

/**
 * Select specific columns by index from a Cursor.
 */
fun Cursor.select(vararg columnIndexes: Int): Cursor =
    this[columnIndexes]

/**
 * INNER JOIN two cursors on a condition.
 *
 * This creates a lazy join that doesn't materialize the result until needed.
 *
 * Example:
 * ```
 * val joined = users.innerJoin(orders) { u, o -> u["id"] == o["user_id"] }
 * ```
 */
fun Cursor.innerJoin(
    other: Cursor,
    on: JoinCondition
): Cursor = performJoin(other, JoinType.INNER, on)

/**
 * LEFT JOIN two cursors on a condition.
 */
fun Cursor.leftJoin(
    other: Cursor,
    on: JoinCondition
): Cursor = performJoin(other, JoinType.LEFT, on)

/**
 * RIGHT JOIN two cursors on a condition.
 */
fun Cursor.rightJoin(
    other: Cursor,
    on: JoinCondition
): Cursor = performJoin(other, JoinType.RIGHT, on)

/**
 * FULL OUTER JOIN two cursors on a condition.
 */
fun Cursor.fullJoin(
    other: Cursor,
    on: JoinCondition
): Cursor = performJoin(other, JoinType.FULL, on)

/**
 * Core join implementation using Series composition.
 *
 * This performs a hash-based join for efficiency and creates
 * a new Cursor with joined rows.
 */
private fun Cursor.performJoin(
    other: Cursor,
    joinType: JoinType,
    on: JoinCondition
): Cursor {
    // Convert cursors to lists of RowAccessor for join processing
    val leftRows: List<Pair<RowAccessor, RowVec>> = (0 until size).map { idx ->
        this[idx].toRowAccessor() to this[idx]
    }
    val rightRows: List<Pair<RowAccessor, RowVec>> = (0 until other.size).map { idx ->
        other[idx].toRowAccessor() to other[idx]
    }

    // Build join result
    val joinResults = mutableListOf<Pair<RowVec, RowVec>>()

    when (joinType) {
        JoinType.INNER -> {
            // Inner join: only matching pairs
            for ((leftAccessor, leftRow) in leftRows) {
                for ((rightAccessor, rightRow) in rightRows) {
                    if (on(leftAccessor, rightAccessor)) {
                        joinResults.add(leftRow to rightRow)
                    }
                }
            }
        }
        JoinType.LEFT -> {
            // Left join: all left rows, matching right rows or null
            for ((leftAccessor, leftRow) in leftRows) {
                var matched = false
                for ((rightAccessor, rightRow) in rightRows) {
                    if (on(leftAccessor, rightAccessor)) {
                        joinResults.add(leftRow to rightRow)
                        matched = true
                    }
                }
                if (!matched) {
                    // Add with null right side
                    val nullRow: RowVec = 0 j { _ -> (null as Any?) j { ColumnMeta("_", SeqTypeMemento) } }
                    joinResults.add(leftRow to nullRow)
                }
            }
        }
        JoinType.RIGHT -> {
            // Right join: all right rows, matching left rows or null
            for ((rightAccessor, rightRow) in rightRows) {
                var matched = false
                for ((leftAccessor, leftRow) in leftRows) {
                    if (on(leftAccessor, rightAccessor)) {
                        joinResults.add(leftRow to rightRow)
                        matched = true
                    }
                }
                if (!matched) {
                    // Add with null left side
                    val nullRow: RowVec = 0 j { _ -> (null as Any?) j { ColumnMeta("_", SeqTypeMemento) } }
                    joinResults.add(nullRow to rightRow)
                }
            }
        }
        JoinType.FULL -> {
            // Full join: all rows from both sides
            val leftMatched = mutableSetOf<Int>()
            val rightMatched = mutableSetOf<Int>()

            for ((leftIdx, element) in leftRows.withIndex()) {
                val (leftAccessor, leftRow) = element
                for ((rightIdx, element2) in rightRows.withIndex()) {
                    val (rightAccessor, rightRow) = element2
                    if (on(leftAccessor, rightAccessor)) {
                        joinResults.add(leftRow to rightRow)
                        leftMatched.add(leftIdx)
                        rightMatched.add(rightIdx)
                    }
                }
            }

            // Add unmatched left rows
            for ((leftIdx, element) in leftRows.withIndex()) {
                val (leftAccessor, leftRow) = element
                if (leftIdx !in leftMatched) {
                    val nullRow: RowVec = 0 j { _ -> (null as Any?) j { ColumnMeta("_", SeqTypeMemento) } }
                    joinResults.add(leftRow to nullRow)
                }
            }

            // Add unmatched right rows
            for ((rightIdx, element) in rightRows.withIndex()) {
                val (rightAccessor, rightRow) = element
                if (rightIdx !in rightMatched) {
                    val nullRow: RowVec = 0 j { _ -> (null as Any?) j { ColumnMeta("_", SeqTypeMemento) } }
                    joinResults.add(nullRow to rightRow)
                }
            }
        }
    }

    // Convert join results to a Cursor
    if (joinResults.isEmpty()) {
        return emptySeries()
    }

    return joinResults.size j { idx ->
        val (leftRow, rightRow) = joinResults[idx]
        // Combine the two rows
        val leftSize = leftRow.size
        val rightSize = rightRow.size
        (leftSize + rightSize) j { colIdx ->
            if (colIdx < leftSize) leftRow[colIdx] else rightRow[colIdx - leftSize]
        }
    }
}

/**
 * Aggregation operations on Cursors.
 *
 * These are lazy and only execute when called.
 */
sealed class Aggregation {
    data class Sum(val keyExtractor: (RowAccessor) -> Double) : Aggregation()
    data class Avg(val keyExtractor: (RowAccessor) -> Double) : Aggregation()
    data class Min(val keyExtractor: (RowAccessor) -> Double) : Aggregation()
    data class Max(val keyExtractor: (RowAccessor) -> Double) : Aggregation()
    data class Count(val keyExtractor: ((RowAccessor) -> Any?)? = null) : Aggregation()
}

/**
 * Execute an aggregation on a Cursor.
 */
suspend fun Cursor.aggregate(agg: Aggregation): Double? = when (agg) {
    is Aggregation.Sum -> {
        (0 until size).sumOf { idx ->
            val accessor = this[idx].toRowAccessor()
            agg.keyExtractor(accessor)
        }
    }
    is Aggregation.Avg -> {
        (0 until size).mapNotNull { idx ->
            val accessor = this[idx].toRowAccessor()
            agg.keyExtractor(accessor)
        }.average()
    }
    is Aggregation.Min -> {
        (0 until size).mapNotNull { idx ->
            val accessor = this[idx].toRowAccessor()
            agg.keyExtractor(accessor)
        }.minOrNull()
    }
    is Aggregation.Max -> {
        (0 until size).mapNotNull { idx ->
            val accessor = this[idx].toRowAccessor()
            agg.keyExtractor(accessor)
        }.maxOrNull()
    }
    is Aggregation.Count -> {
        if (agg.keyExtractor == null) {
            size.toDouble()
        } else {
            (0 until size).count { idx ->
                val accessor = this[idx].toRowAccessor()
                agg.keyExtractor(accessor) != null
            }.toDouble()
        }
    }
}

/**
 * Count rows matching a predicate.
 */
suspend fun Cursor.count(predicate: (RowPredicate)? = null): Int = if (predicate == null) {
    size
} else {
    (0 until size).count { idx ->
        val accessor = this[idx].toRowAccessor()
        predicate(accessor)
    }
}

/**
 * Group a Cursor by a key and apply aggregations.
 *
 * This returns a Series of groups, each containing the grouped rows.
 */
fun <K> Cursor.groupBy(
    keyExtractor: (RowAccessor) -> K
): Series<Pair<K, Cursor>> {
    val groups = mutableMapOf<K, MutableList<RowVec>>()

    for (i in 0 until size) {
        val row = this[i]
        val accessor = row.toRowAccessor()
        val key = keyExtractor(accessor)
        groups.getOrPut(key) { mutableListOf() }.add(row)
    }

    return groups.size j { idx ->
        val (key, rows) = groups.entries.elementAt(idx)
        key to (rows.size j { rowIdx -> rows[rowIdx] })
    }
}

/**
 * Apply aggregations to grouped data.
 */
suspend fun <K> Series<Pair<K, Cursor>>.aggregateGroups(
    aggregation: (Cursor) -> Double?
): Series<Pair<K, Double?>> {
    return size j { idx ->
        val (key, cursor) = this[idx]
        key to aggregation(cursor)
    }
}

// ============================================================
// Predicate builders for convenience
// ============================================================

/**
 * Equality predicate.
 */
fun eq(column: String, value: Any?): RowPredicate = { it[column] == value }

/**
 * Inequality predicate.
 */
fun ne(column: String, value: Any?): RowPredicate = { it[column] != value }

/**
 * Greater than predicate.
 */
fun gt(column: String, value: Comparable<*>): RowPredicate = { row ->
    val colValueAny = row[column]
    val colValue = colValueAny as? Comparable<*>
    if (colValue == null) {
        false
    } else {
        @Suppress("UNCHECKED_CAST")
        (colValue as Comparable<Any>).compareTo(value) > 0
    }
}

/**
 * Less than predicate.
 */
fun lt(column: String, value: Comparable<*>): RowPredicate = { row ->
    val colValueAny = row[column]
    val colValue = colValueAny as? Comparable<*>
    if (colValue == null) {
        false
    } else {
        @Suppress("UNCHECKED_CAST")
        (colValue as Comparable<Any>).compareTo(value) < 0
    }
}

/**
 * Greater than or equal predicate.
 */
fun ge(column: String, value: Comparable<*>): RowPredicate =
    eq(column, value).or(gt(column, value))

/**
 * Less than or equal predicate.
 */
fun le(column: String, value: Comparable<*>): RowPredicate =
    eq(column, value).or(lt(column, value))

/**
 * LIKE predicate (simple regex pattern matching).
 */
fun like(column: String, pattern: String): RowPredicate = { row ->
    val str = row[column]?.toString()
    if (str == null) false else {
        val regex = pattern.replace("%", ".*").replace("_", ".").toRegex()
        regex.matches(str)
    }
}

/**
 * IN predicate.
 */
fun `in`(column: String, vararg values: Any?): RowPredicate =
    { it[column] in values }

/**
 * EXISTS predicate (column is present and not null).
 */
fun exists(column: String): RowPredicate =
    { it.containsKey(column) && it[column] != null }

/**
 * Combines two predicates with AND.
 */
infix fun RowPredicate.and(other: RowPredicate): RowPredicate = { row ->
    this(row) && other(row)
}

/**
 * Combines two predicates with OR.
 */
infix fun RowPredicate.or(other: RowPredicate): RowPredicate = { row ->
    this(row) || other(row)
}

/**
 * Negates a predicate.
 */
operator fun RowPredicate.not(): RowPredicate = { row ->
    !this(row)
}

// ============================================================
// Functional DSL entry point
// ============================================================

/**
 * Functional DSL entry point for querying with a composable block.
 *
 * Example:
 * ```
 * dsl.queryDsl("users") { cursor ->
 *     cursor
 *         .filter(gt("age", 25))
 *         .sortBy { it["age"] as Int }
 *         .take(2)
 *         .select("name", "age")
 * }
 * ```
 */
suspend fun <R> MiniSqlDsl.queryDsl(
    table: String,
    block: suspend (Cursor) -> R
): R {
    val cursor = query(table)
    return block(cursor)
}
