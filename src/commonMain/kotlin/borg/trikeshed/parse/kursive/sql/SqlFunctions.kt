package borg.trikeshed.parse.kursive.sql

/** A scalar function: the argument counts it takes and its body over evaluated arguments. */
class Scalar(val arity: IntRange, val body: (Array<Any?>) -> Any?)

/**
 * The scalar functions the SQL surfaces answer, by lowercase name; [SqlRuntime.call] reaches them from
 * the closure and the bytecode compilers alike. pg's catalog functions answer what this catalog is:
 * one owner, one schema, one database. A name not here is pg's `42883 undefined_function`.
 */
object SqlFunctions {
    const val OWNER = "trikeshed"
    const val DATABASE = "trikeshed"
    const val SCHEMA = "public"
    const val VERSION = "PostgreSQL 14.0 (TrikeShed)"

    /** The type name `format_type(oid, typmod)` prints for the OIDs the wire reports. */
    val TYPE_NAMES: Map<Long, String> = mapOf(
        16L to "boolean", 17L to "bytea", 19L to "name", 20L to "bigint", 21L to "smallint", 23L to "integer", 25L to "text",
        26L to "oid", 700L to "real", 701L to "double precision", 705L to "unknown", 1021L to "real[]", 1043L to "character varying",
        1082L to "date", 1184L to "timestamp with time zone", 3802L to "jsonb",
    )

    val TABLE: Map<String, Scalar> = mapOf(
        "version" to Scalar(0..0) { VERSION },
        "current_schema" to Scalar(0..0) { SCHEMA },
        "current_schemas" to Scalar(0..1) { a -> if (a.isNotEmpty() && a[0] == true) "{pg_catalog,$SCHEMA}" else "{$SCHEMA}" },
        "current_database" to Scalar(0..0) { DATABASE },
        "current_catalog" to Scalar(0..0) { DATABASE },
        "current_user" to Scalar(0..0) { OWNER },
        "session_user" to Scalar(0..0) { OWNER },
        "current_role" to Scalar(0..0) { OWNER },
        "pg_get_userbyid" to Scalar(1..1) { a -> a[0]?.let { OWNER } },
        "pg_table_is_visible" to Scalar(1..1) { a -> a[0]?.let { true } },
        "pg_type_is_visible" to Scalar(1..1) { a -> a[0]?.let { true } },
        "pg_function_is_visible" to Scalar(1..1) { a -> a[0]?.let { true } },
        "pg_get_expr" to Scalar(2..3) { null },
        "obj_description" to Scalar(1..2) { null },
        "col_description" to Scalar(2..2) { null },
        "shobj_description" to Scalar(2..2) { null },
        "pg_encoding_to_char" to Scalar(1..1) { a -> a[0]?.let { "UTF8" } },
        "format_type" to Scalar(1..2) { a -> (a[0] as? Number)?.toLong()?.let { TYPE_NAMES[it] ?: "???" } },
        "coalesce" to Scalar(1..Int.MAX_VALUE) { a -> a.firstOrNull { it != null } },
        "nullif" to Scalar(2..2) { a -> a[0]?.let { x -> if (a[1]?.let { compareCells(x, it) == 0 } == true) null else x } },
        "lower" to Scalar(1..1) { a -> a[0]?.let { scalarText(it).lowercase() } },
        "upper" to Scalar(1..1) { a -> a[0]?.let { scalarText(it).uppercase() } },
        "length" to Scalar(1..1) { a -> a[0]?.let { scalarText(it).length.toLong() } },
        "quote_ident" to Scalar(1..1) { a -> a[0]?.let { scalarText(it) }?.let { s -> if (plainIdentifier(s)) s else "\"" + s.replace("\"", "\"\"") + "\"" } },
        "quote_literal" to Scalar(1..1) { a -> a[0]?.let { "'" + scalarText(it).replace("'", "''") + "'" } },
        "abs" to Scalar(1..1) { a ->
            when (val v = a[0]) {
                null -> null
                is Number -> if (integral(v)) kotlin.math.abs(v.toLong()) else kotlin.math.abs(v.toDouble())
                else -> error("function abs(${v::class.simpleName}) does not exist")
            }
        },
    )

    /** An identifier pg prints bare: a lowercase word. */
    fun plainIdentifier(s: String): Boolean =
        s.isNotEmpty() && (s[0] == '_' || s[0].isLetter() && s[0].isLowerCase()) && s.all { it == '_' || it.isDigit() || it.isLetter() && it.isLowerCase() }

    /** Rejects an unknown name or arity at compile time, so the error names the statement, not a row. */
    fun require(name: String, arity: Int) {
        val scalar = TABLE[name] ?: error("function $name(...) does not exist")
        if (arity !in scalar.arity) error("function $name(...) does not exist with $arity arguments")
    }

    fun call(name: String, args: Array<Any?>): Any? = (TABLE[name] ?: error("function $name(...) does not exist")).body(args)
}
