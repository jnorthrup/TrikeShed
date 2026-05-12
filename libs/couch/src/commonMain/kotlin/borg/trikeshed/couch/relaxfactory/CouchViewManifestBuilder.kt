package borg.trikeshed.couch.relaxfactory

import borg.trikeshed.couch.api.CouchDb11DesignDocument
import borg.trikeshed.couch.api.CouchViewDefinition

/**
 * Minimal JSON encoder for CouchDB view query parameters.
 * Handles null, CharSequence, Boolean, Number, List, Map; falls back to toString() for anything else.
 */
internal fun jsonEncode(value: Any?): CharSequence = when (value) {
    null -> "null"
    is CharSequence -> buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
    is Boolean -> value.toString()
    is Number -> value.toString()
    is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { jsonEncode(it) }
    is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
        jsonEncode(k) + ":" + jsonEncode(v)
    }
    else -> jsonEncode(value.toString())
}

/**
 * CommonMain DSL for building a [CouchViewManifest] without JVM reflection.
 *
 * Usage:
 * ```kotlin
 * val manifest = couchViewManifest("mydb", "_design/myservice") {
 *     view("byBrand", map = "function(doc){if(doc.brand)emit(doc.brand,doc)}", template = "_design/myservice/_view/byBrand?key=%1\$s")
 *     view("byYear",  map = "function(doc){emit(doc.year,doc)}", template = "_design/myservice/_view/byYear?startkey=%1\$s&endkey=%2\$s")
 * }
 * ```
 */
fun couchViewManifest(
    databaseName: CharSequence,
    designDocId: CharSequence,
    block: CouchViewManifestBuilder.() -> Unit,
): CouchViewManifest {
    val builder = CouchViewManifestBuilder(databaseName, designDocId)
    builder.block()
    return builder.build()
}

class CouchViewManifestBuilder(
    private val databaseName: CharSequence,
    private val designDocId: CharSequence,
) {
    private val viewDefs = mutableMapOf<CharSequence, CouchViewDefinition>()
    private val invocations = mutableMapOf<CharSequence, CouchViewInvocation>()

    /**
     * Register a view with an explicit query [template].
     * Templates use `%N$s` placeholders for positional arguments (URL-encoded by [CouchViewInvocation.invoke]).
     */
    fun view(
        name: CharSequence,
        map: CharSequence,
        reduce: CharSequence? = null,
        template: CharSequence,
        returnShape: CouchViewInvocation.ReturnShape = CouchViewInvocation.ReturnShape.ListValue,
    ) {
        viewDefs[name] = CouchViewDefinition(map = map, reduce = reduce)
        invocations[name] = CouchViewInvocation(
            path = "",
            template = template,
            returnShape = returnShape,
            encodeValue = ::jsonEncode,
            databaseName = databaseName,
        )
    }

    fun build(): CouchViewManifest = CouchViewManifest(
        databaseName = databaseName,
        designDocument = CouchDb11DesignDocument(
            id = designDocId,
            language = "javascript",
            views = viewDefs,
        ),
        views = invocations,
    )
}
