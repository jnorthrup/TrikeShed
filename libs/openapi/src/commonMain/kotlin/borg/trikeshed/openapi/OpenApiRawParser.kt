package borg.trikeshed.openapi

import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.json.JsonParser

// ── type aliases over plain Kotlin maps ──────────────────────────────────────
typealias JsonMap = Map<CharSequence, Any?>
@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): JsonMap? = this as? JsonMap
fun Any?.asString(): String? = (this as? CharSequence)?.toString()
fun Any?.asStr(): CharSequence? = this as? CharSequence
fun Any?.asBool(): Boolean? = this as? Boolean
fun Any?.asNum(): Number? = this as? Number
fun Any?.asList(): List<Any?>? = this as? List<Any?>

// ── domain model ─────────────────────────────────────────────────────────────

data class OpenApiToken(
    val kind: CharSequence,
    val value: CharSequence,
    val location: CharSequence,
)

data class OpenApiGap(
    val code: String,
    val location: CharSequence,
    val detail: CharSequence,
)

data class OpenApiGapAnalysis(
    val tokens: List<OpenApiToken> = emptyList(),
    val gaps: List<OpenApiGap> = emptyList(),
) {
    val isComplete: Boolean get() = gaps.isEmpty()

    companion object {
        val EMPTY = OpenApiGapAnalysis()
    }
}

data class OpenApiRawDocument(val root: JsonMap) {
    /** Expose root map directly for resolver use. */
    val raw: JsonMap get() = root

    val version: CharSequence? get() = root["openapi"].asString()
    val info: JsonMap? get() = root["info"].asMap()
    val paths: JsonMap get() = root["paths"].asMap() ?: emptyMap()
    val components: JsonMap? get() = root["components"].asMap()

    fun operations(): List<OpenApiRawOperation> =
        paths.flatMap { (path, pathNode) ->
            val pathObject = pathNode.asMap() ?: return@flatMap emptyList()
            pathObject.mapNotNull { (method, operationNode) ->
                val operation = operationNode.asMap() ?: return@mapNotNull null
                OpenApiRawOperation(path = path, method = method.toString().lowercase(), operation = operation)
            }
        }

    fun refs(): List<CharSequence> {
        val refs = mutableListOf<CharSequence>()
        fun walk(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val m = node as JsonMap
                    m["\$ref"].asString()?.let(refs::add)
                    m.values.forEach(::walk)
                }
                is List<*> -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return refs
    }

    fun tokens(): List<OpenApiToken> = buildList {
        version?.let { add(OpenApiToken(kind = "version", value = it, location = "openapi")) }
        info?.get("title").asString()?.let {
            add(OpenApiToken(kind = "info-title", value = it, location = "info.title"))
        }
        info?.get("version").asString()?.let {
            add(OpenApiToken(kind = "info-version", value = it, location = "info.version"))
        }
        paths.keys.forEach { path ->
            add(OpenApiToken(kind = "path", value = path, location = "paths.$path"))
        }
        operations().forEach { op ->
            add(OpenApiToken(
                kind = "operation",
                value = "${op.method.toString().uppercase()} ${op.path}",
                location = "paths.${op.path}.${op.method}",
            ))
            op.operationId?.let {
                add(OpenApiToken(
                    kind = "operation-id",
                    value = it,
                    location = "paths.${op.path}.${op.method}.operationId",
                ))
            }
        }
        refs().forEach { ref -> add(OpenApiToken(kind = "ref", value = ref, location = ref)) }
    }

    fun gapAnalysis(): OpenApiGapAnalysis {
        val tokens = tokens()
        val gaps = buildList {
            if (info?.get("title").asString().isNullOrBlank()) {
                add(OpenApiGap(
                    code = "missing-info-title",
                    location = "info.title",
                    detail = "OpenAPI info.title should be set while the API surface is still evolving",
                ))
            }
            if (info?.get("version").asString().isNullOrBlank()) {
                add(OpenApiGap(
                    code = "missing-info-version",
                    location = "info.version",
                    detail = "OpenAPI info.version should track the current development revision",
                ))
            }
            val ops = operations()
            if (ops.isEmpty()) {
                add(OpenApiGap(
                    code = "missing-operations",
                    location = "paths",
                    detail = "At least one path operation is required for generation",
                ))
            }
            ops.forEach { op ->
                val loc = "paths.${op.path}.${op.method}"
                if (op.operationId.isNullOrBlank()) {
                    add(OpenApiGap(
                        code = "missing-operation-id",
                        location = "$loc.operationId",
                        detail = "Operation '$loc' is missing an operationId",
                    ))
                }
                val responses = op.operation["responses"].asMap()
                if (responses.isNullOrEmpty()) {
                    add(OpenApiGap(
                        code = "missing-responses",
                        location = "$loc.responses",
                        detail = "Operation '$loc' should declare at least one response",
                    ))
                }
            }
            refs().forEach { ref ->
                if (resolveRef(ref) == null) {
                    add(OpenApiGap(
                        code = "unresolved-ref",
                        location = ref,
                        detail = "Reference '$ref' could not be resolved from the current document",
                    ))
                }
            }
        }
        return OpenApiGapAnalysis(tokens = tokens, gaps = gaps)
    }

    fun resolveRef(ref: CharSequence): Any? {
        if (!ref.startsWith("#/")) return null
        var current: Any? = root
        for (segment in ref.removePrefix("#/").split('/')) {
            val key = segment.replace("~1", "/").replace("~0", "~")
            current = current.asMap()?.get(key) ?: return null
        }
        return current
    }
}

data class OpenApiRawOperation(
    val path: CharSequence,
    val method: CharSequence,
    val operation: JsonMap,
) {
    val operationId: CharSequence? get() = operation["operationId"].asString()
}

class OpenApiParseException(message: CharSequence, cause: Throwable? = null) :
    IllegalArgumentException(message.toString(), cause)

object OpenApiRawParser {
    fun parse(text: CharSequence): OpenApiRawDocument {
        val root: Map<CharSequence, Any?> = when {
            text.isBlank() -> throw OpenApiParseException("OpenAPI spec text is blank")
            text.trimStart().startsWith("{") -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    JsonParser.reify(text.trimStart().toSeries()) as? Map<CharSequence, Any?>
                        ?: throw OpenApiParseException("Parsed JSON root is not a JSON object")
                } catch (cause: Throwable) {
                    throw OpenApiParseException("Failed to parse OpenAPI JSON", cause)
                }
            }
            else -> {
                borg.trikeshed.parse.yaml.parse(text)
            }
        }

        val version = root["openapi"].asString()
        require(!version.isNullOrBlank()) { "OpenAPI document is missing a non-empty 'openapi' version field" }
        require(root["paths"] is Map<*, *>) { "OpenAPI document is missing a 'paths' object" }

        return OpenApiRawDocument(root)
    }
}
