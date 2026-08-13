package borg.trikeshed.reactor.openapi

private fun Any?.resolverMap(): Map<String, Any?>? = this as? Map<String, Any?>
private fun Any?.resolverString(): String? = this as? String
private fun Any?.resolverBoolean(): Boolean? = this as? Boolean
private fun Any?.resolverList(): List<Any?>? = this as? List<Any?>

/**
 * Lowers a parsed contract into an executable reactor plan.  It deliberately
 * records operation identity and path templates instead of generating a
 * statically-bound client per operation.
 */
object OpenApiReactorResolver {
    fun resolve(document: OpenApiRawDocument): ResolvedOpenApiDocument {
        val root = document.root
        val info = root["info"].resolverMap()
        return ResolvedOpenApiDocument(
            rawRoot = root,
            title = info?.get("title").resolverString() ?: "Unknown",
            version = info?.get("version").resolverString() ?: "0.0.0",
            description = info?.get("description").resolverString(),
            servers = root["servers"].resolverList().orEmpty().mapNotNull { it.resolverMap()?.get("url").resolverString() },
            operations = document.operations().mapNotNull { document.resolveOperation(it) },
            trikeshedContext = null,
            trikeshedTitle = root["x-trikeshed-title"].resolverString(),
        )
    }
}

private fun OpenApiRawDocument.resolveOperation(raw: OpenApiRawOperation): ResolvedOperation? {
    val operation = raw.operation
    val operationId = operation["operationId"].resolverString() ?: return null
    return ResolvedOperation(
        path = raw.path,
        method = raw.method,
        operationId = operationId,
        summary = operation["summary"].resolverString(),
        description = operation["description"].resolverString(),
        tags = operation["tags"].resolverList().orEmpty().mapNotNull { it.resolverString() },
        parameters = operation["parameters"].resolverList().orEmpty().mapNotNull { parameter ->
            val node = parameter.resolverMap() ?: return@mapNotNull null
            val name = node["name"].resolverString() ?: return@mapNotNull null
            val location = node["in"].resolverString() ?: return@mapNotNull null
            ResolvedParameter(
                name = name,
                location = location,
                required = node["required"].resolverBoolean() == true,
                schema = resolveSchema(node["schema"]),
                description = node["description"].resolverString(),
                example = node["example"],
            )
        },
        requestBody = operation["requestBody"].resolverMap()?.let { body ->
            body["content"].resolverMap()?.let { content ->
                ResolvedRequestBody(
                    required = body["required"].resolverBoolean() == true,
                    contentTypes = content.map { (mediaType, node) ->
                        ContentType(mediaType, resolveSchema(node.resolverMap()?.get("schema")), node.resolverMap()?.get("example"))
                    },
                )
            }
        },
        responses = operation["responses"].resolverMap().orEmpty().mapNotNull { (status, response) ->
            val node = response.resolverMap() ?: return@mapNotNull null
            ResolvedResponse(
                statusCode = status.toIntOrNull() ?: 0,
                description = node["description"].resolverString(),
                contentTypes = node["content"].resolverMap().orEmpty().map { (mediaType, content) ->
                    ContentType(mediaType, resolveSchema(content.resolverMap()?.get("schema")), content.resolverMap()?.get("example"))
                },
                isDefault = status == "default",
            )
        },
        security = emptyList(),
        isSupervisor = operation["x-trikeshed-supervisor"].resolverBoolean() == true,
    )
}

private fun OpenApiRawDocument.resolveSchema(node: Any?): ResolvedSchema {
    val schema = node.resolverMap() ?: return ResolvedSchema.Generic()
    schema["\$ref"].resolverString()?.let { ref -> return ResolvedSchema.Ref(ref, resolveSchema(resolveRef(ref))) }
    return when (schema["type"].resolverString()) {
        "string" -> ResolvedSchema.Str(schema["format"].resolverString())
        "number" -> ResolvedSchema.Num(schema["format"].resolverString())
        "integer" -> ResolvedSchema.Int(schema["format"].resolverString())
        "boolean" -> ResolvedSchema.BoolSchema()
        "array" -> ResolvedSchema.Arr(resolveSchema(schema["items"]))
        "object" -> ResolvedSchema.Obj(
            properties = schema["properties"].resolverMap().orEmpty().map { (name, value) ->
                ResolvedSchema.Prop(name, resolveSchema(value))
            },
            required = schema["required"].resolverList().orEmpty().mapNotNull { it.resolverString() }.toSet(),
        )
        else -> ResolvedSchema.Generic()
    }
}