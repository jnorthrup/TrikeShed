package borg.trikeshed.openapi

// ── server generation config ──────────────────────────────────────────────────

data class ServerGenConfig(
    val specPath: CharSequence,
    val generatorTask: CharSequence,
    val packageRoot: CharSequence,
    val displayName: CharSequence,
    val moduleSuffix: CharSequence = "",
    val trikeshedContext: TrikeshedContext?,
    val messageTypeName: CharSequence = "ServerMessage",
) {
    val adapterClassName get() = "${displayName}ServerAdapter"
    val keysClassName get() = "Keys${moduleSuffix.toString().replaceFirstChar { it.uppercase() }}"
    val elementsClassName get() = "Elements${moduleSuffix.toString().replaceFirstChar { it.uppercase() }}"
    val adapterPackage get() = packageRoot
    val keysPackage get() = packageRoot
    val elementsPackage get() = packageRoot
}

// ── ServerMessage ─────────────────────────────────────────────────────────────

fun renderServerMessage(cfg: ServerGenConfig): CharSequence {
    val banner = generatedBanner(cfg.specPath, cfg.generatorTask)
    return buildString {
        appendLine("package ${cfg.adapterPackage}")
        appendLine()
        appendLine(banner)
        appendLine()
        appendLine("/** Minimal HTTP response model used by the generated server adapter. */")
        appendLine("data class ${cfg.messageTypeName}(")
        appendLine("    val status: Int,")
        appendLine("    val headers: Map<CharSequence, CharSequence> = emptyMap(),")
        appendLine("    val body: CharSequence?,")
        appendLine(") {")
        appendLine("    val isSuccess: Boolean get() = status in 200..299")
        appendLine("}")
    }
}

// ── ServerAdapter ────────────────────────────────────────────────────────────

fun renderServerAdapter(
    ops: List<ResolvedOperation>,
    cfg: ServerGenConfig,
): CharSequence {
    val banner = generatedBanner(cfg.specPath, cfg.generatorTask)
    val bindings = cfg.trikeshedContext?.serverBindings ?: emptyList()

    val branches = ops.joinToString("\n") { op ->
        val pathEsc = op.path.escapeKotlin()
        val queryParams = op.parameters.filter { it.location == "query" }
        val hasBody = op.hasRequestBody()
        val binding = bindings.firstOrNull()

        buildString {
            appendLine("            ${op.contractClassName()} -> {")
            if (queryParams.isNotEmpty()) {
                appendLine("                val qps = request.queryParams")
                queryParams.forEach { p ->
                    appendLine("                // query param: ${p.name}")
                }
            }
            if (binding != null) {
                appendLine("                val ctx = requireNotNull(context[${binding.keySimple}]) { \"Expected ${binding.name} context\" }")
                append("                val raw = ctx.request(")
                append("method = \"${op.method.toString().uppercase()}\", path = \"$pathEsc\"")
                if (queryParams.isNotEmpty()) append(", queryParams = qps")
                if (hasBody) append(", body = request.body")
                appendLine(")")
                appendLine("                ${cfg.messageTypeName}(status = raw.status, headers = raw.headers, body = raw.body)")
            } else {
                appendLine("                ${cfg.messageTypeName}(status = 501, body = \"${op.operationId.escapeKotlin()} not implemented\")")
            }
            append("            }")
        }
    }

    return buildString {
        appendLine("package ${cfg.adapterPackage}")
        appendLine()
        appendLine(banner)
        appendLine()
        appendLine("import kotlin.coroutines.CoroutineContext")
        appendLine()
        appendLine("/**")
        appendLine(" * Generated server adapter for ${cfg.displayName}.")
        appendLine(" * Routes incoming GeneratedRequests to the appropriate reactor context.")
        appendLine(" */")
        appendLine("class ${cfg.adapterClassName}(private val context: CoroutineContext) {")
        appendLine()
        appendLine("    fun execute(request: ${cfg.adapterPackage}.infrastructure.GeneratedRequest): ${cfg.messageTypeName} {")
        appendLine("        return when (request.operationId) {")
        append(branches)
        appendLine()
        appendLine("            else -> ${cfg.messageTypeName}(status = 404, body = \"Unknown operation: \${request.operationId}\")")
        appendLine("        }")
        appendLine("    }")
        appendLine()
        appendLine("    object Contract {")
        ops.forEach { op ->
            appendLine("        object ${op.contractClassName()} {")
            appendLine("            const val operationId: CharSequence = \"${op.operationId.escapeKotlin()}\"")
            appendLine("            const val path: CharSequence = \"${op.path.escapeKotlin()}\"")
            appendLine("            const val method: CharSequence = \"${op.method.toString().uppercase()}\"")
            appendLine("        }")
        }
        appendLine("    }")
        append("}")
    }
}

// ── ServerKeys ───────────────────────────────────────────────────────────────

fun renderServerKeys(cfg: ServerGenConfig): CharSequence {
    val banner = generatedBanner(cfg.specPath, cfg.generatorTask)
    val bindings = cfg.trikeshedContext?.serverBindings ?: emptyList()

    return buildString {
        appendLine("package ${cfg.keysPackage}")
        appendLine()
        appendLine(banner)
        appendLine()
        appendLine("import borg.trikeshed.context.AsyncContextKey")
        bindings.forEach { b -> appendLine("import ${b.elementImport}"); appendLine("import ${b.keyFqn}") }
        appendLine()
        appendLine("object ${cfg.keysClassName} {")
        bindings.forEachIndexed { i, b ->
            if (i > 0) appendLine()
            appendLine("    val ${b.name}: AsyncContextKey<${b.elementSimple}> = ${b.keySimple}")
        }
        if (bindings.isEmpty()) {
            appendLine("    // No x-trikeshed-context server bindings declared in this spec")
        }
        append("}")
    }
}

// ── ServerElements ────────────────────────────────────────────────────────────

fun renderServerElements(cfg: ServerGenConfig): CharSequence {
    val banner = generatedBanner(cfg.specPath, cfg.generatorTask)
    val bindings = cfg.trikeshedContext?.serverBindings ?: emptyList()

    return buildString {
        appendLine("package ${cfg.elementsPackage}")
        appendLine()
        appendLine(banner)
        appendLine()
        bindings.forEach { b ->
            appendLine("import ${b.elementImport}")
            appendLine("import ${b.openImport} as ${b.openAlias}")
        }
        appendLine()
        appendLine("object ${cfg.elementsClassName} {")
        bindings.forEachIndexed { i, b ->
            if (i > 0) appendLine()
            append("    suspend fun ${b.name}(): ${b.elementSimple} = ${b.openAlias}()")
        }
        if (bindings.isEmpty()) {
            appendLine("    // No x-trikeshed-context server bindings declared in this spec")
        }
        appendLine()
        append("}")
    }
}

// ── render all server sources ─────────────────────────────────────────────────

fun renderAllServerSources(
    doc: ResolvedOpenApiDocument,
    specPath: CharSequence,
    generatorTask: CharSequence,
    moduleSuffix: CharSequence = "",
    messageTypeName: CharSequence = "ServerMessage",
): Map<CharSequence, CharSequence> {
    val pkg = derivePackageRoot(doc.title)
    val display = deriveDisplayName(doc.title)

    val cfg = ServerGenConfig(
        specPath = specPath,
        generatorTask = generatorTask,
        packageRoot = pkg,
        displayName = display,
        moduleSuffix = moduleSuffix,
        trikeshedContext = doc.trikeshedContext,
        messageTypeName = messageTypeName,
    )

    val pkgPath = pkg.toString().replace('.', '/')

    return mapOf(
        "$pkgPath/${cfg.messageTypeName}.kt" to renderServerMessage(cfg),
        "$pkgPath/${cfg.adapterClassName}.kt" to renderServerAdapter(doc.operations, cfg),
        "$pkgPath/${cfg.keysClassName}.kt" to renderServerKeys(cfg),
        "$pkgPath/${cfg.elementsClassName}.kt" to renderServerElements(cfg),
    )
}
