package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.rootCell
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.kursive.sql.confixCellOf
import borg.trikeshed.parse.kursive.sql.scalarText
import borg.trikeshed.parse.kursive.sql.vectorOf

/** What an insert does when its `id` already exists: PostgREST's `Prefer: resolution=`. */
enum class Resolution { ERROR, MERGE, IGNORE }

/** An insert met an existing primary key (SQLSTATE 23505). */
class DuplicateKey(val table: String, val id: String) :
    IllegalStateException("duplicate key value violates unique constraint \"${table}_pkey\"")

/** A name no table answers to (SQLSTATE 42P01). */
class UndefinedTable(val name: String) : NoSuchElementException("relation \"$name\" does not exist")

/** The writes a table accepts, keyed by its `id` column. */
interface TableWrites {
    /** Stores [row], minting `id` when absent; answers the stored row, or null when [resolution] skipped it. */
    fun insert(row: Map<String, Any?>, resolution: Resolution): Map<String, Any?>?

    /** Merges [patch] into row [id] (a null value removes the column); answers the stored row, or null when absent. */
    fun update(id: Any, patch: Map<String, Any?>): Map<String, Any?>?

    fun delete(id: Any): Boolean

    fun exists(id: Any): Boolean
}

/** A named table: its rows on demand, its columns (known with no rows), and the writes it accepts. */
interface CatalogTable {
    val name: String
    fun scan(): Cursor
    fun columns(): List<ColumnMeta> = when (val rows = scan()) {
        is ColumnCursor -> rows.metas.toList()
        else -> if (rows.size == 0) emptyList() else List(rows[0].size) { rows[0].metas[it]() }
    }
    val writes: TableWrites? get() = null
}

/** A read-only table over a cursor supplier. */
class CursorTable(override val name: String, private val rows: () -> Cursor) : CatalogTable {
    override fun scan(): Cursor = rows()
}

/**
 * Table names to tables: what every SQL surface (PostgREST, raw SQL, later pg-wire) resolves `FROM`
 * against. [sources] are read on each lookup, so tables created after construction are found.
 * `information_schema.tables` and `information_schema.columns` describe the catalog itself.
 */
class Catalog(private val sources: List<() -> Iterable<CatalogTable>>) {
    constructor(vararg tables: CatalogTable) : this(listOf({ tables.asIterable() }))

    fun tables(): List<CatalogTable> = sources.flatMap { it() }

    fun table(name: String): CatalogTable? = when (name) {
        "information_schema.tables" -> CursorTable(name) { facet(CatalogK.Tables) }
        "information_schema.columns" -> CursorTable(name) { facet(CatalogK.Columns) }
        else -> tables().let { all -> all.firstOrNull { it.name == name } ?: all.firstOrNull { it.name.equals(name, ignoreCase = true) } }
    }

    fun resolve(name: String): Cursor = (table(name) ?: throw UndefinedTable(name)).scan()
}

/** The catalog's own tables, as `information_schema` shapes them. */
sealed class CatalogK<out R> : OpK<R>() {
    /** table_schema, table_name, table_type. */
    data object Tables : CatalogK<Cursor>()

    /** table_schema, table_name, column_name, ordinal_position, data_type. */
    data object Columns : CatalogK<Cursor>()
}

@Suppress("UNCHECKED_CAST")
fun <R> Catalog.facet(key: CatalogK<R>): R = when (key) {
    CatalogK.Tables -> tables().let { all ->
        rowsCursor(
            listOf("table_schema", "table_name", "table_type"),
            all.map { mapOf("table_schema" to "public", "table_name" to it.name, "table_type" to if (it.writes != null) "BASE TABLE" else "VIEW") },
        )
    }
    CatalogK.Columns -> tables().flatMap { t ->
        t.columns().mapIndexed { i, meta ->
            mapOf(
                "table_schema" to "public", "table_name" to t.name, "column_name" to meta.name.toString(),
                "ordinal_position" to (i + 1).toLong(), "data_type" to pgType(meta),
            )
        }
    }.let { rowsCursor(listOf("table_schema", "table_name", "column_name", "ordinal_position", "data_type"), it) }
} as R

/** The pg type name a column answers to. */
fun pgType(meta: ColumnMeta): String = when {
    meta.isVector -> "vector"
    else -> when (meta.type) {
        IOMemento.IoLong, IOMemento.IoInt -> "bigint"
        IOMemento.IoDouble, IOMemento.IoFloat -> "double precision"
        IOMemento.IoBoolean -> "boolean"
        IOMemento.IoString -> "text"
        else -> "jsonb"
    }
}

/**
 * Rows as a typed [ColumnCursor] over [columns]. A column's type is [declared] (a pg type name) or
 * read from its values: all booleans, all whole numbers (Long), all numbers (Double), all strings,
 * all numeric arrays of one width (a vector), else JSON (maps and lists become Confix cells).
 */
fun rowsCursor(columns: List<String>, rows: List<Map<String, Any?>>, declared: Map<String, String> = emptyMap()): ColumnCursor {
    val metas = ArrayList<ColumnMeta>(columns.size)
    val dense = ArrayList<DenseColumn>(columns.size)
    for (name in columns) {
        val values = Array(rows.size) { rows[it][name] }
        val column = denseOf(values, declared[name]?.let(::declaredKind) ?: inferredKind(values))
        metas += if (column.data is VectorColumn) vectorMeta(name) else ColumnMeta(name, column.kind)
        dense += column.dense
    }
    return ColumnCursor.of(metas.toTypedArray(), dense.toTypedArray())
}

private enum class Kind { BOOLEAN, LONG, DOUBLE, STRING, VECTOR, JSON }

private class Typed(val dense: DenseColumn, val kind: TypeMemento) {
    val data: Any get() = dense.data
}

private fun declaredKind(type: String): Kind {
    val t = type.lowercase().substringBefore('(').trim()
    return when {
        t in setOf("int", "int2", "int4", "int8", "integer", "smallint", "bigint", "serial", "bigserial") -> Kind.LONG
        t in setOf("float", "float4", "float8", "real", "double precision", "double", "numeric", "decimal") -> Kind.DOUBLE
        t in setOf("bool", "boolean") -> Kind.BOOLEAN
        t == "vector" -> Kind.VECTOR
        t in setOf("json", "jsonb") -> Kind.JSON
        else -> Kind.STRING
    }
}

private fun inferredKind(values: Array<Any?>): Kind {
    val present = values.filterNotNull()
    return when {
        present.isEmpty() -> Kind.STRING
        present.all { it is Boolean } -> Kind.BOOLEAN
        present.all { it is Number && whole(it) } -> Kind.LONG
        present.all { it is Number } -> Kind.DOUBLE
        present.all { it is String } -> Kind.STRING
        present.all { numbers(it)?.let { v -> v.isNotEmpty() } == true } &&
            present.map { numbers(it)!!.size }.distinct().size == 1 -> Kind.VECTOR
        else -> Kind.JSON
    }
}

private fun whole(n: Number): Boolean = n is Long || n is Int || n is Short || n is Byte ||
    n.toDouble().let { it == kotlin.math.floor(it) && kotlin.math.abs(it) < 9.007199254740992E15 }

/** A JSON array of numbers (a List, an Array or a FloatArray) as floats; null for anything else. */
private fun numbers(value: Any?): FloatArray? = when (value) {
    is FloatArray -> value
    is DoubleArray -> FloatArray(value.size) { value[it].toFloat() }
    is List<*> -> if (value.all { it is Number }) FloatArray(value.size) { (value[it] as Number).toFloat() } else null
    is Array<*> -> if (value.all { it is Number }) FloatArray(value.size) { (value[it] as Number).toFloat() } else null
    else -> null
}

private fun denseOf(values: Array<Any?>, kind: Kind): Typed {
    val n = values.size
    val valid = BooleanArray(n) { values[it] != null }
    val validity = if (valid.all { it }) null else valid
    fun num(v: Any?): Number? = v as? Number ?: (v as? String)?.toDoubleOrNull()
    return when (kind) {
        Kind.BOOLEAN -> Typed(DenseColumn(BooleanArray(n) { values[it] == true }, validity), IOMemento.IoBoolean)
        Kind.LONG -> Typed(DenseColumn(LongArray(n) { num(values[it])?.toLong() ?: 0L }, validity), IOMemento.IoLong)
        Kind.DOUBLE -> Typed(DenseColumn(DoubleArray(n) { num(values[it])?.toDouble() ?: Double.NaN }, validity), IOMemento.IoDouble)
        Kind.STRING -> Typed(DenseColumn(DictColumn.of(Array(n) { values[it]?.let(::text) ?: "" }), validity), IOMemento.IoString)
        Kind.VECTOR -> {
            val rows = Array(n) { values[it]?.let { v -> numbers(v) ?: (v as? String)?.let { vectorOf(it) } } }
            val dims = rows.firstNotNullOfOrNull { it }?.size ?: 0
            Typed(DenseColumn(VectorColumn.of(dims, n) { rows[it] }, validity), IOMemento.IoArray)
        }
        Kind.JSON -> Typed(DenseColumn(Array(n) { values[it]?.let(::cellOf) }, validity), IOMemento.IoObject)
    }
}

/** A string column's text: strings as they are, anything else by its JSON. */
private fun text(value: Any): String = value as? String ?: scalarText(cellOf(value))

/** Maps and lists as Confix cells (so `->` paths read them); scalars as they are. */
private fun cellOf(value: Any): Any = when (value) {
    is Map<*, *>, is List<*>, is Array<*> -> confixDoc(jsonOf(value)).rootCell ?: value
    else -> value
}

/** One cell as JSON: vectors in pgvector text, Confix cells by their source, whole doubles of integral columns as integers. */
fun cellJson(value: Any?, out: StringBuilder) {
    when (value) {
        null -> out.append("null")
        is FloatArray -> jsonOf(value.joinToString(",", "[", "]"), out)
        is Double -> if (value.isNaN() || value.isInfinite()) out.append("null") else out.append(value.toString())
        is Number, is Boolean, is String -> jsonOf(value, out)
        else -> if (confixCellOf(value) != null) out.append(scalarText(value)) else jsonOf(value, out)
    }
}
