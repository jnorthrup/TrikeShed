package borg.trikeshed.lcnc.reactor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import borg.trikeshed.lcnc.isam.LcncBlock

class MarkdownIngestCodecTest {
    @Test
    fun testMarkdownParsing() = runTest {
        val codec = MarkdownIngestCodec()
        val text = """
            # Header
            This is a paragraph.
        """.trimIndent()
        
        val series = codec.decodeText(text, IngestFormat.MARKDOWN)
        
        assertEquals(2, series.a)
        val block1 = series.b(0) as LcncBlock
        val block2 = series.b(1) as LcncBlock
        
        assertEquals("heading_1", block1.type)
        assertEquals("Header", block1.content)
        
        assertEquals("paragraph", block2.type)
        assertEquals("This is a paragraph.", block2.content)
    }
}
