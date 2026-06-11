package borg.trikeshed.couch.viewserver

import borg.trikeshed.couch.ConfixDocStoreEntry
import borg.trikeshed.couch.ViewStore
import borg.trikeshed.cursor.Series
import borg.trikeshed.lib.j
import borg.trikeshed.parse.confix.ConfixDoc
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value

/**
 * A CouchDB 1.6/1.7 compatible View Server running in-process via GraalVM Polyglot Javascript.
 *
 * Instead of spawning a SpiderMonkey process and sending JSON over standard I/O, this executes
 * map and reduce functions natively, piping the `emit` callback directly into the Kotlin ViewStore.
 */
class GraalVmViewServer(
    val viewStore: ViewStore
) : AutoCloseable {

    private val context: Context = Context.newBuilder("js")
        .build()

    /**
     * Define a view by compiling a Javascript map function (and optional reduce function)
     * and linking it to the ViewStore.
     *
     * @param viewName The name of the view (e.g., "_design/my_doc/_view/by_name")
     * @param mapJs The javascript map function source (e.g., "function(doc) { emit(doc.name, doc.value); }")
     * @param reduceJs Optional javascript reduce function source
     */
    fun defineView(viewName: String, mapJs: String, reduceJs: String? = null) {
        val mapFunction = context.eval("js", "($mapJs)")

        val reduceFunction = reduceJs?.let { context.eval("js", "($it)") }

        viewStore.define(
            name = viewName,
            map = { entry: ConfixDocStoreEntry, emit: (String, String) -> Unit ->
                // To safely pass ConfixDoc into GraalVM JS, we would ideally serialize it to a JS object
                // or expose a polyglot wrapper. For simplicity in this demo, we expose it as a JSON-like object
                // if it can be represented as a map.
                val docStr = entry.doc.toString()

                // Wrap the Kotlin emit function so JS can call it
                val jsEmit = java.util.function.BiConsumer<Any, Any> { key, value ->
                    emit(key.toString(), value.toString())
                }

                // Set the global emit function expected by CouchDB map functions
                context.getBindings("js").putMember("emit", jsEmit)

                // Parse the ConfixDoc representation into a real JS object safely
                val jsonParse = context.eval("js", "JSON.parse")
                val jsDoc = jsonParse.execute(docStr)

                // Execute the map function
                mapFunction.execute(jsDoc)
            },
            reduce = reduceFunction?.let { jsRedFn ->
                { key: String, values: Series<String> ->
                    // For reduce, CouchDB passes (keys, values, rereduce)
                    // We simplify to (key, values) for this implementation
                    // Convert Series<String> to a JS Array
                    val jsValues = context.eval("js", "[]")
                    for (i in 0 until values.size) {
                        jsValues.setArrayElement(i.toLong(), values[i])
                    }

                    val result = jsRedFn.execute(key, jsValues, false)

                    // CouchDB reducers might return ints, we ensure int return for our specific ViewStore definition
                    if (result.isNumber) {
                        result.asInt()
                    } else {
                        result.toString().toIntOrNull() ?: 0
                    }
                }
            }
        )
    }

    override fun close() {
        context.close()
    }
}
