package borg.trikeshed.narsese

import borg.trikeshed.lib.toSeries
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NlpcoreAxiomaticsTest {
    /** Token spans are derived from [text] itself (never hand-counted), one word per entry. */
    private fun document(text: String, words: List<Triple<String, String, String>>, deps: List<NlpDependency>): NlpDocument {
        var cursor = 0
        val tokens = words.mapIndexed { i, (word, lemma, tag) ->
            val begin = text.indexOf(word, cursor)
            check(begin >= 0) { "\"$word\" not found in \"$text\" at or after $cursor" }
            cursor = begin + word.length
            NlpToken(i + 1, begin, cursor, word, lemma, tag, "O")
        }
        val sentence = NlpSentence(0, tokens.first().begin, tokens.last().end, tokens.toSeries(), deps.toSeries())
        return NlpDocument(text, listOf(sentence).toSeries())
    }

    @Test
    fun causalDependencyBecomesDiscountableEternalRule() {
        val text = "Smoke causes fire."
        val doc = document(text, listOf(
            Triple("Smoke", "smoke", "NN"), Triple("causes", "cause", "VBZ"),
            Triple("fire", "fire", "NN"), Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"),
            NlpDependency(2, 3, "obj"), NlpDependency(2, 4, "punct"),
        ))

        val axioms = NlpcoreAxiomatics.recognize(doc, "source-cid")
        assertEquals(1, axioms.size)
        val axiom = axioms[0]
        assertEquals("Smoke", axiom.rule.antecedent)
        assertEquals("fire", axiom.rule.consequent)
        assertEquals(NalCopula.IMPLICATION, axiom.rule.copula)
        assertEquals(900L, axiom.rule.evidence.positive)
        assertEquals("source-cid", axiom.rule.provenanceCid)
        assertTrue(axiom.rule.isEternal)
    }

    @Test
    fun explicitConditionalUsesDependencyMarkAndNotPosition() {
        val text = "If smoke rises, fire spreads."
        val doc = document(text, listOf(
            Triple("If", "if", "IN"), Triple("smoke", "smoke", "NN"), Triple("rises", "rise", "VBZ"),
            Triple(",", ",", ","), Triple("fire", "fire", "NN"), Triple("spreads", "spread", "VBZ"),
            Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 6, "root"), NlpDependency(6, 5, "nsubj"), NlpDependency(6, 3, "advcl"),
            NlpDependency(3, 2, "nsubj"), NlpDependency(3, 1, "mark"), NlpDependency(6, 4, "punct"),
            NlpDependency(6, 7, "punct"),
        ))

        // Both clause predicates are retained — "smoke rise" and "fire spread" are not
        // the same claim as any other reading of bare "smoke" and "fire".
        val rules = NlpcoreAxiomatics.rules(doc)
        assertEquals(1, rules.size)
        assertEquals("smoke rise", rules[0].antecedent)
        assertEquals("fire spread", rules[0].consequent)
        assertEquals(800L, rules[0].evidence.positive)
    }

    @Test
    fun transitiveConditionalPreservesBothClauseObjects() {
        // The review case: collapsing to bare "Acme ==> Gamma" would silently drop Beta and Delta.
        val text = "If Acme pays Beta, Gamma ships Delta."
        val doc = document(text, listOf(
            Triple("If", "if", "IN"), Triple("Acme", "Acme", "NNP"), Triple("pays", "pay", "VBZ"),
            Triple("Beta", "Beta", "NNP"), Triple(",", ",", ","), Triple("Gamma", "Gamma", "NNP"),
            Triple("ships", "ship", "VBZ"), Triple("Delta", "Delta", "NNP"), Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 7, "root"), NlpDependency(7, 6, "nsubj"), NlpDependency(7, 8, "obj"),
            NlpDependency(7, 3, "advcl"), NlpDependency(3, 2, "nsubj"), NlpDependency(3, 4, "obj"),
            NlpDependency(3, 1, "mark"), NlpDependency(7, 5, "punct"), NlpDependency(7, 9, "punct"),
        ))

        val rules = NlpcoreAxiomatics.rules(doc)
        assertEquals(1, rules.size)
        assertEquals("Acme pay Beta", rules[0].antecedent)
        assertEquals("Gamma ship Delta", rules[0].consequent)
        assertEquals(800L, rules[0].evidence.positive)
    }

    @Test
    fun conditionalWithoutAdvclLinkIsRefused() {
        // The mark(rises, If) edge exists, but nothing ties "rises" to "spreads" as its
        // consequence clause (no advcl edge) — pairing them anyway would be a guess.
        val text = "If smoke rises, fire spreads."
        val doc = document(text, listOf(
            Triple("If", "if", "IN"), Triple("smoke", "smoke", "NN"), Triple("rises", "rise", "VBZ"),
            Triple(",", ",", ","), Triple("fire", "fire", "NN"), Triple("spreads", "spread", "VBZ"),
            Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 6, "root"), NlpDependency(6, 5, "nsubj"),
            NlpDependency(3, 2, "nsubj"), NlpDependency(3, 1, "mark"), NlpDependency(6, 4, "punct"),
            NlpDependency(6, 7, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun whenIsTemporalNotEternalImplication() {
        val text = "When smoke rises, fire spreads."
        val doc = document(text, listOf(
            Triple("When", "when", "WRB"), Triple("smoke", "smoke", "NN"), Triple("rises", "rise", "VBZ"),
            Triple(",", ",", ","), Triple("fire", "fire", "NN"), Triple("spreads", "spread", "VBZ"),
            Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 6, "root"), NlpDependency(6, 5, "nsubj"), NlpDependency(6, 3, "advcl"),
            NlpDependency(3, 2, "nsubj"), NlpDependency(3, 1, "mark"), NlpDependency(6, 4, "punct"),
            NlpDependency(6, 7, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun negatedCausalVerbIsRefused() {
        val text = "Smoke does not cause fire."
        val doc = document(text, listOf(
            Triple("Smoke", "smoke", "NN"), Triple("does", "do", "VBZ"), Triple("not", "not", "RB"),
            Triple("cause", "cause", "VB"), Triple("fire", "fire", "NN"), Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 4, "root"), NlpDependency(4, 1, "nsubj"), NlpDependency(4, 2, "aux"),
            NlpDependency(4, 3, "advmod"), NlpDependency(4, 5, "obj"), NlpDependency(4, 6, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun modalCausalVerbIsRefused() {
        val text = "Smoke may cause fire."
        val doc = document(text, listOf(
            Triple("Smoke", "smoke", "NN"), Triple("may", "may", "MD"),
            Triple("cause", "cause", "VB"), Triple("fire", "fire", "NN"), Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 3, "root"), NlpDependency(3, 1, "nsubj"), NlpDependency(3, 2, "aux"),
            NlpDependency(3, 4, "obj"), NlpDependency(3, 5, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun passiveCausalVerbIsRefused() {
        val text = "Fire is caused by smoke."
        val doc = document(text, listOf(
            Triple("Fire", "fire", "NN"), Triple("is", "be", "VBZ"), Triple("caused", "cause", "VBN"),
            Triple("by", "by", "IN"), Triple("smoke", "smoke", "NN"), Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 3, "root"), NlpDependency(3, 1, "nsubj:pass"), NlpDependency(3, 2, "aux:pass"),
            NlpDependency(3, 5, "obl"), NlpDependency(5, 4, "case"), NlpDependency(3, 6, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun reportedCausalClaimIsRefused() {
        // "causes" is not the sentence root here — "say" is — so the causal-verb branch
        // never sees "causes" as a root candidate. Reported speech is refused structurally.
        val text = "Reports say smoke causes fire."
        val doc = document(text, listOf(
            Triple("Reports", "report", "NNS"), Triple("say", "say", "VBP"), Triple("smoke", "smoke", "NN"),
            Triple("causes", "cause", "VBZ"), Triple("fire", "fire", "NN"), Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"), NlpDependency(2, 4, "ccomp"),
            NlpDependency(4, 3, "nsubj"), NlpDependency(4, 5, "obj"), NlpDependency(4, 6, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun quotedSentenceIsRefused() {
        val text = "\"Smoke causes fire.\""
        val doc = document(text, listOf(
            Triple("\"", "\"", "``"), Triple("Smoke", "smoke", "NN"), Triple("causes", "cause", "VBZ"),
            Triple("fire", "fire", "NN"), Triple(".", ".", "."), Triple("\"", "\"", "''"),
        ), listOf(
            NlpDependency(0, 3, "root"), NlpDependency(3, 2, "nsubj"), NlpDependency(3, 4, "obj"),
            NlpDependency(3, 1, "punct"), NlpDependency(3, 5, "punct"), NlpDependency(3, 6, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun coordinatedSubjectIsRefused() {
        // "Rain and wind cause floods." — picking only "Rain" would drop "wind" from the claim.
        val text = "Rain and wind cause floods."
        val doc = document(text, listOf(
            Triple("Rain", "rain", "NN"), Triple("and", "and", "CC"), Triple("wind", "wind", "NN"),
            Triple("cause", "cause", "VBP"), Triple("floods", "flood", "NNS"), Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 4, "root"), NlpDependency(4, 1, "nsubj"), NlpDependency(1, 3, "conj"),
            NlpDependency(3, 2, "cc"), NlpDependency(4, 5, "obj"), NlpDependency(4, 6, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun coordinatedRootIsRefused() {
        // "Rain causes and triggers floods." — a conj on the root itself is a second predicate.
        val text = "Rain causes and triggers floods."
        val doc = document(text, listOf(
            Triple("Rain", "rain", "NN"), Triple("causes", "cause", "VBZ"), Triple("and", "and", "CC"),
            Triple("triggers", "trigger", "VBZ"), Triple("floods", "flood", "NNS"), Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"), NlpDependency(2, 4, "conj"),
            NlpDependency(4, 3, "cc"), NlpDependency(2, 5, "obj"), NlpDependency(2, 6, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun rootAdverbialModifierIsRefused() {
        // Any adverb on the causal root is a modifier the recognizer cannot represent, not just negation.
        val text = "Rain quickly causes floods."
        val doc = document(text, listOf(
            Triple("Rain", "rain", "NN"), Triple("quickly", "quickly", "RB"), Triple("causes", "cause", "VBZ"),
            Triple("floods", "flood", "NNS"), Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 3, "root"), NlpDependency(3, 1, "nsubj"), NlpDependency(3, 2, "advmod"),
            NlpDependency(3, 4, "obj"), NlpDependency(3, 5, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun amodOnSubjectIsRefused() {
        val text = "Heavy rain causes floods."
        val doc = document(text, listOf(
            Triple("Heavy", "heavy", "JJ"), Triple("rain", "rain", "NN"), Triple("causes", "cause", "VBZ"),
            Triple("floods", "flood", "NNS"), Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 3, "root"), NlpDependency(3, 2, "nsubj"), NlpDependency(2, 1, "amod"),
            NlpDependency(3, 4, "obj"), NlpDependency(3, 5, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun compoundOnObjectIsRefused() {
        val text = "Rain causes flash floods."
        val doc = document(text, listOf(
            Triple("Rain", "rain", "NN"), Triple("causes", "cause", "VBZ"), Triple("flash", "flash", "NN"),
            Triple("floods", "flood", "NNS"), Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"), NlpDependency(2, 4, "obj"),
            NlpDependency(4, 3, "compound"), NlpDependency(2, 5, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun determinerOnSubjectIsRefused() {
        val text = "The rain causes floods."
        val doc = document(text, listOf(
            Triple("The", "the", "DT"), Triple("rain", "rain", "NN"), Triple("causes", "cause", "VBZ"),
            Triple("floods", "flood", "NNS"), Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 3, "root"), NlpDependency(3, 2, "nsubj"), NlpDependency(2, 1, "det"),
            NlpDependency(3, 4, "obj"), NlpDependency(3, 5, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun clausalSubjectIsRefused() {
        // csubj carries an entire clause; taking only its head token would drop the clause's meaning.
        val text = "Flooding causes damage."
        val doc = document(text, listOf(
            Triple("Flooding", "flood", "VBG"), Triple("causes", "cause", "VBZ"), Triple("damage", "damage", "NN"),
            Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 2, "root"), NlpDependency(2, 1, "csubj"),
            NlpDependency(2, 3, "obj"), NlpDependency(2, 4, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun pastTenseCausalVerbIsRefused() {
        // VBD narrates a specific past event, not an eternal reading.
        val text = "Smoke caused fire."
        val doc = document(text, listOf(
            Triple("Smoke", "smoke", "NN"), Triple("caused", "cause", "VBD"),
            Triple("fire", "fire", "NN"), Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"),
            NlpDependency(2, 3, "obj"), NlpDependency(2, 4, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun pastTenseConditionalIsRefused() {
        val text = "If rain stopped, floods receded."
        val doc = document(text, listOf(
            Triple("If", "if", "IN"), Triple("rain", "rain", "NN"), Triple("stopped", "stop", "VBD"),
            Triple(",", ",", ","), Triple("floods", "flood", "NNS"), Triple("receded", "recede", "VBD"),
            Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 6, "root"), NlpDependency(6, 5, "nsubj"), NlpDependency(6, 3, "advcl"),
            NlpDependency(3, 2, "nsubj"), NlpDependency(3, 1, "mark"), NlpDependency(6, 4, "punct"),
            NlpDependency(6, 7, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun nonCausalAndTemporalPredicatesAreNotReinterpreted() {
        val text = "Rain precedes floods."
        val doc = document(text, listOf(
            Triple("Rain", "rain", "NN"), Triple("precedes", "precede", "VBZ"), Triple("floods", "flood", "NNS"),
        ), listOf(
            NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"), NlpDependency(2, 3, "obj"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun ambiguousMultipleRootsAreRefused() {
        // A malformed parse claiming two governor-0 roots in one sentence is not one covered claim.
        val text = "Smoke causes fire."
        val doc = document(text, listOf(
            Triple("Smoke", "smoke", "NN"), Triple("causes", "cause", "VBZ"),
            Triple("fire", "fire", "NN"), Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 2, "root"), NlpDependency(0, 1, "root"),
            NlpDependency(2, 3, "obj"), NlpDependency(2, 4, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun duplicateTokenIdsAreRefused() {
        val text = "Smoke causes fire."
        val tokens = listOf(
            NlpToken(1, 0, 5, "Smoke", "smoke", "NN", "O"),
            NlpToken(1, 6, 12, "causes", "cause", "VBZ", "O"), // duplicate index 1
            NlpToken(3, 13, 17, "fire", "fire", "NN", "O"),
            NlpToken(4, 17, 18, ".", ".", ".", "O"),
        ).toSeries()
        val deps = listOf(
            NlpDependency(0, 1, "root"), NlpDependency(1, 3, "obj"), NlpDependency(1, 4, "punct"),
        ).toSeries()
        val doc = NlpDocument(text, listOf(NlpSentence(0, 0, text.length, tokens, deps)).toSeries())
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun forgedTokenTextIsRefused() {
        val text = "Smoke causes fire."
        val tokens = listOf(
            NlpToken(1, 0, 5, "Smoke", "smoke", "NN", "O"),
            NlpToken(2, 6, 12, "ignites", "ignite", "VBZ", "O"), // forged: span 6..12 is actually "causes"
            NlpToken(3, 13, 17, "fire", "fire", "NN", "O"),
            NlpToken(4, 17, 18, ".", ".", ".", "O"),
        ).toSeries()
        val deps = listOf(
            NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"),
            NlpDependency(2, 3, "obj"), NlpDependency(2, 4, "punct"),
        ).toSeries()
        val doc = NlpDocument(text, listOf(NlpSentence(0, 0, text.length, tokens, deps)).toSeries())
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun invalidTokenSpanIsRefused() {
        val text = "Smoke causes fire."
        val tokens = listOf(
            NlpToken(1, 0, 5, "Smoke", "smoke", "NN", "O"),
            NlpToken(2, 6, 12, "causes", "cause", "VBZ", "O"),
            NlpToken(3, 13, text.length + 5, "fire", "fire", "NN", "O"), // end beyond document text
            NlpToken(4, 17, 18, ".", ".", ".", "O"),
        ).toSeries()
        val deps = listOf(
            NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"),
            NlpDependency(2, 3, "obj"), NlpDependency(2, 4, "punct"),
        ).toSeries()
        val doc = NlpDocument(text, listOf(NlpSentence(0, 0, text.length, tokens, deps)).toSeries())
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun uncoveredTokenIsRefused() {
        // Token 3 ("fire") never appears as a dependent of any edge — an orphan the tree doesn't cover.
        val text = "Smoke causes fire."
        val doc = document(text, listOf(
            Triple("Smoke", "smoke", "NN"), Triple("causes", "cause", "VBZ"),
            Triple("fire", "fire", "NN"), Triple(".", ".", "."),
        ), listOf(
            NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"), NlpDependency(2, 4, "punct"),
        ))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }

    @Test
    fun omittedNegationInvalidatesParserInputAndCandidate() {
        val text = "Smoke never causes fire."
        val doc = document(text, listOf(
            Triple("Smoke", "smoke", "NN"), Triple("causes", "cause", "VBZ"),
            Triple("fire", "fire", "NN"), Triple(".", ".", "."),
        ), listOf(NlpDependency(0, 2, "root"), NlpDependency(2, 1, "nsubj"),
            NlpDependency(2, 3, "obj"), NlpDependency(2, 4, "punct")))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
        val cid = ContentId.of(text.encodeToByteArray())
        val source = DocumentSource(cid, cid, text, "source.txt", "text/plain", "coverage")
        assertEquals(DocumentNlpStatus.INVALID, source.curationIndex(doc).facet(DocumentCurationIndexK.NlpStatus))
    }

    @Test
    fun multipleConditionalObjectsCannotBecomeAnIntransitiveClause() {
        val text = "If customers pay invoices bills, suppliers deliver goods."
        val doc = document(text, listOf(
            Triple("If", "if", "IN"), Triple("customers", "customer", "NNS"), Triple("pay", "pay", "VBP"),
            Triple("invoices", "invoice", "NNS"), Triple("bills", "bill", "NNS"), Triple(",", ",", ","),
            Triple("suppliers", "supplier", "NNS"), Triple("deliver", "deliver", "VBP"),
            Triple("goods", "goods", "NNS"), Triple(".", ".", "."),
        ), listOf(NlpDependency(0, 8, "root"), NlpDependency(8, 3, "advcl:if"),
            NlpDependency(3, 1, "mark"), NlpDependency(3, 2, "nsubj"),
            NlpDependency(3, 4, "obj"), NlpDependency(3, 5, "obj"),
            NlpDependency(8, 6, "punct"), NlpDependency(8, 7, "nsubj"),
            NlpDependency(8, 9, "obj"), NlpDependency(8, 10, "punct")))
        assertEquals(0, NlpcoreAxiomatics.recognize(doc).size)
    }
}
