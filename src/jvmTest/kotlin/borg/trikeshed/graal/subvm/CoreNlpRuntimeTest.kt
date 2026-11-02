package borg.trikeshed.graal.subvm

import borg.trikeshed.lib.*
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.narsese.DocumentCuratorCodec
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CoreNlpRuntimeTest {
    @Test
    fun profilesKeepFullDefaultAndSelectOnlyTheKbpAnnotator() {
        CoreNlpRuntime().use { assertEquals(CoreNlpRuntime.Profile.FULL, it.profile) }
        val full = CoreNlpRuntime.Profile.FULL.configuration
        val reduced = CoreNlpRuntime.Profile.NO_KBP.configuration
        assertEquals("tokenize,ssplit,pos,lemma,depparse,ner,natlog,openie,kbp", full["annotators"])
        assertEquals("100", full["kbp.maxlen"])
        assertEquals("1", full["threads"])
        assertEquals(full - "annotators", reduced - "annotators")
        assertEquals("tokenize,ssplit,pos,lemma,depparse,ner,natlog,openie", reduced["annotators"])
        assertFalse(CoreNlpRuntime.Profile.FULL.id == CoreNlpRuntime.Profile.NO_KBP.id)
    }

    @Test
    fun profileCachesAndLifecycleStaySeparateAndReceiptsRetainProvenance() {
        val text = "Acme must pay Beta."
        val full = CoreNlpRuntime()
        try {
            CoreNlpRuntime(CoreNlpRuntime.Profile.NO_KBP).use { reduced ->
                val complete = full.analyze(text)
                val partial = reduced.analyze(text)
                assertNotSame(complete, partial)
                assertSame(complete, full.analyze(text))
                assertSame(partial, reduced.analyze(text))
                assertEquals("full-v1", complete.metadata?.runtime?.get("profile"))
                assertEquals("no-kbp-v1", partial.metadata?.runtime?.get("profile"))
                assertEquals(CoreNlpRuntime.Profile.NO_KBP.configuration, partial.metadata?.configuration)
                assertTrue(partial.metadata?.runtime?.get("manifestCid")?.startsWith("sha256:") == true)
                for (sentence in partial.sentences.view) {
                    assertTrue(sentence.kbp.isEmpty())
                    for (token in sentence.tokens.view)
                        assertEquals(token.word, text.substring(token.begin, token.end))
                    assertTrue(sentence.dependencies.size > 0)
                }
                val receipt = CanonicalCbor.decodeMap(CanonicalCbor.encodeMap(DocumentCuratorCodec.nlp(partial)))
                val metadata = receipt["metadata"] as Map<*, *>
                assertEquals(partial.metadata?.configuration, metadata["configuration"])
                assertEquals(partial.metadata?.runtime, metadata["runtime"])
                full.close()
                assertSame(partial, reduced.analyze(text))
                assertEquals("Beta", reduced.analyze("Beta must pay Acme.").sentences[0].tokens[0].word)
            }
        } finally { full.close() }
    }

    @Test
    fun parseCacheAccountsReplacementsAndEvictsUntilWithinBudget() {
        fun document(text: String) = NlpDocument(text, emptySeriesOf())
        val probe = NlpParseCache(Long.MAX_VALUE)
        probe.put("a", document("a"))
        val weight = probe.bytes
        val cache = NlpParseCache(weight * 3)
        for (text in listOf("a", "b", "c")) cache.put(text, document(text))
        val a = cache["a"]
        cache.put("a", checkNotNull(a))
        assertEquals(weight * 3, cache.bytes)
        val larger = "x".repeat(86)
        cache.put(larger, document(larger))
        assertNull(cache["b"])
        assertNull(cache["c"])
        assertSame(a, cache["a"])
        assertEquals(2, cache.size)
        assertTrue(cache.bytes <= weight * 3)
        cache.put("oversized", document("x".repeat(10_000)))
        assertNull(cache["oversized"])
        assertEquals(2, cache.size)
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0L, cache.bytes)
    }

    @Test
    fun parseCacheBudgetsAnnotationsAndEntryCount() {
        val cache = NlpParseCache(4096, entries = 2)
        for (text in listOf("a", "b", "c")) cache.put(text, NlpDocument(text, emptySeriesOf()))
        assertEquals(2, cache.size)
        assertNull(cache["a"])
        val token = NlpToken(1, 0, 1, "x", "x", "NN", "O")
        val expanded = NlpDocument("x", s_[NlpSentence(0, 0, 1, 100 j { token }, emptySeriesOf())])
        cache.put("x", expanded)
        assertNull(cache["x"])
        assertEquals(2, cache.size)
        assertTrue(cache.bytes <= 4096)
    }

    @Test
    fun closedReaderRejectsFurtherReadsAndClosesIdempotently() {
        val first = CoreNlpRuntime()
        CoreNlpRuntime().use {
            first.close()
            first.close()
            assertFailsWith<IllegalStateException> { first.analyze("Acme pays Beta.") }
        }
    }

    @Test
    fun managedReaderPreservesOriginalTextSpansAndDependencyEndpoints() {
        val text = "Acme pays Beta. Caf\u00e9 holds the \"account\". Use \u00bd cup and mix &amp; well."
        CoreNlpRuntime().use { reader ->
            val parsed = reader.analyze(text)
            assertEquals(text, parsed.text)
            assertEquals(3, parsed.sentences.size)
            for (i in 0 until parsed.sentences.size) {
                val sentence = parsed.sentences[i]
                assertEquals(i, sentence.index)
                assertTrue(sentence.end > sentence.begin)
                val indices = HashSet<Int>()
                for (k in 0 until sentence.tokens.size) {
                    val token = sentence.tokens[k]
                    assertTrue(indices.add(token.index))
                    assertTrue(token.begin >= sentence.begin && token.end <= sentence.end)
                    assertEquals(token.word, text.substring(token.begin, token.end))
                }
                assertTrue(sentence.dependencies.size > 0)
                for (k in 0 until sentence.dependencies.size) {
                    val dependency = sentence.dependencies[k]
                    assertTrue(dependency.governor in indices || (dependency.governor == 0 && dependency.relation == "root"))
                    assertTrue(dependency.dependent in indices)
                    assertTrue(dependency.relation.isNotBlank())
                }
            }
            val cafe = parsed.sentences[1].tokens[0]
            assertEquals("Caf\u00e9", text.substring(cafe.begin, cafe.end))
            val normalizedTokens = parsed.sentences[2].tokens.view
            assertTrue(normalizedTokens.any { it.word == "\u00bd" })
            assertTrue(normalizedTokens.any { it.word == "&amp;" })
        }
    }

    @Test
    fun managedPipelineDoesNotExposeCoreNlpOnTheApplicationLoader() {
        assertFalse(runCatching {
            javaClass.classLoader.loadClass("edu.stanford.nlp.pipeline.StanfordCoreNLP")
        }.isSuccess)
        val original = Thread.currentThread().contextClassLoader
        CoreNlpRuntime().use { reader ->
            assertEquals("Acme", reader.analyze("Acme pays Beta.").sentences[0].tokens[0].word)
        }
        assertTrue(Thread.currentThread().contextClassLoader === original)
    }
}
