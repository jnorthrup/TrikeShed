package borg.trikeshed.nlp

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentRevision
import kotlin.test.*

/** Source-verified observations are fixtures here; no parser or legal interpretation is simulated. */
class EnglishCorpusTest {
    private fun receipt(text: String, begin: Int = 0, end: Int = text.length, salt: String = "a",
        original: ContentId = ContentId.of(text.encodeToByteArray()), textCid: ContentId = ContentId.of(text.encodeToByteArray())): EnglishObservationReceipt {
        val quote = text.substring(begin, end)
        val tokens = Regex("\\S+").findAll(quote).mapIndexed { i, match ->
            EnglishObservationToken(i + 1, begin + match.range.first, begin + match.range.last + 1,
                match.value, match.value.lowercase(), if (match.value.lowercase() in _s["not", "unless", "may", "must"]) "RB" else "NN")
        }.toList().toSeries()
        val cid = ContentId.of(CanonicalCbor.encodeMap(_m["text" j textCid.value, "begin" j begin, "end" j end, "salt" j salt]))
        return EnglishObservationReceipt(cid, original, textCid, begin, end, text.length,
            DocumentRevision("project", "$salt.txt", original.value, "1-source", 1),
            s_[EnglishObservationSentence(0, begin, end, quote, tokens)])
    }

    @Test fun canonicalMatrixAndExactConcordanceKeepLegalGuards() {
        val first = receipt("Tenant MAY NOT disclose records unless notice arrives", salt = "first")
        val second = receipt("Tenant must retain records", salt = "second")
        val a = EnglishCorpus.build(s_[first, second])
        val b = EnglishCorpus.build(s_[second, first])
        assertEquals(a.cid, b.cid)
        assertEquals(a.matrixCid, b.matrixCid)
        assertEquals(12, a.rawTokenCount)
        assertEquals(a.rawTokenCount, a.tokenCount)
        assertEquals(2, a.terms.view.single { it.lemma == "records" }.frequency)
        assertEquals(2, a.terms.view.single { it.lemma == "records" }.documentFrequency)
        val hit = a.search(s_["disclose"])[0]
        assertEquals(first.receiptCid, hit.sentence.receiptCid)
        assertEquals("Tenant MAY NOT disclose records unless notice arrives", hit.sentence.quote)
        assertEquals("disclose", hit.matches[0].word)
        assertEquals("disclose", hit.sentence.quote.substring(hit.matches[0].begin - hit.sentence.begin, hit.matches[0].end - hit.sentence.begin))
        assertEquals(false, a.toMap()["countsAreEvidenceConfidence"])
        assertEquals(a.cid, ContentId.of(CanonicalCbor.encodeMap(a.toMap())))
    }

    @Test fun disjointCoverageAggregatesOneSourceAndOverlapOrMixedExtractionRefuses() {
        val text = "pay not pay unless notice"
        val a = receipt(text, 0, 11, "a")
        val b = receipt(text, 12, text.length, "b")
        val corpus = EnglishCorpus.build(s_[b, a])
        assertEquals(1, corpus.documents.size)
        assertEquals(5, corpus.tokenCount)
        assertFalse(corpus.documents[0].complete) // The unselected separating space is explicit missing coverage.
        assertEquals(2, corpus.terms.view.single { it.lemma == "pay" }.frequency)
        assertFailsWith<IllegalArgumentException> { EnglishCorpus.build(s_[a, a]) }
        assertFailsWith<IllegalArgumentException> { EnglishCorpus.build(s_[a, receipt(text, 4, text.length, "overlap")]) }
        val changed = receipt("new extracted wording", salt = "changed", original = a.originalCid)
        assertFailsWith<IllegalArgumentException> { EnglishCorpus.build(s_[a, changed]) }
    }

    @Test fun parserCopiesAndForgedTokenSpansCannotInflateCounts() {
        val valid = receipt("pay not pay")
        val sentence = valid.sentences[0]
        val badCopy = valid.copy(sentences = s_[sentence.copy(tokens = s_[sentence.tokens[0].copy(copy = 1), sentence.tokens[1], sentence.tokens[2]])])
        assertFailsWith<IllegalArgumentException> { EnglishCorpus.build(s_[badCopy]) }
        val repeated = valid.copy(sentences = s_[sentence.copy(tokens = s_[sentence.tokens[0], sentence.tokens[0], sentence.tokens[1], sentence.tokens[2]])])
        assertFailsWith<IllegalArgumentException> { EnglishCorpus.build(s_[repeated]) }
        val forged = valid.copy(sentences = s_[sentence.copy(tokens = s_[sentence.tokens[0].copy(word = "received"), sentence.tokens[1], sentence.tokens[2]])])
        assertFailsWith<IllegalArgumentException> { EnglishCorpus.build(s_[forged]) }
        assertEquals(3, EnglishCorpus.build(s_[valid]).tokenCount)
    }

    @Test fun explicitPolicyKeepsMarkersAndFrozenInputCannotChangeStatistics() {
        val source = receipt("must not pay unless notice")
        val stop = mutableSetOf("not", "unless", "notice")
        val policy = EnglishCorpusPolicy(stopWords = stop, posPrefixes = _s["NN"])
        val corpus = EnglishCorpus.build(s_[source], policy)
        val identity = corpus.cid
        assertEquals(4, corpus.tokenCount)
        assertTrue(corpus.terms.view.any { it.lemma == "not" })
        assertFalse(corpus.terms.view.any { it.lemma == "notice" })
        assertEquals(5, corpus.occurrences.size)
        stop.add("pay")
        assertEquals(identity, corpus.cid)
        assertEquals(identity, ContentId.of(CanonicalCbor.encodeMap(corpus.toMap())))
        val unprotected = EnglishCorpus.build(s_[source], policy.copy(protectLegalMarkers = false))
        assertEquals(0, unprotected.tokenCount)
    }

    @Test fun aggregateTokenAndVocabularyBudgetsRefuseAcrossReceipts() {
        val tooManyTokens = mutableListOf<EnglishObservationReceipt>()
        for (document in 0..20) {
            val words = mutableListOf<String>()
            repeat(1000) { words.add("pay") }; words.add("doc$document")
            tooManyTokens.add(receipt(words.joinToString(" "), salt = "tokens-$document"))
        }
        val tokenFailure = assertFailsWith<IllegalArgumentException> { EnglishCorpus.build(tooManyTokens.toSeries()) }
        assertTrue(tokenFailure.message.orEmpty().contains("token", true))
        val tooManyTerms = mutableListOf<EnglishObservationReceipt>()
        for (document in 0..2) {
            val text = (document * 1500 until (document + 1) * 1500).joinToString(" ") { "term$it" }
            tooManyTerms.add(receipt(text, salt = "vocabulary-$document"))
        }
        val vocabularyFailure = assertFailsWith<IllegalArgumentException> { EnglishCorpus.build(tooManyTerms.toSeries()) }
        assertTrue(vocabularyFailure.message.orEmpty().contains("4096"))
    }

    @Test fun sourceAndTokenBudgetsRefuseBeforeTopicWork() {
        val source = receipt("pay")
        assertFailsWith<IllegalArgumentException> { EnglishCorpus.build(emptySeriesOf()) }
        assertFailsWith<IllegalArgumentException> { EnglishCorpus.build((0..256).toSeries() α { source.copy(receiptCid = ContentId.of("$it".encodeToByteArray())) }) }
        val huge = source.copy(sentences = s_[source.sentences[0].copy(tokens = 20_001 j { source.sentences[0].tokens[0] })])
        assertFailsWith<IllegalArgumentException> { EnglishCorpus.build(s_[huge]) }
        val wrongSelection = source.copy(selection = source.selection?.copy(cid = ContentId.of("wrong".encodeToByteArray()).value))
        assertFailsWith<IllegalArgumentException> { EnglishCorpus.build(s_[wrongSelection]) }
    }
}
