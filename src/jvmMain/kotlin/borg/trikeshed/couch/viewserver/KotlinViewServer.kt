package borg.trikeshed.couch.viewserver

import borg.trikeshed.couch.ViewRow
import borg.trikeshed.pointcut.PointcutBlackboardAdapter
import borg.trikeshed.pointcut.PointcutEvent
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.relaxfactory.ViewLanguageHost
import borg.trikeshed.relaxfactory.ViewLanguages
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.dependenciesFromClassloader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

/** A compiled `language: "kotlin"` map: one document in, emissions through the second argument. */
typealias KotlinMap = (Map<String, Any?>, (Any?, Any?) -> Unit) -> Unit

/** A compiled `language: "kotlin"` reduce: CouchDB's `(keys, values, rereduce)`. */
typealias KotlinReduce = (List<Any?>, List<Any?>, Boolean) -> Any?

/**
 * The `language: "kotlin"` query server. A view's `map` is a Kotlin lambda over `doc` that calls
 * `emit(key, value)` (`{ doc -> emit(doc["type"], 1) }`); `reduce` is a lambda over
 * `(keys, values, rereduce)`. Each source compiles once as a `.kts` script against the whole
 * classpath of [loader] and evaluates in [loader], so view code reaches the daemon's own
 * `borg.trikeshed.*` classes and every jar the daemon runs with.
 */
class KotlinViewServer(
    val points: PointcutBlackboardAdapter? = null,
    val loader: ClassLoader = KotlinViewServer::class.java.classLoader,
) : ViewLanguageHost {

    private val host = BasicJvmScriptingHost()

    private val compilation = ScriptCompilationConfiguration {
        jvm { dependenciesFromClassloader(classLoader = loader, wholeClasspath = true) }
    }

    private val evaluation = ScriptEvaluationConfiguration {
        jvm { baseClassLoader(loader) }
    }

    private val compiled = HashMap<String, Any?>()

    /** Compile and evaluate a script whose value is the function; failures carry the compiler's diagnostics. */
    private fun function(script: String): Any? = synchronized(compiled) {
        compiled.getOrPut(script) {
            when (val r = host.eval(script.toScriptSource("view.kts"), compilation, evaluation)) {
                is ResultWithDiagnostics.Success<EvaluationResult> -> when (val v = r.value.returnValue) {
                    is ResultValue.Value -> v.value
                    is ResultValue.Error -> throw IllegalArgumentException("view script threw: ${v.error}", v.error)
                    else -> throw IllegalArgumentException("view script produced no function")
                }
                is ResultWithDiagnostics.Failure -> throw IllegalArgumentException(
                    r.reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
                        .joinToString("; ") { d -> d.message + (d.location?.start?.let { " @${it.line}:${it.col}" } ?: "") })
            }
        }
    }

    override fun map(ddoc: String, view: String, source: String, docs: List<Map<String, Any?>>): List<ViewRow> {
        points.viewBoundary(VmFacet.JVM, "map", "begin", ddoc, view, mapOf("docs" to docs.size))
        @Suppress("UNCHECKED_CAST")
        val fn = function(
            "val m: (Map<String, Any?>, (Any?, Any?) -> Unit) -> Unit = { d, emit -> val f: (Map<String, Any?>) -> Unit = (\n$source\n); f(d) }\nm\n",
        ) as KotlinMap
        val rows = ArrayList<ViewRow>()
        for (doc in docs) {
            val id = doc["_id"] as String
            fn(doc) { k, v -> rows.add(ViewRow(k, v, id)) }
        }
        points.viewBoundary(VmFacet.JVM, "map", "end", ddoc, view, mapOf("docs" to docs.size, "rows" to rows.size))
        return rows
    }

    override fun reduce(ddoc: String, view: String, source: String, keys: List<Any?>, values: List<Any?>, rereduce: Boolean): Any? {
        points.viewBoundary(VmFacet.JVM, "reduce", "begin", ddoc, view, mapOf("values" to values.size))
        @Suppress("UNCHECKED_CAST")
        val fn = function("val r: (List<Any?>, List<Any?>, Boolean) -> Any? = (\n$source\n)\nr\n") as KotlinReduce
        return fn(keys, values, rereduce).also {
            points.viewBoundary(VmFacet.JVM, "reduce", "end", ddoc, view, mapOf("values" to values.size))
        }
    }

    companion object {
        /**
         * Install the process's query servers: GraalJS for `javascript`, this host for `kotlin`,
         * both landing their view-execution boundaries on [points].
         */
        fun install(points: PointcutBlackboardAdapter?) {
            ViewLanguages.hosts["javascript"] = GraalVmViewServer(points)
            ViewLanguages.hosts["kotlin"] = KotlinViewServer(points)
        }
    }
}

/**
 * One view-execution boundary as a pointcut landing: coordinate
 * `borg.trikeshed.couch.viewserver.<ddoc>/<view>.<phase>`, property `begin|end`.
 */
fun PointcutBlackboardAdapter?.viewBoundary(facet: VmFacet, phase: String, edge: String, ddoc: String, view: String, fields: Map<String, Any?>) {
    val points = this ?: return
    runCatching {
        points.accept(PointcutEvent(facet, "borg.trikeshed.couch.viewserver.$ddoc/$view.$phase", null, edge,
            fields + mapOf("ddoc" to ddoc, "view" to view, "language" to facet.id)), isWrite = true)
    }
}
