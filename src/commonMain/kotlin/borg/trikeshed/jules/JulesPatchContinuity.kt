package borg.trikeshed.jules

import borg.trikeshed.job.ContentId

/** Result of reducing immutable activity-patch observations for one drain. */
sealed interface JulesPatchDrainSelection {
    /** No activity patch has been observed; callers may inspect session outputs. */
    data object Unobserved : JulesPatchDrainSelection

    /** A CAS snapshot is safe to drain, either monotonically or by review. */
    data class Selected(
        val snapshot: JulesCause.PatchSnapshotObserved,
        val reviewed: Boolean,
        val receiptRef: String? = null,
    ) : JulesPatchDrainSelection

    /**
     * The latest snapshot lost files from [retainedCandidate].  Both hashes stay
     * addressable, but neither recency nor a branch/PR authorizes automatic
     * drain; a [JulesCause.PatchReviewSelected] must choose one explicitly.
     */
    data class ReviewRequired(
        val retainedCandidate: JulesCause.PatchSnapshotObserved,
        val regressedLatest: JulesCause.PatchSnapshotObserved,
        val missingFiles: List<String>,
    ) : JulesPatchDrainSelection

    /**
     * A typed reject, bonded to an operator receipt, closed the chain: the
     * session settles as rejected without applying any observed patch.
     */
    data class Rejected(
        val rejectedSnapshot: JulesCause.PatchSnapshotObserved,
        val reason: String,
        val reviewedBy: String,
        val receiptRef: String,
    ) : JulesPatchDrainSelection
}

/**
 * Result of reducing durable agent reports for report-only settlement.
 * Merely observing a report never authorizes a semantic no-op disposition.
 */
sealed interface JulesReportSettlementSelection {
    data object Unobserved : JulesReportSettlementSelection

    /** The latest complete report is addressable but still needs human review. */
    data class ReviewRequired(
        val finalReport: JulesCause.AgentReportObserved,
    ) : JulesReportSettlementSelection

    /** One report and disposition were explicitly bonded to a review receipt. */
    data class Selected(
        val report: JulesCause.AgentReportObserved,
        val disposition: String,
        val reviewedBy: String,
        val receiptRef: String,
    ) : JulesReportSettlementSelection
}

/**
 * Pure causal reducer for Jules cumulative snapshots.
 *
 * The API's latest artifact is an observation, not truth.  A snapshot becomes
 * the automatic candidate only if its file set contains the previous candidate
 * file set.  An explicit review selection, bonded to a receipt reference,
 * overrides this monotonicity gate without losing either CAS object.
 */
fun selectJulesPatchForDrain(causes: Iterable<JulesCause>): JulesPatchDrainSelection {
    val causalList = causes.toList()
    val observations = causalList.filterIsInstance<JulesCause.PatchSnapshotObserved>()
    if (observations.isEmpty()) return JulesPatchDrainSelection.Unobserved

    // A typed reject, bonded to a receipt and posted after every producer
    // artifact, closes the chain: the session settles as rejected without
    // applying any observed patch.  Recency never launders a reject away and
    // a reject never discards the observed evidence it names.
    val rejectIndex = causalList.indexOfLast { it is JulesCause.PatchRejected }
    val latestObservationIndex = causalList.indexOfLast { it is JulesCause.PatchSnapshotObserved }
    val reject = causalList.getOrNull(rejectIndex) as? JulesCause.PatchRejected
    if (reject != null && rejectIndex > latestObservationIndex) {
        val latestPatchCid = observations.maxWith(snapshotCausalOrder).patchCid
        val latestReportCid = causalList.filterIsInstance<JulesCause.AgentReportObserved>()
            .maxWithOrNull(reportCausalOrder)?.reportCid
        val rejected = observations.lastOrNull {
            it.patchCid == reject.patchCid && it.causalOrdinal == reject.causalOrdinal
        }
        if (rejected != null &&
            reject.latestPatchCid == latestPatchCid &&
            reject.latestReportCid == latestReportCid &&
            reject.reason.isNotBlank() &&
            reject.reviewedBy.isNotBlank() &&
            reject.receiptRef.isNotBlank()
        ) {
            return JulesPatchDrainSelection.Rejected(
                rejectedSnapshot = rejected,
                reason = reject.reason,
                reviewedBy = reject.reviewedBy,
                receiptRef = reject.receiptRef,
            )
        }
    }

    val explicitIndex = causalList.indexOfLast { it is JulesCause.PatchReviewSelected }
    val explicit = causalList.getOrNull(explicitIndex) as? JulesCause.PatchReviewSelected
    if (explicit != null && explicitIndex > latestObservationIndex) {
        val latestPatchCid = observations.maxWith(snapshotCausalOrder).patchCid
        val latestReportCid = causalList.filterIsInstance<JulesCause.AgentReportObserved>()
            .maxWithOrNull(reportCausalOrder)?.reportCid
        val selected = observations.lastOrNull {
            it.patchCid == explicit.patchCid && it.causalOrdinal == explicit.causalOrdinal
        }
        if (selected != null &&
            explicit.latestPatchCid == latestPatchCid &&
            explicit.latestReportCid == latestReportCid &&
            explicit.reviewedBy.isNotBlank() &&
            explicit.receiptRef.isNotBlank()
        ) {
            return JulesPatchDrainSelection.Selected(
                snapshot = selected,
                reviewed = true,
                receiptRef = explicit.receiptRef,
            )
        }
    }

    val latest = observations.maxWith(snapshotCausalOrder)
    // The first immutable snapshot is eligible automatically. Once the
    // producer emits a different CID, filename inclusion is diagnostic only:
    // it cannot prove that earlier hunks survived inside the same file.
    val distinctCids = observations.map { it.patchCid }.distinct()
    if (distinctCids.size == 1 && latest.reviewCandidate) {
        return JulesPatchDrainSelection.Selected(latest, reviewed = false)
    }

    val retained = observations
        .asSequence()
        .filter { it.reviewCandidate && it.causalOrdinal < latest.causalOrdinal }
        .maxWithOrNull(snapshotCausalOrder)
        ?: return JulesPatchDrainSelection.ReviewRequired(latest, latest, latest.missingFromCandidate)
    return JulesPatchDrainSelection.ReviewRequired(
        retainedCandidate = retained,
        regressedLatest = latest,
        missingFiles = latest.missingFromCandidate,
    )
}

/**
 * Select an exact report only through an explicit semantic review cause.
 * Without that cause the latest full report remains reviewable in CAS but is
 * not interpreted as "no-op", "already landed", or any other disposition.
 */
fun selectJulesReportForSettlement(causes: Iterable<JulesCause>): JulesReportSettlementSelection {
    val causalList = causes.toList()
    val reports = causalList.filterIsInstance<JulesCause.AgentReportObserved>()
    if (reports.isEmpty()) return JulesReportSettlementSelection.Unobserved

    val finalReport = reports.maxWith(reportCausalOrder)
    val reviewIndex = causalList.indexOfLast { it is JulesCause.AgentReportReviewSelected }
    // A report-only disposition is authorized only after every producer
    // artifact currently observed. A later patch invalidates an older no-op
    // review just as a later report does.
    val latestProducerArtifactIndex = causalList.indexOfLast {
        it is JulesCause.AgentReportObserved || it is JulesCause.PatchSnapshotObserved
    }
    val review = causalList.getOrNull(reviewIndex) as? JulesCause.AgentReportReviewSelected
    val latestPatchCid = causalList.filterIsInstance<JulesCause.PatchSnapshotObserved>()
        .maxWithOrNull(snapshotCausalOrder)?.patchCid
    if (review != null && reviewIndex > latestProducerArtifactIndex &&
        // Report-only settlement cannot discard a delivered patch. Such a
        // session must settle the patch path (or receive a future typed reject).
        latestPatchCid == null &&
        review.latestPatchCid == null &&
        review.latestReportCid == finalReport.reportCid &&
        review.disposition.isNotBlank() &&
        review.reviewedBy.isNotBlank() &&
        review.receiptRef.isNotBlank()
    ) {
        finalReport.takeIf {
            it.reportCid == review.reportCid && it.causalOrdinal == review.causalOrdinal
        }?.let { report ->
            return JulesReportSettlementSelection.Selected(
                report = report,
                disposition = review.disposition,
                reviewedBy = review.reviewedBy,
                receiptRef = review.receiptRef,
            )
        }
    }

    return JulesReportSettlementSelection.ReviewRequired(
        finalReport,
    )
}

private val snapshotCausalOrder = compareBy<JulesCause.PatchSnapshotObserved>(
    { it.causalOrdinal },
    { it.activitySeq },
    { it.artifactSeq },
    { it.patchCid.value },
)

private val reportCausalOrder = compareBy<JulesCause.AgentReportObserved>(
    { it.causalOrdinal },
    { it.activitySeq },
    { it.activityId },
    { it.reportCid.value },
)

/**
 * Parse every repository path named by a unified diff without materialization
 * state.  Both sides are retained so deletions and renames cannot masquerade
 * as a file-set regression merely because `+++` is `/dev/null` or a rename has
 * no hunk body.
 */
fun julesPatchFiles(patch: String): List<String> = patch.lineSequence().mapNotNull { line ->
    when {
        line.startsWith("--- a/") -> line.removePrefix("--- a/").trim()
            .takeIf { it.isNotEmpty() && it != "/dev/null" }
        line.startsWith("+++ b/") -> line.removePrefix("+++ b/").trim()
            .takeIf { it.isNotEmpty() && it != "/dev/null" }
        line.startsWith("rename from ") -> line.removePrefix("rename from ").trim()
            .takeIf(String::isNotEmpty)
        line.startsWith("rename to ") -> line.removePrefix("rename to ").trim()
            .takeIf(String::isNotEmpty)
        line.startsWith("copy from ") -> line.removePrefix("copy from ").trim()
            .takeIf(String::isNotEmpty)
        line.startsWith("copy to ") -> line.removePrefix("copy to ").trim()
            .takeIf(String::isNotEmpty)
        line.startsWith("+++ ") && !line.startsWith("+++ /dev/null") ->
            line.removePrefix("+++ ").trim()
                .takeIf { it.isNotEmpty() && it != "/dev/null" && !it.startsWith("a/") && !it.startsWith("b/") }
        line.startsWith("--- ") && !line.startsWith("--- /dev/null") ->
            line.removePrefix("--- ").trim()
                .takeIf { it.isNotEmpty() && it != "/dev/null" && !it.startsWith("a/") && !it.startsWith("b/") }
        else -> null
    }
}.distinct().toList()
