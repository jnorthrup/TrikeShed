package borg.trikeshed.openapi

// ── client generation config ──────────────────────────────────────────────────

data class ClientGenConfig(
    val specPath: String,
    val generatorTask: String,
    val packageRoot: String,
    val displayName: String,
    val moduleSuffix: String = "",
    val trikeshedContext: TrikeshedContext?,
) {
    val apiPackage get() = "$packageRoot.api"
    val infraPackage get() = "$packageRoot.infrastructure"
    val modelPackage get() = "$packageRoot.model"
    val rootPackage get() = packageRoot

    val keysClassName get() = "Keys${apiSuffix}"
    val elementsClassName get() = "Elements${apiSuffix}"
    val supervisorClassName get() = "SupervisorJobs${apiSuffix}"
    val apiInterfaceName get() = "${displayName}Api"
    val defaultImplName get() = "Default${displayName}Api"
    val requestClassName get() = "GeneratedRequest"
    val httpMethodName get() = "HttpMethod"

   val apiSuffix get() = moduleSuffix.replaceFirstChar { it.uppercase() }
}

// ── operation contract rendering ─────────────────────────────────────────────

/**
 * Renders a contract object for a single operation inside the ApiContract object.
 */
fun renderOperationContract(op: ResolvedOperation, cfg: ClientGenConfig): String {
    val path = op.path.escapeKotlin()
    val method = op.method.toHttpMethodEnum()
    val hasBody = op.requestBody != null

    return buildString {
        appendLine("        object ${op.contractClassName()} {")
        appendLine("            const val operationId: String = \"${op.operationId.escapeKotlin()}\"")
        append("            val request: ${cfg.requestClassName} = ${cfg.requestClassName}(")
        append("method = ${cfg.httpMethodName}.$method")
        append(", path = \"$path\"")
        if (hasBody) append(", body = body")
        appendLine(")")
        appendLine("        }")
    }
}

// ── Keys.kt ─────────────────────────────────────────────────────────────────

fun renderClientKeys(cfg: ClientGenConfig): String {
    val banner = generatedBanner(cfg.specPath, cfg.generatorTask)
    val ctx = cfg.trikeshedContext
    val bindings = ctx?.clientBindings ?: emptyList()

    val keyImports = buildString {
        appendLine("import borg.trikeshed.context.AsyncContextKey")
        bindings.forEach { b ->
            appendLine("import ${b.elementImport}")
            appendLine("import ${b.keyFqn}")
        }
    }.trimEnd()

    return buildString {
        appendLine("package ${cfg.rootPackage}")
        appendLine()
        appendLine(banner)
        appendLine()
        appendLine(keyImports)
        appendLine()
        appendLine("object ${cfg.keysClassName} {")
        bindings.forEachIndexed { i, b ->
            if (i > 0) appendLine()
            appendLine("    val ${b.name}: AsyncContextKey<${b.elementSimple}> = ${b.keySimple}")
        }
        if (bindings.isEmpty()) {
            appendLine("    // No x-trikeshed-context client bindings declared in this spec")
        }
        append("}")
    }
}

// ── Elements.kt ─────────────────────────────────────────────────────────────

fun renderClientElements(cfg: ClientGenConfig): String {
    val banner = generatedBanner(cfg.specPath, cfg.generatorTask)
    val ctx = cfg.trikeshedContext
    val bindings = ctx?.clientBindings ?: emptyList()

    val imports = buildString {
        bindings.forEach { b ->
            appendLine("import ${b.elementImport}")
            appendLine("import ${b.openImport} as ${b.openAlias}")
        }
    }.trimEnd()

    return buildString {
        appendLine("package ${cfg.rootPackage}")
        appendLine()
        appendLine(banner)
        appendLine()
        if (imports.isNotEmpty()) {
            appendLine(imports)
            appendLine()
        }
        appendLine("object ${cfg.elementsClassName} {")
        bindings.forEachIndexed { i, b ->
            if (i > 0) appendLine()
            append("    suspend fun ${b.name}(): ${b.elementSimple} = ${b.openAlias}()")
        }
        if (bindings.isEmpty()) {
            appendLine("    // No x-trikeshed-context client bindings declared in this spec")
        }
        appendLine()
        append("}")
    }
}

// ── SupervisorJobs.kt ───────────────────────────────────────────────────────

fun renderClientSupervisorJobs(
    ops: List<ResolvedOperation>,
    cfg: ClientGenConfig,
): String {
    val banner = generatedBanner(cfg.specPath, cfg.generatorTask)
    val superOps = ops.filter { it.isSupervisor }

    return buildString {
        appendLine("package ${cfg.rootPackage}")
        appendLine()
        appendLine(banner)
        appendLine()
        appendLine("import kotlinx.coroutines.Job")
        appendLine("import kotlinx.coroutines.SupervisorJob")
        appendLine()
        appendLine("object ${cfg.supervisorClassName} {")
        superOps.forEachIndexed { i, op ->
            if (i > 0) appendLine()
            append("    fun ${op.operationId}(parent: Job? = null): Job = SupervisorJob(parent)")
        }
        if (superOps.isEmpty()) {
            appendLine("    // No x-trikeshed-supervisor operations declared in this spec")
        }
        appendLine()
        append("}")
    }
}

// ── HttpMethod + GeneratedRequest infrastructure ─────────────────────────────

fun renderClientRequest(cfg: ClientGenConfig): String {
    val banner = generatedBanner(cfg.specPath, cfg.generatorTask)

    return buildString {
        appendLine("package ${cfg.infraPackage}")
        appendLine()
        appendLine(banner)
        appendLine()
        appendLine("/** HTTP method enum. */")
        appendLine("enum class ${cfg.httpMethodName} {")
        listOf("GET", "POST", "PUT", "DELETE", "PATCH").forEach { appendLine("    $it,") }
        appendLine("}")
        appendLine()
        appendLine("/**")
        appendLine(" * A fully-bound HTTP request — method, path, query params, and optional body.")
        appendLine(" * Consumed by the server adapter which routes it through the reactor context.")
        appendLine(" */")
        appendLine("data class ${cfg.requestClassName}(")
        appendLine("    val method: ${cfg.httpMethodName},")
        appendLine("    val path: String,")
        appendLine("    val queryParams: Map<String, String> = emptyMap(),")
        appendLine("    val body: String? = null,")
        appendLine("    val operationId: String? = null,")
        appendLine(")")
    }
}

// ── Api.kt ─────────────────────────────────────────────────────────────────

fun renderClientApi(
    ops: List<ResolvedOperation>,
    cfg: ClientGenConfig,
): String {
    val banner = generatedBanner(cfg.specPath, cfg.generatorTask)
    val contracts = ops.joinToString("\n\n") { renderOperationContract(it, cfg) }

    return buildString {
        appendLine("package ${cfg.apiPackage}")
        appendLine()
        appendLine(banner)
        appendLine()
        appendLine("import ${cfg.rootPackage}.infrastructure.${cfg.requestClassName}")
        appendLine("import ${cfg.rootPackage}.infrastructure.${cfg.httpMethodName}")
        appendLine()
        appendLine("/** Generated API interface for ${cfg.displayName}. */")
        appendLine("interface ${cfg.apiInterfaceName} {")
        ops.forEach { op ->
            val params = op.toKotlinParams()
            val retType = op.successKotlinType()
            appendLine("    suspend fun ${op.apiMethodName()}($params): $retType")
        }
        appendLine("}")
        appendLine()
        appendLine("/** Default implementation — caller provides the low-level call. */")
        appendLine("class ${cfg.defaultImplName}(")
        appendLine("   val call: suspend (${cfg.requestClassName}) -> String,")
        appendLine(") : ${cfg.apiInterfaceName} {")
        ops.forEach { op ->
            val params = op.toKotlinParams()
            val retType = op.successKotlinType()
            val args = op.toKotlinArgs()
            val queryBlock = op.toQueryParamBlock()
            if (queryBlock.isNotEmpty()) {
                appendLine("    override suspend fun ${op.apiMethodName()}($params): $retType = run {")
                appendLine("        val queryParams = mutableMapOf<String, String>()")
                appendLine(queryBlock)
                appendLine("        call(${cfg.apiInterfaceName}Contract.${op.contractClassName()}.request.copy(queryParams = queryParams)) }")
            } else {
                appendLine("    override suspend fun ${op.apiMethodName()}($params): $retType =")
                appendLine("        call(${cfg.apiInterfaceName}Contract.${op.contractClassName()}.request${if (args.isNotEmpty()) ".copy($args)" else ""})")
            }
            appendLine()
        }
        appendLine()
        appendLine("}")
        appendLine()
        appendLine("/** Contract constants for each ${cfg.displayName} operation. */")
        appendLine("object ${cfg.apiInterfaceName}Contract {")
        append(contracts.indented(2))
        appendLine()
        append("}")
    }
}

// ── Models.kt ───────────────────────────────────────────────────────────────
fun successSchema(op: ResolvedOperation): ResolvedSchema? =
    op.primarySuccessResponse()?.contentTypes?.firstOrNull()?.schema

fun renderClientModels(
    ops: List<ResolvedOperation>,
    cfg: ClientGenConfig,
): String {
    val banner = generatedBanner(cfg.specPath, cfg.generatorTask)
    val schemas = ops.mapNotNull { op ->
        successSchema(op)?.let { op.responseModelName() to it }
    }.distinctBy { it.first }

    return buildString {
        appendLine("package ${cfg.modelPackage}")
        appendLine()
        appendLine(banner)
        appendLine()
        appendLine("import kotlin.Any")
        appendLine("import kotlin.Boolean")
        appendLine("import kotlin.Double")
        appendLine("import kotlin.Int")
        appendLine("import kotlin.Long")
        appendLine("import kotlin.String")
        appendLine("import kotlin.collections.List")
        appendLine("import kotlin.collections.Map")
        appendLine()
        if (schemas.isEmpty()) {
            appendLine("/** Placeholder — no response schemas resolved from this API surface. */")
        } else {
            schemas.forEach { (name, schema) ->
                appendLine("/** Generated from ${cfg.displayName} response schema. */")
                appendLine(renderSchemaAsDataClass(name, schema))
                appendLine()
            }
        }
    }
}
fun renderSchemaAsDataClass(name: String, schema: ResolvedSchema): String {
    val props: List<ResolvedSchema.Prop> = when (schema) {
        is ResolvedSchema.Obj -> schema.properties
        is ResolvedSchema.Ref -> emptyList()
        else -> emptyList()
    }

    if (props.isEmpty()) {
        return "data class $name(val raw: String)"
    }

    val fields = props.joinToString(",\n") { prop ->
        val type = prop.schema.toKotlinType() ?: "Any?"
        val reqMark = if (prop.required) "" else "?"
        "    val ${prop.name}: $type$reqMark"
    }
    return "data class $name(\n$fields\n)"
}

// ── all client sources ───────────────────────────────────────────────────────

/**
 * Returns a map of relativePath → fileContent for all client sources.
 */
fun renderAllClientSources(
    doc: ResolvedOpenApiDocument,
    specPath: String,
    generatorTask: String,
    moduleSuffix: String = "",
): Map<String, String> {
    val pkg = derivePackageRoot(doc.title)
    val display = deriveDisplayName(doc.title)

    val config = ClientGenConfig(
        specPath = specPath,
        generatorTask = generatorTask,
        packageRoot = pkg,
        displayName = display,
        moduleSuffix = moduleSuffix,
        trikeshedContext = doc.trikeshedContext,
    )

    val rootPath = pkg.replace('.', '/')
    val infraPath = "$pkg/infrastructure".replace('.', '/')
    val modelPath = "$pkg/model".replace('.', '/')
    val apiPath = "$pkg/api".replace('.', '/')

    val base = mapOf(
        "$rootPath/${config.keysClassName}.kt" to renderClientKeys(config),
        "$rootPath/${config.elementsClassName}.kt" to renderClientElements(config),
        "$infraPath/${config.requestClassName}.kt" to renderClientRequest(config),
        "$apiPath/${config.apiInterfaceName}.kt" to renderClientApi(doc.operations, config),
        "$modelPath/${display}Models.kt" to renderClientModels(doc.operations, config),
    )

    val superOps = doc.trikeshedContext?.supervisorOperationIds
    return if (!superOps.isNullOrEmpty()) {
        base + mapOf("$rootPath/${config.supervisorClassName}.kt" to renderClientSupervisorJobs(doc.operations, config))
    } else base
}
