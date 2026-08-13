package borg.trikeshed.reactor.openapi

import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.parse.yaml.parse as parseYaml

private typealias OpenApiMap = Map<String, Any?>

private fun Any?.asMap(): OpenApiMap? = this as? OpenApiMap
private fun Any?.asString(): String? = this as? String

data class OpenApiToken(val kind: String, val value: String, val location: String)
data class OpenApiGap(val code: String, val location: String, val detail: String)
data class OpenApiGapAnalysis(
    val tokens: List<OpenApiToken> = emptyList(),
    val gaps: List<OpenApiGap> = emptyList(),
) {
    val isComplete: Boolean get() = gaps.isEmpty()
    companion object { val EMPTY = OpenApiGapAnalysis() }
}

data class OpenApiRawOperation(val path: String, val method: String, val operation: OpenApiMap) {
    val operationId: String? get() = operation["operationId"].asString()
}

data class OpenApiRawDocument(val root: OpenApiMap) {
    fun operations(): List<OpenApiRawOperation> =
        (root["paths"].asMap() ?: emptyMap()).flatMap { (path, pathNode) ->
            (pathNode.asMap() ?: emptyMap()).mapNotNull { (method, operationNode) ->
                operationNode.asMap()?.takeIf { method.lowercase() in HTTP_METHODS }
                    ?.let { OpenApiRawOperation(path, method.lowercase(), it) }
            }
        }

    fun refs(): List<String> = buildList {
        fun collect(node: Any?) {
            when (node) {
                is Map<*, *> -> {
                    node["\$ref"].asString()?.let(::add)
                    node.values.forEach(::collect)
                }
                is List<*> -> node.forEach(::collect)
            }
        }
        collect(root)
    }

    fun resolveRef(ref: String): Any? {
        if (!ref.startsWith("#/")) return null
        return ref.removePrefix("#/").split('/').fold(root as Any?) { node, segment ->
            node.asMap()?.get(segment.replace("~1", "/").replace("~0", "~"))
        }
    }

    fun gapAnalysis(): OpenApiGapAnalysis {
        val operations = operations()
        val gaps = buildList {
            if (root["info"].asMap()?.get("title").asString().isNullOrBlank()) {
                add(OpenApiGap("missing-info-title", "info.title", "OpenAPI info.title is required"))
            }
            if (root["info"].asMap()?.get("version").asString().isNullOrBlank()) {
                add(OpenApiGap("missing-info-version", "info.version", "OpenAPI info.version is required"))
            }
            operations.forEach { operation ->
                if (operation.operationId.isNullOrBlank()) {
                    add(OpenApiGap("missing-operation-id", "paths.${operation.path}.${operation.method}.operationId", "Operation id is required"))
                }
                if (operation.operation["responses"].asMap().isNullOrEmpty()) {
                    add(OpenApiGap("missing-responses", "paths.${operation.path}.${operation.method}.responses", "Operation responses are required"))
                }
            }
            refs().filter { resolveRef(it) == null }.forEach { ref ->
                add(OpenApiGap("unresolved-ref", ref, "Reference cannot be resolved"))
            }
        }
        val tokens = operations.flatMap { operation ->
            listOfNotNull(
                OpenApiToken("operation", "${operation.method.uppercase()} ${operation.path}", "paths.${operation.path}.${operation.method}"),
                operation.operationId?.let { OpenApiToken("operation-id", it, "paths.${operation.path}.${operation.method}.operationId") },
            )
        }
        return OpenApiGapAnalysis(tokens, gaps)
    }
}

object OpenApiRawParser {
    fun parse(text: String): OpenApiRawDocument {
        val root = when {
            text.isBlank() -> error("OpenAPI spec text is blank")
            text.trimStart().startsWith("{") -> JsonSupport.parse(text).asMap()
            else -> parseYaml(text)
        } ?: error("OpenAPI root is not an object")
        require(!root["openapi"].asString().isNullOrBlank()) { "OpenAPI document is missing openapi version" }
        require(root["paths"] is Map<*, *>) { "OpenAPI document is missing paths object" }
        return OpenApiRawDocument(root)
    }
}

private val HTTP_METHODS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")
