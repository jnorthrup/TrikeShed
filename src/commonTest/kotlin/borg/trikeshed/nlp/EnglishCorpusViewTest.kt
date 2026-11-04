package borg.trikeshed.nlp

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentRevision
import kotlin.test.*

/** Explicit reported-token fixtures; no NLP is run and no topic is treated as a fact. */
class EnglishCorpusViewTest {
    private fun receipt(label: String, words: Series<Join<String, Join<String, String>>>): EnglishObservationReceipt {
        val quote = words.view.joinToString(" ") { it.a }
        val source = ContentId.of(quote.encodeToByteArray())
        val tokens = mutableListOf<EnglishObservationToken>()
        var begin = 0
        for ((index, word) in words.view.withIndex()) {
            tokens.add(EnglishObservationToken(index + 1, begin, begin + word.a.length, word.a, word.b.a, word.b.b))
            begin += word.a.length + 1
        }
        return EnglishObservationReceipt(ContentId.of("$label:${source.value}".encodeToByteArray()), source, source,
            0, quote.length, quote.length, DocumentRevision("fixture", "$label.txt", source.value, "1-fixture", 1),
            s_[EnglishObservationSentence(0, 0, quote.length, quote, tokens.toSeries())])
    }

    private fun receipts() = s_[
        receipt("tenant", s_["THE" j ("the" j "DT"), "Tenant" j ("Tenant" j "NNP"),
            "must" j ("must" j "MD"), "not" j ("not" j "RB"), "be" j ("be" j "VB"),
            "ready" j ("ready" j "JJ"), "unless" j ("unless" j "IN"), "owners" j ("owner" j "NNS"),
            "have" j ("have" j "VBP"), "written" j ("write" j "VBN"), "consent" j ("consent" j "NN"),
            "and" j ("and" j "CC"), "do" j ("do" j "VBP")]),
        receipt("ledger", s_["THE" j ("the" j "DT"), "Ledger" j ("Ledger" j "NNP"),
            "must" j ("must" j "MD"), "have" j ("have" j "VB"), "credit" j ("credit" j "NN")])]

    @Test fun allLexicalPolicyPreservesDefaultCanonicalBytes() {
        val input = receipts()
        val default = EnglishCorpus.build(input)
        val explicit = EnglishCorpus.build(input, EnglishCorpusView.ALL_LEXICAL.policy())
        assertEquals(default.cid, explicit.cid)
        assertEquals(default.matrixCid, explicit.matrixCid)
        assertContentEquals(CanonicalCbor.encodeMap(default.toMap()), CanonicalCbor.encodeMap(explicit.toMap()))
        assertEquals(18, explicit.tokenCount)
        for (invalid in s_[null, "", "unknown", "CONTENT", 1, _m["view" j "content"]].view)
            assertFailsWith<IllegalArgumentException> { EnglishCorpusView.parse(invalid) }
        assertEquals(EnglishCorpusView.CONTENT, EnglishCorpusView.parse("content"))
    }

    @Test fun contentCountsKeepGuardWordsAndEveryOriginalSourceSpan() {
        val input = receipts()
        val raw = EnglishCorpus.build(input)
        val content = EnglishCorpus.build(input, EnglishCorpusView.CONTENT.policy())
        assertEquals(18, content.rawTokenCount)
        assertEquals(11, content.tokenCount)
        assertEquals(_s["Tenant", "must", "not", "ready", "unless", "owner", "write", "consent", "Ledger", "credit"],
            content.terms.view.map { it.lemma }.toSet())
        assertEquals(2, content.terms.view.single { it.lemma == "must" }.documentFrequency)
        assertEquals(raw.sentences.size, content.sentences.size)
        for (index in 0 until raw.sentences.size) assertEquals(raw.sentences[index], content.sentences[index])
        for (index in 0 until raw.occurrences.size) {
            val before = raw.occurrences[index]; val after = content.occurrences[index]
            assertEquals(before.copy(included = after.included), after)
        }
        assertTrue(content.occurrences.view.all { it.term !in _s["must", "not", "unless"] || it.included })
        assertTrue(content.occurrences.view.none { it.term in _s["the", "be", "have", "and", "do"] && it.included })
        val hit = content.search(s_["write"])[0]
        assertEquals("written", hit.matches[0].word)
        assertTrue(hit.sentence.quote.contains("have written consent"))
        assertEquals(false, content.toMap()["countsAreEvidenceConfidence"])
        assertNotEquals(raw.matrixCid, content.matrixCid)
    }

    @Test fun selectedMatrixConservesTopicCountsAndReproducesWithReorderedInputs() {
        val input = receipts()
        val content = EnglishCorpus.build(input, EnglishCorpusView.CONTENT.policy())
        val config = EnglishLdaConfig(topics = 2, iterations = 10, seed = 7)
        val model = content.lda(config)
        val repeated = EnglishCorpus.build(s_[input[1], input[0]], EnglishCorpusView.CONTENT.policy()).lda(config)
        assertEquals(model.cid, repeated.cid)
        assertEquals(content.cid, model.corpusCid)
        assertEquals(content.matrixCid, model.matrixCid)
        assertEquals(content.tokenCount, model.topicTotals.view.sum())
        for (document in content.documents.view)
            assertEquals(document.includedTokens, model.documentTopicCounts[document.index].view.sum())
        for (term in content.terms.view)
            assertEquals(term.frequency, model.topicWordCounts.view.sumOf { it[term.index] })
        assertEquals("descriptive-statistics-only", model.toMap()["authority"])
    }

    @Test fun emptyProjectionKeepsRawWordsAndRefusesLdaWithoutFallback() {
        val source = receipt("determiner", s_["The" j ("the" j "DT")])
        val content = EnglishCorpus.build(s_[source], EnglishCorpusView.CONTENT.policy())
        assertEquals(1, content.rawTokenCount)
        assertEquals(0, content.tokenCount)
        assertEquals(0, content.terms.size)
        assertEquals("The", content.sentences[0].quote)
        assertFalse(content.occurrences[0].included)
        assertFailsWith<IllegalArgumentException> { content.lda() }
    }
}
