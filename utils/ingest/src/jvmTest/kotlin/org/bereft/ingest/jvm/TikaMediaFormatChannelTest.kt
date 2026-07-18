package org.bereft.ingest.jvm

import borg.trikeshed.job.CasStore
import org.bereft.ingest.IngestProjection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class TikaMediaFormatChannelTest {

    @Test
    fun `test extraction round-trips through CasStore with canonical metadata`() {
        val casStore = CasStore.inMemory()
        val channel = TikaMediaFormatChannel(casStore)

        val tempFile = Files.createTempFile("test", ".txt").toFile()
        tempFile.writeText("Hello, World!")

        try {
            val projections = setOf(IngestProjection.TEXT_EXTRACTION, IngestProjection.METADATA)
            val envelope = channel.extract(tempFile.absolutePath, projections)

            assertNotNull(envelope.rawPayloadCid)
            assertNotNull(envelope.canonicalMetadataCid)
            assertTrue(envelope.extractedPayloadCids.isNotEmpty())
            assertEquals("text/plain", envelope.mediaType)
            assertEquals("text", envelope.formatFacet)

            // 1. raw/extracted/metadata bytes are CAS-addressed and round-trip
            val rawBytes = casStore.get(envelope.rawPayloadCid)
            assertNotNull(rawBytes)
            assertEquals("Hello, World!", String(rawBytes!!))

            val extractedBytes = casStore.get(envelope.extractedPayloadCids.first())
            assertNotNull(extractedBytes)
            // Tika typically appends newline
            assertTrue(String(extractedBytes!!).contains("Hello, World!"))

            val metadataBytes = casStore.get(envelope.canonicalMetadataCid)
            assertNotNull(metadataBytes)

            // 2. stable Cursor column names and IOMemento types
            assertNotNull(envelope.metadataCursor)
            assertTrue(envelope.metadataCursor.a > 0) // size is 'a'
            val firstRowVec = envelope.metadataCursor.b(0)
            assertNotNull(firstRowVec)
            // Verify IOMemento
            assertEquals(borg.trikeshed.cursor.IOMemento.IoObject, firstRowVec.b(0)().type)

            // 3. identical metadata yields identical ContentId
            val envelope2 = channel.extract(tempFile.absolutePath, projections)
            assertEquals(envelope.canonicalMetadataCid, envelope2.canonicalMetadataCid)

            // 4. corruption is detected on read (CasStore interface exposes corrupt function)
            casStore.corrupt(envelope.rawPayloadCid)
            assertThrows(IllegalStateException::class.java) {
                casStore.get(envelope.rawPayloadCid)
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `test fallback when file does not exist`() {
        val casStore = CasStore.inMemory()
        val channel = TikaMediaFormatChannel(casStore)

        val info = channel.detect("nonexistent.pdf")

        // Should fall back to suffix based and detect PDF
        assertEquals("application/pdf", info.mediaType)
    }
}
