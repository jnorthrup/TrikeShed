package borg.trikeshed.narsese

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.CborLimitException
import borg.trikeshed.collections.associative.CborLimitKind
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.nlp.*
import kotlin.test.*

/** Structural/resource checks only; not source authenticity, authorization or legal review. */
class BoundedCborTest {
    private fun bytes(vararg octets: Int): ByteArray = ByteArray(octets.size) { octets[it].toByte() }

    @Test fun retainedDocumentRecordRoundTripsWithinLimits() {
        val text = "A \uD83D\uDE80 B"
        val source = DocumentSource(ContentId.of("source".encodeToByteArray()),
            ContentId.of(text.encodeToByteArray()), text, "fixture.txt", "text/plain", "fixture")
        val record = DocumentCurationRecord(source, null, null, "fixture", emptySeriesOf(), emptySeriesOf())
        val data = DocumentCuratorCodec.encode(record)
        val decoded = DocumentCuratorCodec.decodeBounded(data, maxBytes = data.size)
        assertEquals(text, decoded.source.text)
        assertEquals(source.originalCid, decoded.source.originalCid)
        assertEquals(source.extractedTextCid, decoded.source.extractedTextCid)
        assertEquals(0, decoded.proposals.size)
    }

    @Test fun currentAndOlderRecordShapesRemainReadable() {
        val text = "Alice left."
        val original = ContentId.of("source".encodeToByteArray())
        val extracted = ContentId.of(text.encodeToByteArray())
        val source = DocumentSource(original, extracted, text, "fixture.txt", "text/plain", "fixture",
            anchors = _l[DocumentAnchor(0, text.length, page = 3)])
        val tokens = s_[NlpToken(1, 0, 5, "Alice", "alice", "NNP", "PERSON"),
            NlpToken(2, 6, 10, "left", "leave", "VBD", "O")]
        val sentence = NlpSentence(0, 0, text.length, tokens, s_[NlpDependency(0, 2, "root")])
        val nlp = NlpDocument(text, s_[sentence], NlpMetadata("fixture-parser", "fixture-host",
            _m["runtime" j "fixture"], _m["language" j "en"]))
        val proposal = DocumentProposal("candidate", subject = "Alice", predicate = "leave",
            confidence = 0.75, quote = "Alice", quotationBegin = 0, quotationEnd = 5)
        val tool = DocumentToolReceipt(DocumentToolStage.NLP, "fixture", "fixture#1", DocumentToolStatus.SUCCESS,
            _m["language" j "en"], s_[extracted], s_[original])
        val record = DocumentCurationRecord(source, nlp, null, "fixture", s_[proposal], s_["unreviewed"],
            toolReceipts = s_[tool])
        val encoded = DocumentCuratorCodec.encode(record)
        val decoded = DocumentCuratorCodec.decodeBounded(encoded, maxBytes = encoded.size)
        assertEquals(3, decoded.source.anchors.single().page)
        assertEquals("fixture-parser", decoded.nlp?.metadata?.processor)
        assertEquals("Alice", decoded.nlp?.sentences?.get(0)?.tokens?.get(0)?.word)
        assertEquals(0.75, decoded.proposals[0].confidence)
        assertEquals(DocumentToolStatus.SUCCESS, decoded.toolReceipts[0].status)

        // Simulate an older v1 record lacking fields documented as optional by the codec.
        val oldFields = DocumentCuratorCodec.record(record) - _s["instructions", "quotationReserved",
            "quotationSubmitted", "quotationDuplicates", "toolOntology", "receipt", "toolReceipts", "axioms"]
        val oldBytes = CanonicalCbor.encodeMap(oldFields)
        val old = DocumentCuratorCodec.decodeBounded(oldBytes, maxBytes = oldBytes.size)
        assertEquals(text, old.source.text)
        assertEquals(0, old.toolReceipts.size)
        assertEquals(0, old.quotationReservedReceiptCids.size)
    }

    @Test fun rejectsDuplicateOrNonTextKeysEvenNested() {
        assertFails { CanonicalCbor.decodeMapBounded(bytes(0xa2, 0x61, 0x61, 1, 0x61, 0x61, 2), 8) }
        assertFails { CanonicalCbor.decodeMapBounded(bytes(0xa1, 1, 2), 3) }
        assertFails { CanonicalCbor.decodeMapBounded(bytes(0xa1, 0x61, 0x78, 0xa2, 0x61, 0x61, 1, 0x61, 0x61, 2), 10) }
    }

    @Test fun rejectsTrailingNonMapIndefiniteTagsAndInvalidUtf8() {
        assertFails { CanonicalCbor.decodeMapBounded(bytes(0xa0, 0), 2) }
        assertFails { CanonicalCbor.decodeMapBounded(bytes(0x80), 1) }
        assertFails { CanonicalCbor.decodeMapBounded(bytes(0xbf, 0xff), 2) }
        assertFails { CanonicalCbor.decodeMapBounded(bytes(0xa1, 0x61, 0x78, 0xc0, 0xf6), 5) }
        assertFails { CanonicalCbor.decodeMapBounded(bytes(0xa1, 0x61, 0x78, 0xf7), 4) }
        assertFails { CanonicalCbor.decodeMapBounded(bytes(0xa1, 0x61, 0x78, 0x61, 0xff), 5) }
    }

    @Test fun boundsDepthItemsBytesAndDeclaredLengths() {
        // Root at depth zero; a root array plus one scalar is depth one.
        assertEquals(CborLimitKind.DEPTH, assertFailsWith<CborLimitException> {
            Cbor.decodeBounded(bytes(0x81, 0xf6), maxBytes = 2, maxDepth = 0)
        }.kind)
        Cbor.decodeBounded(bytes(0x81, 0xf6), maxBytes = 2, maxDepth = 1)
        assertEquals(CborLimitKind.ITEMS, assertFailsWith<CborLimitException> {
            Cbor.decodeBounded(bytes(0x82, 1, 2), maxBytes = 3, maxItems = 2)
        }.kind)
        assertEquals(CborLimitKind.BYTES, assertFailsWith<CborLimitException> {
            Cbor.decodeBounded(bytes(0x82, 1, 2), maxBytes = 2)
        }.kind)
        assertFails { Cbor.decodeBounded(bytes(0x9a, 0x7f, 0xff, 0xff, 0xff), maxBytes = 5) }
        assertFails { Cbor.decodeBounded(bytes(0xa0), maxBytes = 1, maxDepth = 65) }
    }

    @Test fun legacyDecoderStillAcceptsPreviouslySupportedStructure() {
        assertEquals(emptyMap<String, Any?>(), CanonicalCbor.decodeMap(bytes(0xbf, 0xff)))
        assertEquals(emptyMap<String, Any?>(), CanonicalCbor.decodeMap(bytes(0xa0, 0)))
    }
}
