package org.bereft.ingest

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.size
import borg.trikeshed.lib.j
import borg.trikeshed.lib.α
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IngestResultTest {

    @Test
    fun `char Series content is accessible via index without copy`() {
        val text = "hello forge"
        val result = IngestResult(
            sourcePath = "/tmp/x.txt",
            mediaType = "text/plain",
            detectedFormat = "text",
            extractedContent = charSeries(text),
            projections = setOf(IngestProjection.TEXT_EXTRACTION),
        )
        // PRELOAD: index the Series directly, no materialization.
        assertEquals('h', result[0])
        assertEquals('o', result[4])
        assertEquals(text.length, result.contentSize)
    }

    @Test
    fun `IngestProjection sets compose`() {
        val a = setOf(IngestProjection.TEXT_EXTRACTION, IngestProjection.METADATA)
        val b = IngestProjection.TEXT_ONLY
        assertEquals(a, b)
        assertTrue(IngestProjection.ALL.size == IngestProjection.entries.size)
    }

    @Test
    fun `alpha projection over char Series maps without copy`() {
        val s: Series<Char> = charSeries("abc")
        // PRELOAD α projection: upper-case each char lazily.
        val upper: Series<Char> = s α { it.uppercaseChar() }
        assertEquals(3, upper.size)
        assertEquals('A', upper.b(0))
        assertEquals('C', upper.b(2))
    }
}
