package borg.trikeshed.lcnc

import borg.trikeshed.lcnc.HeadhunterStoreTest.Companion.cid
import borg.trikeshed.lcnc.HeadhunterStoreTest.Companion.fields
import borg.trikeshed.lcnc.HeadhunterStoreTest.Companion.id
import borg.trikeshed.lcnc.HeadhunterStoreTest.Rig
import borg.trikeshed.lib.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HeadhunterCaptureTest {
    @Test fun concurrentAcquisitionsOfTheSameOriginalCommitOneSourceAndEvidence() = runTest {
        val rig = Rig()
        val original = rig.cas.put("local document bytes".encodeToByteArray()).value
        val first = async { capture(rig, original, 1) }
        val second = async { capture(rig, original, 2) }
        val one = first.await()
        val two = second.await()
        assertEquals(one.a, two.a)
        assertEquals(one.b, two.b)
        assertEquals(2, rig.store.records().size)
        assertEquals(2, rig.log.committed)
        assertEquals(1, rig.store.history(id(one.a)).size)
        assertEquals(1, rig.store.history(id(checkNotNull(one.b))).size)
    }

    @Test fun reacquisitionAndReplayReuseTheCorrectedHeadAndItsReview() = runTest {
        val rig = Rig()
        val original = rig.cas.put("local original".encodeToByteArray()).value
        val captured = capture(rig, original, 1)
        val initial = checkNotNull(captured.b)
        val corrected = rig.save("evidence", fields(initial) + mapOf(
            "text" to "User-corrected professional history", "title" to "Curated resume", "category" to "correction",
        ), initial)
        val review = rig.save("review", mapOf("subjectId" to id(corrected), "subjectCid" to cid(corrected), "decision" to "approved"))
        val committed = rig.log.committed
        val reopened = Rig(rig.cas, rig.log)
        reopened.store.restore()
        val revision = reopened.board.snapshot().revision
        val repeated = capture(reopened, original, 300)
        assertEquals(captured.a, repeated.a, "An acquisition timestamp is not a source revision")
        assertEquals(corrected, repeated.b)
        assertEquals(review, reopened.store.record(id(review)))
        assertEquals(initial, reopened.store.version(id(initial), cid(initial)))
        assertEquals(2, reopened.store.history(id(initial)).size)
        assertEquals(committed, rig.log.committed)
        assertEquals(revision, reopened.board.snapshot().revision)
    }

    @Test fun relativePathsAccumulateOnTheSourceWithoutRevisingEvidenceOrItsPinnedSource() = runTest {
        val rig = Rig()
        val original = rig.cas.put("one file in several folders".encodeToByteArray()).value
        val first = capture(rig, original, 1, "current/resume.docx")
        val firstEvidence = checkNotNull(first.b)
        val again = rig.store.capture("evidence",
            source(original, 2, "archive/CV.docx") + mapOf("filename" to "CV.docx", "relativePaths" to listOf("backup/copy.docx", "archive/CV.docx")),
            evidence(original, 2))
        assertEquals(id(first.a), id(again.a))
        assertNotEquals(cid(first.a), cid(again.a))
        assertEquals("resume.docx", fields(again.a)["filename"])
        assertEquals("current/resume.docx", fields(again.a)["relativePath"])
        assertEquals(listOf("current/resume.docx", "archive/CV.docx", "backup/copy.docx"), fields(again.a)["relativePaths"])
        assertEquals(firstEvidence, again.b)
        assertEquals(cid(first.a), fields(firstEvidence)["sourceCid"], "The evidence pins the source used for its extraction")
        assertEquals(1, rig.store.history(id(firstEvidence)).size)
        val committed = rig.log.committed
        val reopened = Rig(rig.cas, rig.log)
        val repeated = capture(reopened, original, 3, "archive/CV.docx")
        assertEquals(again.a, repeated.a)
        assertEquals(again.b, repeated.b)
        assertEquals(committed, rig.log.committed)
    }

    @Test fun sameFilenameWithDifferentBytesProducesDistinctOriginals() = runTest {
        val rig = Rig()
        val first = capture(rig, rig.cas.put("first document".encodeToByteArray()).value, 1)
        val second = capture(rig, rig.cas.put("changed document".encodeToByteArray()).value, 2)
        assertNotEquals(id(first.a), id(second.a))
        assertNotEquals(id(checkNotNull(first.b)), id(checkNotNull(second.b)))
        assertEquals(4, rig.store.records().size)
    }

    @Test fun identicalUrlBodiesDoNotMergeDifferentSourcesOrExtractionProcedures() = runTest {
        val rig = Rig()
        val original = rig.cas.put("shared body bytes".encodeToByteArray()).value
        val uploaded = capture(rig, original, 1)
        val remote = source(original, 2) + mapOf("origin" to "https://example.test", "url" to "https://example.test/profile")
        val first = rig.store.capture("evidence", remote + ("extraction" to mapOf("startAfter" to "Experience")), evidence(original, 2))
        val second = rig.store.capture("evidence", remote + ("extraction" to mapOf("startAfter" to "Projects")), evidence(original, 2))
        assertNotEquals(id(uploaded.a), id(first.a))
        assertNotEquals(id(first.a), id(second.a))
        assertNotEquals(id(checkNotNull(first.b)), id(checkNotNull(second.b)))
        assertEquals(mapOf("startAfter" to "Experience"), fields(first.a)["extraction"])
        assertEquals(mapOf("startAfter" to "Projects"), fields(second.a)["extraction"])
        assertEquals(6, rig.store.records().size)
    }

    @Test fun explicitEvidenceVersionsStillRejectStaleCaptureAndPermitAnIntentionalUpdate() = runTest {
        val rig = Rig()
        val original = rig.cas.put("original bytes".encodeToByteArray()).value
        val first = capture(rig, original, 1)
        val initial = checkNotNull(first.b)
        val corrected = rig.save("evidence", fields(initial) + ("text" to "Corrected"), initial)
        assertFailsWith<IllegalArgumentException> {
            rig.store.capture("evidence", source(original, 2), evidence(original, 2), id = id(initial), baseCid = cid(initial))
        }
        assertEquals(corrected, rig.store.record(id(initial)))
        val explicit = rig.store.capture("evidence", source(original, 3), evidence(original, 3) + ("text" to "Explicit re-extraction"),
            sourceId = id(first.a), sourceBaseCid = cid(first.a), id = id(initial), baseCid = cid(corrected))
        assertEquals(id(initial), id(checkNotNull(explicit.b)))
        assertEquals(cid(corrected), explicit.b!!["previousCid"])
        assertEquals("Explicit re-extraction", fields(explicit.b!!)["text"])
        assertEquals(3, rig.store.history(id(initial)).size)
    }

    @Test fun legacyDuplicatesAndTheirHistoryRemainWhileDefaultIntakeReusesTheFirstEvidence() = runTest {
        val rig = Rig()
        val original = rig.cas.put("previously duplicated original".encodeToByteArray()).value
        val sourceOne = rig.save("source", source(original, 1))
        val evidenceOne = rig.save("evidence", evidence(original, 1) + mapOf("sourceId" to id(sourceOne), "sourceCid" to cid(sourceOne)))
        val sourceTwo = rig.save("source", source(original, 2) + ("extraction" to mapOf("startAfter" to "Resume")))
        val evidenceTwo = rig.save("evidence", evidence(original, 2) + mapOf("sourceId" to id(sourceTwo), "sourceCid" to cid(sourceTwo)))
        val corrected = rig.save("evidence", fields(evidenceOne) + ("text" to "Retained correction"), evidenceOne)
        val committed = rig.log.committed
        val default = capture(rig, original, 3)
        assertEquals(sourceOne, default.a)
        assertEquals(corrected, default.b)
        val selected = rig.store.capture("evidence", source(original, 4), evidence(original, 4), sourceId = id(sourceTwo), sourceBaseCid = cid(sourceTwo))
        assertEquals(sourceTwo, selected.a)
        assertEquals(evidenceTwo, selected.b)
        assertEquals(4, rig.store.records().size)
        assertEquals(evidenceOne, rig.store.version(id(evidenceOne), cid(evidenceOne)))
        assertEquals(evidenceTwo, rig.store.record(id(evidenceTwo)))
        assertEquals(committed, rig.log.committed)
    }

    @Test fun aDurableSourceWithoutEvidenceCanBeCompletedAfterReopening() = runTest {
        val rig = Rig()
        val original = rig.cas.put("stored before extraction completed".encodeToByteArray()).value
        val incomplete = rig.store.capture("evidence", source(original, 1), null)
        assertNull(incomplete.b)
        val reopened = Rig(rig.cas, rig.log)
        val complete = capture(reopened, original, 2)
        assertEquals(incomplete.a, complete.a)
        assertNotNull(complete.b)
        assertEquals(2, rig.log.committed)
        assertEquals(2, reopened.store.records().size)
    }

    companion object {
        fun source(originalCid: String, attempt: Int, path: String = "resume.docx"): HeadhunterRecord = mapOf(
            "title" to "Resume", "origin" to "upload", "originalCid" to originalCid,
            "filename" to "resume.docx", "relativePath" to path, "captureKind" to "evidence",
            "lastCapture" to mapOf("acquiredAtMs" to attempt, "status" to "ready", "acquisition" to "import"),
        )

        fun evidence(originalCid: String, attempt: Int): HeadhunterRecord = mapOf(
            "title" to "Resume", "text" to "Extracted professional history", "category" to "resume",
            "originalCid" to originalCid, "filename" to "resume.docx",
            "provenance" to mapOf("acquiredAtMs" to attempt, "acquisition" to "import"),
        )

        suspend fun capture(rig: Rig, originalCid: String, attempt: Int, path: String = "resume.docx") =
            rig.store.capture("evidence", source(originalCid, attempt, path), evidence(originalCid, attempt))
    }
}
