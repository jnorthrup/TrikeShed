package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.narsese.NormClauses
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpToken

enum class EvidenceSign { POSITIVE, NEGATIVE }

enum class DefinitionConclusion { APPLIES, DOES_NOT_APPLY, CONFLICTED, CANNOT_BE_TOLD }

/** One element of a definition: the genus lemma, the relation to it, and the lemmas any one of which meets it. */
data class DefinitionElement(val genus: String, val relation: String, val alternatives: List<String>) {
    val words: String get() = "$genus $relation ${alternatives.joinToString(" or ")}"
}

/**
 * A fact sentence that speaks to an element. [dependency] is the dependency in the fact that meets it, as
 * `governor relation dependent` lemmas; [equalLemma] is the `lemma=canonical` entry used, if one was.
 */
data class DefinitionProofStep(
    val element: DefinitionElement,
    val fact: String,
    val dependency: String,
    val sign: EvidenceSign,
    val equalLemma: String?,
)

data class SetAsideFact(val fact: String, val reason: String)

/** A definition applied to parsed facts. [named] holds the steps that decide [conclusion]. */
data class DefinitionApplication(
    val source: DictionarySource,
    val headword: String,
    val definition: String,
    val elements: List<DefinitionElement>,
    val conclusion: DefinitionConclusion,
    val steps: List<DefinitionProofStep>,
    val named: List<DefinitionProofStep>,
    val withoutEvidence: List<DefinitionElement>,
    val setAside: List<SetAsideFact>,
)

/** Applies a parsed definition to parsed fact sentences. Equal lemmas are an input; nothing else links a verb to a noun. */
object DefinitionProof {
    private val negators = setOf("not", "never", "no")

    private fun content(t: NlpToken) =
        t.tag.startsWith("NN") || t.tag.startsWith("JJ") || t.tag == "CD" || t.tag.startsWith("VB") && t.lemma.lowercase() != "be"

    /** The elements of the definition's one sentence: one per dependency from its genus (the root) to a content word. */
    fun elements(definition: NlpDocument): List<DefinitionElement> {
        val sentence = definition.sentences.toList().single()
        val tokens = sentence.tokens.toList().associateBy { it.index }
        val deps = sentence.dependencies.toList()
        val genusIndex = deps.single { it.governor == 0 && it.relation.equals("root", ignoreCase = true) }.dependent
        val genus = tokens.getValue(genusIndex).lemma.lowercase()

        fun orGroup(index: Int): List<Int> =
            listOf(index) + deps.filter { it.governor == index && it.relation.equals("conj:or", ignoreCase = true) }
                .flatMap { orGroup(it.dependent) }

        fun elementsAt(index: Int, relation: String): List<DefinitionElement> {
            val group = orGroup(index)
            val andHeads = group.flatMap { member ->
                deps.filter { it.governor == member && it.relation.equals("conj:and", ignoreCase = true) }.map { it.dependent }
            }
            return listOf(DefinitionElement(genus, relation, group.map { tokens.getValue(it).lemma.lowercase() })) +
                andHeads.flatMap { elementsAt(it, relation) }
        }

        return deps.filter { it.governor == genusIndex && tokens[it.dependent]?.let(::content) == true }
            .flatMap { elementsAt(it.dependent, it.relation.lowercase()) }
    }

    fun apply(
        source: DictionarySource,
        headword: String,
        definition: NlpDocument,
        facts: List<NlpDocument>,
        equalLemmas: Map<String, String>,
    ): DefinitionApplication {
        val elements = elements(definition)
        require(elements.isNotEmpty()) { "Definition of $headword has no elements" }
        val canonical = equalLemmas.mapKeys { it.key.lowercase() }.mapValues { it.value.lowercase() }
        fun canon(lemma: String) = canonical[lemma.lowercase()] ?: lemma.lowercase()

        val steps = ArrayList<DefinitionProofStep>()
        val setAside = ArrayList<SetAsideFact>()
        for (doc in facts) for (sentence in doc.sentences.toList()) {
            val text = doc.text.substring(sentence.begin, sentence.end)
            val tokens = sentence.tokens.toList().associateBy { it.index }
            val deps = sentence.dependencies.toList()
            // A negation attaches to the word it negates: a `neg` or an `advmod`/`det` of not, never or no. A negation on the
            // sentence's root verb negates every element the sentence speaks to.
            val root = deps.firstOrNull { it.governor == 0 }?.dependent
            val negatedAt = deps.filter { d ->
                d.relation.lowercase().startsWith("neg") ||
                    (d.relation.lowercase() == "advmod" || d.relation.lowercase() == "det") && tokens[d.dependent]?.lemma?.lowercase() in negators
            }.map { it.governor }.toSet()
            val positiveShape = NormClauses.maySupplyPositivePremise(doc, sentence)
            var spoke = false
            for (element in elements) {
                for (dep in deps) {
                    val g = tokens[dep.governor] ?: continue
                    val d = tokens[dep.dependent] ?: continue
                    val meeting = when {
                        dep.relation.lowercase() != element.relation -> continue
                        canon(g.lemma) == element.genus && canon(d.lemma) in element.alternatives -> g to d
                        canon(d.lemma) == element.genus && canon(g.lemma) in element.alternatives -> d to g
                        else -> continue
                    }
                    spoke = true
                    val (genusToken, altToken) = meeting
                    val negated = dep.governor in negatedAt || dep.dependent in negatedAt || root in negatedAt
                    val sign = when {
                        negated -> EvidenceSign.NEGATIVE
                        positiveShape -> EvidenceSign.POSITIVE
                        else -> null
                    }
                    if (sign == null) {
                        setAside += SetAsideFact(text, "speaks to ${element.words}, but is neither negated nor a positive premise (modal, past tense, question, quotation, attribution or condition)")
                        continue
                    }
                    val changed = listOf(genusToken, altToken).firstOrNull { canon(it.lemma) != it.lemma.lowercase() }
                    steps += DefinitionProofStep(
                        element, text, "${g.lemma} ${dep.relation.lowercase()} ${d.lemma}", sign,
                        changed?.let { "${it.lemma.lowercase()}=${canon(it.lemma)}" },
                    )
                }
            }
            if (!spoke) setAside += SetAsideFact(text, "speaks to no element")
        }

        val byElement = elements.associateWith { e -> steps.filter { it.element == e } }
        fun has(e: DefinitionElement, s: EvidenceSign) = byElement.getValue(e).any { it.sign == s }
        val conflicted = elements.firstOrNull { has(it, EvidenceSign.POSITIVE) && has(it, EvidenceSign.NEGATIVE) }
        val denied = elements.firstOrNull { has(it, EvidenceSign.NEGATIVE) && !has(it, EvidenceSign.POSITIVE) }
        val withoutEvidence = elements.filter { byElement.getValue(it).isEmpty() }
        val (conclusion, named) = when {
            conflicted != null -> DefinitionConclusion.CONFLICTED to byElement.getValue(conflicted)
            denied != null -> DefinitionConclusion.DOES_NOT_APPLY to byElement.getValue(denied).filter { it.sign == EvidenceSign.NEGATIVE }
            withoutEvidence.isEmpty() -> DefinitionConclusion.APPLIES to steps.toList()
            else -> DefinitionConclusion.CANNOT_BE_TOLD to emptyList()
        }
        return DefinitionApplication(
            source, headword, definition.text.trim(), elements, conclusion, steps.toList(), named, withoutEvidence, setAside.toList(),
        )
    }
}
