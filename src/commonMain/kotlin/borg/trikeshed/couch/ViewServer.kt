package borg.trikeshed.couch

import borg.trikeshed.lib.*
import borg.trikeshed.mutable.MutableSeries
import borg.trikeshed.mutable.mutableSeriesOf
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.docAt
import borg.trikeshed.parse.confix.reify
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * ViewRow — a single emitted row from a view map function.
 *
 * - key: the emitted key (Any? for generality — strings, numbers, arrays all valid)
 * - value: the emitted value (Any? for generality)
 * - docId: the _id of the document that produced this emission
 * - jsPath: optional JS-style path (e.g. "doc.type" or "_id") used in the emit call
 */
@Serializable
data class ViewRow(
    @Contextual val key: Any?,
    @Contextual val value: Any?,
    val docId: String,
    val jsPath: String = ""
) {
    /** Convenience: rows with numeric keys can be compared numerically. */
    operator fun compareTo(other: ViewRow): Int = when {
        this.key is Comparable<*> && other.key is Comparable<*> ->
            (this.key as Comparable<Any>).compareTo(other.key)
        else -> 0
    }
}

/**
 * ViewResult — the result of executing a view map function (and optional reduce).
 *
 * Holds the list of emitted ViewRow instances. Can be reduced via built-in
 * reducers (_count, _sum, _stats) or a custom Confix DSL reducer.
 */
@Serializable
data class ViewResult(
    val rows: MutableSeries<ViewRow> = mutableSeriesOf()
) {
    val size: Int get() = rows.size
    operator fun get(index: Int): ViewRow = rows[index]

    /** Reduce this view result using a built-in reducer name. */
    fun reduce(reducer: String): ViewResult = when (reducer) {
        "_count" -> reduceCount()
        "_sum"   -> reduceSum()
        "_stats" -> reduceStats()
        else     -> error("Unknown built-in reducer: $reducer")
    }

    /** _count reducer: group by key, count emissions per key. */
    fun reduceCount(): ViewResult {
        val groups = mutableMapOf<Any?, MutableList<ViewRow>>()
        for (row in rows) {
            val key = row.key
            groups.getOrPut(key) { mutableListOf() }.add(row)
        }
        val reduced = mutableSeriesOf<ViewRow>()
        for ((key, group) in groups) {
            reduced.append(ViewRow(key = key, value = group.size.toLong(), docId = "_count", jsPath = "_count"))
        }
        return ViewResult(reduced)
    }

    /** _sum reducer: group by key, sum numeric values per key. */
    fun reduceSum(): ViewResult {
        val groups = mutableMapOf<Any?, MutableList<ViewRow>>()
        for (row in rows) {
            val key = row.key
            groups.getOrPut(key) { mutableListOf() }.add(row)
        }
        val reduced = mutableSeriesOf<ViewRow>()
        for ((key, group) in groups) {
            var sum: Double = 0.0
            for (row in group) {
                sum += when (row.value) {
                    is Number   -> row.value.toDouble()
                    is String   -> row.value.toDoubleOrNull() ?: 0.0
                    else        -> 0.0
                }
            }
            reduced.append(ViewRow(key = key, value = sum, docId = "_sum", jsPath = "_sum"))
        }
        return ViewResult(reduced)
    }

    /** _stats reducer: group by key, compute count/sum/min/max/sumSqr per key. */
    fun reduceStats(): ViewResult {
        val groups = mutableMapOf<Any?, MutableList<ViewRow>>()
        for (row in rows) {
            val key = row.key
            groups.getOrPut(key) { mutableListOf() }.add(row)
        }
        val reduced = mutableSeriesOf<ViewRow>()
        for ((key, group) in groups) {
            var count = 0L
            var sum = 0.0
            var minVal: Double? = null
            var maxVal: Double? = null
            var sumSqr = 0.0
            for (row in group) {
                val v = when (row.value) {
                    is Number   -> row.value.toDouble()
                    is String   -> row.value.toDoubleOrNull() ?: 0.0
                    else        -> 0.0
                }
                count++
                sum += v
                minVal = if (minVal == null) v else kotlin.math.min(minVal, v)
                maxVal = if (maxVal == null) v else kotlin.math.max(maxVal, v)
                sumSqr += v * v
            }
            val stats = mapOf<String, Any>(
                "count"  to count,
                "sum"    to sum,
                "min"    to (minVal ?: 0.0),
                "max"    to (maxVal ?: 0.0),
                "sumsqr" to sumSqr
            )
            reduced.append(ViewRow(key = key, value = stats, docId = "_stats", jsPath = "_stats"))
        }
        return ViewResult(reduced)
    }
}

/**
 * ViewDefinition — a design document view definition using Confix DSL.
 *
 * - ddoc: design document name (e.g. "_design/mydesign")
 * - viewName: view name within the design doc
 * - mapFn: Confix DSL expression representing the map function
 * - reduceFn: optional Confix DSL expression for reduce (or builtin name)
 */
@Serializable
data class ViewDefinition(
    val ddoc: String,
    val viewName: String,
    val mapFn: MapFunction,
    val reduceFn: ReduceFunction? = null
) {
    val fullName: String get() = "$ddoc/$viewName"
}

/**
 * MapFunction — Confix DSL representation of a map function.
 *
 * Instead of arbitrary JS eval, map functions are expressed as Confix DSL trees
 * that can be statically analyzed, compiled to JS/WASM, and executed in commonMain.
 *
 * Built-in map operations:
 *   - emit(doc.field)           → emit the value of doc.field as key, 1 as value
 *   - emit(doc._id, doc.value)  → emit doc._id as key, doc.value as value
 *   - emit(keyExpr, valExpr)    → emit arbitrary key/value expressions
 *
 * The DSL is a simple expression tree:
 *   MapFunction.Emit(key: KeyExpr, value: ValueExpr)
 *   KeyExpr  = DocField(String) | DocId | Const(Any) | JsPathExpr(String)
 *   ValueExpr = DocField(String) | DocValue | Const(Any) | JsPathExpr(String)
 */
sealed interface MapFunction {
    /** Emit a single (key, value) pair for the current document. */
    @Serializable
    data class Emit(
        val key: KeyExpr,
        val value: ValueExpr = ValueExpr.Const(1)
    ) : MapFunction

    /** Emit multiple rows from an array field (e.g., emit each tag). */
    @Serializable
    data class EmitEach(
        val arrayField: String,
        val keyExpr: KeyExpr = KeyExpr.DocField(""),
        val valueExpr: ValueExpr = ValueExpr.Const(1)
    ) : MapFunction
}

/** Key expressions for map functions. */
sealed interface KeyExpr {
    @Serializable
    data class DocField(val fieldName: String) : KeyExpr
    @Serializable
    object DocId : KeyExpr
    @Serializable
    data class Const(@Contextual val value: Any?) : KeyExpr
    @Serializable
    data class JsPathExpr(val path: String) : KeyExpr
}

/** Value expressions for map functions. */
sealed interface ValueExpr {
    @Serializable
    data class DocField(val fieldName: String) : ValueExpr
    @Serializable
    object DocValue : ValueExpr  // the whole document
    @Serializable
    data class Const(@Contextual val value: Any?) : ValueExpr
    @Serializable
    data class JsPathExpr(val path: String) : ValueExpr
}

/** Reduce function — either builtin name or custom Confix DSL. */
sealed interface ReduceFunction {
    @Serializable
    data class Builtin(val name: String) : ReduceFunction  // "_count", "_sum", "_stats"
    @Serializable
    data class Custom(val dsl: String) : ReduceFunction    // Confix DSL string
}

/**
 * ViewServer — Confix-native view server engine.
 *
 * Executes map functions expressed as Confix DSL against documents stored
 * as ConfixDoc (Confix-backed JSON/CBOR/YAML). Zero JVM dependencies,
 * compiles to JS target.
 */
class ViewServer {

    /**
     * Production path: view map over [CouchStore] via [CouchStore.query] Cursor.
     * Cursor enumerates rows (and _id column); store.get supplies map body.
     * Closes S5: query algebra with a real consumer outside tests.
     */
    fun execute(viewDef: ViewDefinition, store: CouchStore): ViewResult {
        val qr = store.query()
        val cursor = qr.cursor
        require(qr.totalCount == store.size.toLong()) {
            "query totalCount ${qr.totalCount} != store.size ${store.size}"
        }
        require(cursor.size == store.size) {
            "query cursor.size ${cursor.size} != store.size ${store.size}"
        }
        val docs = ArrayList<Document>(cursor.size)
        for (i in 0 until cursor.size) {
            val row = cursor[i]
            require(row.size > 0) { "empty cursor row $i" }
            val id = row.b(0).a as? String
                ?: error("query cursor row $i missing _id string, got ${row.b(0).a}")
            docs.add(store.get(id) ?: error("store missing doc $id from query cursor"))
        }
        return execute(viewDef, docs)
    }

    /** Execute a view definition against a list of documents. */
    fun execute(viewDef: ViewDefinition, documents: List<Document>): ViewResult {
        val rows = mutableSeriesOf<ViewRow>()
        for (doc in documents) {
            executeMap(viewDef.mapFn, doc, rows)
        }
        var result = ViewResult(rows)
        viewDef.reduceFn?.let { reduceFn ->
            result = when (reduceFn) {
                is ReduceFunction.Builtin -> result.reduce(reduceFn.name)
                is ReduceFunction.Custom -> executeCustomReduce(reduceFn.dsl, result)
            }
        }
        return result
    }

    /** Map one [Document] — DocField reads store fields; JsPath uses Confix when needed. */
    private fun executeMap(mapFn: MapFunction, doc: Document, rows: MutableSeries<ViewRow>) {
        when (mapFn) {
            is MapFunction.Emit -> {
                val key = evaluateKeyExpr(mapFn.key, doc)
                val value = evaluateValueExpr(mapFn.value, doc)
                rows.append(ViewRow(key = key, value = value, docId = doc.id, jsPath = describeEmit(mapFn)))
            }
            is MapFunction.EmitEach -> {
                val arrayValue = fieldValue(doc, mapFn.arrayField) as? List<*> ?: return
                for (item in arrayValue) {
                    val key = when (mapFn.keyExpr) {
                        is KeyExpr.DocField -> item
                        is KeyExpr.Const -> mapFn.keyExpr.value
                        is KeyExpr.DocId -> doc.id
                        is KeyExpr.JsPathExpr -> item
                    }
                    val value = when (mapFn.valueExpr) {
                        is ValueExpr.DocField -> item
                        is ValueExpr.Const -> mapFn.valueExpr.value
                        is ValueExpr.DocValue -> item
                        is ValueExpr.JsPathExpr -> item
                    }
                    rows.append(ViewRow(key = key, value = value, docId = doc.id, jsPath = "${mapFn.arrayField}[]"))
                }
            }
        }
    }

    private fun fieldValue(doc: Document, name: String): Any? =
        doc.fields.firstOrNull { it.name == name }?.value

    private fun evaluateKeyExpr(expr: KeyExpr, doc: Document): Any? = when (expr) {
        is KeyExpr.DocField -> fieldValue(doc, expr.fieldName)
        is KeyExpr.DocId -> doc.id
        is KeyExpr.Const -> expr.value
        is KeyExpr.JsPathExpr -> documentToConfixDoc(doc).value(expr.path)
    }

    private fun evaluateValueExpr(expr: ValueExpr, doc: Document): Any? = when (expr) {
        is ValueExpr.DocField -> fieldValue(doc, expr.fieldName)
        is ValueExpr.DocValue -> doc
        is ValueExpr.Const -> expr.value
        is ValueExpr.JsPathExpr -> documentToConfixDoc(doc).value(expr.path)
    }

    /** Generate a JS-path description for an emit operation (for debugging). */
    private fun describeEmit(emit: MapFunction.Emit): String {
        val keyStr = when (emit.key) {
            is KeyExpr.DocField     -> "doc.${emit.key.fieldName}"
            is KeyExpr.DocId        -> "doc._id"
            is KeyExpr.Const        -> emit.key.value.toString()
            is KeyExpr.JsPathExpr   -> emit.key.path
        }
        val valStr = when (emit.value) {
            is ValueExpr.DocField   -> "doc.${emit.value.fieldName}"
            is ValueExpr.DocValue   -> "doc"
            is ValueExpr.Const      -> emit.value.value.toString()
            is ValueExpr.JsPathExpr -> emit.value.path
        }
        return "emit($keyStr, $valStr)"
    }

    /** Convert a CouchStore Document to a ConfixDoc for DSL evaluation. */
    private fun documentToConfixDoc(doc: Document): ConfixDoc {
        // Build JSON string from Document
        val fieldsJson = doc.fields.joinToString(",") { field ->
            "\"${field.name}\":${valueToJson(field.value)}"
        }
        val json = "{\"_id\":\"${doc.id}\",$fieldsJson}"
        return borg.trikeshed.parse.confix.confixDoc(json)
    }

    /** Convert a Kotlin value to JSON representation. */
    private fun valueToJson(value: Any?): String {
        return when {
            value == null -> "null"
            value is String -> "\"$value\""
            value is Number -> value.toString()
            value is Boolean -> value.toString()
            value is List<*> -> "[${value.joinToString(",") { valueToJson(it) }}]"
            value is Map<*, *> -> "{${value.entries.joinToString(",") { "\"${it.key}\":${valueToJson(it.value)}" }}}"
            else -> "\"$value\""
        }
    }

    /** Execute a custom Confix DSL reducer (stub — future expansion). */
    private fun executeCustomReduce(dsl: String, input: ViewResult): ViewResult {
        // TODO: Parse and execute Confix DSL reducer expression
        throw UnsupportedOperationException("Confix DSL reducer expression parsing not yet implemented")
    }
}

/**
 * Extension on ConfixDoc for convenient field access.
 */
fun ConfixDoc.value(field: String): Any? {
    return if (field == "_id") {
        this.value("_id")
    } else {
        val cell = this.docAt(field)
        cell?.reify()
    }
}