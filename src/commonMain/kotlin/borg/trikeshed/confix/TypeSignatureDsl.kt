package borg.trikeshed.confix

import borg.trikeshed.cursor.*

// ── TypeSignatureDsl ───────────────────────────────────────────────
//
// Kotlin DSL for declaring typedef patch operations.
// Produced patch specs are handed to background agents via delegate_task.
//
// Example usage:
// ```
// val patches = typeSignature {
//     module("org/xvm/asm/Constants") {
//         typedef("Join") implies "Tuple"
//         typedef("Cursor") implies "Series"
//         typedef("Twin<T>") implies "Join<T,T>"
//     }
//     module("org/xvm/asm/Ops") {
//         typedef("Add") implies "Op"
//     }
// }
//
// // Hand to background agent
// delegate_task(
//     goal = "Apply these typedef patches to the xvm ConstantPool",
//     context = patches.toJson()
// )
// ```

// ── PatchSpec — the serializable patch declaration ──────────────────

/**
 * A complete patch specification ready for delegation to a background agent.
 * Serializable to JSON for transport over delegate_task.
 */
data class PatchSpec(
    /** Human-readable description of what this patch does. */
    val description: String,
    /** Ordered list of module-scoped typedef operations. */
    val modules: List<ModulePatch>,
) {
    /** Serialize to JSON for delegate_task transport. */
    fun toJson(): String = PatchSpecJson(this).toString()
}

/**
 * Per-module patch: all typedefs declared within one module/class path.
 */
data class ModulePatch(
    val modulePath: String,
    val typedefs: List<TypedefPatch>,
)

/**
 * A single typedef operation.
 * name:       the typedef name (e.g. "Join", "Cursor")
 * params:     type parameters (e.g. ["T"] for "Twin<T>")
 * implies:    the referred-to type (e.g. "Join<T,T>" for "Twin<T>")
 * source:     source file or context for debugging
 */
data class TypedefPatch(
    val name: String,
    val params: List<String>,
    val implies: String,
    val source: String? = null,
)

// ── DSL builders ──────────────────────────────────────────────────

/**
 * Root of the TypeSignature DSL.
 *
 * ```kotlin
 * typeSignature {
 *     module("org/xvm/asm/Constants") { ... }
 * }
 * ```
 */
class TypeSignatureBuilder {
    private val _modules = mutableListOf<ModulePatch>()
    val modules: List<ModulePatch> get() = _modules

    /**
     * Declare a module's typedefs.
     * @param path e.g. "org/xvm/asm/Constants"
     * @param block  typedef builder block
     */
    fun module(path: String, block: TypedefBuilder.() -> Unit) {
        val builder = TypedefBuilder(path)
        builder.block()
        _modules.add(builder.build())
    }

    /**
     * Build the final [PatchSpec].
     */
    fun build(): PatchSpec = PatchSpec(
        description = "${_modules.sumOf { it.typedefs.size }} typedefs across ${_modules.size} modules",
        modules = _modules.toList(),
    )
}

/**
 * Typedef declarations within one module.
 */
class TypedefBuilder(private val modulePath: String) {
    private val _typedefs = mutableListOf<TypedefPatch>()

    /**
     * Declare a typedef: `typedef <implies> as <name>;`
     *
     * ```kotlin
     * typedef("Join<T,T>") implies "Twin<T>"
     * // produces: typedef Join<T,T> as Twin<T>;
     * ```
     *
     * @param implies  the referred-to type expression
     * @param name     the typedef name (inferred from implies for simple names)
     */
    infix fun String.implies(name: String): TypedefPatch {
        val patch = TypedefPatch(
            name = name,
            params = extractParams(this),
            implies = this,
            source = modulePath,
        )
        _typedefs.add(patch)
        return patch
    }

    /**
     * Shorthand: `typedef("Join<T,T>")` — name is inferred from the type expression.
     * For simple single-word types, the name is the word itself.
     * For parameterized types, name is extracted from the type expression.
     */
    operator fun String.invoke(): TypedefPatch {
        val params = extractParams(this)
        val name = extractName(this)
        val patch = TypedefPatch(
            name = name,
            params = params,
            implies = this,
            source = modulePath,
        )
        _typedefs.add(patch)
        return patch
    }

    fun build(): ModulePatch = ModulePatch(modulePath, _typedefs.toList())
}

// ── DSL entry point ───────────────────────────────────────────────

/**
 * Build a [PatchSpec] from the DSL.
 *
 * ```kotlin
 * val spec = typeSignature {
 *     module("org/xvm/asm/Constants") {
 *         typedef("Join<T,T>") implies "Twin<T>"
 *         "Series<RowVec>" implies "Cursor"
 *     }
 * }
 * ```
 */
fun typeSignature(block: TypeSignatureBuilder.() -> Unit): PatchSpec {
    val builder = TypeSignatureBuilder()
    builder.block()
    return builder.build()
}

// ── Helpers ───────────────────────────────────────────────────────

private val paramPattern = Regex("<([^>]+)>")

/** Extract type parameters from a type expression like "Join<T,T>". */
fun extractParams(typeExpr: String): List<String> {
    val m = paramPattern.find(typeExpr)
    if (m == null) return emptyList()
    return m.groupValues[1].split(",").map { it.trim() }
}

/** Extract the type name from an expression like "Join<T,T>" → "Join". */
fun extractName(typeExpr: String): String =
    typeExpr.substringBefore('<').substringAfterLast('.')

// ── JSON serialization ────────────────────────────────────────────

private class PatchSpecJson(val spec: PatchSpec) {
    override fun toString(): String = buildString {
        appendLine("{")
        appendLine("  \"description\": \"${spec.description}\",")
        appendLine("  \"modules\": [")
        spec.modules.forEachIndexed { mi, mod ->
            appendLine("    {")
            appendLine("      \"modulePath\": \"${mod.modulePath}\",")
            appendLine("      \"typedefs\": [")
            mod.typedefs.forEachIndexed { ti, td ->
                val params = td.params.joinToString("\", \"")
                appendLine("        {")
                appendLine("          \"name\": \"${td.name}\",")
                appendLine("          \"params\": [\"$params\"],")
                appendLine("          \"implies\": \"${td.implies}\"")
                appendLine("        }${if (ti < mod.typedefs.lastIndex) "," else ""}")
            }
            appendLine("      ]")
            appendLine("    }${if (mi < spec.modules.lastIndex) "," else ""}")
        }
        appendLine("  ]")
        appendLine("}")
    }
}
