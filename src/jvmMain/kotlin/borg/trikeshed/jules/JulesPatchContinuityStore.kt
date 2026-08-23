package borg.trikeshed.jules

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.utils.kanban.JulesBoardStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Durable boundary for Jules activity patches and complete agent reports.
 *
 * Every textual patch is UTF-8 encoded exactly once at the API boundary, put in
 * CAS before visibility, then bonded to session/activity/ordinal in the board
 * WAL.  Complete agent messages follow the same durable-before-visible rule.
 * The patch fold retains a last-known-good candidate when a later file set
 * regresses; report-only settlement always requires explicit semantic review.
 * No repository, branch, or PR lookup participates in selection.
 */
class JulesPatchContinuityStore(
    private val casStore: FileCasStore,
    private val boardStore: JulesBoardStore,
) {
    /** Persist all unseen snapshots in causal order; returns newly appended facts. */
    suspend fun observe(
        sessionId: String,
        patches: Series<JulesRestClient.ActivityPatch>,
        priorCauses: Iterable<JulesCause>,
    ): List<JulesCause.PatchSnapshotObserved> {
        var causalFacts = priorCauses.filterIsInstance<JulesCause.PatchSnapshotObserved>()
        var appended = emptyList<JulesCause.PatchSnapshotObserved>()
        for (index in 0 until patches.size) {
            val patch = patches[index]
            val bytes = patch.patch.encodeToByteArray()
            val patchCid = withContext(Dispatchers.IO) { casStore.put(bytes) }
            val alreadyObserved = causalFacts.any {
                it.activityId == patch.activityId &&
                    it.artifactSeq == patch.artifactSeq &&
                    it.patchCid == patchCid
            }
            if (alreadyObserved) continue

            val touchedFiles = julesPatchFiles(patch.patch).filterNot(::isScratchPatchPath)
            // Bolt: avoid intermediate Sequence allocations and lazy iterator overhead in hot paths
            var retainedBefore: JulesCause.PatchSnapshotObserved? = null
            val patchOrder = compareBy<JulesCause.PatchSnapshotObserved>({ it.causalOrdinal }, { it.activitySeq }, { it.artifactSeq })
            for (item in causalFacts) {
                if (item.reviewCandidate && item.causalOrdinal < patch.causalOrdinal) {
                    if (retainedBefore == null || patchOrder.compare(item, retainedBefore) > 0) {
                        retainedBefore = item
                    }
                }
            }
            val missing = retainedBefore?.touchedFiles
                ?.filterNot(touchedFiles.toSet()::contains)
                ?.distinct()
                ?: emptyList()
            val fact = JulesCause.PatchSnapshotObserved(
                patchCid = patchCid,
                causalOrdinal = patch.causalOrdinal,
                artifactSeq = patch.artifactSeq,
                touchedFiles = touchedFiles,
                missingFromCandidate = missing,
                reviewCandidate = missing.isEmpty(),
                at = System.currentTimeMillis(),
                activityId = patch.activityId,
                activitySeq = patch.activitySeq,
            )
            boardStore.appendCause(sessionId, fact)
            causalFacts = causalFacts + fact
            appended = appended + fact
        }
        return appended
    }

    /**
     * Persist every unseen complete agent message in causal order.  The board's
     * AgentMessaged excerpt is intentionally not used here: report-only
     * settlement must be able to recover and review the exact full text.
     */
    suspend fun observeReports(
        sessionId: String,
        reports: Series<JulesRestClient.ActivityReport>,
        priorCauses: Iterable<JulesCause>,
    ): List<JulesCause.AgentReportObserved> {
        var causalFacts = priorCauses.filterIsInstance<JulesCause.AgentReportObserved>()
        var appended = emptyList<JulesCause.AgentReportObserved>()
        for (index in 0 until reports.size) {
            val report = reports[index]
            val bytes = report.message.encodeToByteArray()
            val reportCid = withContext(Dispatchers.IO) { casStore.put(bytes) }
            val alreadyObserved = causalFacts.any {
                it.activityId == report.activityId &&
                    it.reportCid == reportCid
            }
            if (alreadyObserved) continue

            val fact = JulesCause.AgentReportObserved(
                reportCid = reportCid,
                causalOrdinal = report.causalOrdinal,
                bytes = bytes.size.toLong(),
                apiCreateTime = report.createTime,
                at = System.currentTimeMillis(),
                activityId = report.activityId,
                activitySeq = report.activitySeq,
            )
            boardStore.appendCause(sessionId, fact)
            causalFacts = causalFacts + fact
            appended = appended + fact
        }
        return appended
    }

    /** Resolve and verify the selected immutable bytes. */
    suspend fun bytes(selection: JulesPatchDrainSelection.Selected): ByteArray =
        withContext(Dispatchers.IO) {
            requireNotNull(casStore.get(selection.snapshot.patchCid)) {
                "missing Jules patch CAS object ${selection.snapshot.patchCid}"
            }
        }

    /** Resolve the exact full report selected for reviewed settlement. */
    suspend fun reportBytes(selection: JulesReportSettlementSelection.Selected): ByteArray =
        withContext(Dispatchers.IO) {
            requireNotNull(casStore.get(selection.report.reportCid)) {
                "missing Jules agent report CAS object ${selection.report.reportCid}"
            }.also { bytes ->
                require(bytes.size.toLong() == selection.report.bytes) {
                    "Jules agent report size mismatch for ${selection.report.reportCid}: " +
                        "WAL=${selection.report.bytes}, CAS=${bytes.size}"
                }
            }
        }

    /** Record a reviewed selection only when it names an observed CAS snapshot. */
    suspend fun selectReviewed(
        sessionId: String,
        patchCid: ContentId,
        causalOrdinal: Int,
        reviewedBy: String,
        receiptRef: String,
        causes: Iterable<JulesCause>,
    ): JulesCause.PatchReviewSelected {
        require(reviewedBy.isNotBlank()) { "reviewedBy must not be blank" }
        require(receiptRef.isNotBlank()) { "receiptRef must not be blank" }
        require(causes
            // Bolt: avoid intermediate List allocations from filterIsInstance
            .any { it is JulesCause.PatchSnapshotObserved &&
            it.patchCid == patchCid && it.causalOrdinal == causalOrdinal
        }) { "snapshot $causalOrdinal/$patchCid was not observed for session $sessionId" }
        // Bolt: avoid intermediate List allocations from filterIsInstance by using sequence for terminal ops
        val patchOrder = compareBy<JulesCause.PatchSnapshotObserved>({ it.causalOrdinal }, { it.activitySeq }, { it.artifactSeq })
        var maxPatch: JulesCause.PatchSnapshotObserved? = null
        val reportOrder = compareBy<JulesCause.AgentReportObserved>({ it.causalOrdinal }, { it.activitySeq }, { it.activityId })
        var maxReport: JulesCause.AgentReportObserved? = null
        for (item in causes) {
            if (item is JulesCause.PatchSnapshotObserved) {
                if (maxPatch == null || patchOrder.compare(item, maxPatch) > 0) {
                    maxPatch = item
                }
            } else if (item is JulesCause.AgentReportObserved) {
                if (maxReport == null || reportOrder.compare(item, maxReport) > 0) {
                    maxReport = item
                }
            }
        }
        val latestPatchCid = maxPatch?.patchCid
        val latestReportCid = maxReport?.reportCid
        val cause = JulesCause.PatchReviewSelected(
            patchCid = patchCid,
            causalOrdinal = causalOrdinal,
            latestPatchCid = latestPatchCid,
            latestReportCid = latestReportCid,
            reviewedBy = reviewedBy,
            receiptRef = receiptRef,
            at = System.currentTimeMillis(),
        )
        boardStore.appendCause(sessionId, cause)
        return cause
    }

    /**
     * Record a typed reject of one observed snapshot chain.  Like a reviewed
     * selection this never settles the session; it makes the named chain
     * eligible for receipt-producing reject settlement, which retires the
     * session without applying any patch.
     */
    suspend fun selectRejected(
        sessionId: String,
        patchCid: ContentId,
        causalOrdinal: Int,
        reason: String,
        reviewedBy: String,
        receiptRef: String,
        causes: Iterable<JulesCause>,
    ): JulesCause.PatchRejected {
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(reviewedBy.isNotBlank()) { "reviewedBy must not be blank" }
        require(receiptRef.isNotBlank()) { "receiptRef must not be blank" }
        require(causes
            // Bolt: avoid intermediate List allocations from filterIsInstance
            .any { it is JulesCause.PatchSnapshotObserved &&
            it.patchCid == patchCid && it.causalOrdinal == causalOrdinal
        }) { "snapshot $causalOrdinal/$patchCid was not observed for session $sessionId" }
        // Bolt: avoid intermediate List allocations from filterIsInstance by using sequence for terminal ops
        val patchOrder = compareBy<JulesCause.PatchSnapshotObserved>({ it.causalOrdinal }, { it.activitySeq }, { it.artifactSeq })
        var maxPatch: JulesCause.PatchSnapshotObserved? = null
        val reportOrder = compareBy<JulesCause.AgentReportObserved>({ it.causalOrdinal }, { it.activitySeq }, { it.activityId })
        var maxReport: JulesCause.AgentReportObserved? = null
        for (item in causes) {
            if (item is JulesCause.PatchSnapshotObserved) {
                if (maxPatch == null || patchOrder.compare(item, maxPatch) > 0) {
                    maxPatch = item
                }
            } else if (item is JulesCause.AgentReportObserved) {
                if (maxReport == null || reportOrder.compare(item, maxReport) > 0) {
                    maxReport = item
                }
            }
        }
        val latestPatchCid = maxPatch?.patchCid
        val latestReportCid = maxReport?.reportCid
        val cause = JulesCause.PatchRejected(
            patchCid = patchCid,
            causalOrdinal = causalOrdinal,
            latestPatchCid = latestPatchCid,
            latestReportCid = latestReportCid,
            reason = reason,
            reviewedBy = reviewedBy,
            receiptRef = receiptRef,
            at = System.currentTimeMillis(),
        )
        boardStore.appendCause(sessionId, cause)
        return cause
    }

    /**
     * Record the operator's semantic disposition for one observed full report.
     * This does not settle the session; it only makes the exact report eligible
     * for a receipt-producing report settlement command.
     */
    suspend fun selectReportReviewed(
        sessionId: String,
        reportCid: ContentId,
        causalOrdinal: Int,
        disposition: String,
        reviewedBy: String,
        receiptRef: String,
        causes: Iterable<JulesCause>,
    ): JulesCause.AgentReportReviewSelected {
        require(disposition.isNotBlank()) { "disposition must not be blank" }
        require(reviewedBy.isNotBlank()) { "reviewedBy must not be blank" }
        require(receiptRef.isNotBlank()) { "receiptRef must not be blank" }
        require(causes
            // Bolt: avoid intermediate List allocations from filterIsInstance
            .any { it is JulesCause.AgentReportObserved &&
            it.reportCid == reportCid && it.causalOrdinal == causalOrdinal
        }) { "agent report $causalOrdinal/$reportCid was not observed for session $sessionId" }
        require(causes.none { it is JulesCause.PatchSnapshotObserved }) {
            "session $sessionId has an observed patch; report-only review cannot discard it"
        }
        require(withContext(Dispatchers.IO) { casStore.get(reportCid) } != null) {
            "missing Jules agent report CAS object $reportCid"
        }
        val cause = JulesCause.AgentReportReviewSelected(
            reportCid = reportCid,
            causalOrdinal = causalOrdinal,
            latestPatchCid = null,
            latestReportCid = run {
                val reportOrder = compareBy<JulesCause.AgentReportObserved>({ it.causalOrdinal }, { it.activitySeq }, { it.activityId })
                var maxReport: JulesCause.AgentReportObserved? = null
                for (item in causes) {
                    if (item is JulesCause.AgentReportObserved) {
                        if (maxReport == null || reportOrder.compare(item, maxReport) > 0) {
                            maxReport = item
                        }
                    }
                }
                maxReport?.reportCid
            },
            disposition = disposition,
            reviewedBy = reviewedBy,
            receiptRef = receiptRef,
            at = System.currentTimeMillis(),
        )
        boardStore.appendCause(sessionId, cause)
        return cause
    }
}
