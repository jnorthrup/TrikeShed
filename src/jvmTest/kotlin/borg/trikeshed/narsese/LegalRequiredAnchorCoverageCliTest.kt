package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LegalRequiredAnchorCoverageCliTest {
    private val original = "docs/fixtures/2026-11-04/english-symbol-corpus"
    private val coverage = "docs/fixtures/2026-11-05/legal-required-anchor-coverage"
    private fun score(manifest: String, predictions: String) = LegalNarsEvaluationCli.report(_a["score", manifest, predictions])
    private fun rows(report: Map<String, Any?>): List<Map<*, *>> =
        (report["rows"] as List<*>).map { it as Map<*, *> }

    @Test fun suppliedPartialAndFullCorpusControlsSeparateAgreementFromCoverage() {
        val partial = score("$coverage/manifest.json", "$original/controls/scorer-contract-predictions.json")
        val full = score("$coverage/manifest.json", "$coverage/full-anchor-predictions.json")
        for (report in _l[partial, full]) {
            assertEquals(0, report["invalidFixtures"])
            assertEquals(0, report["invalidPredictions"])
            assertEquals(14, report["missing"])
            assertEquals(2, report["groundedAgreements"])
            assertEquals(6, report["requiredAnchorCases"])
            assertEquals(1, report["requiredAnchorComparableCases"])
            assertNull(rows(report).single { it["name"] == "S6" }["requiredAnchorCoverage"])
        }
        val a = rows(partial).single { it["name"] == "S2" }["requiredAnchorCoverage"] as Map<*, *>
        val b = rows(full).single { it["name"] == "S2" }["requiredAnchorCoverage"] as Map<*, *>
        assertEquals(false, a["complete"])
        assertEquals(1, a["matchedCount"])
        assertEquals("An Agent is an Entity.", ((a["missingAnchors"] as List<*>).single() as Map<*, *>)["quote"])
        assertEquals(true, b["complete"])
        assertEquals(2, b["matchedCount"])
        assertEquals(emptyList<Any?>(), b["missingAnchors"])
        assertEquals(0.0, partial["completeRequiredAnchorCoverageRate"])
        assertEquals(1.0, full["completeRequiredAnchorCoverageRate"])
    }

    @Test fun oldManifestAndMissingSeatKeepOldOutcomesAndBlindIdentity() {
        val old = score("$original/manifest.json", "$original/controls/scorer-contract-predictions.json")
        assertEquals(2, old["groundedAgreements"])
        assertEquals(0, old["requiredAnchorCases"])
        assertTrue(rows(old).all { it["requiredAnchorCoverage"] == null })
        assertNull(old["completeRequiredAnchorCoverageRate"])
        val missing = score("$coverage/manifest.json", "$original/empty-seat-predictions.json")
        assertEquals(16, missing["missing"])
        assertEquals(0, missing["scored"])
        assertEquals(0, missing["explicitUnknown"])
        assertEquals(6, missing["requiredAnchorCases"])
        assertEquals(0, missing["requiredAnchorComparableCases"])
        assertNull(missing["completeRequiredAnchorCoverageRate"])
        assertTrue(rows(missing).all { it["predicted"] == null })
        fun requests(manifest: String) = LegalNarsEvaluationCli.report(_a["prepare", manifest])["requests"] as List<*>
        val originalRequests = requests("$original/manifest.json")
        val coverageRequests = requests("$coverage/manifest.json")
        assertEquals(originalRequests, coverageRequests)
        for (request in coverageRequests) {
            assertEquals(_s["name", "propositionCid", "proposition", "claimedOriginalCid", "excerptCid", "excerpt"],
                (request as Map<*, *>).keys)
        }
    }

    @Test fun requiredAnchorCodecRejectsWrongTypeAndInvalidDeclarationBeforePreparing() {
        val manifest = (reifyStrict(Files.readString(Path.of("$original/manifest.json"))) as Map<*, *>)
            .mapKeys { it.key as String }
        val first = ((manifest["cases"] as List<*>).first() as Map<*, *>).mapKeys { it.key as String }
        val file = Files.createTempFile("legal-required-anchors-", ".json")
        try {
            for (required in _l["not-an-array", emptyList<Any>()]) {
                val invalid = manifest + _m["cases" j _l[first + _m["requiredAnchors" j required]]]
                Files.writeString(file, jsonOf(invalid))
                assertFails { LegalNarsEvaluationCli.report(_a["prepare", file.toString()]) }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
