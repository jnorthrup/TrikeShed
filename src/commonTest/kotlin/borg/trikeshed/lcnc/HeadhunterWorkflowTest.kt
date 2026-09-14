package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HeadhunterWorkflowTest {
    class Fixture {
        val rig = HeadhunterStoreTest.Rig()
        lateinit var evidence: HeadhunterRecord
        lateinit var listing: HeadhunterRecord
        lateinit var application: HeadhunterRecord
        lateinit var unselected: HeadhunterRecord

        suspend fun open() {
            evidence = rig.evidence("Built structured Kotlin channel compositions.")
            listing = rig.listing("Maintain Kotlin services.", mapOf("url" to "https://example.test/roles/42"))
            application = rig.application(listing, mapOf("evidenceIds" to listOf(evidence["id"])))
            unselected = rig.evidence("UNSELECTED PRIVATE RECORD")
        }

        fun request(mode: String = "assemble"): HeadhunterRecord = mapOf(
            "applicationId" to application["id"], "evidenceIds" to listOf(evidence["id"]),
            "mode" to mode, "instructions" to "Keep the wording concise.",
        )

        suspend fun run(workflow: HeadhunterWorkflow, request: HeadhunterRecord): LcncRunner.ScopeResult =
            LcncRunner(workflow.registry()).apply { ledger = LcncConsumedLedger() }
                .runProcedure(HeadhunterWorkflow.program(), mapOf("request" to request))

        fun versions(): Map<String, String> = mapOf(
            evidence["id"] as String to evidence["cid"] as String,
            listing["id"] as String to listing["cid"] as String,
            application["id"] as String to application["cid"] as String,
        )
    }

    @Test fun actualCompositionProducesFourProposalsAndRepeatingAssemblyAddsNoVersions() = runTest {
        val fixture = Fixture().apply { open() }
        val workflow = HeadhunterWorkflow(fixture.rig.store) { error("Evidence assembly must not call a model") }
        val pinned = workflow.pin(fixture.request())
        val first = fixture.run(workflow, pinned)
        val prepared = artifacts(first)
        assertEquals(setOf("resume", "email", "phone", "qa"), prepared.view.map { fields(it)["type"] }.toSet())
        assertEquals(setOf("request", "context", "draft", "save", "artifacts", "collisions"), first.nodeOutputs.keys)
        assertEquals(setOf("artifacts", "collisions"), first.returns.keys)
        assertEquals(fixture.versions(), first.consumed.associate { it["id"] as String to it["cid"] as String })
        assertTrue(first.consumed.all { it["kind"] == "headhunter" })
        assertNotNull(first.inputFingerprint)
        assertFalse(first.consumedTruncated)
        for (artifact in prepared.view) {
            val fields = fields(artifact)
            assertEquals("proposed", fields["reviewStatus"])
            assertEquals("assembled", fields["generation"])
            assertEquals("markdown", fields["format"])
            assertEquals(fixture.versions(), HeadhunterStore.references(fields).view.associate { it.pair })
            assertEquals(artifact, fixture.rig.store.record(artifact["id"] as String))
            assertFalse("UNSELECTED PRIVATE RECORD" in fields["content"].toString())
        }
        val revision = fixture.rig.board.snapshot().revision
        val committed = fixture.rig.log.committed
        val repeated = fixture.run(workflow, pinned)
        assertEquals(prepared.view.associate { it["id"] to it["cid"] }, artifacts(repeated).view.associate { it["id"] to it["cid"] })
        assertEquals(first.inputFingerprint, repeated.inputFingerprint)
        assertEquals(revision, fixture.rig.board.snapshot().revision)
        assertEquals(committed, fixture.rig.log.committed)
        for (artifact in artifacts(repeated).view) assertEquals(1, fixture.rig.store.history(artifact["id"] as String).size)
    }

    @Test fun replayKeepsOldEvidenceWhileFreshPinUsesTheCorrectionAndPreservesPriorDrafts() = runTest {
        val fixture = Fixture().apply { open() }
        val workflow = HeadhunterWorkflow(fixture.rig.store) { error("No model expected") }
        val pinned = workflow.pin(fixture.request())
        val first = fixture.run(workflow, pinned)
        val oldEvidenceCid = fixture.evidence["cid"] as String
        val correction = fixture.rig.save("evidence", fields(fixture.evidence) + ("text" to "Built and measured structured Kotlin channel compositions."), fixture.evidence)
        val replay = fixture.run(workflow, pinned)
        assertEquals(first.inputFingerprint, replay.inputFingerprint)
        assertEquals(artifacts(first).view.associate { it["id"] to it["cid"] }, artifacts(replay).view.associate { it["id"] to it["cid"] })

        val fresh = fixture.run(workflow, workflow.pin(fixture.request()))
        assertNotEquals(first.inputFingerprint, fresh.inputFingerprint)
        for (artifact in artifacts(fresh).view) {
            val refs = HeadhunterStore.references(fields(artifact))
            assertTrue(refs.view.any { it.a == fixture.evidence["id"] && it.b == correction["cid"] })
            assertEquals("proposed", fields(artifact)["reviewStatus"])
        }
        for (artifact in artifacts(first).view) {
            val retained = fixture.rig.store.record(artifact["id"] as String)!!
            assertEquals(artifact["cid"], retained["cid"])
            assertTrue(HeadhunterStore.references(fields(retained)).view.any { it.a == fixture.evidence["id"] && it.b == oldEvidenceCid })
        }
        assertEquals(8, fixture.rig.store.records().view.count { it["kind"] == "artifact" })
    }

    @Test fun repeatingPreparationRetainsTheUsersEditedArtifactAndItsExactVersionReview() = runTest {
        val fixture = Fixture().apply { open() }
        val workflow = HeadhunterWorkflow(fixture.rig.store) { error("No model expected") }
        val pinned = workflow.pin(fixture.request())
        val first = fixture.run(workflow, pinned)
        val resume = artifacts(first).view.single { fields(it)["type"] == "resume" }
        val edited = fixture.rig.save("artifact", fields(resume) + mapOf(
            "title" to "Reviewed wording", "content" to "Built structured Kotlin channel compositions; selected wording corrected by the user.",
        ), resume)
        val review = fixture.rig.save("review", mapOf(
            "subjectId" to edited["id"], "subjectCid" to edited["cid"], "decision" to "approved", "notes" to "Checked against the selected source",
        ))
        val revision = fixture.rig.board.snapshot().revision
        val committed = fixture.rig.log.committed
        val repeated = fixture.run(workflow, pinned)
        val returned = artifacts(repeated).view.single { it["id"] == edited["id"] }
        assertEquals(edited, returned)
        assertEquals(review, fixture.rig.store.record(review["id"] as String))
        assertEquals(returned["cid"], fields(review)["subjectCid"])
        assertEquals(2, fixture.rig.store.history(edited["id"] as String).size)
        assertEquals(revision, fixture.rig.board.snapshot().revision)
        assertEquals(committed, fixture.rig.log.committed)
        assertFailsWith<IllegalArgumentException> {
            fixture.rig.store.save("artifact", resume["id"] as String, resume["cid"] as String, fields(resume))
        }

        fixture.rig.save("evidence", fields(fixture.evidence) + ("text" to "Built and measured structured Kotlin channel compositions."), fixture.evidence)
        val fresh = fixture.run(workflow, workflow.pin(fixture.request()))
        assertTrue(artifacts(fresh).view.none { it["id"] == edited["id"] })
        assertEquals(edited, fixture.rig.store.record(edited["id"] as String))
        assertEquals(review, fixture.rig.store.record(review["id"] as String))
        assertEquals(8, fixture.rig.store.records().view.count { it["kind"] == "artifact" })
    }

    @Test fun collisionReviewReturnsActualPriorRecordsAlongsideTheArtifacts() = runTest {
        val fixture = Fixture().apply { open() }
        val mirroredListing = fixture.rig.listing("Mirror of the same vacancy", mapOf("url" to "https://example.test/roles/42"))
        val previousApplication = fixture.rig.application(mirroredListing)
        val action = fixture.rig.save("action", mapOf("applicationId" to previousApplication["id"], "type" to "email", "status" to "reported", "notes" to "User reports prior recruiter contact"))
        val workflow = HeadhunterWorkflow(fixture.rig.store) { error("No model expected") }
        val result = fixture.run(workflow, fixture.request())
        val collisions = objects(result.returns["collisions"])
        assertTrue(collisions.view.any { it["id"] == mirroredListing["id"] && it["reason"] == "same-listing-url" })
        assertTrue(collisions.view.any { it["id"] == previousApplication["id"] })
        assertTrue(collisions.view.any { it["id"] == action["id"] })
        for (collision in collisions.view) {
            for (evidence in objects(collision["evidence"]).view) {
                for (ref in HeadhunterStore.references(evidence).view) assertNotNull(fixture.rig.store.version(ref.a, ref.b))
            }
        }
        assertEquals(4, artifacts(result).size)
        assertEquals("research", fields(fixture.rig.store.record(fixture.application["id"] as String)!!)["status"])
    }

    @Test fun configuredModelExecutesInsideItsInvocationAndOnlySelectedRecordsEnterThePrompt() = runTest {
        val fixture = Fixture().apply { open() }
        var prompt = ""
        var invocation: LcncNodeElement? = null
        lateinit var workflow: HeadhunterWorkflow
        workflow = HeadhunterWorkflow(fixture.rig.store) { supplied ->
            prompt = supplied
            assertSame(workflow, currentCoroutineContext()[HeadhunterWorkflowKey]?.value)
            invocation = assertNotNull(currentCoroutineContext()[LcncNodeKey.HEADHUNTER_DRAFT])
            val citation = "[${fixture.evidence["id"]}@${fixture.evidence["cid"]}]"
            JsonSupport.stringify(mapOf(
                "resume" to "Built structured Kotlin channel compositions. $citation",
                "email" to "Please review the selected experience. $citation",
                "phone" to "Discuss the selected experience. $citation",
                "qa" to "Which responsibilities matched this experience? $citation",
            )) j "configured-model-fixture"
        }
        val result = fixture.run(workflow, fixture.request("model"))
        assertTrue(fields(fixture.evidence)["text"].toString() in prompt)
        assertTrue(fixture.evidence["cid"].toString() in prompt)
        assertFalse("UNSELECTED PRIVATE RECORD" in prompt)
        assertFalse(fixture.unselected["id"].toString() in prompt)
        assertTrue(assertNotNull(invocation).supervisor.isCompleted)
        assertTrue(invocation?.outcome is LcncNodeOutcome.Returned)
        for (artifact in artifacts(result).view) {
            assertEquals("model", fields(artifact)["generation"])
            assertEquals("configured-model-fixture", fields(artifact)["model"])
            assertEquals("proposed", fields(artifact)["reviewStatus"])
            assertEquals(fixture.versions(), HeadhunterStore.references(fields(artifact)).view.associate { it.pair })
        }
    }

    @Test fun providerFailureAndMalformedModelOutputSaveNoArtifacts() = runTest {
        val valid = JsonSupport.stringify(mapOf("resume" to "r", "email" to "e", "phone" to "p", "qa" to "q"))
        for (response in s_[
            null,
            "{\"resume\":\"only one artifact\"}",
            valid + " trailing non-JSON output",
            JsonSupport.stringify(mapOf("resume" to "", "email" to "e", "phone" to "p", "qa" to "q")),
            JsonSupport.stringify(mapOf("resume" to "r".repeat(24001), "email" to "e", "phone" to "p", "qa" to "q")),
        ].view) {
            val fixture = Fixture().apply { open() }
            val before = fixture.rig.log.committed
            val revision = fixture.rig.board.snapshot().revision
            var invocation: LcncNodeElement? = null
            val workflow = HeadhunterWorkflow(fixture.rig.store) {
                invocation = currentCoroutineContext()[LcncNodeKey.HEADHUNTER_DRAFT]
                if (response == null) error("Provider unavailable")
                response j "configured-model-fixture"
            }
            assertFails { fixture.run(workflow, fixture.request("model")) }
            assertEquals(0, fixture.rig.store.records().view.count { it["kind"] == "artifact" })
            assertEquals(before, fixture.rig.log.committed)
            assertEquals(revision, fixture.rig.board.snapshot().revision)
            assertTrue(assertNotNull(invocation).supervisor.isCompleted)
            assertTrue(invocation?.outcome is LcncNodeOutcome.Failed)
        }
    }

    companion object {
        fun objects(value: Any?): Series<HeadhunterRecord> =
            (value as List<*>).toSeries() α { objectOf(it, "record") }
        fun artifacts(result: LcncRunner.ScopeResult) = objects(result.returns["artifacts"])
        fun fields(record: HeadhunterRecord) = HeadhunterStore.fieldsOf(record)
    }
}
