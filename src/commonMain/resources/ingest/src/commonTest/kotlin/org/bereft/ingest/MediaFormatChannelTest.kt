package org.bereft.ingest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MediaFormatChannelTest {

    @Test
    fun `default projections cover known media types`() {
        val dp = MediaFormatChannel.DEFAULT_PROJECTIONS
        assertNotNull(dp["application/pdf"])
        assertTrue(dp["application/pdf"]!!.contains(IngestProjection.TEXT_EXTRACTION))
        assertTrue(dp["audio/wav"]!!.contains(IngestProjection.AUDIO_TRANSCRIPTION))
    }

    @Test
    fun `MediaFormatInfo toString renders TSV row`() {
        val info = MediaFormatInfo(
            path = "/x/y.pdf",
            mediaType = "application/pdf",
            formatFacet = "pdf",
            confidence = 0.9,
            availableProjections = setOf(IngestProjection.TEXT_EXTRACTION, IngestProjection.METADATA),
            sizeBytes = 12345L,
        )
        val row = info.toString()
        assertEquals(6, row.split('\t').size)
        assertTrue(row.startsWith("/x/y.pdf"))
        assertTrue(row.contains("application/pdf"))
        assertTrue(row.endsWith("12345"))
    }

    @Test
    fun `unknown media type falls back to metadata-only`() {
        val dp = MediaFormatChannel.DEFAULT_PROJECTIONS
        val projections = dp["application/x-unknown"]
            ?: setOf(IngestProjection.METADATA)
        assertEquals(setOf(IngestProjection.METADATA), projections)
    }
}
