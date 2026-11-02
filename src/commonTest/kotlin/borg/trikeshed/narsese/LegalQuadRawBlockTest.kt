package borg.trikeshed.narsese

import borg.trikeshed.cursor.values
import borg.trikeshed.htx.client.ipfs.CID
import borg.trikeshed.htx.client.ipfs.MemoryBlockStore
import borg.trikeshed.ipns.IpnsCid
import borg.trikeshed.ipns.IpnsEncoding
import borg.trikeshed.ipns.toIpnsCid
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.rdf.QuadStore
import borg.trikeshed.rdf.RdfQuad
import borg.trikeshed.rdf.RdfTerm
import borg.trikeshed.rdf.SparqlQuery
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LegalQuadRawBlockTest {
    private fun cid(text: String) = ContentId.of(text.encodeToByteArray())
    private fun candidate() = LegalQuadCandidate(
        RdfQuad(RdfTerm.Iri("urn:clause:notice"), RdfTerm.Iri("urn:term:deadline"),
            RdfTerm.Literal("30 days"), RdfTerm.Iri("urn:graph:agreement")),
        cid("original"), cid("extracted"), cid("receipt"), "30 days", 5, 12,
    )

    @Test
    fun rawCidAndIsolatedNamedGraphQueryRoundTrip() = runTest {
        // Existing independent Kubo fixture: CIDv1 raw/sha2-256 for the bytes "hello\n".
        assertEquals("bafkreicysg23kiwv34eg2d7qweipxwosdo2py4ldv42nbauguluen5v6am",
            IpnsCid.raw("hello\n".encodeToByteArray()).toString())
        val store = MemoryBlockStore()
        val original = listOf(candidate())
        val encoded = LegalQuadSnapshot.encode(original)
        val rawCid = LegalQuadRawBlock.retain(store, original)
        assertEquals(ContentId.of(encoded).toIpnsCid(0x55u), rawCid)
        val parsed = IpnsCid.parse(rawCid.toString())
        assertEquals(rawCid, parsed)
        val recovered = LegalQuadRawBlock.read(store, parsed)
        assertEquals(original, recovered)

        // Explicit synthetic projection to a fresh store, not promotion to the live legal graph.
        // Candidate provenance remains a separate, unverified claim alongside this query.
        val isolated = QuadStore().add(recovered.single().quad)
        val result = SparqlQuery.parse(
            "SELECT ?s ?o FROM NAMED <urn:graph:agreement> WHERE { GRAPH <urn:graph:agreement> { ?s <urn:term:deadline> ?o } }"
        ).plan(isolated.scan())
        assertEquals(1, result.size)
        assertEquals("<urn:clause:notice>", result[0].values[0])
        assertEquals("\"30 days\"", result[0].values[1])
        assertEquals(cid("receipt"), recovered.single().curationReceiptCid)
        assertEquals("30 days", recovered.single().quote)
        val wrongGraph = SparqlQuery.parse(
            "SELECT ?s FROM NAMED <urn:graph:other> WHERE { GRAPH <urn:graph:other> { ?s <urn:term:deadline> ?o } }"
        ).plan(isolated.scan())
        assertEquals(0, wrongGraph.size)
    }

    @Test
    fun rejectsAbsentWrongCodecWrongHashAndCorruptedBackendBytes() = runTest {
        val store = MemoryBlockStore()
        val rows = listOf(candidate())
        val encoded = LegalQuadSnapshot.encode(rows)
        val expected = ContentId.of(encoded)
        val good = LegalQuadRawBlock.retain(store, rows)
        assertFailsWith<IllegalArgumentException> { LegalQuadRawBlock.read(store, IpnsCid.raw("absent".encodeToByteArray())) }
        assertFailsWith<IllegalArgumentException> { LegalQuadRawBlock.read(store, expected.toIpnsCid(0x70u)) }
        val identityHash = IpnsCid(0x55u, IpnsEncoding.multihash(0u, ByteArray(32)))
        assertFailsWith<IllegalArgumentException> { LegalQuadRawBlock.read(store, identityHash) }

        // MemoryBlockStore does not protect its returned array. Read must re-hash after copying.
        val held = assertNotNull(store.get(CID(good.bytes)))
        held[0] = (held[0].toInt() xor 1).toByte()
        assertFailsWith<IllegalArgumentException> { LegalQuadRawBlock.read(store, good) }

        // Nor does the store verify a key when accepting bytes.
        val wrong = IpnsCid.raw("different".encodeToByteArray())
        store.put(CID(wrong.bytes), encoded.copyOf())
        assertFailsWith<IllegalArgumentException> { LegalQuadRawBlock.read(store, wrong) }
    }

    @Test
    fun refusesOversizedSingleBlocksEvenWhenSnapshotEncodingAllowsThem() = runTest {
        val store = MemoryBlockStore()
        val quote = "\u4e2d".repeat(8_000)
        val rows = List(120) { candidate().copy(quote = quote, begin = 0, end = quote.length) }
        val bytes = LegalQuadSnapshot.encode(rows)
        assertTrue(bytes.size > LegalQuadRawBlock.MAX_BYTES)
        assertFailsWith<IllegalArgumentException> { LegalQuadRawBlock.retain(store, rows) }

        // The generic BlockStore can return a too-large array; reject it before copying/decoding.
        val cid = IpnsCid.raw(bytes)
        store.put(CID(cid.bytes), bytes)
        assertFailsWith<IllegalArgumentException> { LegalQuadRawBlock.read(store, cid) }
    }
}
