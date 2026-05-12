package borg.trikeshed.openapi

// ── helpers ──────────────────────────────────────────────────────────────────

// ── reference resolution ──────────────────────────────────────────────────────

/**
 * Walks the full document tree and resolves every $ref in place,
 * producing a new map with all references materialised.
 */
fun OpenApiRawDocument.resolveAllRefs(): Map<CharSequence, Any?> {
    val cache = mutableMapOf<CharSequence, Any?>()
    fun doResolve(ref: CharSequence): Any? {
        cache[ref]?.let { return it }
        val result = this@resolveAllRefs.resolveRef(ref)
        cache[ref] = result
        return result
    }
    @Suppress("UNCHECKED_CAST")
    return walkAndResolve(root, ::doResolve).let { it as? Map<CharSequence, Any?> }!!
}
fun walkAndResolve(node: Any?, resolveRef: (CharSequence) -> Any?): Any? {
    return when (node) {
        null -> null
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val map = node as Map<CharSequence, Any?>
            val ref: CharSequence? = map["\$ref"] as? CharSequence
            if (ref != null) {
                val resolved: Any? = resolveRef(ref)
                resolved ?: map
            } else {
                map.mapValues { (_, v): Map.Entry<CharSequence, Any?> -> walkAndResolve(v, resolveRef) }
            }
        }
        is List<*> -> {
            node.map { walkAndResolve(it, resolveRef) }
        }
        else -> node
    }
}

// ── schema resolver ────────────────────────────────────────────────────────────
fun resolveSchemaImpl(node: Any?, description: CharSequence?, resolveRef: (CharSequence) -> Any?): ResolvedSchema {
    if (node == null) return ResolvedSchema.Generic(description)

    val map = node.asMap() ?: return ResolvedSchema.Generic(description)

    // Handle references
    map["\$ref"]?.asStr()?.let { ref ->
        val target = resolveRef(ref)
        return resolveSchemaImpl(target, description, resolveRef)
    }

    // allOf / anyOf / oneOf
    map["allOf"]?.asList()?.let { items ->
        val props = mutableListOf<ResolvedSchema.Prop>()
        for (item in items) {
            val resolved = resolveSchemaImpl(item, null, resolveRef)
            if (resolved is ResolvedSchema.Obj) {
                props.addAll(resolved.properties)
            }
        }
        return ResolvedSchema.Obj(properties = props, description = description)
    }
    if (map.containsKey("anyOf") || map.containsKey("oneOf")) {
        return ResolvedSchema.Variant(
            schema = ResolvedSchema.Generic(description),
            description = description,
        )
    }

    return when (map["type"]?.asStr()) {
        "string" -> ResolvedSchema.Str(
            format = map["format"].asStr(),
            enum = map["enum"]?.asList()?.mapNotNull { it.asStr() },
            default = map["default"].asStr(),
            description = description,
        )
        "number" -> ResolvedSchema.Num(
            format = map["format"].asStr(),
            enum = map["enum"]?.asList()?.mapNotNull { it.asNum() },
            default = map["default"].asNum(),
            description = description,
        )
        "integer" -> ResolvedSchema.Int(
            format = map["format"].asStr(),
            enum = map["enum"]?.asList()?.mapNotNull { it.asNum()?.toLong() },
            default = map["default"].asNum()?.toLong(),
            description = description,
        )
        "boolean" -> ResolvedSchema.BoolSchema(
            default = map["default"].asBool(),
            description = description,
        )
        "array" -> ResolvedSchema.Arr(
            items = resolveSchemaImpl(map["items"], description, resolveRef),
            description = description,
        )
        "object" -> ResolvedSchema.Obj(
            properties = resolveProperties(map["properties"].asMap(), description, resolveRef),
            required = (map["required"]?.asList()?.mapNotNull { it.asStr() } ?: emptyList()).toSet(),
            additionalProperties = map["additionalProperties"]?.asBool() ?: true,
            description = description,
        )
        null -> {
            if (map.isEmpty()) ResolvedSchema.Generic(description)
            else ResolvedSchema.Generic(description)
        }
        else -> ResolvedSchema.Generic(description)
    }
}
fun resolveProperties(
    props: JsonMap?,
    description: CharSequence?,
    resolveRef: (CharSequence) -> Any?,
): List<ResolvedSchema.Prop> {
    if (props == null) return emptyList()
    return props.mapNotNull { (name, node) ->
        val propNode = node.asMap() ?: return@mapNotNull null
        ResolvedSchema.Prop(
            name = name,
            schema = resolveSchemaImpl(propNode, propNode["description"].asStr(), resolveRef),
            required = propNode["required"].asBool() == true,
            description = propNode["description"].asStr(),
        )
    }
}

fun OpenApiRawDocument.resolveSchema(node: Any?, description: CharSequence? = null): ResolvedSchema =
    resolveSchemaImpl(node, description) { resolveRef(it) }

// ── parameter resolver ────────────────────────────────────────────────────────

fun OpenApiRawDocument.resolveParameter(paramNode: Any?): ResolvedParameter? {
    val map = paramNode.asMap() ?: return null
    return ResolvedParameter(
        name = map["name"].asStr() ?: return null,
        location = map["in"].asStr() ?: return null,
        required = map["required"].asBool() == true,
        schema = resolveSchema(map["schema"]),
        description = map["description"].asStr(),
        example = map["example"],
    )
}

// ── content resolver ─────────────────────────────────────────────────────────

fun OpenApiRawDocument.resolveContent(content: Any?): List<ContentType> {
    val map = content.asMap() ?: return emptyList()
    return map.mapNotNull { (mediaType, node) ->
        val nodeMap = node.asMap() ?: return@mapNotNull null
        val schemaNode = nodeMap["schema"]
        ContentType(
            mediaType = mediaType,
            schema = resolveSchema(schemaNode),
            example = nodeMap["example"],
        )
    }
}

// ── response resolver ────────────────────────────────────────────────────────

fun OpenApiRawDocument.resolveResponse(
    responseNode: Any?,
    description: CharSequence?,
    isDefault: Boolean = false,
): List<ResolvedResponse> {
    if (responseNode == null) return emptyList()
    val map = responseNode.asMap() ?: return emptyList()

    if (map.containsKey("content")) {
        return listOf(
            ResolvedResponse(
                statusCode = 0,
                description = description,
                contentTypes = resolveContent(map["content"]),
                isDefault = isDefault,
            )
        )
    }

    val results = mutableListOf<ResolvedResponse>()
    for ((statusKey, value) in map) {
        val statusCode = statusKey.toString().toIntOrNull() ?: continue
        val respMap = value.asMap() ?: continue
        results.add(
            ResolvedResponse(
                statusCode = statusCode,
                description = respMap["description"].asStr(),
                contentTypes = resolveContent(respMap["content"]),
                isDefault = isDefault,
            )
        )
    }
    return results
}

// ── security resolver ────────────────────────────────────────────────────────

fun resolveSecurity(security: Any?): List<SecurityRequirement> {
    val list = security.asList() ?: return emptyList()
    return list.mapNotNull { item ->
        val map = item.asMap() ?: return@mapNotNull null
        val name = map.keys.firstOrNull() ?: return@mapNotNull null
        val scopes = map[name].asList()?.mapNotNull { it.asStr() } ?: emptyList()
        SecurityRequirement(schemeName = name, scopes = scopes)
    }
}

// ── trikeshed context parser ─────────────────────────────────────────────────

fun parseTrikeshedContext(root: JsonMap): TrikeshedContext? {
    val ctx = root["x-trikeshed-context"]?.asMap()
    val supervisorIds = mutableListOf<CharSequence>()

    val paths = root["paths"]?.asMap()
    if (paths != null) {
        for ((_, methodsNode) in paths) {
            val methods = methodsNode.asMap() ?: continue
            for ((methodName, opNode) in methods) {
                if (methodName.startsWith("x-")) continue
                val operation = opNode.asMap() ?: continue
                if (operation["x-trikeshed-supervisor"]?.asBool() == true) {
                    val opId = operation["operationId"]?.asStr()
                    if (opId != null) {
                        supervisorIds.add(opId)
                    }
                }
            }
        }
    }

    if (ctx == null && supervisorIds.isEmpty()) return null

    val clientBindings = parseBindingsFromSectionMap(ctx?.get("client")?.asList())
    val serverBindings = parseBindingsFromSectionMap(ctx?.get("server")?.asList())

    return TrikeshedContext(
        clientBindings = clientBindings,
        serverBindings = serverBindings,
        supervisorOperationIds = supervisorIds,
    )
}

fun parseBindingsFromSectionMap(list: List<Any?>?): List<ContextBinding> {
    if (list == null) return emptyList()
    return list.mapNotNull { item ->
        val map = item.asMap() ?: return@mapNotNull null
        val name = map["name"]?.asStr() ?: return@mapNotNull null
        val key = map["key"]?.asStr() ?: return@mapNotNull null
        val element = map["element"]?.asStr() ?: return@mapNotNull null
        val open = map["open"]?.asStr() ?: return@mapNotNull null
        ContextBinding(
            name = name,
            keyFqn = key,
            elementFqn = element,
            openFqn = open,
        )
    }
}

// ── request body resolver ───────────────────────────────────────────────────
fun OpenApiRawDocument.resolveRequestBody(node: Any?): ResolvedRequestBody? {
    val map = node.asMap() ?: return null
    val content = map["content"].asMap() ?: return null
    val contentTypes = resolveContent(content)
    if (contentTypes.isEmpty()) return null
    return ResolvedRequestBody(
        required = map["required"].asBool() == true,
        contentTypes = contentTypes,
    )
}

// ── operation resolver ────────────────────────────────────────────────────────
fun OpenApiRawDocument.resolveOperation(rawOp: OpenApiRawOperation): ResolvedOperation? {
    val opMap = rawOp.operation
    if (opMap.isEmpty()) return null

    val operationId = opMap["operationId"].asStr() ?: return null

    val parameters = opMap["parameters"].asList()?.mapNotNull { resolveParameter(it) } ?: emptyList()
    val requestBody = resolveRequestBody(opMap["requestBody"])

    val responsesMap = opMap["responses"].asMap()
    val resolvedResponses = responsesMap?.flatMap { (statusKey, responseNode) ->
        val description = responseNode.asMap()?.get("description").asStr()
        resolveResponse(responseNode, description, statusKey == "default")
    } ?: emptyList()

    val security = resolveSecurity(opMap["security"])
    val isSupervisor = opMap["x-trikeshed-supervisor"].asBool() == true

    return ResolvedOperation(
        path = rawOp.path,
        method = rawOp.method,
        operationId = operationId,
        summary = opMap["summary"].asStr(),
        description = opMap["description"].asStr(),
        tags = opMap["tags"].asList()?.mapNotNull { it.asStr() } ?: emptyList(),
        parameters = parameters,
        requestBody = requestBody,
        responses = resolvedResponses,
        security = security,
        isSupervisor = isSupervisor,
    )
}

// ── full document resolver ───────────────────────────────────────────────────

/**
 * Resolves every operation in the raw document into a fully materialised
 * ResolvedOpenApiDocument — all refs inlined, schemas concrete, security flattened.
 */
fun OpenApiRawDocument.resolve(): ResolvedOpenApiDocument {
    // Resolve all $ref references upfront so parameters, schemas, etc.
    // are already materialised before we walk the tree.
    val derefDoc = OpenApiRawDocument(resolveAllRefs())
    val infoMap = derefDoc.info

    val title = infoMap?.get("title").asStr() ?: "Unknown"
    val version = infoMap?.get("version").asStr() ?: "0.0.0"
    val description = infoMap?.get("description").asStr()

    val servers = derefDoc.root["servers"].asList()?.mapNotNull {
        it.asMap()?.get("url").asStr()
    } ?: emptyList()

    val trikeshedTitle = derefDoc.root["x-trikeshed-title"].asStr()
    val trikeshedContext = parseTrikeshedContext(derefDoc.root)

    val resolvedOps = derefDoc.operations().mapNotNull { rawOp ->
        derefDoc.resolveOperation(rawOp)
    }

    return ResolvedOpenApiDocument(
        rawRoot = derefDoc.root,
        title = title,
        version = version,
        description = description,
        servers = servers,
        operations = resolvedOps,
        trikeshedContext = trikeshedContext,
        trikeshedTitle = trikeshedTitle,
    )
}
