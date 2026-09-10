package borg.trikeshed.lcnc

import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.json.JsonSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentCurationLegosTest {
    @Test
    fun sourceDecodingPreservesTextAndBinaryBytes() {
        val text = "\u0000λ source"
        val decoded = DocumentCurationLegos.content(mapOf("name" to "source.txt", "text" to text))
        assertContentEquals(text.encodeToByteArray(), decoded.bytes)
        val binary = byteArrayOf(0, -1, 10)
        val encoded = java.util.Base64.getEncoder().encodeToString(binary)
        assertContentEquals(binary, DocumentCurationLegos.content(mapOf("name" to "source.bin", "base64" to encoded)).bytes)
        assertFailsWith<IllegalArgumentException> {
            DocumentCurationLegos.content(mapOf("name" to "source", "text" to text, "base64" to encoded))
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentCurationLegos.content(mapOf("name" to "source", "base64" to "not base64"))
        }
    }

    @Test
    fun jsonExtentsRetainIntegralCoordinates() {
        val extent = DocumentCurationLegos.extent(JsonSupport.parse(
            """{"lba":8,"byteLength":3,"name":"source.txt","mediaType":"text/plain"}"""))
        assertEquals(8L, extent.lba)
        assertEquals(3, extent.byteLength)
        assertEquals("source.txt", extent.name)
        assertEquals("text/plain", extent.mediaType)
    }

    @Test
    fun invalidExtentsCannotBecomeDifferentValidCoordinates() {
        val extent = mapOf("lba" to 0L, "byteLength" to 3, "name" to "source.txt")
        for (fields in listOf(
            extent + ("lba" to 0.5),
            extent + ("lba" to 9_007_199_254_740_992.0),
            extent + ("byteLength" to 2_147_483_648L),
            extent + ("byteLength" to -1),
            extent + ("name" to ""),
            extent - "name",
        )) assertFailsWith<IllegalArgumentException> { DocumentCurationLegos.extent(fields) }
    }

    @Test
    fun documentSheetMatesWithExistingSheetConsumer() {
        val program = LcncProgram("document-sheet", listOf(
            LcncNode("source", "scope.in", mapOf("name" to "extent")),
            LcncNode("curate", DocumentCurationLegos.CURATE),
            LcncNode("count", "sheet.count"),
        ).toSeries(), listOf(
            LcncWire("source", "value", "curate", "extent"),
            LcncWire("curate", "sheet", "count", "sheet"),
        ).toSeries())
        assertEquals(LcncNodeKey.DOCUMENT_CURATE, LcncNodeKey.of(DocumentCurationLegos.CURATE))
        assertTrue(LcncTypeCheck.check(program).isEmpty())
    }
}
