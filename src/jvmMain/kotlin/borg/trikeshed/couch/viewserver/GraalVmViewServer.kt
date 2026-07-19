package borg.trikeshed.couch.viewserver

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.ResourceLimits

class GraalVmViewServer : AutoCloseable {

    private val context: Context = Context.newBuilder("js")
        .allowHostAccess(HostAccess.NONE)
        .resourceLimits(ResourceLimits.newBuilder().statementLimit(10000, null).build())
        .build()

    fun evalJs(expr: String): String {
        val source = org.graalvm.polyglot.Source.newBuilder("js", expr, "eval.js").build()
        return context.eval(source).toString()
    }

    private var mapFunction: org.graalvm.polyglot.Value? = null
    private var reduceFunction: org.graalvm.polyglot.Value? = null

    /**
     * Define a view by compiling a Javascript map function (and optional reduce function)
     * and linking it to the ViewStore.
     *
     * @param viewName The name of the view (e.g., "_design/my_doc/_view/by_name")
     * @param mapJs The javascript map function source (e.g., "function(doc) { emit(doc.name, doc.value); }")
     * @param reduceJs Optional javascript reduce function source
     */
    fun defineView(viewName: String, mapJs: String, reduceJs: String? = null) {
        // Prevent Javascript Code Injection during view definition.
        // Instead of evaluating `($mapJs)` which allows breaking out of the syntax context
        // and executing arbitrary code during definition, we parse the function's arguments
        // and body, and use the JS `Function` constructor. This separates code parsing from
        // string evaluation, ensuring no code is executed until the map function is actually called.
        val functionPattern = Regex("""^\s*function\s*\(([^)]*)\)\s*\{([\s\S]*)\}\s*$""")

        val mapMatch = functionPattern.find(mapJs) ?: throw IllegalArgumentException("Invalid map function format")
        val mapArgs = mapMatch.groupValues[1]
        val mapBody = mapMatch.groupValues[2]

        val functionCompiler = context.eval("js", "(function(args, body) { return new Function(args, body); })")

        mapFunction = functionCompiler.execute(mapArgs, mapBody)

        reduceFunction = reduceJs?.let {
            val reduceMatch = functionPattern.find(it) ?: throw IllegalArgumentException("Invalid reduce function format")
            val reduceArgs = reduceMatch.groupValues[1]
            val reduceBody = reduceMatch.groupValues[2]
            functionCompiler.execute(reduceArgs, reduceBody)
        }
    }

    override fun close() {
        context.close()
    }
}
