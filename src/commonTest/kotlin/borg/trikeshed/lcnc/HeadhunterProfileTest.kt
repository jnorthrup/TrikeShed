package borg.trikeshed.lcnc

import borg.trikeshed.lcnc.HeadhunterStoreTest.Companion.cid
import borg.trikeshed.lcnc.HeadhunterStoreTest.Companion.fields
import borg.trikeshed.lcnc.HeadhunterStoreTest.Companion.id
import borg.trikeshed.lcnc.HeadhunterStoreTest.Rig
import borg.trikeshed.lib.get
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HeadhunterProfileTest {
    @Test fun profilesRetainExactSourcesAndClaimReviewsAfterCorrectionAndReplay() = runTest {
        val rig = Rig()
        val evidence = rig.evidence()
        val profile = rig.save("profile", profile(rig, evidence))
        val approved = rig.save("review", mapOf("subjectId" to id(profile), "subjectCid" to cid(profile), "claimId" to "kotlin", "decision" to "approved"))
        rig.save("evidence", fields(evidence) + ("text" to "Corrected professional history"), evidence)
        val reopened = Rig(rig.cas, rig.log)
        val restored = assertNotNull(reopened.store.record(id(profile)))
        assertEquals(profile, restored)
        assertEquals(approved, reopened.store.record(id(approved)))
        assertEquals(cid(evidence), HeadhunterStore.references(fields(restored))[0].b)
        assertEquals(evidence, reopened.store.version(id(evidence), cid(evidence)))
        assertEquals("proposed", fields(restored)["reviewStatus"], "Review decisions remain separate, versioned records")
    }

    @Test fun profileReplayPreservesUserCorrectionsAndStaleClaimReviewsAreRejected() = runTest {
        val rig = Rig()
        val originalFields = profile(rig, rig.evidence())
        val original = rig.save("profile", originalFields)
        val corrected = rig.save("profile", fields(original) + ("claims" to listOf(mapOf("id" to "channels", "text" to "Structured channel composition", "grounded" to true))), original)
        val committed = rig.log.committed
        assertEquals(corrected, rig.save("profile", originalFields))
        assertEquals(committed, rig.log.committed)
        assertFailsWith<IllegalArgumentException> {
            rig.save("review", mapOf("subjectId" to id(original), "subjectCid" to cid(original), "claimId" to "kotlin", "decision" to "approved"))
        }
        assertFailsWith<IllegalArgumentException> {
            rig.save("review", mapOf("subjectId" to id(corrected), "subjectCid" to cid(corrected), "claimId" to "kotlin", "decision" to "approved"))
        }
        val review = rig.save("review", mapOf("subjectId" to id(corrected), "subjectCid" to cid(corrected), "claimId" to "channels", "decision" to "approved"))
        assertEquals(cid(corrected), fields(review)["subjectCid"])
    }

    @Test fun claimApprovalRequiresGroundingAndUnchangedSourceHeads() = runTest {
        val rig = Rig()
        val source = rig.evidence()
        val ungrounded = rig.save("profile", profile(rig, source) + ("claims" to listOf(mapOf("id" to "unsupported", "text" to "Unsupported proposal", "grounded" to false))))
        val review = mapOf("subjectId" to id(ungrounded), "subjectCid" to cid(ungrounded), "claimId" to "unsupported", "decision" to "approved")
        assertFailsWith<IllegalArgumentException> { rig.save("review", review) }
        assertEquals("changes-requested", fields(rig.save("review", review + ("decision" to "changes-requested")))["decision"])

        val grounded = rig.save("profile", profile(rig, source))
        val proposal = mapOf("subjectId" to id(grounded), "subjectCid" to cid(grounded), "claimId" to "kotlin", "decision" to "approved")
        rig.save("evidence", fields(source) + ("text" to "Changed source"), source)
        assertFailsWith<IllegalArgumentException> { rig.save("review", proposal) }
        assertEquals("rejected", fields(rig.save("review", proposal + ("decision" to "rejected")))["decision"])
    }

    @Test fun aProfileRequiresItsReceiptSourcesAndModelWithoutSelfApproving() = runTest {
        val rig = Rig()
        val source = rig.evidence()
        val valid = profile(rig, source)
        assertFailsWith<IllegalArgumentException> { rig.save("profile", valid - "receiptCid") }
        assertFailsWith<IllegalArgumentException> { rig.save("profile", valid + ("receiptCid" to "sha256:" + "0".repeat(64))) }
        assertFailsWith<IllegalArgumentException> { rig.save("profile", valid + ("sources" to emptyList<Any>())) }
        assertFailsWith<IllegalArgumentException> { rig.save("profile", valid + ("sources" to listOf(mapOf("id" to "evidence/absent", "cid" to cid(source))))) }
        assertFailsWith<IllegalArgumentException> { rig.save("profile", valid + ("model" to " ")) }
        assertFailsWith<IllegalArgumentException> { rig.save("profile", valid + ("claims" to "Unstructured assertion")) }
        assertFailsWith<IllegalArgumentException> { rig.save("profile", valid + ("reviewStatus" to "reviewed")) }
        assertEquals(1, rig.log.committed)
        assertFailsWith<IllegalArgumentException> {
            rig.save("review", mapOf("subjectId" to id(source), "subjectCid" to cid(source), "claimId" to "kotlin", "decision" to "approved"))
        }
    }

    companion object {
        fun profile(rig: Rig, source: HeadhunterRecord): HeadhunterRecord = mapOf(
            "title" to "Professional profile", "model" to "fixture-model", "reviewStatus" to "proposed",
            "receiptCid" to rig.cas.put("Model response containing proposed claims".encodeToByteArray()).value,
            "sources" to listOf(HeadhunterStore.reference(source)),
            "claims" to listOf(mapOf("id" to "kotlin", "text" to "Kotlin experience", "grounded" to true)),
        )
    }
}
