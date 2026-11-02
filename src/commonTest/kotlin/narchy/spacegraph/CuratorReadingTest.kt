package narchy.spacegraph

import kotlin.test.*

class CuratorReadingTest {
    @Test fun sectionLinkCarriesTheExactBookAndOrdinal() {
        val book = "A & B/party's agreement"
        val page = borg.trikeshed.wiki.WikiSurface.curationSectionPath(book, 3)
        val html = CuratorReading.html(mapOf("book" to book, "ordinal" to 3, "wikiAvailable" to true))
        assertTrue("href='${borg.trikeshed.wiki.WikiSurface.url(page)}'" in html)
        assertTrue("A%2520%2526%2520B%252Fparty%2527s%2520agreement" in html)
        assertFalse("Open this section in the wiki" in CuratorReading.html(mapOf("book" to book)))
        assertFalse("Open this section in the wiki" in CuratorReading.html(mapOf("book" to book, "ordinal" to 3, "wikiAvailable" to false)))
    }

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
