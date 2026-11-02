package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.nlp.*
import kotlin.test.*

/** Tests the conservative gate using explicit parses; no model download or parser runtime. */
class ApplicabilityFactsTest {
    private fun document(text: String, rootIsDue: Boolean = true): NlpDocument {
        val matches = Regex("[A-Za-z]+(?:['’][A-Za-z]+)?").findAll(text).toList()
        val tokens = matches.mapIndexed { index, match ->
            val word = match.value
            val lower = word.lowercase()
            val lemma = when (lower) { "is" -> "be"; "claims" -> "claim"; "says" -> "say"; else -> lower }
            val tag = when (lower) { "rent" -> "NN"; "due" -> "JJ"; "is" -> "VBZ"; "may", "might" -> "MD"; else -> "RB" }
            NlpToken(index + 1, match.range.first, match.range.last + 1, word, lemma, tag, "O")
        }
        val rent = tokens.single { it.lemma == "rent" }.index
        val due = tokens.single { it.lemma == "due" }.index
        val copula = tokens.firstOrNull { it.lemma == "be" }?.index
        val dependencies = mutableListOf(NlpDependency(0, if (rootIsDue) due else rent, "root"),
            NlpDependency(due, rent, "nsubj"))
        if (copula != null) dependencies.add(NlpDependency(due, copula, "cop"))
        return NlpDocument(text, s_[NlpSentence(0, 0, text.length, tokens.toSeries(), dependencies.toSeries())])
    }

    @Test fun directPositiveRootIsRetainedWithoutClaimingVerification() {
        val doc = document("The rent is due.")
        assertEquals(_s["rent be due"], NormClauses.applicabilityFacts(doc))
        assertEquals(_s["rent be due"], NormClauses.facts(doc))
        assertTrue(NormClauses.applicabilityFacts(document("The rent is due.", rootIsDue = false)).isEmpty())
    }

    @Test fun negatedModalQuestionHypotheticalAndAttributedClausesAreNotPositivePremises() {
        for (text in _l[
            "The rent is not due.", "No rent is due.", "The rent isn't due.", "Is the rent due?",
            "The rent may be due.", "The rent might be due.", "The rent is possibly due.",
            "If the rent is due, payment follows.", "Payment follows unless the rent is due.",
            "The landlord claims the rent is due.", "The landlord says the rent is due.",
            "The rent was due.", "The rent is due or waived.", "The notice says \"the rent is due\".",
        ]) {
            assertTrue(NormClauses.applicabilityFacts(document(text)).isEmpty(), text)
        }
        // The legacy extraction API remains available, but is not safe for this applicability gate.
        assertEquals(_s["rent be due"], NormClauses.facts(document("The rent is not due.")))
    }
}
