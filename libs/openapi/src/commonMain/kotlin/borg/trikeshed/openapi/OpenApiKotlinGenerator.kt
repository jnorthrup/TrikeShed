package borg.trikeshed.openapi

import borg.trikeshed.lib.System

// ── naming utilities ─────────────────────────────────────────────────────────

fun CharSequence.toPascalCase(): CharSequence =
    split('_', '-', ' ', '/')
        .filter { it.isNotBlank() }
        .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }

fun CharSequence.toCamelCase(): CharSequence {
    val pascal = toPascalCase()
    return pascal.asString()!!.replaceFirstChar { it.lowercase() }
}

fun CharSequence.toKotlinIdentifier(): CharSequence =
    replace(Regex("[^a-zA-Z0-9_]"), "_")
        .let { s -> if (s.isEmpty() || s[0].isLetter()) s else "_$s" }

fun CharSequence.toHttpMethodEnum(): CharSequence = toString(). uppercase()

fun CharSequence.escapeKotlin(): CharSequence =this.asString()!!.
    replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

fun generatedBanner(sourceSpecPath: CharSequence, generatorTask: CharSequence): CharSequence = """
    /**
     * Generated from $sourceSpecPath
     * by ./gradlew $generatorTask.
     * Repository policy: this checked-in file must be regenerated, not edited by hand.
     */
""".trimIndent()

fun derivePackageRoot(title: CharSequence): CharSequence {
    val htxClientWorkaround = System.getenv("FORCE_HTX_CLIENT_GENERATED_PACKAGE")
    if (htxClientWorkaround != null) {
        return htxClientWorkaround
    }

    val slug = title.toString()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(" ", ".")
    return when {
        slug.contains("kraken") -> "borg.trikeshed.krak"
        slug.contains("coinmarketcap") || slug.contains("cmc") -> "borg.trikeshed.cmc"
        slug.contains("robinhood") || slug.contains("rhood") -> "borg.trikeshed.rhood"
        slug.contains("htx") -> "borg.trikeshed.htx"
        else -> "borg.trikeshed.openapi"
    }
}

fun deriveDisplayName(title: CharSequence): CharSequence {
    val htxClientWorkaround = System.getenv("FORCE_HTX_CLIENT_DISPLAY_NAME")
    if (htxClientWorkaround != null) {
        return htxClientWorkaround
    }
    return title.toPascalCase().toString().replace("Api", "")
        .replace("Rest", "")
        .replace("Server", "")
        .replace("Documentation", "")
        .trim()
}

// ── Kotlin type mapping ───────────────────────────────────────────────────────

fun ResolvedSchema.toKotlinType(): CharSequence? = when (this) {
    is ResolvedSchema.Str -> when (format) {
        "date-time", "date", "time", "uuid", "email", "uri", "url",
        "password", "byte", "binary" -> "CharSequence"

        else -> "CharSequence"
    }

    is ResolvedSchema.Num -> when (format) {
        "float" -> "Float"
        else -> "Double"
    }

    is ResolvedSchema.Int -> when (format) {
        "int32" -> "Int"
        "int64" -> "Long"
        else -> "Int"
    }

    is ResolvedSchema.BoolSchema -> "Boolean"
    is ResolvedSchema.Obj -> if (properties.isEmpty() && additionalProperties) "Map<CharSequence, Any?>" else "Map<CharSequence, Any?>"
    is ResolvedSchema.Arr -> {
        val itemType = items.toKotlinType() ?: "Any?"
        "List<$itemType>"
    }

    is ResolvedSchema.Generic -> null
    is ResolvedSchema.Ref -> target?.toKotlinType()
    is ResolvedSchema.Variant -> null
}

// ── per-operation helpers ────────────────────────────────────────────────────

fun ResolvedOperation.toKotlinParams(): CharSequence {
    val all = parameters.filter { it.location == "path" || it.location == "query" }
    if (all.isEmpty()) return ""
    return all.joinToString(", ") { p ->
        val type = p.schema.toKotlinType() ?: "Any?"
        "${p.name}: $type${if (p.required) "" else "?"}"
    }
}

fun ResolvedOperation.toKotlinArgs(): CharSequence {
    val pathParams = parameters.filter { it.location == "path" }
    if (pathParams.isEmpty()) return ""
    // Generate path substitution: .let { it.replace("{name}", name).replace("{session}", session) }
    return pathParams.joinToString(", ") { "${it.name} = ${it.name}" }
}

/**
 * Generate path-substitution block for DefaultImpl override methods.
 * Returns the call expression that substitutes path parameters into the request path.
 */
fun ResolvedOperation.toPathSubstitutionCall(cfg: ClientGenConfig): CharSequence {
    val pathParams = parameters.filter { it.location == "path" }
    val queryParams = parameters.filter { it.location == "query" }
    val contractRef = "${cfg.apiInterfaceName}Contract.${contractClassName()}.request"

    return buildString {
        if (pathParams.isNotEmpty()) {
            append("        val path = $contractRef.path")
            pathParams.forEach { p ->
                appendLine(".replace(\"{${p.name}}\", ${p.name})")
            }
            if (queryParams.isNotEmpty()) {
                appendLine("        val queryParams = mapOf(")
                queryParams.forEachIndexed { i, p ->
                    val comma = if (i < queryParams.size - 1) "," else ""
                    appendLine("            \"${p.name}\" to ${p.name}${comma}")
                }
                appendLine("        )")
                append("        call($contractRef.copy(path = path, queryParams = queryParams))")
            } else {
                append("        call($contractRef.copy(path = path))")
            }
        } else if (queryParams.isNotEmpty()) {
            appendLine("        val queryParams = mapOf(")
            queryParams.forEachIndexed { i, p ->
                val comma = if (i < queryParams.size - 1) "," else ""
                appendLine("            \"${p.name}\" to ${p.name}${comma}")
            }
            appendLine("        )")
            append("        call($contractRef.copy(queryParams = queryParams))")
        } else {
            append("        call($contractRef)")
        }
    }
}

fun ResolvedOperation.toQueryParamBlock(): CharSequence {
    val qps = parameters.filter { it.location == "query" }
    if (qps.isEmpty()) return ""
    return buildString {
        qps.forEach { p ->
            appendLine("        if (${p.name} != null) queryParams[\"${p.name}\"] = ${p.name}.toString()")
        }
    }
}

fun ResolvedOperation.primarySuccessResponse(): ResolvedResponse? =
    responses.filter { it.statusCode in 200..299 }.firstOrNull()
        ?: responses.firstOrNull { it.statusCode == 0 && it.contentTypes.isNotEmpty() }

fun ResolvedOperation.successKotlinType(): CharSequence =
    primarySuccessResponse()?.contentTypes?.firstOrNull()?.schema?.toKotlinType() ?: "CharSequence"

fun ResolvedOperation.hasRequestBody(): Boolean =
    requestBody != null && requestBody.contentTypes.isNotEmpty()

fun ResolvedOperation.contractClassName(): CharSequence = operationId.toPascalCase()
fun ResolvedOperation.apiMethodName(): CharSequence = operationId.toCamelCase()
fun ResolvedOperation.responseModelName(): CharSequence = "${operationId.toPascalCase()}Response"

fun renderImports(vararg imports: String): String {
    val sorted = imports.filter { it.isNotBlank() }.sorted().distinct()
    return if (sorted.isEmpty()) "" else sorted.joinToString("\n") { "import $it" } + "\n"
}

fun CharSequence.indented(spaces: Int): CharSequence =
    lines().joinToString("\n") { " ".repeat(spaces) + it }
