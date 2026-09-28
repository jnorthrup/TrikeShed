package borg.trikeshed.couch.viewserver

import borg.trikeshed.parse.reify
import borg.trikeshed.parse.jsonOf

import borg.trikeshed.couch.ViewRow
import borg.trikeshed.relaxfactory.ViewLanguageHost
import borg.trikeshed.pointcut.PointcutBlackboardAdapter
import borg.trikeshed.pointcut.VmFacet
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.Value

/**
 * The `language: "javascript"` query server: CouchDB map/reduce function source runs in a
 * GraalJS context with no host access and a per-call statement budget ([STATEMENT_LIMIT]).
 * Documents cross as JSON text and emissions return as JSON text, so no host object is ever
 * reachable from guest code.
 */
class GraalVmViewServer(val points: PointcutBlackboardAdapter? = null) : AutoCloseable, ViewLanguageHost {

    private fun newContext(): Context = Context.newBuilder("js")
        .allowHostAccess(HostAccess.NONE)
        .resourceLimits(ResourceLimits.newBuilder().statementLimit(STATEMENT_LIMIT, null).build())
        .build()

    /** Replaced whole after a limit breach: a cancelled context accepts no further calls. */
    private var context: Context = newContext()

    fun evalJs(expr: String): String {
        val source = org.graalvm.polyglot.Source.newBuilder("js", expr, "eval.js").build()
        return context.eval(source).toString()
    }

    private var mapFunction: org.graalvm.polyglot.Value? = null
    private var reduceFunction: org.graalvm.polyglot.Value? = null

    private val functionPattern = Regex("""^\s*function\s*[A-Za-z0-9_$]*\s*\(([^)]*)\)\s*\{([\s\S]*)\}\s*$""")

    /**
     * Compile function source without evaluating it: the parameter list and body are split and
     * handed to the JS `Function` constructor, so no code runs until the function is called.
     */
    private fun compile(source: String): Value {
        val match = functionPattern.find(source) ?: throw IllegalArgumentException("Invalid function format")
        context.resetLimits()
        return context.eval("js", "(function(args, body) { return new Function(args, body); })")
            .execute(match.groupValues[1], match.groupValues[2])
    }

    /**
     * Define a view by compiling a Javascript map function (and optional reduce function)
     * and linking it to the ViewStore.
     *
     * @param viewName The name of the view (e.g., "_design/my_doc/_view/by_name")
     * @param mapJs The javascript map function source (e.g., "function(doc) { emit(doc.name, doc.value); }")
     * @param reduceJs Optional javascript reduce function source
     */
    fun defineView(viewName: String, mapJs: String, reduceJs: String? = null) {
        mapFunction = compile(mapJs)
        reduceFunction = reduceJs?.let(::compile)
    }

    /** Compiled functions by source text; a design doc edit changes the source and so the entry. */
    private val compiled = HashMap<String, Value>()

    /** The guest-side `emit`/`sum`/`log` CouchDB exposes, and the JSON-text call boundary. */
    private var runtimeValue: Value? = null
    private val runtime: Value get() = runtimeValue ?: context.eval("js", """
            var __rows = [];
            function emit(k, v) { __rows.push([k === undefined ? null : k, v === undefined ? null : v]); }
            function sum(xs) { var s = 0; for (var i = 0; i < xs.length; i++) s += xs[i]; return s; }
            function log(m) {}
            ({
              map: function(fn, text) { __rows = []; fn(JSON.parse(text)); return JSON.stringify(__rows); },
              reduce: function(fn, keys, values, rereduce) {
                var r = fn(JSON.parse(keys), JSON.parse(values), rereduce);
                return JSON.stringify(r === undefined ? null : r);
              }
            })
        """.trimIndent()).also { runtimeValue = it }

    /** Run [block]; a statement-limit breach cancels the context, so the context is rebuilt and the breach reported. */
    private fun <T> bounded(ddoc: String, view: String, block: () -> T): T = try {
        block()
    } catch (e: PolyglotException) {
        if (!e.isResourceExhausted && !e.isCancelled) throw e
        context.close(true)
        context = newContext()
        runtimeValue = null
        compiled.clear()
        throw IllegalStateException("$ddoc/$view exceeded $STATEMENT_LIMIT statements", e)
    }

    override fun map(ddoc: String, view: String, source: String, docs: List<Map<String, Any?>>): List<ViewRow> = synchronized(this) {
        points.viewBoundary(VmFacet.GRAAL_JS, "map", "begin", ddoc, view, mapOf("docs" to docs.size))
        val rows = ArrayList<ViewRow>()
        bounded(ddoc, view) {
            val fn = compiled.getOrPut(source) { compile(source) }
            val mapCall = runtime.getMember("map")
            for (doc in docs) {
                context.resetLimits()
                val id = doc["_id"] as String
                val emitted = reify(mapCall.execute(fn, jsonOf(doc)).asString()) as List<*>
                for (pair in emitted) (pair as List<*>).let { rows.add(ViewRow(it[0], it[1], id)) }
            }
        }
        points.viewBoundary(VmFacet.GRAAL_JS, "map", "end", ddoc, view, mapOf("docs" to docs.size, "rows" to rows.size))
        rows
    }

    override fun reduce(ddoc: String, view: String, source: String, keys: List<Any?>, values: List<Any?>, rereduce: Boolean): Any? = synchronized(this) {
        points.viewBoundary(VmFacet.GRAAL_JS, "reduce", "begin", ddoc, view, mapOf("values" to values.size))
        val out = bounded(ddoc, view) {
            val fn = compiled.getOrPut(source) { compile(source) }
            context.resetLimits()
            reify(runtime.getMember("reduce")
                .execute(fn, jsonOf(keys), jsonOf(values), rereduce).asString())
        }
        points.viewBoundary(VmFacet.GRAAL_JS, "reduce", "end", ddoc, view, mapOf("values" to values.size))
        out
    }

    override fun close() {
        context.close()
    }

    companion object {
        /** Guest statements one map (per document) or reduce call may execute. */
        const val STATEMENT_LIMIT = 100_000L
    }
}
