package borg.trikeshed.jules

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.AppendWal
import borg.trikeshed.utils.kanban.JulesBoardStore
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JulesPatchContinuityTest {
    @Test
    fun laterFileSetRegressionBlocksAutomaticDrainAndRetainsEarlierCandidate() {
        val retained = observation(
            ordinal = 3,
            cidDigit = '1',
            files = listOf("src/Target.kt", "src/TargetTest.kt"),
            candidate = true,
        )
        val regressed = observation(
            ordinal = 4,
            cidDigit = '2',
            files = listOf(".Jules/session.log"),
            missing = retained.touchedFiles,
            candidate = false,
        )

        val selection = assertIs<JulesPatchDrainSelection.ReviewRequired>(
            selectJulesPatchForDrain(listOf(retained, regressed)),
        )

        assertEquals(retained.patchCid, selection.retainedCandidate.patchCid)
        assertEquals(regressed.patchCid, selection.regressedLatest.patchCid)
        assertEquals(retained.touchedFiles, selection.missingFiles)

        val reviewed = JulesCause.PatchReviewSelected(
            patchCid = retained.patchCid,
            causalOrdinal = retained.causalOrdinal,
            latestPatchCid = regressed.patchCid,
            reviewedBy = "operator",
            receiptRef = "review:5063547852779883447:target-test",
            at = 30,
        )
        val explicit = assertIs<JulesPatchDrainSelection.Selected>(
            selectJulesPatchForDrain(listOf(retained, regressed, reviewed)),
        )
        assertTrue(explicit.reviewed)
        assertEquals(retained.patchCid, explicit.snapshot.patchCid)
    }

    @Test
    fun deletionAndRenamePathsParticipateInFileSetContinuity() {
        val patch = """
            diff --git a/src/Deleted.kt b/src/Deleted.kt
            deleted file mode 100644
            --- a/src/Deleted.kt
            +++ /dev/null
            diff --git a/src/OldName.kt b/src/NewName.kt
            similarity index 100%
            rename from src/OldName.kt
            rename to src/NewName.kt
        """.trimIndent()

        assertEquals(
            listOf("src/Deleted.kt", "src/OldName.kt", "src/NewName.kt"),
            julesPatchFiles(patch),
        )
    }

    @Test
    fun snapshotAndReviewSelectionRoundTripThroughCodecAndWalReplay() = runTest {
        val wal = MemoryWal()
        val store = JulesBoardStore(wal)
        val snapshot = JulesSnapshot(
            sessionId = "s-continuity",
            state = "COMPLETED",
            title = "continuity",
            patchBytes = 128,
            headSha = "abc",
            activeCount = 0,
            awaitingCount = 0,
            capturedAt = 10,
        )
        store.append(snapshot, drained = false, cause = null)
        val observed = observation(
            ordinal = 7,
            cidDigit = 'a',
            files = listOf("src/A.kt", "src/Removed.kt"),
            missing = listOf("src/Previous.kt"),
            candidate = false,
        )
        val review = JulesCause.PatchReviewSelected(
            patchCid = observed.patchCid,
            causalOrdinal = observed.causalOrdinal,
            latestPatchCid = observed.patchCid,
            reviewedBy = "alice",
            receiptRef = "receipt:42",
            at = 12,
        )
        store.appendCause(snapshot.sessionId, observed)
        store.appendCause(snapshot.sessionId, review)

        val replayed = store.load().getValue(snapshot.sessionId).causes
        val replayedObserved = assertIs<JulesCause.PatchSnapshotObserved>(replayed[0])
        val replayedReview = assertIs<JulesCause.PatchReviewSelected>(replayed[1])
        assertEquals(observed, replayedObserved)
        assertEquals(review, replayedReview)
    }

    @Test
    fun fullAgentReportRoundTripsButRequiresExplicitSemanticReview() = runTest {
        val wal = MemoryWal()
        val store = JulesBoardStore(wal)
        val snapshot = JulesSnapshot(
            sessionId = "s-report-only",
            state = "COMPLETED",
            title = "report-only",
            patchBytes = 0,
            headSha = "def",
            activeCount = 0,
            awaitingCount = 0,
            capturedAt = 20,
        )
        store.append(snapshot, drained = false, cause = null)
        assertEquals(JulesLane.REVIEW, laneFor(snapshot, drained = false))
        val report = JulesCause.AgentReportObserved(
            reportCid = ContentId("sha256:" + "b".repeat(64)),
            causalOrdinal = 5,
            bytes = 173,
            apiCreateTime = "2026-08-13T10:11:12.123456Z",
            at = 21,
            activityId = "activity-final-report",
            activitySeq = 19,
        )

        val blocked = assertIs<JulesReportSettlementSelection.ReviewRequired>(
            selectJulesReportForSettlement(listOf(report)),
        )
        assertEquals(report, blocked.finalReport)

        val review = JulesCause.AgentReportReviewSelected(
            reportCid = report.reportCid,
            causalOrdinal = report.causalOrdinal,
            latestReportCid = report.reportCid,
            disposition = "no-op-already-satisfied",
            reviewedBy = "alice",
            receiptRef = "review:s-report-only:final",
            at = 22,
        )
        val selected = assertIs<JulesReportSettlementSelection.Selected>(
            selectJulesReportForSettlement(listOf(report, review)),
        )
        assertEquals(report, selected.report)
        assertEquals(review.disposition, selected.disposition)
        assertEquals(review.receiptRef, selected.receiptRef)

        store.appendCause(snapshot.sessionId, report)
        store.appendCause(snapshot.sessionId, review)
        val replayed = store.load().getValue(snapshot.sessionId).causes
        assertEquals(report, assertIs<JulesCause.AgentReportObserved>(replayed[0]))
        assertEquals(review, assertIs<JulesCause.AgentReportReviewSelected>(replayed[1]))
    }

    private fun observation(
        ordinal: Int,
        cidDigit: Char,
        files: List<String>,
        missing: List<String> = emptyList(),
        candidate: Boolean,
    ) = JulesCause.PatchSnapshotObserved(
        patchCid = ContentId("sha256:" + cidDigit.toString().repeat(64)),
        causalOrdinal = ordinal,
        artifactSeq = 0,
        touchedFiles = files,
        missingFromCandidate = missing,
        reviewCandidate = candidate,
        at = 11L + ordinal,
        activityId = "activity-$ordinal",
        activitySeq = ordinal,
    )

    private class MemoryWal : AppendWal {
        override val key: CoroutineContext.Key<*> get() = AppendWal
        private val records = mutableListOf<Pair<String, ByteArray>>()

        override suspend fun append(key: String, payload: ByteArray): Long {
            records += key to payload.copyOf()
            return records.lastIndex.toLong()
        }

        override fun replay(): Sequence<Pair<String, ByteArray>> = records.asSequence()
        override fun close() = Unit
    }
}
