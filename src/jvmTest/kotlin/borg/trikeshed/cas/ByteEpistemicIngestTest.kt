package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.narsese.RelationKind as SemanticRelationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ByteEpistemicIngestTest {

    /** Synthetic PDF-like bytes: header, object, compressed stream, xref, trailer, EOF. */
    private fun syntheticPdf(): ByteArray {
        val sb = StringBuilder()
        // PDF header
        sb.append("%PDF-1.4\n")
        // Object 1: catalog dict
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        // Object 2: pages dict (with a stream — high-bit bytes to simulate FlateDecode)
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        // Object 3: page content stream (repeated binary-ish payload)
        sb.append("3 0 obj\n<< /Length 64 >>\nstream\n")
        val streamPayload = ByteArray(64) { (0x80 + (it % 128)).toByte() } // high-bit run
        sb.append(String(streamPayload, Charsets.ISO_8859_1))
        sb.append("\nendstream\nendobj\n")
        // Cross-reference table
        sb.append("xref\n0 4\n")
        sb.append("0000000000 65535 f \n")
        sb.append("0000000010 00000 n \n")
        sb.append("0000000058 00000 n \n")
        sb.append("0000000115 00000 n \n")
        // Trailer
        sb.append("trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n310\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun ingestProducesChunksAndSignals() {
        val cas = CasStore.inMemory()
        val pdf = syntheticPdf()
        val surface = ByteEpistemicIngest.ingest(cas, pdf, chunkSize = 64)

        assertTrue(surface.totalBytes > 0, "totalBytes > 0")
        assertTrue(surface.chunks.size > 1, "multiple chunks from PDF bytes")
        assertTrue(surface.links.size > 0, "links connect chunks")
        assertTrue(surface.signals.size > 0, "signals emitted for belief bag")

        // Every chunk CID is recoverable from CAS
        for (i in 0 until surface.chunks.size) {
            val chunk = surface.chunks[i]
            val recovered = cas.get(chunk.cid)
            assertNotNull(recovered, "chunk $i CID is in CAS")
            assertEquals(chunk.endOffset - chunk.startOffset, recovered!!.size, "chunk $i byte count matches")
        }
    }

    @Test
    fun structuralKeyDistinguishesRegions() {
        val cas = CasStore.inMemory()
        // Build a payload with distinct regions: text, binary, text
        val payload = ByteArray(256) { i ->
            when {
                i < 64 -> (0x41 + (i % 26)).toByte()          // printable ASCII (A-Z)
                i < 192 -> (0x80 + (i % 128)).toByte()        // high-bit binary
                else -> (0x41 + (i % 26)).toByte()             // printable ASCII again
            }
        }
        val surface = ByteEpistemicIngest.ingest(cas, payload, chunkSize = 64)
        // Structural keys should differ between text and binary chunks
        val textKey = surface.chunks[0].structuralKey  // first chunk: printable
        val binKey = surface.chunks[1].structuralKey   // second chunk: high-bit
        assertNotEquals(textKey, binKey, "printable and high-bit chunks have different structural keys")
    }

    @Test
    fun emptyInputReturnsEmptySurface() {
        val cas = CasStore.inMemory()
        val surface = ByteEpistemicIngest.ingest(cas, ByteArray(0))
        assertEquals(0, surface.totalBytes)
        assertEquals(0, surface.chunks.size)
        assertEquals("_", surface.documentSchema.structuralKey)
    }

    @Test
    fun chunkCountMatchesInput() {
        val cas = CasStore.inMemory()
        val payload = ByteArray(1024) { (it * 7 and 0xFF).toByte() }
        val surface = ByteEpistemicIngest.ingest(cas, payload, chunkSize = 256)
        assertEquals(4, surface.chunks.size, "1024 bytes / 256 chunk = 4 chunks")
    }

    @Test
    fun signalsMapRelationsCorrectly() {
        val cas = CasStore.inMemory()
        val payload = ByteArray(128) { (0x41 + (it % 26)).toByte() }
        val surface = ByteEpistemicIngest.ingest(cas, payload, chunkSize = 32)
        // With adjacent chunks, expect CAUSALITY signals
        var causal = 0
        for (i in 0 until surface.signals.size) {
            if (surface.signals[i].relation == SemanticRelationKind.CAUSALITY) causal++
        }
        assertTrue(causal > 0, "adjacent chunks produce CAUSALITY signals")
    }

    @Test
    fun byteClassLabelCoversAllClasses() {
        // Verify all 5 byte classes are correctly classified
        assertEquals('P', byteClassLabel(0x41))         // 'A' → printable
        assertEquals('W', byteClassLabel(0x20))         // space → whitespace
        assertEquals('C', byteClassLabel(0x01))         // SOH → control
        assertEquals('H', byteClassLabel(0x80.toByte()))// high-bit
        assertEquals('\u2400', byteClassLabel(0x00))    // NUL → null marker
    }

    private fun assertNotNull(value: Any?, msg: String) {
        assertTrue(value != null, msg)
    }
}
