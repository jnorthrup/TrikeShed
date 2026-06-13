package borg.trikeshed.lsm

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.j
import borg.trikeshed.lib.s_

// ──────────────────────────────────────────────────────────────────────────────
// LSM + DuckDB Unified Suffixes
// ──────────────────────────────────────────────────────────────────────────────
//
// Kernel algebra from PRELOAD.md:
//   Join<A,B>  |  Twin<T> = Join<T,T>  |  Series<T> = Join<Int, (Int)->T>
//   .j         |  .α (lazy map)        |  .↺ (left identity)
//   s_[...]    |  .view (IterableSeries)
//
// LSM-Tree: MemTable + WAL → SSTable levels → Compaction
// DuckDB:   WAL + Columnar row groups → Checkpoints
// ──────────────────────────────────────────────────────────────────────────────

/** LSM SSTable level marker */
sealed class LsmLevel {
    data class L0(val memTable: MutableSeries<Entry>) : LsmLevel()
    data class Ln(val level: Int, val runs: Series<SortedRun>) : LsmLevel()
    data class Compacted(val input: LsmLevel, val output: SortedRun) : LsmLevel()
}

/** DuckDB columnar row group marker */
sealed class ColumnarStage {
    data class WalBuffer(val entries: MutableSeries<Entry>) : ColumnarStage()
    data class RowGroup(val columns: Series<Column>, val rows: Int) : ColumnarStage()
    data class Checkpoint(val groups: Series<RowGroup>) : ColumnarStage()
}

/** Single entry - works for both LSM and columnar */
data class Entry(
    val key: Comparable<*>,
    val value: Any?,
    val seq: Long,
    val deleted: Boolean = false,
    // Columnar extension
    val column: String? = null,
    val row: Int? = null
) : Comparable<Entry> {
    override fun compareTo(other: Entry): Int = key.compareTo(other.key)
}

/** Sorted run = immutable SSTable */
data class SortedRun(
    val entries: Series<Entry>,
    val minKey: Comparable<*>,
    val maxKey: Comparable<*>,
    val level: Int = 0,
    val version: Long = System.nanoTime()
)

/** Columnar column vector */
data class Column(
    val name: String,
    val data: Series<Any?>,
    val type: ColumnType,
    val stats: ColumnStats = ColumnStats()
)

data class ColumnType(val name: String)
data class ColumnStats(val min: Any? = null, val max: Any? = null, val nullCount: Int = 0)

// ──────────────────────────────────────────────────────────────────────────────
// SUFFIX BUILDERS - Zero-cost extension properties
// ──────────────────────────────────────────────────────────────────────────────

/** LSM suffix chain */
class LsmSuffixes<T : MutableSeries<Entry>>(val source: T) {
    
    /** .wal(size) - Write-Ahead Log buffer */
    fun wal(capacity: Int = 1024): LsmWal<T> = LsmWal(source, capacity)
    
    /** .memTable(size) - In-memory sorted table (LSM MemTable) */
    fun memTable(capacity: Int = 4096): LsmMemTable<T> = LsmMemTable(source, capacity)
    
    /** .flush() - Immediate flush to SSTable */
    fun flush(): SortedRun = source.toSortedRun()
    
    /** .level(n) - Target LSM level */
    infix fun level(n: Int): LsmLevelTarget<T> = LsmLevelTarget(source, n)
    
    /** .compact() - Trigger compaction */
    fun compact(): LsmCompact<T> = LsmCompact(source)
}

/** DuckDB suffix chain */
class ColumnarSuffixes<T : MutableSeries<Entry>>(val source: T) {
    
    /** .wal(capacity) - WAL buffer (same as LSM) */
    fun wal(capacity: Int = 1024): DuckWal<T> = DuckWal(source, capacity)
    
    /** .rowGroup(size) - Accumulate into row group */
    fun rowGroup(maxRows: Int = 122880): RowGroupBuilder<T> = RowGroupBuilder(source, maxRows)
    
    /** .checkpoint() - Finalize and checkpoint */
    fun checkpoint(): ColumnarStage.Checkpoint = source.toCheckpoint()
    
    /** .column(name) - Project to column vector */
    infix fun column(name: String): ColumnVector<T> = ColumnVector(source, name)
    
    /** .partition(by) - Partition by key */
    infix fun partition(by: String): Partitioned<T> = Partitioned(source, by)
}

/** WAL buffer - shared by both LSM and DuckDB */
class LsmWal<T>(val source: T, val capacity: Int) {
    fun drain(): SortedRun = source.toSortedRun()
    fun toMemTable(): LsmMemTable<T> = LsmMemTable(source, capacity)
}

/** DuckDB WAL buffer */
class DuckWal<T>(val source: T, val capacity: Int) {
    fun drain(): Series<Entry> = source.toSortedRun().entries
    fun toRowGroup(maxRows: Int): RowGroupBuilder<T> = RowGroupBuilder(source, maxRows)
}

/** LSM MemTable (in-memory sorted) */
class LsmMemTable<T>(val source: T, val capacity: Int) {
    fun flush(): SortedRun = source.toSortedRun()
    infix fun level(n: Int): LsmLevelTarget<T> = LsmLevelTarget(source, n)
}

/** Target LSM level for placement */
class LsmLevelTarget<T>(val source: T, val level: Int) {
    fun place(): SortedRun = SortedRun(source.toSortedRun().entries, level = level)
}

/** Compaction trigger */
class LsmCompact<T>(val source: T) {
    fun merge(levels: Series<LsmLevel>): SortedRun = source.toSortedRun()
}

/** Row group builder - columnar */
class RowGroupBuilder<T>(val source: T, val maxRows: Int) {
    fun build(): ColumnarStage.RowGroup = 
        ColumnarStage.RowGroup(source.toColumns(), source.size)
    infix fun column(name: String): ColumnVector<T> = ColumnVector(source, name)
}

/** Column vector */
class ColumnVector<T>(val source: T, val name: String) {
    val data: Series<Any?> = source.α { it.value }
}

/** Partitioned */
class Partitioned<T>(val source: T, val by: String) {
    fun build(): Series<SortedRun> = source.partitionBy { it.key }.map { it.toSortedRun() }.toSeries()
}

/** Partitioned columnar */
class PartitionedColumnar<T>(val source: T, val by: String) {
    fun build(): Series<ColumnarStage.RowGroup> = source.partitionBy { it.getColumn(by) }.map { it.toRowGroup() }.toSeries()
}

// ──────────────────────────────────────────────────────────────────────────────
// EXTENSION PROPERTIES ON MutableSeries<Entry>
// ──────────────────────────────────────────────────────────────────────────────

/** LSM suffixes */
val <T : MutableSeries<Entry>> T.lsm: LsmSuffixes<T>
    get() = LsmSuffixes(this)

/** Columnar/DuckDB suffixes */
val <T : MutableSeries<Entry>> T.columnar: ColumnarSuffixes<T>
    get() = ColumnarSuffixes(this)

/** Universal WAL suffix */
val <T : MutableSeries<Entry>> T.wal: LsmWal<T>
    get() = LsmWal(this, 1024)

// ──────────────────────────────────────────────────────────────────────────────
// HELPER EXTENSIONS (using kernel algebra)
// ──────────────────────────────────────────────────────────────────────────────

fun <T : MutableSeries<Entry>> T.toSortedRun(): SortedRun {
    val sorted = this.sortedBy { it.key }
    val entries = s_ * sorted
    return SortedRun(entries, sorted.first().key, sorted.last().key)
}

fun <T : MutableSeries<Entry>> T.toColumns(): Series<Column> = 
    this.groupBy { it.column ?: "default" }
        .map { (name, entries) -> 
            Column(name, entries.map { it.value }.toSeries(), ColumnType("auto")) 
        }
        .toSeries()

fun <T : MutableSeries<Entry>> T.toCheckpoint(): ColumnarStage.Checkpoint =
    ColumnarStage.Checkpoint(this.toRowGroups().toSeries())

fun <T : MutableSeries<Entry>> T.toRowGroups(maxRows: Int = 122880): Series<ColumnarStage.RowGroup> =
    this.chunked(maxRows).map { it.toRowGroup(maxRows) }.toSeries()

fun <T : MutableSeries<Entry>> T.toRowGroup(maxRows: Int): ColumnarStage.RowGroup =
    ColumnarStage.RowGroup(this.toColumns(), minOf(this.size, maxRows))

fun <T : MutableSeries<Entry>> T.partitionBy(key: (Entry) -> Any): Series<T> =
    this.groupBy(key).map { (_, entries) -> entries.toMutableSeries() }.toSeries()

fun <T : MutableSeries<Entry>> T.chunked(size: Int): Series<T> =
    (0 until this.size step size).map { i -> 
        this.slice(i..minOf(i + size - 1, this.size - 1)).toMutableSeries() 
    }.toSeries()
