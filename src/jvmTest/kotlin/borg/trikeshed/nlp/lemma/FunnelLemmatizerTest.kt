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
        // regular -ed / -ing / -s evidence, each rule supported ≥ 2 times
        LemmaObservation("walked", "he", "home", "walk", weight = 3),
        LemmaObservation("jumped", "she", "high", "jump", weight = 3),
        LemmaObservation("walking", "be", "home", "walk", weight = 2),
        LemmaObservation("jumping", "be", "high", "jump", weight = 2),
        LemmaObservation("cats", "the", "run", "cat", weight = 2),
        LemmaObservation("flies", "the", "buzz", "fly", weight = 2),
        LemmaObservation("tries", "he", "hard", "try", weight = 2),
        // a function word that the "-s" rule would mangle: whole-word exception must win
        LemmaObservation("his", "on", "way", "he"),
    )

    private val lemmatizer = FunnelLemmatizer.freeze(corpus, seed = 42L)

    @Test
    fun suffixRulesAreDerivedByCommonPrefix() {
        assertEquals(SuffixRule(4, ""), SuffixRule.derive("running", "run"))
        assertEquals(SuffixRule(3, "y"), SuffixRule.derive("flies", "fly"))
        assertEquals(SuffixRule(2, "un"), SuffixRule.derive("ran", "run"))    // irregulars still share a prefix
        assertEquals(SuffixRule(2, "be"), SuffixRule.derive("is", "be"))      // none shared: whole-word rule
        assertEquals(SuffixRule(0, ""), SuffixRule.derive("dog", "dog"))
        assertEquals("fly", SuffixRule(3, "y").apply("flies"))
    }

    @Test
    fun contextDisambiguatesSawViaWholeWordLinkedRung() {
        assertEquals(listOf("i", "see", "the", "dog", "."), lemmatizer.lemmatize(listOf("I", "saw", "the", "dog", ".")))
        assertEquals(listOf("the", "saw", "be", "sharp", "."), lemmatizer.lemmatize(listOf("the", "saw", "is", "sharp", ".")))
        val verb = lemmatizer.resolve("saw", "i", "the")
        assertEquals("see", verb.lemma)
        assertEquals(MatchGrade.LINKED, verb.grade)
        assertEquals(3, verb.suffixLength)   // whole word
    }

    @Test
    fun unseenRegularFormsGeneralizeThroughSuffixRules() {
        // never observed; must come from the "-ed"/"-ing"/"-s"/"-ies" rules with suffix length < word length
        val talked = lemmatizer.resolve("talked", null, null)
        assertEquals("talk", talked.lemma)
        assertTrue(talked.suffixLength < "talked".length)
        assertEquals("talk", lemmatizer.resolveContentOnly("talking"))
        assertEquals("hat", lemmatizer.resolveContentOnly("hats"))
        assertEquals("cry", lemmatizer.resolveContentOnly("cries"))
    }

    @Test
    fun irregularsAreWholeWordExceptionsAndNeverGeneralize() {
        val ran = lemmatizer.resolve("ran", "dog", ".")
        assertEquals("run", ran.lemma)
        assertEquals("ran".length, ran.suffixLength)                 // whole-word exception
        assertEquals(SuffixRule.exception("ran", "run"), ran.rule)
        assertEquals("man", lemmatizer.resolveContentOnly("man"))    // ablaut did not become an "-an" rule
        assertEquals("be", lemmatizer.resolve("is", "saw", "sharp").lemma)
        // pure suffix stripping would say "this"→"thi"; that is the accepted approximation for UNSEEN words…
        assertEquals("thi", lemmatizer.resolveContentOnly("this"))
        // …while a seen function word is a whole-word exception and beats the "-s" rule
        assertEquals("he", lemmatizer.resolveContentOnly("his"))
    }

    @Test
    fun unseenWordWithNoApplicableRuleFallsThroughToIdentity() {
        val r = lemmatizer.resolve("Zebra", "the", ".")
        assertEquals("zebra", r.lemma)
        assertNull(r.grade)
        assertNull(r.rule)
    }

    @Test
    fun singletonSuffixEvidenceIsNotARule() {
        // "sharp" alone would suggest "-p"→"" nothing; more to the point "dogs"(1 vote) alone cannot make "s"→"" — cats(2) does.
        val lone = FunnelLemmatizer.freeze(listOf(LemmaObservation("dogs", null, null, "dog")), seed = 1L)
        assertEquals("hats", lone.resolveContentOnly("hats"))   // minSupport=2 rejects the single vote
        assertEquals("dog", lone.resolveContentOnly("dogs"))    // but the whole-word rung accepts it
    }

    @Test
    fun freezingIsDeterministic() {
        val again = FunnelLemmatizer.freeze(corpus, seed = 42L)
        val words = listOf("I", "saw", "the", "dogs", "ran", "and", "talked", ".")
        assertEquals(lemmatizer.lemmatize(words), again.lemmatize(words))
        assertEquals(lemmatizer.linkedContexts, again.linkedContexts)
        assertEquals(lemmatizer.vocabulary, again.vocabulary)
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
        assertEquals(1, stats.paragraphsSkipped)
        assertEquals(6, stats.sentences)
        assertEquals(4, stats.sentencesSkipped)
        assertEquals(8, stats.tokensLemmatized)
        assertTrue(stats.tokenReduction > 0.6)
        assertTrue(cache.generationCount >= 1)
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
