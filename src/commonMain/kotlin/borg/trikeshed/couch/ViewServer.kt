package borg.trikeshed.couch

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.collections.MutableSeries
import borg.trikeshed.collections.mutableSeriesOf
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.parse.confix.ConfixCell
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.cellKids
import borg.trikeshed.parse.confix.docAt
import borg.trikeshed.parse.confix.get
import borg.trikeshed.parse.confix.reify
import borg.trikeshed.parse.confix.row
import borg.trikeshed.parse.confix.tag
import borg.trikeshed.parse.confix.value
import borg.trikeshed.viewserver.MapReduceProofReceipt
import borg.trikeshed.viewserver.ReducerIdentity
import borg.trikeshed.viewserver.ViewDefinitionIdentity
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
        "rollup-count" -> reduceRollupCount()
        else     -> error("Unknown built-in reducer: $reducer")
    }

    /** _count reducer: group by key, count emissions per key. */
    fun reduceCount(): ViewResult {
        // ⚡ Bolt: Accumulate count directly instead of allocating a List<ViewRow> per key
        val counts = mutableMapOf<Any?, Long>()
        for (row in rows) {
            val key = row.key
            val current = counts[key] ?: 0L
            counts[key] = current + 1L
        }
        val reduced = mutableSeriesOf<ViewRow>()
        for ((key, count) in counts) {
            reduced.append(ViewRow(key = key, value = count, docId = "_count", jsPath = "_count"))
        }
        return ViewResult(reduced)
    }

    /** _sum reducer: group by key, sum numeric values per key. */
    fun reduceSum(): ViewResult {
        // ⚡ Bolt: Accumulate sum directly instead of allocating a List<ViewRow> per key
        val sums = mutableMapOf<Any?, Double>()
        for (row in rows) {
            val key = row.key
            val currentSum = sums[key] ?: 0.0
            sums[key] = currentSum + row.value.toDoubleValue()
        }
        val reduced = mutableSeriesOf<ViewRow>()
        for ((key, sum) in sums) {
            reduced.append(ViewRow(key = key, value = sum, docId = "_sum", jsPath = "_sum"))
        }
        return ViewResult(reduced)
    }

    /**
     * CouchDbCascadeTool bounded shape: per key `[rollup,count]`, where rollup is
     * the numeric sum of emitted values. The shape is fixed-size and rereduce-safe.
     */
    fun reduceRollupCount(): ViewResult {
        val acc = mutableMapOf<Any?, DoubleArray>()
        for (row in rows) {
            val a = acc.getOrPut(row.key) { doubleArrayOf(0.0, 0.0) }
            a[0] += row.value.toDoubleValue()
            a[1] += 1.0
        }
        val reduced = mutableSeriesOf<ViewRow>()
        for ((key, a) in acc) {
            reduced.append(ViewRow(key, listOf(a[0], a[1].toLong()), "rollup-count", "rollup-count"))
        }
        return ViewResult(reduced)
    }

    /** _stats reducer: group by key, compute count/sum/min/max/sumSqr per key. */
    private class StatsAcc(
        var count: Long = 0L,
        var sum: Double = 0.0,
        var minVal: Double? = null,
        var maxVal: Double? = null,
        var sumSqr: Double = 0.0
    )

    fun reduceStats(): ViewResult {
        // ⚡ Bolt: Accumulate stats directly instead of allocating a List<ViewRow> per key
        val statsMap = mutableMapOf<Any?, StatsAcc>()
        for (row in rows) {
            val key = row.key
            val acc = statsMap.getOrPut(key) { StatsAcc() }
            val v = row.value.toDoubleValue()
            acc.count++
            acc.sum += v
            acc.minVal = if (acc.minVal == null) v else kotlin.math.min(acc.minVal!!, v)
            acc.maxVal = if (acc.maxVal == null) v else kotlin.math.max(acc.maxVal!!, v)
            acc.sumSqr += v * v
        }
        val reduced = mutableSeriesOf<ViewRow>()
        for ((key, acc) in statsMap) {
            val stats = mapOf<String, Any>(
                "count"  to acc.count,
                "sum"    to acc.sum,
                "min"    to (acc.minVal ?: 0.0),
                "max"    to (acc.maxVal ?: 0.0),
                "sumsqr" to acc.sumSqr
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

/** A real ViewServer result paired with replay-verifiable execution evidence. */
data class ViewProofExecution(
    val result: ViewResult,
    val receipt: MapReduceProofReceipt,
)

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
     * Executes this server's native map/reduce path and binds its canonical output to the
     * supplied document sequence. No cached rows or reducer state participates in the receipt.
     */
    fun executeWithProof(viewDef: ViewDefinition, documents: Iterable<Document>): ViewProofExecution {
        val result = execute(viewDef, documents)
        return ViewProofExecution(result, receiptFor(viewDef, documents, result))
    }

    /**
     * Pure replay verification: re-executes [viewDef] against the provided ordered documents,
     * then independently re-mints the receipt to validate source CIDs, reducer, output, and CID.
     */
    fun verifyReplay(
        viewDef: ViewDefinition,
        documents: Iterable<Document>,
        result: ViewResult,
        receipt: MapReduceProofReceipt,
        reducer: ReducerIdentity = reducerIdentity(viewDef),
    ): Boolean {
        if (reducer != reducerIdentity(viewDef)) return false
        // Bolt: Prevent O(N) boxing allocation by using contentEquals instead of toList()
        if (!receipt.viewDefinition.canonicalBytes.contentEquals(definitionBytes(viewDef))) return false
        if (receipt.reducer != reducer) return false
        val replayBytes = resultBytes(execute(viewDef, documents))
        if (!replayBytes.contentEquals(receipt.outputBytes)) return false
        if (!resultBytes(result).contentEquals(receipt.outputBytes)) return false
        val reminted = MapReduceProofReceipt.mint(
            ViewDefinitionIdentity(definitionBytes(viewDef)),
            documents.map { ContentId.of(documentBytes(it)) },
            reducer,
            replayBytes,
        )
        return reminted.contentId == receipt.contentId && reminted.canonicalBytes.contentEquals(receipt.canonicalBytes)
    }

    /**
     * Production path: view map over [CouchStore] via [CouchStore.query] Cursor.
     * Cursor enumerates rows (and _id column); store.get supplies map body.
     * Closes S5: query algebra with a real consumer outside tests.
     */
    suspend fun load(viewDef: ViewDefinition, store: CouchStore): ViewResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        execute(viewDef, store)
    }

    fun execute(viewDef: ViewDefinition, store: CouchStore): ViewResult {
        val qr = store.query()
        val cursor = qr.cursor
        require(qr.totalCount == store.size.toLong()) {
            "query totalCount ${qr.totalCount} != store.size ${store.size}"
        }
        require(cursor.size == store.size) {
            "query cursor.size ${cursor.size} != store.size ${store.size}"
        }
        val docs = cursor.toDocuments()
        return execute(viewDef, docs.view)
    }

    /** Execute a view definition against a list of documents. */
    suspend fun load(viewDef: ViewDefinition, documents: Series<Document>): ViewResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        execute(viewDef, documents.view)
    }

    fun execute(viewDef: ViewDefinition, documents: Iterable<Document>): ViewResult {
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

    private fun receiptFor(
        viewDef: ViewDefinition,
        documents: Iterable<Document>,
        result: ViewResult,
    ): MapReduceProofReceipt = MapReduceProofReceipt.mint(
        viewDefinition = ViewDefinitionIdentity(definitionBytes(viewDef)),
        sourceDocumentCids = documents.map { ContentId.of(documentBytes(it)) },
        reducer = reducerIdentity(viewDef),
        outputBytes = resultBytes(result),
    )

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

    /** Execute a custom Confix DSL reducer. */
    private fun executeCustomReduce(dsl: String, input: ViewResult): ViewResult {
        val doc = borg.trikeshed.parse.confix.confixDoc(dsl)

        val groups = mutableMapOf<Any?, MutableList<ViewRow>>()
        for (row in input.rows) {
            groups.getOrPut(row.key) { mutableListOf() }.add(row)
        }

        val reduced = borg.trikeshed.collections.mutableSeriesOf<ViewRow>()
        for ((key, group) in groups) {
            val reducedValue = evaluateReducerAst(doc, group)
            reduced.append(ViewRow(key = key, value = reducedValue, docId = "_custom", jsPath = "_custom"))
        }
        return ViewResult(reduced)
    }

    private fun evaluateReducerAst(ast: ConfixDoc, group: List<ViewRow>): Any? {
        val op = ast.value("op") as? String ?: return null
        val mapExpr: ConfixCell? = ast.docAt("map")

        val values: Series<Any?> = if (mapExpr != null) {
            group.size j { i: Int -> evaluateExpr(mapExpr, group[i].value) }
        } else {
            group.size j { i: Int -> group[i].value }
        }

        return when (op) {
            "sum" -> values.view.sumOf { it.toDoubleValue() }
            "count" -> values.size.toLong()
            "min" -> values.view.minOfOrNull { it.toDoubleValue(Double.MAX_VALUE) } ?: 0.0
            "max" -> values.view.maxOfOrNull { it.toDoubleValue(Double.MIN_VALUE) } ?: 0.0
            "avg" -> {
                if (values.size == 0) 0.0
                else values.view.sumOf { it.toDoubleValue() } / values.size
            }
            "concat" -> values.view.joinToString("") { it?.toString() ?: "" }
            "collect" -> values.toList()
            else -> null
        }
    }

    /**
     * Evaluate one Confix DSL expression cell against a row value. Objects are
     * `{"op": "+"|"*"|"value", "args": [...]}`; the string `"$value"` and the
     * `{"op":"value"}` form both read the row value; any other scalar is a literal.
     */
    private fun evaluateExpr(expr: ConfixCell, rowValue: Any?): Any? {
        if (expr.row.tag != IOMemento.IoObject) {
            val literal = expr.reify()
            return if (literal == "\$value") rowValue else literal
        }
        val op = expr["op"]?.reify() as? String ?: return null
        val args: Series<ConfixCell> = expr["args"]?.cellKids ?: (0 j { _: Int -> expr })

        return when (op) {
            "+" -> args.view.sumOf { evaluateExpr(it, rowValue).toDoubleValue() }
            "*" -> {
                var acc = 1.0
                for (i in 0 until args.size) acc *= evaluateExpr(args[i], rowValue).toDoubleValue(1.0)
                acc
            }
            "value" -> rowValue
            else -> null
        }
    }
}

private fun Any?.toDoubleValue(default: Double = 0.0): Double = when (this) {
    is Number -> this.toDouble()
    is String -> this.toDoubleOrNull() ?: default
    else -> default
}

private fun reducerIdentity(viewDef: ViewDefinition): ReducerIdentity = when (val reduce = viewDef.reduceFn) {
    null -> ReducerIdentity("_map", "builtin-v1")
    is ReduceFunction.Builtin -> ReducerIdentity(reduce.name, "builtin-v1")
    is ReduceFunction.Custom -> ReducerIdentity("confix:${reduce.dsl}", "confix-v1")
}

private fun definitionBytes(viewDef: ViewDefinition): ByteArray = canonicalFields(
    "view-definition-v1",
    viewDef.ddoc,
    viewDef.viewName,
    mapFunctionValue(viewDef.mapFn),
    reduceFunctionValue(viewDef.reduceFn),
).encodeToByteArray()

private fun documentBytes(document: Document): ByteArray = canonicalFields(
    "document-v1",
    document.id,
    *document.fields.map { field -> canonicalFields(field.name, canonicalValue(field.value)) }.toTypedArray(),
).encodeToByteArray()

private fun resultBytes(result: ViewResult): ByteArray = canonicalFields(
    "view-result-v1",
    *(result.rows.size j { i: Int ->
        val row = result.rows[i]
        canonicalFields(row.docId, row.jsPath, canonicalValue(row.key), canonicalValue(row.value))
    }).toArray<String>(),
).encodeToByteArray()

private fun mapFunctionValue(map: MapFunction): String = when (map) {
    is MapFunction.Emit -> canonicalFields("emit", keyExprValue(map.key), valueExprValue(map.value))
    is MapFunction.EmitEach -> canonicalFields("emit-each", map.arrayField, keyExprValue(map.keyExpr), valueExprValue(map.valueExpr))
}

private fun reduceFunctionValue(reduce: ReduceFunction?): String = when (reduce) {
    null -> "none"
    is ReduceFunction.Builtin -> canonicalFields("builtin", reduce.name)
    is ReduceFunction.Custom -> canonicalFields("custom", reduce.dsl)
}

private fun keyExprValue(expression: KeyExpr): String = when (expression) {
    is KeyExpr.DocField -> canonicalFields("field", expression.fieldName)
    is KeyExpr.DocId -> "doc-id"
    is KeyExpr.Const -> canonicalFields("const", canonicalValue(expression.value))
    is KeyExpr.JsPathExpr -> canonicalFields("path", expression.path)
}

private fun valueExprValue(expression: ValueExpr): String = when (expression) {
    is ValueExpr.DocField -> canonicalFields("field", expression.fieldName)
    is ValueExpr.DocValue -> "doc-value"
    is ValueExpr.Const -> canonicalFields("const", canonicalValue(expression.value))
    is ValueExpr.JsPathExpr -> canonicalFields("path", expression.path)
}

private fun canonicalValue(value: Any?): String = when (value) {
    null -> "null"
    is String -> canonicalFields("string", value)
    is Boolean -> canonicalFields("boolean", value.toString())
    is Byte, is Short, is Int, is Long, is Float, is Double -> canonicalFields("number", value.toString())
    is List<*> -> canonicalFields("list", *value.map(::canonicalValue).toTypedArray())
    is Map<*, *> -> canonicalFields("map", *value.entries.map { entry ->
        canonicalFields(canonicalValue(entry.key), canonicalValue(entry.value))
    }.sorted().toTypedArray())
    is Document -> canonicalFields("document", documentBytes(value).decodeToString())
    else -> canonicalFields("other", value::class.toString(), value.toString())
}

private fun canonicalFields(vararg fields: String): String = buildString {
    fields.forEach { field -> append(field.length).append(':').append(field) }
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

// WorkDrained