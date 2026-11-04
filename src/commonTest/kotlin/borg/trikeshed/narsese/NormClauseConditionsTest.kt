package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.nlp.*
import kotlin.test.*

/** Explicit dependency fixtures test interpretation boundaries, without a parser/model dependency. */
class NormClauseConditionsTest {
    private data class Edge(val governor: String, val dependent: String, val relation: String)
    private fun edges(left: Series<Edge>, right: Series<Edge>): Series<Edge> =
        (left.size + right.size) j { i: Int -> if (i < left.size) left[i] else right[i - left.size] }

    private fun document(text: String, guard: Series<Edge>, triples: Boolean = false): NlpDocument {
        val matches = Regex("[A-Za-z]+(?:['’][A-Za-z]+)?|[^\\sA-Za-z]").findAll(text).toList()
        val tokens = matches.mapIndexed { index, match ->
            val word = match.value
            val lower = word.lowercase()
            val lemma = when (lower) {
                "is", "are" -> "be"; "does" -> "do"; "approves" -> "approve"
                "waives" -> "waive"; "received" -> "receive"; else -> lower
            }
            val tag = when (lower) {
                "must", "may", "will" -> "MD"
                "pay", "keep", "approve" -> "VB"
                "is", "does", "approves", "waives" -> "VBZ"
                "received" -> "VBN"; "due" -> "JJ"; "it" -> "PRP"
                "if", "unless", "except", "when", "in" -> "IN"
                "not" -> "RB"; "and", "or" -> "CC"; ".", "," -> "."
                else -> "NN"
            }
            NlpToken(index + 1, match.range.first, match.range.last + 1, word, lemma, tag, "O")
        }
        fun index(word: String) = if (word == "ROOT") 0 else tokens.single { it.word.equals(word, true) }.index
        val main = s_[Edge("ROOT", "pay", "root"), Edge("pay", "Tenant", "nsubj"),
            Edge("pay", "must", "aux"), Edge("pay", "rent", "obj")]
        val merged = edges(main, guard)
        val dependencies = merged.size j { i: Int ->
            val edge = merged[i]
            NlpDependency(index(edge.governor), index(edge.dependent), edge.relation)
        }
        val relations = if (triples) _l[NlpRelation(index("Tenant")..index("Tenant"), index("pay")..index("pay"),
            index("rent")..index("rent"), "Tenant", "pay", "rent", 1.0)] else emptyList()
        return NlpDocument(text, s_[NlpSentence(0, 0, text.length, tokens.toSeries(), dependencies,
            relations = relations, kbp = relations)])
    }

    private fun due(mark: String, extra: Series<Edge> = emptySeriesOf()): Series<Edge> =
        edges(s_[Edge("pay", "due", "advcl"), Edge("due", mark, "mark"),
            Edge("due", "payment", "nsubj"), Edge("due", "is", "cop")], extra)

    @Test fun simplePositiveIfAndUnlessKeepTheirExistingPremise() {
        for (mark in s_["if", "unless"].view) {
            val source = "Tenant must pay rent $mark payment is due."
            val doc = document(source, due(mark))
            assertNull(NormClauses.conditionIssue(doc, doc.sentences[0]))
            val clause = NormClauses.extract(doc).single()
            assertEquals("$mark payment be due", clause.condition)
            assertEquals("payment be due", NormStatement.of(clause).premise)
            assertEquals(source, doc.text)
        }
        val plain = document("Tenant must pay rent.", emptySeriesOf())
        assertNull(NormClauses.extract(plain).single().condition)
    }

    @Test fun negativeExceptionsModalAndPassiveGuardsAreNotReversedOrMadeUnconditional() {
        val negative = document("Tenant must pay rent unless payment is not due.",
            due("unless", s_[Edge("due", "not", "neg")]))
        val modal = document("Tenant must pay rent if landlord may approve.", s_[
            Edge("pay", "approve", "advcl"), Edge("approve", "if", "mark"),
            Edge("approve", "landlord", "nsubj"), Edge("approve", "may", "aux")])
        val passive = document("Tenant must pay rent if notice is received.", s_[
            Edge("pay", "received", "advcl"), Edge("received", "if", "mark"),
            Edge("received", "notice", "nsubj:pass"), Edge("received", "is", "aux:pass")])
        for ((doc, issue) in s_[negative j NormConditionIssue.NEGATED, modal j NormConditionIssue.MODAL,
            passive j NormConditionIssue.PASSIVE].view) {
            val original = doc.text
            assertEquals(issue, NormClauses.conditionIssue(doc, doc.sentences[0]))
            assertTrue(NormClauses.extract(doc).isEmpty(), original)
            assertEquals(original, doc.text)
        }
    }

    @Test fun allGuardsAndCoordinationMustSurviveBeforeAnyNormCanBeInterpreted() {
        val multiple = document("Tenant must pay rent if payment is due unless landlord waives notice.",
            edges(due("if"), s_[Edge("pay", "waives", "advcl"), Edge("waives", "unless", "mark"),
                Edge("waives", "landlord", "nsubj"), Edge("waives", "notice", "obj")]))
        val coordinatedGuard = document("Tenant must pay rent if payment is due or landlord waives notice.",
            due("if", s_[Edge("due", "waives", "conj:or"), Edge("waives", "or", "cc"),
                Edge("waives", "landlord", "nsubj"), Edge("waives", "notice", "obj")]))
        val coordinatedEffect = document("Tenant must pay rent and keep records if payment is due.",
            due("if", s_[Edge("pay", "keep", "conj:and"), Edge("keep", "and", "cc"), Edge("keep", "records", "obj")]))
        assertEquals(NormConditionIssue.MULTIPLE, NormClauses.conditionIssue(multiple, multiple.sentences[0]))
        for (doc in s_[coordinatedGuard, coordinatedEffect].view)
            assertEquals(NormConditionIssue.COORDINATED, NormClauses.conditionIssue(doc, doc.sentences[0]))
        for (doc in s_[multiple, coordinatedGuard, coordinatedEffect].view)
            assertTrue(NormClauses.extract(doc).isEmpty(), doc.text)
        val exception = document("Tenant must pay rent except when payment is due.", due("when"))
        assertEquals(NormConditionIssue.UNSUPPORTED, NormClauses.conditionIssue(exception, exception.sentences[0]))
        assertTrue(NormClauses.extract(exception).isEmpty())
        val unbound = document("Tenant must pay rent if it is due.", s_[Edge("pay", "due", "advcl"),
            Edge("due", "if", "mark"), Edge("due", "it", "nsubj"), Edge("due", "is", "cop")])
        assertTrue(NormClauses.extract(unbound).isEmpty(), "An unbound pronoun cannot be guessed to mean rent")
    }

    @Test fun alternateOpenIeAndKbpReadingsCannotEraseTheGuard() {
        val supported = document("Tenant must pay rent if payment is due.", due("if"), triples = true)
        val clauses = NormClauses.extract(supported)
        assertEquals(1, clauses.size)
        assertEquals("if payment be due", clauses.single().condition)
        val unsupported = document("Tenant must pay rent unless payment is not due.",
            due("unless", s_[Edge("due", "not", "neg")]), triples = true)
        assertTrue(NormClauses.extract(unsupported).isEmpty())
    }
}
