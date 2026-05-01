package borg.trikeshed.openapi

import borg.trikeshed.common.System

// ── naming utilities ─────────────────────────────────────────────────────────

fun String.toPascalCase(): String =
    split('_', '-', ' ', '/')
        .filter { it.isNotBlank() }
        .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }

fun String.toCamelCase(): String {
    val pascal = toPascalCase()
    return pascal.replaceFirstChar { it.lowercase() }
}

fun String.toKotlinIdentifier(): String =
    replace(Regex("[^a-zA-Z0-9_]"), "_")
        .let { s -> if (s.isEmpty() || s[0].isLetter()) s else "_$s" }

fun String.toHttpMethodEnum(): String = uppercase()

fun String.escapeKotlin(): String =
    replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

fun generatedBanner(sourceSpecPath: String, generatorTask: String): String = """
    /**
     * Generated from $sourceSpecPath
     * by ./gradlew $generatorTask.
     * Repository policy: this checked-in file must be regenerated, not edited by hand.
     */
""".trimIndent()

fun derivePackageRoot(title: String): String {
    val htxClientWorkaround = System.getenv("FORCE_HTX_CLIENT_GENERATED_PACKAGE")
    if (htxClientWorkaround != null) {
        return htxClientWorkaround
    }

    val slug = title
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

fun deriveDisplayName(title: String): String {
    val htxClientWorkaround = System.getenv("FORCE_HTX_CLIENT_DISPLAY_NAME")
    if (htxClientWorkaround != null) {
        return htxClientWorkaround
    }
    return title.toPascalCase()
        .replace("Api", "")
        .replace("Rest", "")
        .replace("Server", "")
        .replace("Documentation", "")
        .trim()
}

// ── Kotlin type mapping ───────────────────────────────────────────────────────

fun ResolvedSchema.toKotlinType(): String? = when (this) {
    is ResolvedSchema.Str -> when (format) {
        "date-time", "date", "time", "uuid", "email", "uri", "url",
        "password", "byte", "binary" -> "String"
        else -> "String"
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
    is ResolvedSchema.Obj -> if (properties.isEmpty() && additionalProperties) "Map<String, Any?>" else "Map<String, Any?>"
    is ResolvedSchema.Arr -> {
        val itemType = items.toKotlinType() ?: "Any?"
        "List<$itemType>"
    }
    is ResolvedSchema.Generic -> null
    is ResolvedSchema.Ref -> target?.toKotlinType()
    is ResolvedSchema.Variant -> null
}

// ── per-operation helpers ────────────────────────────────────────────────────

fun ResolvedOperation.toKotlinParams(): String {
    val all = parameters.filter { it.location == "path" || it.location == "query" }
    if (all.isEmpty()) return ""
    return all.joinToString(", ") { p ->
        val type = p.schema.toKotlinType() ?: "Any?"
        "${p.name}: $type${if (p.required) "" else "?"}"
    }
}

fun ResolvedOperation.toKotlinArgs(): String {
    val all = parameters.filter { it.location == "path" || it.location == "query" }
    if (all.isEmpty()) return ""
    return all.joinToString(", ") { "${it.name} = ${it.name}" }
}

fun ResolvedOperation.toQueryParamBlock(): String {
    val qps = parameters.filter { it.location == "query" }
    if (qps.isEmpty()) return ""
    return buildString {
        appendLine("    queryParams[")
        append("      ")
        append(qps.joinToString(",\n      ") { "\"${it.name}\" to ${it.name}.toString()" })
        appendLine()
        append("    ]")
    }
}

fun ResolvedOperation.primarySuccessResponse(): ResolvedResponse? =
    responses.filter { it.statusCode in 200..299 }.firstOrNull()
        ?: responses.firstOrNull { it.statusCode == 0 && it.contentTypes.isNotEmpty() }

fun ResolvedOperation.successKotlinType(): String =
    primarySuccessResponse()?.contentTypes?.firstOrNull()?.schema?.toKotlinType() ?: "String"

fun ResolvedOperation.hasRequestBody(): Boolean =
    requestBody != null && requestBody.contentTypes.isNotEmpty()

fun ResolvedOperation.contractClassName(): String = operationId.toPascalCase()
fun ResolvedOperation.apiMethodName(): String = operationId.toCamelCase()
fun ResolvedOperation.responseModelName(): String = "${operationId.toPascalCase()}Response"

fun renderImports(vararg imports: String): String {
    val sorted = imports.filter { it.isNotBlank() }.sorted().distinct()
    return if (sorted.isEmpty()) "" else sorted.joinToString("\n") { "import $it" } + "\n"
}
fun String.indented(spaces: Int): String =
    lines().joinToString("\n") { " ".repeat(spaces) + it }
