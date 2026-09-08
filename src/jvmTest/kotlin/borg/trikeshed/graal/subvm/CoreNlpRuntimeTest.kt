package borg.trikeshed.graal.subvm

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreNlpRuntimeTest {
    @Test
    fun managedReaderPreservesOriginalTextSpansAndDependencyEndpoints() {
        val text = "Acme pays Beta. Caf\u00e9 holds the \"account\"."
        CoreNlpRuntime().use { reader ->
            val parsed = reader.analyze(text)
            assertEquals(text, parsed.text)
            assertEquals(2, parsed.sentences.size)
            for (i in 0 until parsed.sentences.size) {
                val sentence = parsed.sentences[i]
                assertEquals(i, sentence.index)
                assertTrue(sentence.end > sentence.begin)
                val indices = HashSet<Int>()
                for (k in 0 until sentence.tokens.size) {
                    val token = sentence.tokens[k]
                    assertTrue(indices.add(token.index))
                    assertTrue(token.begin >= sentence.begin && token.end <= sentence.end)
                    assertTrue(text.substring(token.begin, token.end).isNotEmpty())
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
