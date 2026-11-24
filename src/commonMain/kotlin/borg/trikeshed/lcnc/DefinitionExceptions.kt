package borg.trikeshed.lcnc

import borg.trikeshed.narsese.Jev

enum class DefinitionExceptionKind { MEETS, VOID }

/**
 * An exception to what the rules of [DefinitionProof] read, assigned by a model to one element the rules left without
 * evidence. [DefinitionExceptionKind.MEETS]: [fact] states what the element requires, though no agreement rule joins
 * them. [DefinitionExceptionKind.VOID]: the element requires nothing of the facts (alternatives that leave nothing
 * out, "expressed or implied").
 *
 * It is a candidate. [author] is the model that chose it and [probability] what the model gave its choice; neither is
 * weighed here. It moves a conclusion only when a caller hands it back to [DefinitionProof.apply], and the application
 * then lists it beside the steps the rules made.
 */
data class DefinitionException(val element: DefinitionElement, val kind: DefinitionExceptionKind, val fact: String?,
                               val author: String, val probability: Double)

/** The questions a model is put where the rules of [DefinitionProof] could not tell, and the exceptions its answers assign. */
object DefinitionExceptions {
    const val VOID = "void"
    const val NONE = "none"

    private fun id(application: DefinitionApplication, element: DefinitionElement) = "e${application.elements.indexOf(element)}"

    /** What every question of one ask reads: the definition, its source and the facts by option id. */
    fun state(application: DefinitionApplication, facts: List<String>): Map<String, Any?> = linkedMapOf(
        "dictionary" to application.source.dictionary, "edition" to application.source.edition, "year" to application.source.year,
        "headword" to application.headword, "definition" to application.definition,
        "facts" to facts.withIndex().associate { (i, fact) -> "f$i" to fact })

    /**
     * One choice an element of [application] without evidence: which of [facts] states it, that it requires nothing,
     * or that none does. Nothing is asked where the rules decided, and nothing where more elements lack evidence than
     * [asks]: answers that cannot settle the conclusion are not asked for. [asks] is one until a caller clears more.
     */
    fun questions(application: DefinitionApplication, facts: List<String>, asks: Int = 1): Map<String, Map<String, Any?>> {
        val open = application.withoutEvidence
        if (application.conclusion != DefinitionConclusion.CANNOT_BE_TOLD || open.isEmpty() || open.size > asks) return emptyMap()
        require(facts.size + 2 <= 255) { "${facts.size} facts are more than one choice holds" }
        val options = facts.indices.associate { "f$it" to "`facts.f$it` states it" } +
            mapOf(VOID to "it requires nothing of the facts: its alternatives leave nothing out", NONE to "no fact states it")
        return open.associate { element ->
            id(application, element) to Jev.choice(mapOf("element" to element.words, "words" to element.alternatives,
                "question" to "`definition` requires `element` of whatever `headword` names. Which of `facts` states it?"), options)
        }
    }

    /**
     * The exceptions [answers] to [questions] assign, [author] the model that gave them. A choice of [NONE] assigns
     * nothing; an answer that does not fit its question is refused ([Jev.answerOf]).
     */
    fun assigned(application: DefinitionApplication, facts: List<String>, questions: Map<String, Map<String, Any?>>,
                 answers: Map<*, *>, author: String): List<DefinitionException> = application.withoutEvidence.mapNotNull { element ->
        val id = id(application, element)
        val question = questions[id] ?: return@mapNotNull null
        val answer = Jev.answerOf(answers, id, question)
        val choice = answer["choice"] as String
        val probability = ((answer["probabilities"] as Map<*, *>)[choice] as Number).toDouble()
        when (choice) {
            NONE -> null
            VOID -> DefinitionException(element, DefinitionExceptionKind.VOID, null, author, probability)
            else -> DefinitionException(element, DefinitionExceptionKind.MEETS, facts[choice.removePrefix("f").toInt()], author, probability)
        }
    }
}
