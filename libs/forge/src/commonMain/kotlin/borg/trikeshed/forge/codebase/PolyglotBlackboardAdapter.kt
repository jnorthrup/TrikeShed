package borg.trikeshed.forge.codebase

import kotlinx.serialization.Serializable

/**
 * Polyglot adapter: translates per-language symbols (JS exports, Python defs,
 * Kotlin classes) into ConfixBlackboard DAG entries.
 *
 * One adapter per language. Each adapter knows how to:
 *   1. Parse its language's symbol table into [BlackboardDagEntry] values.
 *   2. Resolve symbols against the forge project state's classpath.
 *
 * The ConfixBlackboard DAG is the master blackboard's substrate — adapters
 * are the *only* seam through which polyglot symbols enter it.
 */
interface PolyglotBlackboardAdapter {
    val language: CodebaseLanguage

    /**
     * Resolve symbols for [service] and produce the DAG entries that the
     * master blackboard should ingest. Entries are keyed by a language-tagged
     * coordinate so the DAG keeps JS/Python/Kotlin symbols disjoint.
     */
    fun resolveSymbols(service: CodebaseService): List<BlackboardDagEntry>
}

/**
 * One entry in the ConfixBlackboard DAG.
 *
 * The [coordinate] follows the convention `<lang>:<symbol>@<manifest>` so a
 * downstream rete rule can match on language, symbol name, or origin service
 * without needing a join table.
 */
@Serializable
data class BlackboardDagEntry(
    val coordinate: String,
    val symbolName: String,
    val kind: BlackboardSymbolKind,
    val language: CodebaseLanguage,
    val originService: String,
    val facet: String = "",
    val sourceLocation: String = "",
) {
    companion object {
        fun coordinate(language: CodebaseLanguage, symbol: String, manifest: String): String =
            "${language.adapterKind}:$symbol@$manifest"
    }
}

@Serializable
enum class BlackboardSymbolKind {
    FUNCTION,
    CLASS,
    VARIABLE,
    EXPORT,
    MODULE,
    ENTRY_POINT,
}

/** TypeScript adapter — resolves `export` declarations as EXPORT symbols. */
class TypeScriptBlackboardAdapter : PolyglotBlackboardAdapter {
    override val language: CodebaseLanguage get() = CodebaseLanguage.TYPESCRIPT

    override fun resolveSymbols(service: CodebaseService): List<BlackboardDagEntry> {
        return service.entryPoints.map { ep ->
            BlackboardDagEntry(
                coordinate = BlackboardDagEntry.coordinate(language, ep, service.manifestPath),
                symbolName = ep,
                kind = BlackboardSymbolKind.EXPORT,
                language = language,
                originService = service.name,
                facet = "js-export",
                sourceLocation = ep,
            )
        }
    }
}

/** Kotlin/JVM adapter — resolves class file names as CLASS symbols. */
class KotlinJvmBlackboardAdapter : PolyglotBlackboardAdapter {
    override val language: CodebaseLanguage get() = CodebaseLanguage.KOTLIN_JVM

    override fun resolveSymbols(service: CodebaseService): List<BlackboardDagEntry> {
        return service.classpath.map { entry ->
            val symbol = entry.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".jar").removeSuffix(".class")
            BlackboardDagEntry(
                coordinate = BlackboardDagEntry.coordinate(language, symbol, service.manifestPath),
                symbolName = symbol,
                kind = BlackboardSymbolKind.CLASS,
                language = language,
                originService = service.name,
                facet = "jvm-class",
                sourceLocation = entry,
            )
        }
    }
}

/** Python adapter — resolves module entry points as MODULE symbols. */
class PythonBlackboardAdapter : PolyglotBlackboardAdapter {
    override val language: CodebaseLanguage get() = CodebaseLanguage.PYTHON

    override fun resolveSymbols(service: CodebaseService): List<BlackboardDagEntry> {
        return service.entryPoints.map { ep ->
            BlackboardDagEntry(
                coordinate = BlackboardDagEntry.coordinate(language, ep, service.manifestPath),
                symbolName = ep,
                kind = BlackboardSymbolKind.MODULE,
                language = language,
                originService = service.name,
                facet = "py-module",
                sourceLocation = ep,
            )
        }
    }
}

/** Registry of available polyglot adapters, keyed by [CodebaseLanguage]. */
object PolyglotAdapters {
    private val adapters: Map<CodebaseLanguage, PolyglotBlackboardAdapter> = mapOf(
        CodebaseLanguage.TYPESCRIPT to TypeScriptBlackboardAdapter(),
        CodebaseLanguage.KOTLIN_JVM to KotlinJvmBlackboardAdapter(),
        CodebaseLanguage.PYTHON to PythonBlackboardAdapter(),
    )

    fun forLanguage(language: CodebaseLanguage): PolyglotBlackboardAdapter =
        adapters[language] ?: error("no adapter registered for $language")

    /** Resolve all symbols for a service ensemble into DAG entries. */
    fun resolveEnsemble(services: List<CodebaseService>): List<BlackboardDagEntry> =
        services.flatMap { service -> forLanguage(service.language).resolveSymbols(service) }
}