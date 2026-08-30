package borg.trikeshed.lcnc

import borg.trikeshed.lib.get
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The vm.corenlp / vm.corenlp.extract legos executed for real: the JVM-facet
 * GraalJS guest calls the bundled Stanford CoreNLP classes through the
 * hostTrusted door (GuestBounds.JVM — the deliberate OWN-trust exception).
 *
 * (The paragraph above is the original, by jnorthrup in `wip` 49c94c868 — the
 * commit that converted these legos from Groovy to GraalJS and added this test.
 * It is kept because it states the design as it stood, and because the delta
 * below only makes sense against it.)
 *
 * SINCE 053e79ff1 the "bundled" half of that is no longer true. CoreNLP is not on
 * this JVM's classpath at all; it is mounted from the guest module
 * `utils/subvm/corenlp` (VmSpec.module → Context.Builder.hostClassLoader), and
 * allowHostClassLookup narrows from hostTrusted to "resolvable in that module".
 * The hostTrusted door itself is unchanged and still governs any facet with no
 * module mounted. `-Xmx3g` in build.gradle.kts, added by the same wip commit,
 * stays load-bearing: the guest classloader lives in THIS JVM, so moving CoreNLP
 * off the classpath did not move its models off this heap.
 *
 * These are not parse-only checks: each test asserts on the JSON the guest
 * actually printed, so a broken script (the old Groovy-flavored ones never
 * ran), a missing annotator model, or a sandbox regression all fail here.
 */
class CoreNlpLegoExecutionTest {

    private val text = "Barack Obama was born in Hawaii. Microsoft cited Smith v. Jones, 384 U.S. 436."

    @Test
    fun corenlpEmitsWordTagLemmaLines() = runTest {
        val host = borg.trikeshed.vm.HypervisorVmHost()
        val runner = SubVmLegos.corenlp(host)
        val node = LcncNode("n1", SubVmLegos.CORENLP, params = mapOf("text" to text))
        val out = runner.run(node, emptyMap())
        val lines = (out["text"] as? String)?.lines()?.filter { it.isNotBlank() } ?: emptyList()
        assertTrue(lines.isNotEmpty(), "corenlp must emit token lines")
        // The guest prints word\ttag\tlemma; the lego reads it back off the
        // VM's xterm screen, where tabs render as aligned columns. Parse the
        // columns by whitespace-split (tokens contain no internal spaces).
        for (line in lines) {
            val cols = line.trim().split(Regex("\\s+"))
            assertTrue(cols.size >= 3, "token line must carry word/tag/lemma columns: '$line'")
            assertTrue(cols[0].isNotBlank(), "word column must be non-blank")
        }
        assertTrue(lines.any { it.trim().startsWith("Barack") }, "first token must appear: ${lines.take(3)}")
        host.close()
    }

    @Test
    fun corenlpExtractEmitsSentencesWithTokensDepsAndEntities() = runTest {
        val host = borg.trikeshed.vm.HypervisorVmHost()
        val runner = SubVmLegos.corenlpExtract(host)
        val node = LcncNode("n2", SubVmLegos.CORENLP_EXTRACT, params = mapOf("text" to text))
        val out = runner.run(node, emptyMap())
        val raw = out["text"] as? String ?: error("corenlp.extract must emit text")
        val sentences = JsonSupport.parse(raw) as? List<*> ?: error("output must be a JSON array: $raw")
        assertEquals(2, sentences.size, "two input sentences: $raw")

        val s1 = sentences[0] as Map<*, *>
        val tokens = s1["tokens"] as? List<*> ?: error("sentence must carry tokens")
        assertTrue(tokens.size > 3, "sentence 1 must have tokens")
        val first = tokens[0] as Map<*, *>
        assertEquals("Barack", first["word"])
        // CoreNLP lowercases proper-noun lemmas; assert the lemma is the
        // case-folded word, not the word itself.
        assertEquals("barack", first["lemma"]?.toString()?.lowercase())

        // Dependency edges are present with governor/dependent/relation.
        val deps = s1["deps"] as? List<*> ?: error("sentence must carry deps")
        assertTrue(deps.isNotEmpty(), "depparse must yield edges")
        val anyEdge = deps[0] as Map<*, *>
        assertTrue(anyEdge.containsKey("gov") && anyEdge.containsKey("dep") && anyEdge.containsKey("rel"))

        // NER fires: Barack Obama is a contiguous PERSON run with recovered text.
        val entities = s1["entities"] as? List<*> ?: error("NER must find entities in sentence 1")
        @Suppress("UNCHECKED_CAST")
        val person = entities.mapNotNull { it as? Map<String, Any?> }
            .firstOrNull { it["ner"] == "PERSON" }
        assertTrue(person != null, "Barack Obama must be tagged PERSON: $entities")
        assertEquals("Barack Obama", person["text"])
        assertEquals(1.0, (person!!["begin"] as Number).toDouble(), "PERSON run starts at token 1")
        assertEquals(2.0, (person["end"] as Number).toDouble(), "PERSON run ends at token 2")
        host.close()
    }

    @Test
    fun corenlpExtractHandlesHostileTextWithoutBreakingTheScript() = runTest {
        // The old scripts spliced this text straight into the generated source —
        // quotes and backslashes there were a syntax error before the pipeline
        // ever ran. GUEST_TEXT binding must swallow it.
        val hostile = """He said "don't \ escape" — then cited 42 U.S.C. § 1983."""
        val host = borg.trikeshed.vm.HypervisorVmHost()
        val runner = SubVmLegos.corenlpExtract(host)
        val node = LcncNode("n3", SubVmLegos.CORENLP_EXTRACT, params = mapOf("text" to hostile))
        val out = runner.run(node, emptyMap())
        val raw = out["text"] as? String ?: error("hostile text must still produce JSON")
        val sentences = JsonSupport.parse(raw) as? List<*>
        assertTrue(sentences != null && !sentences.isEmpty(), "hostile text must parse to a JSON array: $raw")
        host.close()
    }
}
