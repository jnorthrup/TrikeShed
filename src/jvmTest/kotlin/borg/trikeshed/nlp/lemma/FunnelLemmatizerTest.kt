package borg.trikeshed.nlp.lemma

import borg.trikeshed.cas.MatchGrade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FunnelLemmatizerTest {

    /** A reference corpus in the shape CoreNLP would produce: word + neighbor lemmas + lemma. */
    private val corpus = listOf(
        // "I saw the dog ." — saw is the verb
        LemmaObservation("I", null, "see", "I"),
        LemmaObservation("saw", "i", "the", "see"),
        LemmaObservation("the", "see", "dog", "the"),
        LemmaObservation("dog", "the", ".", "dog"),
        LemmaObservation(".", "dog", null, "."),
        // "the saw is sharp ." — saw is the noun
        LemmaObservation("the", null, "saw", "the"),
        LemmaObservation("saw", "the", "be", "saw"),
        LemmaObservation("is", "saw", "sharp", "be"),
        LemmaObservation("sharp", "be", ".", "sharp"),
        LemmaObservation(".", "sharp", null, "."),
        // "dogs ran ." — plural + irregular past
        LemmaObservation("dogs", null, "run", "dog"),
        LemmaObservation("ran", "dog", ".", "run"),
        LemmaObservation(".", "run", null, "."),
    )

    private val lemmatizer = FunnelLemmatizer.freeze(corpus, seed = 42L)

    @Test
    fun contextDisambiguatesSawViaLinkedRung() {
        assertEquals(listOf("i", "see", "the", "dog", "."), lemmatizer.lemmatize(listOf("I", "saw", "the", "dog", ".")))
        assertEquals(listOf("the", "saw", "be", "sharp", "."), lemmatizer.lemmatize(listOf("the", "saw", "is", "sharp", ".")))
        val verb = lemmatizer.resolve("saw", "i", "the")
        assertEquals("see", verb.lemma)
        assertEquals(MatchGrade.LINKED, verb.grade)
    }

    @Test
    fun unseenWordFallsThroughToIdentityWithNullGrade() {
        val r = lemmatizer.resolve("Zebra", "the", ".")
        assertEquals("zebra", r.lemma)
        assertNull(r.grade)
    }

    @Test
    fun contentOnlyRungHoldsMajorityLemma() {
        // "saw" seen once as see, once as saw → first-seen wins the tie deterministically
        val r = lemmatizer.resolve("saw", "zzz", "yyy")
        assertEquals(MatchGrade.CONTENT_ONLY, r.grade)
        assertTrue(r.lemma == "see" || r.lemma == "saw")
        // distinct normalized surface forms: i, saw, the, dog, ., is, sharp, dogs, ran
        assertEquals(9, lemmatizer.vocabulary)
    }

    @Test
    fun freezingIsDeterministic() {
        val again = FunnelLemmatizer.freeze(corpus, seed = 42L)
        val words = listOf("I", "saw", "the", "dogs", "ran", ".")
        assertEquals(lemmatizer.lemmatize(words), again.lemmatize(words))
        assertEquals(lemmatizer.linkedContexts, again.linkedContexts)
    }

    @Test
    fun fractalCacheSkipsRepeatedSentencesAndParagraphs() {
        val s1 = listOf("I", "saw", "the", "dog", ".")
        val s2 = listOf("dogs", "ran", ".")
        val p1 = listOf(s1, s2)
        val doc = listOf(p1, listOf(s2, s1), p1)   // p1 repeats wholesale; s1/s2 repeat across paragraphs
        val cache = ResidualLemmaCache(freezeAt = 2)
        val (lemmas, stats) = lemmatizer.lemmatizeDocument(doc, cache)

        assertEquals(listOf("i", "see", "the", "dog", "."), lemmas[0][0])
        assertEquals(lemmas[0], lemmas[2])
        assertEquals(3, stats.paragraphs)
        assertEquals(1, stats.paragraphsSkipped)          // third paragraph served by paragraph CID
        assertEquals(6, stats.sentences)
        assertEquals(4, stats.sentencesSkipped)           // 2 via paragraph skip + 2 via sentence CIDs
        assertEquals(8, stats.tokensLemmatized)           // only s1 and s2 once: 5 + 3
        assertTrue(stats.tokenReduction > 0.6)
        assertTrue(cache.generationCount >= 1)            // staging froze at least once (freezeAt = 2)
    }

    @Test
    fun spineCidsAreContentAddressedAcrossScales() {
        val a = LemmaSpines.sentenceCid(listOf("I", "saw", "the", "dog"))
        val b = LemmaSpines.sentenceCid(listOf("i", " saw", "THE", "dog "))
        val c = LemmaSpines.sentenceCid(listOf("the", "saw", "is", "sharp"))
        assertEquals(a, b)
        assertTrue(a != c)
        assertEquals(LemmaSpines.paragraphCid(listOf(a, c)), LemmaSpines.paragraphCid(listOf(b, c)))
        assertTrue(LemmaSpines.paragraphCid(listOf(a, c)) != LemmaSpines.paragraphCid(listOf(c, a)))
    }
}
