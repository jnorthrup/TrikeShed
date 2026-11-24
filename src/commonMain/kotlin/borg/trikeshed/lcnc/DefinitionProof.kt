package borg.trikeshed.lcnc

enum class DefinitionConclusion { APPLIES, DOES_NOT_APPLY, CANNOT_BE_TOLD }

/** One fact that meets core words of a definition: the words it meets, in definition order, and the fact's index and text. */
data class DefinitionProofStep(val words: List<String>, val fact: Int, val factText: String)

/**
 * A definition applied to facts. [core] is the words the facts must meet; [unmet] the core words no fact meets.
 * [conclusion] is APPLIES when [unmet] is empty, DOES_NOT_APPLY when some core word is unmet and the facts are the
 * whole case, and CANNOT_BE_TOLD when some core word is unmet and the facts may be incomplete.
 */
data class DefinitionApplication(
    val source: DictionarySource,
    val headword: String,
    val definition: String,
    val core: List<String>,
    val conclusion: DefinitionConclusion,
    val steps: List<DefinitionProofStep>,
    val unmet: List<String>,
)

/** Applies a definition to facts by word form: a fact meets a core word when they share its stem. Negation is not read. */
object DefinitionProof {
    private val stopWords = setOf("a", "an", "the", "of", "in", "upon", "on", "to", "for", "by", "and", "or", "is", "be",
        "that", "with", "at", "from", "as", "it")
    private val letters = Regex("[^a-z]+")
    private val clauseEnd = Regex("[,;.]")

    /** The lowercase words of [text], in order. */
    private fun words(text: String): List<String> = text.lowercase().split(letters).filter { it.isNotEmpty() }

    /** Strips one common suffix, so that `delivery`, `delivered` and `delivers` share `deliver`, and `goods` shares `good`. */
    private fun stem(word: String): String {
        for (suffix in listOf("ing", "ed", "es", "s", "y")) {
            if (word.length - suffix.length >= 3 && word.endsWith(suffix)) return word.dropLast(suffix.length)
        }
        return word
    }

    /** The core words of [definition]: the words before its first comma, semicolon or full stop, without stop words. */
    fun core(definition: String): List<String> {
        val clause = definition.split(clauseEnd, limit = 2).first()
        return words(clause).filter { it !in stopWords }.distinct()
    }

    fun apply(source: DictionarySource, headword: String, definition: String, facts: List<String>, complete: Boolean): DefinitionApplication {
        val core = core(definition)
        require(core.isNotEmpty()) { "Definition of $headword has no core words" }
        val factStems = facts.map { fact -> words(fact).map(::stem).toSet() }
        val allStems = factStems.flatten().toSet()
        val unmet = core.filter { stem(it) !in allStems }
        val steps = facts.indices.mapNotNull { i ->
            val met = core.filter { stem(it) in factStems[i] }
            if (met.isEmpty()) null else DefinitionProofStep(met, i, facts[i])
        }
        val conclusion = when {
            unmet.isEmpty() -> DefinitionConclusion.APPLIES
            complete -> DefinitionConclusion.DOES_NOT_APPLY
            else -> DefinitionConclusion.CANNOT_BE_TOLD
        }
        return DefinitionApplication(source, headword, definition, core, conclusion, steps, unmet)
    }
}
