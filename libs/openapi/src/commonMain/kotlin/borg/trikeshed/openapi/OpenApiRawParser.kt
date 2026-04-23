package borg.trikeshed.openapi

 import borg.trikeshed.parse.json


data class OpenApiToken(
    val kind: String,
    val value: String,
    val location: String,
)

data class OpenApiGap(
    val code: String,
    val location: String,
    val detail: String,
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

data class OpenApiRawDocument(
    val root: JsonObject,
) {
    val version: String? get() = root["openapi"]?.jsonPrimitiveOrNull?.contentOrNull

    val info: JsonObject? get() = root["info"] as? JsonObject

    val paths: JsonObject get() = root["paths"] as? JsonObject ?: JsonObject(emptyMap())

    val components: JsonObject? get() = root["components"] as? JsonObject

    fun operations(): List<OpenApiRawOperation> =
        paths.entries.flatMap { (path, pathNode) ->
            val pathObject = pathNode as? JsonObject ?: return@flatMap emptyList()
            pathObject.entries.mapNotNull { (method, operationNode) ->
                val operation = operationNode as? JsonObject ?: return@mapNotNull null
                OpenApiRawOperation(path = path, method = method.lowercase(), operation = operation)
            }
        }

    fun refs(): List<String> {
        val refs = mutableListOf<String>()

        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    node["\$ref"]?.jsonPrimitiveOrNull?.contentOrNull?.let(refs::add)
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }

        walk(root)
        return refs
    }

    fun tokens(): List<OpenApiToken> = buildList {
        version?.let { add(OpenApiToken(kind = "version", value = it, location = "openapi")) }
        info?.get("title")?.jsonPrimitiveOrNull?.contentOrNull?.let {
            add(OpenApiToken(kind = "info-title", value = it, location = "info.title"))
        }
        info?.get("version")?.jsonPrimitiveOrNull?.contentOrNull?.let {
            add(OpenApiToken(kind = "info-version", value = it, location = "info.version"))
        }
        paths.keys.forEach { path ->
            add(OpenApiToken(kind = "path", value = path, location = "paths.$path"))
        }
        operations().forEach { operation ->
            add(
                OpenApiToken(
                    kind = "operation",
                    value = "${operation.method.uppercase()} ${operation.path}",
                    location = "paths.${operation.path}.${operation.method}",
                ),
            )
            operation.operationId?.let {
                add(
                    OpenApiToken(
                        kind = "operation-id",
                        value = it,
                        location = "paths.${operation.path}.${operation.method}.operationId",
                    ),
                )
            }
        }
        refs().forEach { ref ->
            add(OpenApiToken(kind = "ref", value = ref, location = ref))
        }
    }

    fun gapAnalysis(): OpenApiGapAnalysis {
        val tokens = tokens()
        val gaps = buildList {
            if (info?.get("title")?.jsonPrimitiveOrNull?.contentOrNull.isNullOrBlank()) {
                add(
                    OpenApiGap(
                        code = "missing-info-title",
                        location = "info.title",
                        detail = "OpenAPI info.title should be set while the API surface is still evolving",
                    ),
                )
            }
            if (info?.get("version")?.jsonPrimitiveOrNull?.contentOrNull.isNullOrBlank()) {
                add(
                    OpenApiGap(
                        code = "missing-info-version",
                        location = "info.version",
                        detail = "OpenAPI info.version should track the current development revision",
                    ),
                )
            }
            val operations = operations()
            if (operations.isEmpty()) {
                add(
                    OpenApiGap(
                        code = "missing-operations",
                        location = "paths",
                        detail = "At least one path operation is required for generation",
                    ),
                )
            }
            operations.forEach { operation ->
                val location = "paths.${operation.path}.${operation.method}"
                if (operation.operationId.isNullOrBlank()) {
                    add(
                        OpenApiGap(
                            code = "missing-operation-id",
                            location = "$location.operationId",
                            detail = "Operation '$location' is missing an operationId",
                        ),
                    )
                }
                val responses = operation.operation["responses"] as? JsonObject
                if (responses.isNullOrEmpty()) {
                    add(
                        OpenApiGap(
                            code = "missing-responses",
                            location = "$location.responses",
                            detail = "Operation '$location' should declare at least one response",
                        ),
                    )
                }
            }
            refs().forEach { ref ->
                if (resolveRef(ref) == null) {
                    add(
                        OpenApiGap(
                            code = "unresolved-ref",
                            location = ref,
                            detail = "Reference '$ref' could not be resolved from the current document",
                        ),
                    )
                }
            }
        }
        return OpenApiGapAnalysis(tokens = tokens, gaps = gaps)
    }

    private fun resolveRef(ref: String): JsonElement? {
        if (!ref.startsWith("#/")) return null
        var current: JsonElement = root
        for (segment in ref.removePrefix("#/").split('/')) {
            val key = segment.replace("~1", "/").replace("~0", "~")
            current = (current as? JsonObject)?.get(key) ?: return null
        }
        return current
    }
}

data class OpenApiRawOperation(
    val path: String,
    val method: String,
    val operation: JsonObject,
) {
    val operationId: String? get() = operation["operationId"]?.jsonPrimitiveOrNull?.contentOrNull
}

class OpenApiParseException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object OpenApiRawParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun parse(text: String): OpenApiRawDocument {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (cause: Throwable) {
            throw OpenApiParseException("Failed to parse OpenAPI JSON", cause)
        }

        val version = root["openapi"]?.jsonPrimitiveOrNull?.contentOrNull
        require(!version.isNullOrBlank()) { "OpenAPI document is missing a non-empty 'openapi' version field" }
        require(root["paths"] is JsonObject) { "OpenAPI document is missing a 'paths' object" }

        return OpenApiRawDocument(root)
    }
}

private val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive
