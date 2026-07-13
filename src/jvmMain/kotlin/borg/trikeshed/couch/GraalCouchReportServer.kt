package borg.trikeshed.couch

import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.src
import borg.trikeshed.pointcut.PointcutReporter
import borg.trikeshed.pointcut.VmFacet
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyExecutable

private data class MappedEmission(
    val docId: String,
    val key: List<Any?>,
    val value: Any?,
)

data class CouchMetricStats(
    val sum: Double,
    val avg: Double,
    val min: Double,
    val max: Double,
)

data class CouchCascadeReport(
    val viewName: String,
    val count: Long,
    val metrics: Map<String, CouchMetricStats>,
)

/**
 * In-process CouchDB-style report server.
 *
 * Documents remain Confix-backed. GraalJS executes the recovered cascade map
 * and reduce functions, while the report reactor owns lifecycle and receives
 * the same pointcut stream used by Java 25 classfile instrumentation.
 */
class GraalCouchReportServer private constructor(
    private val store: ConfixDocStore,
    parentJob: Job?,
    private val reduceChunkSize: Int,
) {
    private val supervisor: CompletableJob = SupervisorJob(parentJob)
    private val scope = CoroutineScope(supervisor + Dispatchers.Default)
    val reactor = CouchReportReactorElement(supervisor)

    private val executionMutex = Mutex()
    private val context = Context.newBuilder("js")
        .option("engine.WarnInterpreterOnly", "false")
        .build()
    private val jsonParse: Value
    private val reduceFunction: Value
    private val mapFunctions: Map<CouchCascadeView, Value>
    private val cancelPointcutSubscription: () -> Unit
    private var closed = false

    init {
        require(reduceChunkSize > 0) { "reduceChunkSize must be positive" }
        context.eval(
            "js",
            "globalThis.sum = function(values) { " +
                "return values.reduce(function(total, value) { return total + Number(value); }, 0); " +
                "};",
        )
        jsonParse = context.eval("js", "JSON.parse")
        reduceFunction = context.eval("js", "(${CouchCascadeView.reduceSource})")
        mapFunctions = CouchCascadeView.entries.associateWith { view ->
            context.eval("js", "(${view.mapSource})")
        }
        cancelPointcutSubscription = PointcutReporter.subscribe { event ->
            reactor.ingest(
                CouchReportEvent.PointcutObserved(
                    vmFacet = event.vmFacet.id,
                    coordinate = event.coordinate,
                    propertyName = event.propertyName,
                    newValue = event.newValue.toString(),
                    timestampMs = event.timestamp,
                ),
            )
        }
    }

    suspend fun report(
        view: CouchCascadeView,
        startKey: List<Any?>,
        endKey: List<Any?>,
    ): CouchCascadeReport = withContext(scope.coroutineContext + reactor) {
        check(currentCoroutineContext()[CouchReportReactorElement.Key] === reactor) {
            "report reactor is not mapped into the supervisor context"
        }
        check(reactor.lifecycleState == ElementState.ACTIVE) { "report server is not active" }
        executionMutex.withLock {
            executeReport(view, startKey, endKey)
        }
    }

    suspend fun close() {
        executionMutex.withLock {
            if (closed) return
            closed = true
            cancelPointcutSubscription()
            context.close(true)
        }
        reactor.close()
        supervisor.cancelAndJoin()
    }

    private fun executeReport(
        view: CouchCascadeView,
        startKey: List<Any?>,
        endKey: List<Any?>,
    ): CouchCascadeReport {
        val emitted = mapDocuments(view)
        val selected = emitted.filter { row ->
            compareKeys(row.key, startKey) >= 0 && compareKeys(row.key, endKey) <= 0
        }
        val report = reduce(view, selected)
        reactor.ingest(CouchReportEvent.Reduced(view.viewName, report.count))
        PointcutReporter.report(
            VmFacet.GRAAL_JS.id,
            "couch.${view.viewName}.reduce",
            null,
            "result",
            report.count,
        )
        return report
    }

    private fun mapDocuments(view: CouchCascadeView): List<MappedEmission> {
        val mapFunction = mapFunctions.getValue(view)
        val emitted = ArrayList<MappedEmission>()
        val entries = store.entries

        for (index in 0 until entries.size) {
            val entry = entries[index]
            val emit = ProxyExecutable { arguments ->
                val key = arguments.getOrNull(0).toKotlinValue().asKey()
                val value = arguments.getOrNull(1).toKotlinValue()
                emitted.add(MappedEmission(entry.id, key, value))
                reactor.ingest(CouchReportEvent.MapEmitted(view.viewName, entry.id, key.toString()))
                PointcutReporter.report(
                    VmFacet.GRAAL_JS.id,
                    "couch.${view.viewName}.map",
                    null,
                    "emit",
                    key,
                )
                null
            }
            context.getBindings("js").putMember("emit", emit)
            mapFunction.execute(jsonParse.execute(entry.doc.sourceText()))
        }
        return emitted
    }

    private fun reduce(
        view: CouchCascadeView,
        rows: List<MappedEmission>,
    ): CouchCascadeReport {
        if (rows.isEmpty()) {
            return CouchCascadeReport(
                viewName = view.viewName,
                count = 0,
                metrics = CouchCascadeView.metricFields.associateWith {
                    CouchMetricStats(0.0, 0.0, 0.0, 0.0)
                },
            )
        }

        val partials = rows.chunked(reduceChunkSize).map { chunk ->
            executeReduce(
                keys = chunk.map { listOf(it.key, it.docId) },
                values = chunk.map { it.value },
                rereduce = false,
            )
        }
        val reduced = if (partials.size == 1) {
            partials[0]
        } else {
            executeReduce(keys = emptyList(), values = partials, rereduce = true)
        }
        return reduced.toReport(view.viewName)
    }

    private fun executeReduce(
        keys: List<Any?>,
        values: List<Any?>,
        rereduce: Boolean,
    ): Any? {
        val jsKeys = jsonParse.execute(keys.toJsonElement().toString())
        val jsValues = jsonParse.execute(values.toJsonElement().toString())
        return reduceFunction.execute(jsKeys, jsValues, rereduce).toKotlinValue()
    }

    companion object {
        suspend fun open(
            store: ConfixDocStore,
            parentJob: Job? = null,
            reduceChunkSize: Int = 256,
        ): GraalCouchReportServer = GraalCouchReportServer(
            store = store,
            parentJob = parentJob,
            reduceChunkSize = reduceChunkSize,
        ).also { it.reactor.open() }
    }
}

private fun ConfixDoc.sourceText(): String =
    ByteArray(src.size) { index -> src[index] }.decodeToString()

private fun Value?.toKotlinValue(): Any? {
    if (this == null || isNull) return null
    return when {
        isBoolean -> asBoolean()
        isString -> asString()
        isNumber && fitsInLong() -> asLong()
        isNumber -> asDouble()
        hasArrayElements() -> List(arraySize.toInt()) { index -> getArrayElement(index.toLong()).toKotlinValue() }
        hasMembers() -> memberKeys.associateWith { key -> getMember(key).toKotlinValue() }
        else -> toString()
    }
}

private fun Any?.asKey(): List<Any?> = when (this) {
    is List<*> -> this
    else -> listOf(this)
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is List<*> -> JsonArray(map { it.toJsonElement() })
    is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJsonElement() })
    else -> JsonPrimitive(toString())
}

private fun Any?.toNumber(): Double = when (this) {
    is Number -> toDouble()
    is String -> toDouble()
    else -> error("Expected numeric report value, got $this")
}

private fun Any?.toReport(viewName: String): CouchCascadeReport {
    val pair = this as? List<*> ?: error("Reducer must return [metrics, count], got $this")
    require(pair.size == 2) { "Reducer must return [metrics, count], got $pair" }
    val rawMetrics = pair[0] as? Map<*, *> ?: error("Reducer metrics must be an object")
    val count = (pair[1] as? Number)?.toLong() ?: error("Reducer count must be numeric")
    val metrics = CouchCascadeView.metricFields.associateWith { field ->
        val raw = rawMetrics[field] as? Map<*, *> ?: error("Reducer omitted metric $field")
        CouchMetricStats(
            sum = raw["sum"].toNumber(),
            avg = raw["avg"].toNumber(),
            min = raw["min"].toNumber(),
            max = raw["max"].toNumber(),
        )
    }
    return CouchCascadeReport(viewName, count, metrics)
}

private fun compareKeys(left: List<Any?>, right: List<Any?>): Int {
    val shared = minOf(left.size, right.size)
    for (index in 0 until shared) {
        val compared = compareKeyParts(left[index], right[index])
        if (compared != 0) return compared
    }
    return left.size.compareTo(right.size)
}

private fun compareKeyParts(left: Any?, right: Any?): Int = when {
    left === right -> 0
    left == null -> -1
    right == null -> 1
    left is Number && right is Number -> left.toDouble().compareTo(right.toDouble())
    left is String && right is String -> left.compareTo(right)
    left is Boolean && right is Boolean -> left.compareTo(right)
    else -> left.toString().compareTo(right.toString())
}
