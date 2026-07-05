package borg.trikeshed.miniduck.v2

import borg.trikeshed.lib.*
import borg.trikeshed.mutable.MutableSeries
import borg.trikeshed.mutable.CowSeriesHandle
import borg.trikeshed.mutable.CowSeriesBody

@DslMarker
annotation class MiniDuckDsl

class MiniDuckSeries(
    private val handle: CowSeriesHandle<Any?> = CowSeriesHandle(CowSeriesBody()),
    val columnSchema: ColumnSchema = ColumnSchema.empty,
) : MutableSeries<Any?>, Series<Any?> {

    override val a: Int get() = handle.a
    override val b: (Int) -> Any? get() = handle.b

    override fun set(index: Int, item: Any?) { handle.set(index, item) }
    override fun add(item: Any?) { handle.add(item) }
    override fun add(index: Int, item: Any?) { handle.add(index, item) }
    override fun removeAt(index: Int): Any? = handle.removeAt(index)
    override fun remove(item: Any?): Boolean = handle.remove(item)
    override fun clear() { handle.clear() }
    override fun plus(item: Any?): MutableSeries<Any?> { handle.add(item); return this }
    override fun minus(item: Any?): MutableSeries<Any?> { handle.remove(item); return this }
    override fun plusAssign(item: Any?) { handle.add(item) }
    override fun minusAssign(item: Any?) { handle.remove(item) }

    fun withSchema(newSchema: ColumnSchema): MiniDuckSeries = MiniDuckSeries(handle, newSchema)

    fun withAddedColumn(name: String, type: ColumnType): MiniDuckSeries {
        val newSchema = columnSchema.addColumn(name, type)
        val newHandle = CowSeriesHandle(CowSeriesBody())
        for (i in 0 until handle.a) newHandle.add(handle[i])
        return MiniDuckSeries(newHandle, newSchema)
    }

    operator fun get(columnName: String): Any? = columnSchema.indexOf(columnName)?.let { handle[it] }
    operator fun set(columnName: String, value: Any?) = columnSchema.indexOf(columnName)?.let { handle.set(it, value) }

    companion object {
        inline fun <reified T> empty(schema: ColumnSchema = ColumnSchema.empty): MiniDuckSeries =
            MiniDuckSeries(CowSeriesHandle(CowSeriesBody()), schema)
        fun of(vararg rows: Array<Any?>): MiniDuckSeries {
            val handle = CowSeriesHandle(CowSeriesBody())
            for (row in rows) handle.add(row)
            return MiniDuckSeries(handle)
        }
        inline fun <reified T> build(schema: ColumnSchema = ColumnSchema.empty, init: MiniDuckSeries.() -> Unit): MiniDuckSeries {
            val series = empty(schema)
            series.init()
            return series
        }
    }
}

data class ColumnSchema(
    val names: List<String> = emptyList(),
    val types: List<ColumnType> = emptyList(),
) {
    override val size: Int get() = names.size
    val byName: Map<String, Int> = names.withIndex().toMap()
    fun indexOf(name: String): Int? = byName[name]
    fun addColumn(name: String, type: ColumnType): ColumnSchema = ColumnSchema(names + name, types + type)
    fun removeColumn(name: String): ColumnSchema {
        val idx = indexOf(name) ?: return this
        return ColumnSchema(names.filterIndexed { i, _ -> i != idx }, types.filterIndexed { i, _ -> i != idx })
    }
    companion object { val empty: ColumnSchema = ColumnSchema() }
}

enum class ColumnType { STRING, INT, LONG, DOUBLE, BOOLEAN, BYTES, TIMESTAMP, JSON }

data class ColumnMeta(
    val name: String,
    val type: ColumnType,
    val child: ColumnMeta? = null,
)

class Block(
    private val rows: RecursiveMutableSeries<MiniDuckSeries> = RecursiveMutableSeries.create(),
    var sealed: Boolean = false,
) {
    val rowCount: Int get() = rows.size
    val rowSchema: ColumnSchema get() = if (rows.size > 0) rows[0].columnSchema else ColumnSchema.empty
    fun append(row: MiniDuckSeries) { check(!sealed) { "Cannot append to a sealed block" }; rows.add(row) }
    fun seal(): Block { sealed = true; return this }
    operator fun get(index: Int): MiniDuckSeries = rows[index]
    fun sequence(): Sequence<MiniDuckSeries> = rows.sequence()
    fun toList(): List<MiniDuckSeries> = rows.toList()
    companion object { fun mutable(): Block = Block() }
}

class Cursor(
    private val blocks: RecursiveMutableSeries<Block> = RecursiveMutableSeries.create(),
) : Series<MiniDuckSeries> {
    override val a: Int get() = blocks.a
    override val b: (Int) -> MiniDuckSeries = { i -> blocks[i] }
    fun addBlock(block: Block) { blocks.add(block) }

    fun where(pred: (MiniDuckSeries) -> Boolean): Cursor {
        val hits by lazy {
            val a = mutableListOf<Int>()
            for (i in 0 until size) if (pred(this[i])) a.add(i)
            a
        }
        return object : Cursor {
            override val a get() = hits.size
            override val b = { i: Int -> this@where[hits[i]] }
        }
    }

    fun whereColumn(name: String, pred: (Any?) -> Boolean): Cursor =
        where { row -> val idx = row.columnSchema.indexOf(name) ?: return@where false; pred(row[idx]) }

    fun limit(n: Int): Cursor = minOf(size, n) j { i: Int -> this[i] }
    fun offset(n: Int): Cursor = maxOf(0, size - n) j { i: Int -> this[n + i] }

    fun project(columnNames: List<String>): Cursor {
        val result = Cursor()
        for (i in 0 until blocks.size) {
            val row = blocks[i]
            val projected = columnNames.mapNotNull { name -> row[name] }.toTypedArray()
            result.addBlock(Block.mutable().apply {
                val newSchema = ColumnSchema(columnNames, columnNames.map { name ->
                    row.columnSchema.types[row.columnSchema.indexOf(name) ?: 0] ?: ColumnType.JSON
                })
                val newHandle = CowSeriesHandle(CowSeriesBody())
                for (val cell in projected) newHandle.add(cell)
                append(MiniDuckSeries(newHandle, newSchema))
            }.seal())
        }
        return result
    }

    fun select(vararg names: String): Cursor = project(names.toList())

    fun aggregate(keyColumn: String, valueColumn: String, fn: (List<Any?>) -> Any?): Cursor {
        val groups = mutableMapOf<Any?, MutableList<Any?>>()
        for (i in 0 until blocks.size) {
            val row = blocks[i]; val key = row[keyColumn]; val value = row[valueColumn]
            groups.getOrPut(key) { mutableListOf() }.add(value)
        }
        val result = Cursor()
        val outSchema = ColumnSchema(listOf(keyColumn, valueColumn), listOf(ColumnType.JSON, ColumnType.JSON))
        for ((key, values) in groups) {
            val aggValue = fn(values)
            result.addBlock(Block.mutable().apply {
                val newHandle = CowSeriesHandle(CowSeriesBody())
                newHandle.add(key); newHandle.add(aggValue)
                append(MiniDuckSeries(newHandle, outSchema))
            }.seal())
        }
        return result
    }

    companion object { inline fun <reified T> empty(): Cursor = Cursor() }
}

typealias KeyRange<K> = Join<K?, K?>
val <K> KeyRange<K>.lo: K? get() = a
val <K> KeyRange<K>.hi: K? get() = b
fun <K> from(lo: K): KeyRange<K> = lo to null
fun <K> upTo(hi: K): KeyRange<K> = null to hi
fun <K> between(lo: K, hi: K): KeyRange<K> = lo to hi

typealias CursorSource = Join<CharSequence, Join<Series<ColumnMeta>, suspend (KeyRange<*>?) -> Cursor>>

val CursorSource.sourceName: CharSequence get() = a
val CursorSource.schema: Series<ColumnMeta> get() = b.a
val CursorSource.openFn: suspend (KeyRange<*>?) -> Cursor get() = b.b
suspend fun CursorSource.open(range: KeyRange<*>? = null): Cursor = openFn(range)

@MiniDuckDsl
class CursorSourceBuilder(private val name: CharSequence) {
    private val cols = mutableListOf<ColumnMeta>()
    private var openFn: (suspend (KeyRange<*>?) -> Cursor)? = null

    fun column(name: String, type: ColumnType, child: ColumnMeta? = null) { cols.add(ColumnMeta(name, type, child)) }
    fun column(name: String, typeName: String, child: ColumnMeta? = null) = column(name, ColumnType.valueOf(typeName.uppercase()), child)

    inline fun <reified E : Enum<E>> columnEnum(crossinline spec: (E) -> Pair<String, ColumnType>) {
        enumSeries<E>().view.forEach { e -> val (n, t) = spec(e); column(n, t) }
    }

    fun from(block: suspend (KeyRange<*>?) -> Cursor) { openFn = block }

    fun build(): CursorSource {
        val schema = cols.size j { i: Int -> cols[i] }
        val fn = openFn ?: error("CursorSourceBuilder '$name': no from() supplied")
        return name to (schema to fn)
    }
}

fun cursorSource(name: String, block: CursorSourceBuilder.() -> Unit): CursorSource = CursorSourceBuilder(name).apply(block).build()

@MiniDuckDsl
class CursorQueryBuilder(private val source: CursorSource) {
    private val preds = mutableListOf<(MiniDuckSeries) -> Boolean>()
    private var cols = emptyList<String>()
    private var limitN: Int? = null
    private var offsetN = 0
    private var range: KeyRange<*>? = null

    fun where(pred: (MiniDuckSeries) -> Boolean) { preds.add(pred) }
    fun eq(col: String, v: Any?) = where { row -> row[col] == v }
    fun ne(col: String, v: Any?) = where { row -> row[col] != v }
    fun gt(col: String, v: Number) = where { row -> (row[col] as? Number)?.toDouble()?.let { it > v.toDouble() } == true }
    fun lt(col: String, v: Number) = where { row -> (row[col] as? Number)?.toDouble()?.let { it < v.toDouble() } == true }
    fun like(col: String, pfx: String) = where { row -> row[col]?.toString()?.startsWith(pfx) == true }

    fun select(vararg names: String) { cols = names.toList() }
    fun limit(n: Int) { limitN = n }
    fun offset(n: Int) { offsetN = n }
    fun range(r: KeyRange<*>) { range = r }

    suspend fun execute(): Cursor {
        var c: Cursor = source.open(range)
        preds.forEach { p -> c = c.where(p) }
        if (cols.isNotEmpty()) c = c.select(*cols.toTypedArray())
        if (offsetN > 0) c = c.offset(offsetN)
        limitN?.let { c = c.limit(it) }
        return c
    }
}

suspend fun CursorSource.query(block: CursorQueryBuilder.() -> Unit): Cursor = CursorQueryBuilder(this).apply(block).execute()

typealias MapFn<K, V> = (MiniDuckSeries) -> Series<Join<K, V>>
typealias ReduceFn<K, V, R> = (K?, Series<V>, Boolean) -> R
typealias MapReduceView<K, V, R> = Join<String, Join<MapFn<K, V>, ReduceFn<K, V, R>>>

val <K, V, R> MapReduceView<K, V, R>.viewName: String get() = a
val <K, V, R> MapReduceView<K, V, R>.mapFn: MapFn<K, V> get() = b.a
val <K, V, R> MapReduceView<K, V, R>.reduceFn: ReduceFn<K, V, R> get() = b.b

@MiniDuckDsl
class MapReduceViewBuilder<K : Any, V : Any, R : Any>(private val name: String) {
    private var mapFn: MapFn<K, V>? = null
    private var reduceFn: ReduceFn<K, V, R>? = null

    fun map(block: (MiniDuckSeries) -> Series<Join<K, V>>) { mapFn = block }
    fun reduce(block: (K?, Series<V>, Boolean) -> R) { reduceFn = block }

    fun mapOne(block: (MiniDuckSeries) -> Join<K, V>?) {
        mapFn = { doc -> block(doc)?.let { 1 j { _: Int -> it } } ?: (0 j { error("empty") }) }
    }

    fun build(): MapReduceView<K, V, R> {
        val m = mapFn ?: error("MapReduceViewBuilder '$name': map not defined")
        val r = reduceFn ?: error("MapReduceViewBuilder '$name': reduce not defined")
        return name to (m to r)
    }
}

fun <K : Any, V : Any, R : Any> mapReduceView(name: String, block: MapReduceViewBuilder<K, V, R>.() -> Unit): MapReduceView<K, V, R> = MapReduceViewBuilder<K, V, R>(name).apply(block).build()

enum class AggFn { SUM, AVG, MIN, MAX, COUNT }
typealias AggReducer = (Series<Any?>) -> Any?

val AGG_TABLE: Series<Join<AggFn, AggReducer>> = enumSeries<AggFn>() α { fn ->
    fn to when (fn) {
        AggFn.SUM -> AggReducer { vals -> var s = 0.0; for (i in 0 until vals.size) s += (vals[i] as? Number)?.toDouble() ?: 0.0; s }
        AggFn.AVG -> AggReducer { vals -> var s = 0.0; for (i in 0 until vals.size) s += (vals[i] as? Number)?.toDouble() ?: 0.0; if (vals.size > 0) s / vals.size else 0.0 }
        AggFn.COUNT -> AggReducer { vals -> vals.size.toLong() }
        AggFn.MIN -> AggReducer { vals ->
            @Suppress("UNCHECKED_CAST") (0 until vals.size).mapNotNull { vals[it] as? Comparable<Any> }.minOrNull()?.let { (it as? Number)?.toDouble() } ?: 0.0 }
        AggFn.MAX -> AggReducer { vals ->
            @Suppress("UNCHECKED_CAST") (0 until vals.size).mapNotNull { vals[it] as? Comparable<Any> }.maxOrNull()?.let { (it as? Number)?.toDouble() } ?: 0.0 }
    }
}

fun AggFn.reducer(): AggReducer = AGG_TABLE.view.first { it.a == this }.b

sealed class QueryPlan
data class ScanNode(val table: String) : QueryPlan()
data class FilterNode(val source: QueryPlan, val pred: (MiniDuckSeries) -> Boolean) : QueryPlan()
data class ProjectNode(val source: QueryPlan, val cols: List<String>) : QueryPlan()
data class LimitNode(val source: QueryPlan, val n: Int) : QueryPlan()
data class OffsetNode(val source: QueryPlan, val n: Int) : QueryPlan()
data class JoinNode(val left: QueryPlan, val right: QueryPlan, val lCol: String, val rCol: String) : QueryPlan()
data class AggNode(val source: QueryPlan, val groupBy: List<String>, val aggs: List<AggSpec>) : QueryPlan()

data class AggSpec(val col: String, val fn: AggFn, val alias: String = col)

typealias TableRegistry = Join<Int, (Int) -> CursorSource>
typealias ExecutionContext = Join<TableRegistry, Unit>

fun executionContext(sources: List<CursorSource>): ExecutionContext = (sources.size j { i: Int -> sources[i] }) to Unit

fun ExecutionContext.find(name: String): CursorSource? = a.view.firstOrNull { it.sourceName == name }

suspend fun QueryPlan.execute(ctx: ExecutionContext): Cursor = when (this) {
    is ScanNode -> ctx.find(table)?.open() ?: error("Table '$table' not found")
    is FilterNode -> source.execute(ctx).where(pred)
    is ProjectNode -> source.execute(ctx).select(*cols.toTypedArray())
    is LimitNode -> source.execute(ctx).limit(n)
    is OffsetNode -> source.execute(ctx).offset(n)
    is JoinNode -> join(left.execute(ctx), right.execute(ctx))
    is AggNode -> AggEngine.execute(this, ctx)
}

object AggEngine {
    suspend fun execute(node: AggNode, ctx: ExecutionContext): Cursor {
        val src = node.source.execute(ctx)
        val groups = mutableMapOf<List<Any?>, MutableList<MiniDuckSeries>>()
        for (i in 0 until src.size) {
            val row = src[i]; val key = node.groupBy.map { col -> row[col] }
            groups.getOrPut(key) { mutableListOf() }.add(row)
        }
        val resultRows = groups.entries.map { (groupKey, rows) ->
            val aggResults: List<Join<Any?, () -> ColumnMeta>> = node.aggs.map { spec ->
                val reducer = spec.fn.reducer()
                val colVals = rows.size j { i -> rows[i][spec.col] }
                val result = reducer(colVals)
                (result as Any?) to { ColumnMeta(spec.alias, ColumnType.DOUBLE) }
            }
            val keyParts: List<Join<Any?, () -> ColumnMeta>> = node.groupBy.mapIndexed { i, col ->
                groupKey[i] to { ColumnMeta(col, ColumnType.STRING) }
            }
            val allCols = keyParts + aggResults
            allCols.size j { c: Int -> allCols[c] }
        }
        return resultRows.size j { i: Int -> resultRows[i] }
    }
}

@MiniDuckDsl
class SqlQueryBuilder(private val table: String) {
    private val preds = mutableListOf<(MiniDuckSeries) -> Boolean>()
    private var cols = emptyList<String>()
    private var aggs = emptyList<AggSpec>()
    private var groupBy = emptyList<String>()
    private var joinWith: Pair<SqlQueryBuilder, Pair<String, String>>? = null
    private var limitN: Int? = null
    private var offsetN = 0

    fun where(pred: (MiniDuckSeries) -> Boolean) { preds.add(pred) }
    fun eq(col: String, v: Any?) { preds.add { it[col] == v } }
    fun gt(col: String, v: Number) { preds.add { (it[col] as? Number)?.toDouble()?.let { n -> n > v.toDouble() } == true } }
    fun lt(col: String, v: Number) { preds.add { (it[col] as? Number)?.toDouble()?.let { n -> n < v.toDouble() } == true } }

    fun select(vararg names: String) { cols = names.toList() }
    fun groupBy(vararg names: String) { groupBy = names.toList() }
    fun sum(col: String, alias: String = col) { aggs += AggSpec(col, AggFn.SUM, alias) }
    fun avg(col: String, alias: String = col) { aggs += AggSpec(col, AggFn.AVG, alias) }
    fun count(alias: String = "count") { aggs += AggSpec("*", AggFn.COUNT, alias) }

    fun join(rhs: SqlQueryBuilder, on: Pair<String, String>) { joinWith = rhs to on }

    fun limit(n: Int) { limitN = n }
    fun offset(n: Int) { offsetN = n }

    fun plan(): QueryPlan {
        var node: QueryPlan = ScanNode(table)
        joinWith?.let { (rhs, on) -> node = JoinNode(node, rhs.plan(), on.first, on.second) }
        preds.forEach { p -> node = FilterNode(node, p) }
        if (aggs.isNotEmpty() || groupBy.isNotEmpty()) node = AggNode(node, groupBy, aggs)
        if (cols.isNotEmpty()) node = ProjectNode(node, cols)
        limitN?.let { node = LimitNode(node, it) }
        if (offsetN > 0) node = OffsetNode(node, offsetN)
        return node
    }

    suspend fun execute(ctx: ExecutionContext): Cursor = plan().execute(ctx)
}

fun from(table: String): SqlQueryBuilder = SqlQueryBuilder(table)

@Suppress("BOUNDS_NOT_ALLOWED_IF_BOUNDED_BY_TYPE_PARAMETER")
inline fun <reified E : Enum<E>> enumSeries(): Series<E> {
    val entries = enumEntries<E>()
    return entries.size j { i: Int -> entries[i] }
}

inline fun <reified E : Enum<E>> Series<E>.byName(): (String) -> E? {
    val map = view.associateBy { it.name }
    return { name: String -> map[name] }
}

inline fun <reified E> Series<E>.asColumnMeta(crossinline nameOf: (E) -> String, crossinline typeOf: (E) -> ColumnType): Series<ColumnMeta> = this α { e -> ColumnMeta(nameOf(e), typeOf(e)) }
