package narchy.spacegraph

import kotlin.test.*

class CuratorReadingTest {
    @Test fun bertReceiptsAreNotRenderedAsJevJudgments() {
        val receipt = mapOf("model" to "bert-test", "status" to "Supported", "neighbors" to
            listOf(mapOf("source" to "label's-source", "table" to "retained/0.json", "sense" to "Organization", "cosine" to 0.9)))
        val sentence = mapOf("text" to "The bank holds the account.", "concepts" to listOf(
            mapOf("lemma" to "bank", "class" to "Organization", "read" to "LandArea", "by" to "bert", "bert" to receipt)))
        val html = CuratorReading.html(mapOf("book" to "Reader's book", "sentences" to listOf(sentence)))
        assertTrue("BERT · 7/7 labels" in html)
        assertTrue("retained/0.json" in html)
        assertTrue("label&#39;s-source" in html)
        assertTrue("data-book='Reader&#39;s book'" in html)
        assertFalse("Jev 0%" in html)
    }
}
