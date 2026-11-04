package borg.trikeshed.nlp

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentRevision
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.*

/** Statistical synthetic fixtures only; topics do not establish comprehension or legal authority. */
class EnglishLdaTest {
    private fun observation(label: String, words: Series<String>): EnglishObservationReceipt {
        val quote = words.view.joinToString(" ")
        val content = ContentId.of(quote.encodeToByteArray())
        val tokens = mutableListOf<EnglishObservationToken>()
        var begin = 0
        for ((index, word) in words.view.withIndex()) {
            tokens.add(EnglishObservationToken(index + 1, begin, begin + word.length, word, word, "NN"))
            begin += word.length + 1
        }
        return EnglishObservationReceipt(ContentId.of("$label:${content.value}".encodeToByteArray()), content, content, 0, quote.length, quote.length,
            DocumentRevision("synthetic", "$label.txt", content.value, "1-fixture", 1),
            s_[EnglishObservationSentence(0, 0, quote.length, quote, tokens.toSeries())])
    }
    private fun corpus(): Series<EnglishObservationReceipt> {
        val groups = s_[s_["rent", "lease", "tenant", "occupy"], s_["invoice", "ledger", "debit", "credit"]]
        val records = mutableListOf<EnglishObservationReceipt>()
        for (group in 0..1) for (document in 0..3) {
            val words = mutableListOf<String>()
            repeat(4 + document) { repeat(4) { word -> words.add(groups[group][(word + document) % 4]) } }
            words.add("record"); words.add("record")
            records.add(observation("$group-$document", words.toSeries()))
        }
        return records.toSeries()
    }

    @Test fun countMatricesConserveEveryDocumentTermAndToken() {
        val matrix = EnglishCorpus.build(corpus())
        val model = matrix.lda(EnglishLdaConfig(topics = 2, iterations = 80, seed = 7))
        assertEquals(matrix.matrixCid, model.matrixCid)
        assertEquals(matrix.cid, model.corpusCid)
        assertEquals(matrix.tokenCount, model.topicTotals.view.sum())
        for (document in matrix.documents.view) {
            assertEquals(document.includedTokens, model.documentTopicCounts[document.index].view.sum())
            assertTrue(abs(model.documentProbabilities(document.index).view.sum() - 1.0) < 1e-12)
        }
        for (term in matrix.terms.view) assertEquals(term.frequency, model.topicWordCounts.view.sumOf { it[term.index] })
        for (topic in 0 until model.config.topics) {
            assertEquals(model.topicTotals[topic], model.topicWordCounts[topic].view.sum())
            assertTrue(abs(model.vocabulary.view.sumOf { requireNotNull(model.wordProbability(topic, it)) } - 1.0) < 1e-12)
        }
        assertEquals(matrix.tokenCount.toLong() * 80 * 2, model.conditionalWeights)
        assertEquals(false, model.toMap()["countsAreEvidenceConfidence"])
        assertEquals("descriptive-statistics-only", model.toMap()["authority"])
        assertEquals(model.cid, ContentId.of(CanonicalCbor.encodeMap(model.toMap())))
    }

    @Test fun seedAndCanonicalInputOrderReproduceTheSameModel() {
        val receipts = corpus()
        val a = EnglishCorpus.build(receipts).lda(EnglishLdaConfig(topics = 2, iterations = 40, seed = 23))
        val reversed = receipts.size j { index: Int -> receipts[receipts.size - index - 1] }
        val b = EnglishCorpus.build(reversed).lda(a.config)
        assertEquals(a.cid, b.cid)
        assertTrue(CanonicalCbor.encodeMap(a.toMap()).contentEquals(CanonicalCbor.encodeMap(b.toMap())))
        val otherSeed = EnglishCorpus.build(receipts).lda(a.config.copy(seed = 24))
        assertNotEquals(a.cid, otherSeed.cid) // Configuration is part of identity, even if a separated corpus reaches the same counts.
        assertNull(a.wordProbability(0, "unseen-held-out-vocabulary"))
    }

    @Test fun twoSeparatedVocabularyGroupsPredictHeldOutGroupWords() {
        val model = EnglishCorpus.build(corpus()).lda(EnglishLdaConfig(topics = 2, iterations = 100, seed = 7))
        val housing = s_["rent", "lease", "tenant", "occupy"]
        val accounting = s_["invoice", "ledger", "debit", "credit"]
        fun dominant(words: Series<String>): Int = (0 until model.config.topics).maxBy { topic -> words.view.sumOf { requireNotNull(model.wordProbability(topic, it)) } }
        val housingTopic = dominant(housing); val accountingTopic = dominant(accounting)
        assertNotEquals(housingTopic, accountingTopic)
        // These documents were not training rows. This is a bounded single-topic predictive comparison,
        // not a universal held-out perplexity result or an interpretation of their legal meaning.
        fun predict(words: Series<String>): Int = (0 until model.config.topics).maxBy { topic ->
            words.view.sumOf { ln(requireNotNull(model.wordProbability(topic, it))) }
        }
        assertEquals(housingTopic, predict(s_["tenant", "rent", "lease"]))
        assertEquals(accountingTopic, predict(s_["debit", "ledger", "credit"]))
        assertTrue(model.topicTerms(housingTopic, 4).view.count { it["lemma"] in housing } >= 3)
        assertTrue(model.topicTerms(accountingTopic, 4).view.count { it["lemma"] in accounting } >= 3)
    }

    @Test fun legalMarkersRemainInTheMatrixAndPriorsAreExplicit() {
        val source = observation("guards", s_["must", "not", "disclose", "unless", "notice"])
        val model = EnglishCorpus.build(s_[source]).lda(EnglishLdaConfig(topics = 2, iterations = 10, seed = 0))
        assertTrue("not" in model.vocabulary && "unless" in model.vocabulary && "must" in model.vocabulary)
        assertEquals(.1, model.config.alpha)
        assertEquals(.01, model.config.beta)
        val filtered = EnglishCorpus.build(s_[source], EnglishCorpusPolicy(stopWords = _s["disclose", "notice"]))
        assertEquals(3, filtered.tokenCount)
        val empty = EnglishCorpus.build(s_[source], EnglishCorpusPolicy(posPrefixes = _s["ZZ"], protectLegalMarkers = false))
        assertFailsWith<IllegalArgumentException> { empty.lda() }
    }

    @Test fun samplerParametersAndWorkBudgetRefuseBeforeSampling() {
        val small = EnglishCorpus.build(s_[observation("small", s_["rent", "lease"])])
        for (invalid in s_[EnglishLdaConfig(topics = 17), EnglishLdaConfig(iterations = 101),
            EnglishLdaConfig(alpha = 0.0), EnglishLdaConfig(beta = Double.NaN), EnglishLdaConfig(alpha = Double.POSITIVE_INFINITY)].view)
            assertFailsWith<IllegalArgumentException> { small.lda(invalid) }
        val records = mutableListOf<EnglishObservationReceipt>()
        for (document in 0..7) {
            val words = mutableListOf<String>()
            repeat(2000) { words.add("pay") }; words.add("doc$document")
            records.add(observation("work-$document", words.toSeries()))
        }
        val large = EnglishCorpus.build(records.toSeries())
        assertEquals(16_008, large.tokenCount)
        assertFailsWith<IllegalArgumentException> { large.lda(EnglishLdaConfig(topics = 16, iterations = 100)) }
    }
}
