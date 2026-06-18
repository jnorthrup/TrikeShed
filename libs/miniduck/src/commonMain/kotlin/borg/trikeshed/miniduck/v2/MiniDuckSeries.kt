package borg.trikeshed.miniduck.v2

import borg.trikeshed.lib.*
import borg.trikeshed.mutable.MutableSeries
import borg.trikeshed.mutable.CowSeriesHandle
import borg.trikeshed.mutable.CowSeriesBody

/**
 * MiniDuckSeries — the unified row vector type.
 *
 * A row vector IS a Series<Any?> (Join<Int, (Int) -> Any?>) with column metadata.
 * Mutations delegate to a CowSeriesHandle for copy-on-write semantics.
 */
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

    fun withSchema(newSchema: ColumnSchema): MiniDuckSeries =
        MiniDuckSeries(handle, newSchema)

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
    fun filter(predicate: (MiniDuckSeries) -> Boolean): Cursor {
        val result = Cursor()
        for (i in 0 until blocks.size) { val row = blocks[i]; if (predicate(row)) result.addBlock(Block.mutable().apply { append(row) }.seal()) }
        return result
    }
    fun project(columnNames: List<String>): Cursor {
        val result = Cursor()
        for (i in 0 until blocks.size) {
            val row = blocks[i]
            val projected = columnNames.mapNotNull { name -> row[name] }.toTypedArray()
            result.addBlock(Block.mutable().apply {
                val newSchema = ColumnSchema(columnNames, columnNames.map { name -> row.columnSchema.types[row.columnSchema.indexOf(name) ?: 0] ?: ColumnType.JSON })
                val newHandle = CowSeriesHandle(CowSeriesBody())
                for (val cell in projected) newHandle.add(cell)
                append(MiniDuckSeries(newHandle, newSchema))
            }.seal())
        }
        return result
    }
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
    companion object {
        inline fun <reified T> empty(): Cursor = Cursor()
    }
}

class QueryBuilder(private val source: Cursor) {
    private var filters: List<(MiniDuckSeries) -> Boolean> = emptyList()
    private var projections: List<String>? = null
    private var aggregations: List<Triple<String, String, (List<Any?>) -> Any?>> = emptyList()
    fun filter(predicate: (MiniDuckSeries) -> Boolean): QueryBuilder { filters += predicate; return this }
    fun project(vararg columnNames: String): QueryBuilder { projections = columnNames.toList(); return this }
    fun aggregate(keyColumn: String, valueColumn: String, fn: (List<Any?>) -> Any?): QueryBuilder { aggregations += Triple(keyColumn, valueColumn, fn); return this }
    fun build(): Cursor {
        var cursor = source
        for (filter in filters) cursor = cursor.filter(filter)
        projections?.let { cursor = cursor.project(it) }
        for ((key, value, fn) in aggregations) cursor = cursor.aggregate(key, value, fn)
        return cursor
    }
}

class Table(
    val name: String,
    private val cursor: Cursor = Cursor(),
    var schema: ColumnSchema = ColumnSchema.empty,
) {
    fun insert(row: MiniDuckSeries) { cursor.addBlock(Block.mutable().apply { append(row) }.seal()) }
    fun insertAll(rows: List<MiniDuckSeries>) { for (row in rows) insert(row) }
    fun query(): QueryBuilder = QueryBuilder(cursor)
    operator fun iterate(): Sequence<MiniDuckSeries> = cursor.sequence()
    companion object { fun create(name: String, schema: ColumnSchema): Table = Table(name, Cursor(), schema) }
}
