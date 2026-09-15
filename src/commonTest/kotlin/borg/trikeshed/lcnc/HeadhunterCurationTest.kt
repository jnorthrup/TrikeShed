package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.ModelUsage
import borg.trikeshed.narsese.*
import borg.trikeshed.nlp.NlpDocument
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HeadhunterCurationTest {
    @Test fun authoredInstructionsProjectFromTheirActualReceiptAndKeepTheirOwnIdentity() = runTest {
        val rig = HeadhunterStoreTest.Rig()
        val evidence = rig.evidence("Built Kotlin services with structured concurrency.")
        val text = fields(evidence)["text"] as String
        val textCid = rig.cas.put(text.encodeToByteArray())
        val source = DocumentSource(textCid, textCid, text, "Synthetic professional evidence", "text/plain", "fixture",
            mapOf("evidenceId" to listOf(evidence["id"] as String), "evidenceCid" to listOf(evidence["cid"] as String)))
        val original = DocumentCurationRecord(source, NlpDocument(text, emptySeriesOf()),
            ModelResponse("Synthetic response", ModelUsage(1, 1, 2), "fixture", "fixture"), "fixture",
            s_[DocumentProposal("Synthetic proposal", "applicant", "experience", "Kotlin services", 0.8, text,
                0, text.length, true, "asserted", receiptCid = rig.cas.put("Synthetic proposal receipt".encodeToByteArray()))],
            emptySeriesOf(), instructions = HeadhunterCuration.INSTRUCTIONS)
        fun receipt(record: DocumentCurationRecord): DocumentCurationResult {
            val encoded = DocumentCuratorCodec.encode(record)
            return DocumentCurationResult(rig.cas.put(encoded), DocumentCuratorCodec.decode(encoded), emptySeriesOf())
        }
        val curation = HeadhunterCuration(rig.store, emptyMap(), { false }) { _, _ -> error("Projecting a retained receipt must not call a model") }
        val baseline = curation.project(receipt(original))
        val authored = original.copy(instructions = original.instructions + "\nKeep platform migration experience separate from future learning goals.")
        val result = receipt(authored)
        assertEquals(authored.instructions, result.record.instructions)
        val profile = curation.project(result)
        assertNotEquals(baseline["id"], profile["id"], "Different authored instructions describe a different retained projection")
        assertEquals(ContentId.of(authored.instructions.encodeToByteArray()).value, fields(profile)["instructionsCid"])
        assertNotEquals(fields(baseline)["instructionsCid"], fields(profile)["instructionsCid"])
        assertEquals(result.recordCid.value, fields(profile)["receiptCid"])
        assertEquals(evidence["cid"], HeadhunterStore.references(fields(profile))[0].b)
        val committed = rig.log.committed
        assertEquals(profile, curation.project(result))
        assertEquals(committed, rig.log.committed)
        val reopened = HeadhunterStoreTest.Rig(rig.cas, rig.log)
        assertEquals(profile, reopened.store.record(profile["id"] as String))
    }

    @Test fun profileDisplayUsesWholeAndClaimReviewsByRevisionTimeWithLastOnTie() = runTest {
        val rig = HeadhunterStoreTest.Rig()
        val evidence = rig.evidence()
        val profile = rig.save("profile", mapOf("title" to "Applicant profile", "model" to "fixture",
            "receiptCid" to rig.cas.put("Synthetic curation receipt".encodeToByteArray()).value,
            "sources" to listOf(HeadhunterStore.reference(evidence)),
            "claims" to listOf(mapOf("id" to "kotlin", "grounded" to true), mapOf("id" to "channels", "grounded" to true))))
        val curation = HeadhunterCuration(rig.store, emptyMap(), { false }) { _, _ -> error("Retained reviews require no model") }
        val subject = mapOf("subjectId" to profile["id"], "subjectCid" to profile["cid"])
        val whole = rig.save("review", subject + ("decision" to "approved"))
        val specific = rig.save("review", subject + mapOf("claimId" to "kotlin", "decision" to "rejected"))
        suspend fun claims(): Series<HeadhunterRecord> =
            (curation.profile()["claims"] as List<*>).toSeries() α { objectOf(it, "claim") }
        val initial = claims()
        assertEquals("rejected", initial.view.single { it["id"] == "kotlin" }["decision"])
        assertEquals(HeadhunterStore.reference(specific), initial.view.single { it["id"] == "kotlin" }["review"])
        assertEquals("approved", initial.view.single { it["id"] == "channels" }["decision"])

        val editedWhole = rig.save("review", fields(whole) + ("decision" to "changes-requested"), whole)
        assertTrue(claims().view.all { it["decision"] == "changes-requested" && it["review"] == HeadhunterStore.reference(editedWhole) },
            "Editing an earlier review must take precedence over a newer record's older decision")
        rig.now = 100
        rig.save("review", subject + mapOf("claimId" to "kotlin", "decision" to "approved"))
        rig.now = 100
        val last = rig.save("review", subject + ("decision" to "rejected"))
        assertTrue(claims().view.all { it["decision"] == "rejected" && it["review"] == HeadhunterStore.reference(last) },
            "Equal timestamps resolve to the last retained record, as preparation does")
    }

    @Test fun deletedSourceVersionsAreStaleEvenWhenTheirCidStillMatchesTheProfile() = runTest {
        val rig = HeadhunterStoreTest.Rig()
        val evidence = rig.evidence()
        val source = rig.save("source", mapOf("title" to "Imported original", "origin" to "upload"))
        for (record in s_[evidence, source].view) {
            val deleted = rig.save(record["kind"] as String, fields(record) + ("deleted" to true), record)
            rig.save("profile", mapOf("title" to "Retained profile for ${record["kind"]}", "model" to "fixture",
                "receiptCid" to rig.cas.put("Synthetic retained receipt".encodeToByteArray()).value,
                "sources" to listOf(HeadhunterStore.reference(deleted)),
                "claims" to listOf(mapOf("id" to record["kind"], "grounded" to true))))
        }
        val curation = HeadhunterCuration(rig.store, emptyMap(), { false }) { _, _ -> error("Reading retained profiles requires no model") }
        val claims = (curation.profile()["claims"] as List<*>).toSeries() α { objectOf(it, "claim") }
        assertEquals(2, claims.size)
        assertTrue(claims.view.all { it["stale"] == true })
        assertTrue(claims.view.all { it["decision"] == "proposed" })
    }

    @Test fun reviewedTransferPinsSourcesAndRetainedProfilesDoNotRerunModels() = runTest {
        val rig = HeadhunterStoreTest.Rig()
        val evidence = rig.evidence("Built Kotlin services with structured concurrency.")
        var calls = 0
        val curation = HeadhunterCuration(rig.store, mapOf("model" to "fixture", "url" to "https://fixture.invalid/chat/completions"), { true }) { source, instructions ->
            calls++
            val record = DocumentCurationRecord(source, NlpDocument(source.text, emptySeriesOf()),
                ModelResponse("fixture response", ModelUsage(1, 1, 2), "fixture", "fixture"), "fixture",
                s_[DocumentProposal("fixture proposal", "applicant", "skill", "Kotlin", 0.8, source.text,
                    0, source.text.length, true, "asserted", s_["unsupported clause structure"], rig.cas.put("proposal".encodeToByteArray()))],
                emptySeriesOf(), instructions = instructions)
            DocumentCurationResult(rig.cas.put(DocumentCuratorCodec.encode(record)), record, emptySeriesOf())
        }
        val plan = curation.plan()
        assertEquals(0, calls, "Previewing disclosure is a local read")
        val run = curation.run(plan["planCid"] as String)
        assertEquals(1, calls)
        val profile = objectOf(run["profile"], "profile")
        assertEquals(0, profile["pending"])
        val claim = objectOf((profile["claims"] as List<*>).single(), "claim")
        assertEquals(true, claim["grounded"])
        assertEquals("proposed", claim["decision"])
        assertEquals("Kotlin", claim["text"])
        assertEquals(evidence["cid"], objectOf(claim["source"], "source")["cid"])
        assertFailsWith<IllegalArgumentException> { curation.run(plan["planCid"] as String) }
        curation.run(curation.plan()["planCid"] as String)
        assertEquals(1, calls, "Retained curation is reused rather than sent to the model again")
        rig.save("evidence", fields(evidence) + ("text" to "Learning Kotlin; no production experience claimed."), evidence)
        val changed = curation.profile()
        assertEquals(1, changed["pending"])
        assertEquals(true, objectOf((changed["claims"] as List<*>).single(), "claim")["stale"])
    }

    @Test fun quoteLocationIsVerifiedWithoutRewritingModelEvidence() {
        val rig = HeadhunterStoreTest.Rig()
        val curation = HeadhunterCuration(rig.store, emptyMap(), { false }) { _, _ -> error("No model") }
        val text = "Kotlin services. Repeated. Repeated."
        val cid = rig.cas.put(text.encodeToByteArray())
        val source = DocumentSource(cid, cid, text, "synthetic.txt", "text/plain", "fixture")
        fun result(quote: String) = DocumentCurationResult(cid, DocumentCurationRecord(source, null, null, "fixture",
            s_[DocumentProposal("raw", "applicant", "skill", "Kotlin", 0.7, quote, 100, 200, true, "asserted", receiptCid = cid)], emptySeriesOf()), emptySeriesOf())
        val located = curation.proposals(result("Kotlin services."))[0]
        assertEquals(true, located["grounded"])
        assertEquals(0, located["begin"])
        assertEquals(100, located["modelBegin"])
        assertEquals(true, located["spanResolved"])
        assertEquals(false, curation.proposals(result("Repeated."))[0]["grounded"])
        assertEquals(false, curation.proposals(result("Invented qualification."))[0]["grounded"])
    }
}
