package borg.trikeshed.cas

import borg.trikeshed.collections.LineAperture
import borg.trikeshed.job.CasStore
import borg.trikeshed.lcnc.reactor.MarkdownIngestCodec
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.narsese.RelationKind as SemanticRelationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContentEpistemicIngestTest {
    private val markdown = """
        # Alpha
        shared knowledge line
        - first fact
        role: evidence
        # Beta
        shared knowledge line
        - second fact
        role: evidence
    """.trimIndent()

    @Test
    fun ingestBuildsSelfContentRegionsMetricsSchemasAndTypedLinks() {
        val cas = CasStore.inMemory()
        val surface = ContentEpistemicIngest.ingest(cas, markdown, LineAperture.L1)

        assertEquals(8, surface.spine.size)
        assertEquals(4, surface.regions.size)
        assertNotEquals(surface.spineCid, surface.treeRoot)
        for (region in surface.regions.view) {
            assertTrue(region.isomorphicTo(surface.spine))
            assertTrue(region.metrics.bytes > 0)
            assertTrue(region.metrics.shannonBitsPerByte > 0.0)
            assertTrue(region.schema.structuralKey.isNotEmpty())
            assertTrue(region.schema.lzPhraseCount > 0)
            assertEquals(region.coordinate.size, region.lineCids.size)
        }

        val causal = surface.links.view.filter { it.kind == RelationKind.CAUSALITY }
        val attraction = surface.links.view.filter { it.kind == RelationKind.ATTRACTION }
        assertEquals(3, causal.count())
        assertTrue(attraction.count() >= 2)
        assertEquals(surface.links.size, surface.signals.size)
        assertTrue(surface.signals.view.any { it.relation == SemanticRelationKind.CAUSALITY })
        assertTrue(surface.signals.view.any { it.relation == SemanticRelationKind.ATTRACTION })
        assertTrue(surface.signals.view.all { it.subjectCid.isNotEmpty() && it.objectCid != null })
    }

    @Test
    fun formattingNoiseRetainsSelfEpistemicSpineAndSchemaIdentity() {
        val casA = CasStore.inMemory()
        val casB = CasStore.inMemory()
        val a = ContentEpistemicIngest.ingest(casA, "# A\nvalue: one\n- x", LineAperture.L0)
        val b = ContentEpistemicIngest.ingest(casB, "  # A  \n value: one \n  - x ", LineAperture.L0)
        assertEquals(a.spineCid, b.spineCid)
        assertEquals(a.regions[0].schema.cid, b.regions[0].schema.cid)
        assertEquals(a.regions[0].metrics, b.regions[0].metrics)
    }

    @Test
    fun kolmogorovSchemaSignatureSeparatesStructuralTextFamilies() {
        val prose = listOf("ordinary prose", "another sentence")
        val code = listOf("class A {", "fun x() = 1", "}")
        val proseSeries = prose.size j { i: Int -> prose[i] }
        val codeSeries = code.size j { i: Int -> code[i] }
        val proseSignature = kolmogorovSchemaSignature(proseSeries)
        val codeSignature = kolmogorovSchemaSignature(codeSeries)
        assertNotEquals(proseSignature.structuralKey, codeSignature.structuralKey)
        assertNotEquals(proseSignature.cid, codeSignature.cid)
        assertTrue(proseSignature.normalizedComplexity in 0.0..1.0)
        assertTrue(codeSignature.normalizedComplexity in 0.0..1.0)
    }

    @Test
    fun markdownCodecExposesTheEpistemicIngestLane() {
        val surface = MarkdownIngestCodec().ingestEpistemic(CasStore.inMemory(), markdown, LineAperture.L2)
        assertEquals(surface.spine.size, surface.regions.size)
        assertTrue(surface.links.size >= surface.regions.size - 1)
    }
}
