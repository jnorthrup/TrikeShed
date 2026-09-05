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
    val causalList = causes as? List<JulesCause> ?: causes.toList()

<<<<<<< HEAD
    // ⚡ Bolt: Replace intermediate List allocations (.filterIsInstance, .maxWith)
    // with a single manual iteration to find the latest snapshot and latest report.
    var latestSnapshot: JulesCause.PatchSnapshotObserved? = null
    var maxReport: JulesCause.AgentReportObserved? = null
    for (item in causalList) {
        if (item is JulesCause.PatchSnapshotObserved) {
            if (latestSnapshot == null || snapshotCausalOrder.compare(item, latestSnapshot) > 0) {
                latestSnapshot = item
            }
        } else if (item is JulesCause.AgentReportObserved) {
            if (maxReport == null || reportCausalOrder.compare(item, maxReport) > 0) {
                maxReport = item
            }
        }
    }

    if (latestSnapshot == null) return JulesPatchDrainSelection.Unobserved
=======
    // Bolt: Prevent intermediate List allocations with filterIsInstance<T>()
    var maxObservation: JulesCause.PatchSnapshotObserved? = null
    var latestObservationIndex = -1
    var rejectIndex = -1

    for ((index, item) in causalList.withIndex()) {
        if (item is JulesCause.PatchSnapshotObserved) {
            latestObservationIndex = index
            if (maxObservation == null || snapshotCausalOrder.compare(item, maxObservation) > 0) {
                maxObservation = item
            }
        } else if (item is JulesCause.PatchRejected) {
            rejectIndex = index
        }
    }

    if (maxObservation == null) return JulesPatchDrainSelection.Unobserved
>>>>>>> origin/bolt-patch-continuity-opt-7763142742223188880

    // A typed reject, bonded to a receipt and posted after every producer
    // artifact, closes the chain: the session settles as rejected without
    // applying any observed patch.  Recency never launders a reject away and
    // a reject never discards the observed evidence it names.
    val reject = causalList.getOrNull(rejectIndex) as? JulesCause.PatchRejected
    if (reject != null && rejectIndex > latestObservationIndex) {
<<<<<<< HEAD
        val latestPatchCid = latestSnapshot.patchCid
        val latestReportCid = maxReport?.reportCid

        // ⚡ Bolt: Replace .lastOrNull on filtered list with manual loop
        var rejected: JulesCause.PatchSnapshotObserved? = null
        for (i in causalList.indices.reversed()) {
            val item = causalList[i]
            if (item is JulesCause.PatchSnapshotObserved && item.patchCid == reject.patchCid && item.causalOrdinal == reject.causalOrdinal) {
                rejected = item
                break
            }
        }

=======
        val latestPatchCid = maxObservation.patchCid
        // Bolt: avoid intermediate List allocations from filterIsInstance by using sequence for terminal ops
        var maxReport: JulesCause.AgentReportObserved? = null
        for (item in causalList) {
            if (item is JulesCause.AgentReportObserved) {
                if (maxReport == null || reportCausalOrder.compare(item, maxReport) > 0) {
                    maxReport = item
                }
            }
        }
        val latestReportCid = maxReport?.reportCid
        val rejected = causalList.lastOrNull {
            it is JulesCause.PatchSnapshotObserved && it.patchCid == reject.patchCid && it.causalOrdinal == reject.causalOrdinal
        } as? JulesCause.PatchSnapshotObserved
>>>>>>> origin/bolt-patch-continuity-opt-7763142742223188880
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
<<<<<<< HEAD
        val latestPatchCid = latestSnapshot.patchCid
        val latestReportCid = maxReport?.reportCid

        // ⚡ Bolt: Replace .lastOrNull on filtered list with manual loop
        var selected: JulesCause.PatchSnapshotObserved? = null
        for (i in causalList.indices.reversed()) {
            val item = causalList[i]
            if (item is JulesCause.PatchSnapshotObserved && item.patchCid == explicit.patchCid && item.causalOrdinal == explicit.causalOrdinal) {
                selected = item
                break
            }
        }

=======
        val latestPatchCid = maxObservation.patchCid
        // Bolt: avoid intermediate List allocations from filterIsInstance by using sequence for terminal ops
        var maxReport: JulesCause.AgentReportObserved? = null
        for (item in causalList) {
            if (item is JulesCause.AgentReportObserved) {
                if (maxReport == null || reportCausalOrder.compare(item, maxReport) > 0) {
                    maxReport = item
                }
            }
        }
        val latestReportCid = maxReport?.reportCid
        val selected = causalList.lastOrNull {
            it is JulesCause.PatchSnapshotObserved && it.patchCid == explicit.patchCid && it.causalOrdinal == explicit.causalOrdinal
        } as? JulesCause.PatchSnapshotObserved
>>>>>>> origin/bolt-patch-continuity-opt-7763142742223188880
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

<<<<<<< HEAD
    val latest = latestSnapshot
=======
    val latest = maxObservation
>>>>>>> origin/bolt-patch-continuity-opt-7763142742223188880
    // The automatic gate is file-set monotonicity against the previous
    // CANDIDATE, exactly as documented: a snapshot is eligible when its file
    // set contains the retained candidate's file set (reviewCandidate).
    // Global CID distinctness is NOT the gate — a cumulative patch stream
    // legitimately emits new bytes each activity, and requiring a single CID
    // forever blocks every evolved session even when the latest snapshot is
    // byte-identical to the retained candidate (same CID, empty drop set).
    // A latest snapshot that actually dropped files keeps reviewCandidate
    // false and stays review-blocked below.
    if (latest.reviewCandidate) {
        return JulesPatchDrainSelection.Selected(latest, reviewed = false)
    }

    // ⚡ Bolt: Replace sequence allocation with zero-allocation for loop over causalList directly
    var retained: JulesCause.PatchSnapshotObserved? = null
    for (item in causalList) {
        if (item is JulesCause.PatchSnapshotObserved && item.reviewCandidate && item.causalOrdinal < latest.causalOrdinal) {
            if (retained == null || snapshotCausalOrder.compare(item, retained) > 0) {
                retained = item
            }
        }
    }
    if (retained == null) {
        return JulesPatchDrainSelection.ReviewRequired(latest, latest, latest.missingFromCandidate)
    }
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
    val causalList = causes as? List<JulesCause> ?: causes.toList()

<<<<<<< HEAD
    // ⚡ Bolt: Replace intermediate List allocations (.filterIsInstance, .maxWith)
    // with a single manual iteration to find the final report.
    var finalReport: JulesCause.AgentReportObserved? = null
    for (item in causalList) {
=======
    // Bolt: Prevent intermediate List allocations with filterIsInstance<T>()
    var finalReport: JulesCause.AgentReportObserved? = null
    var reviewIndex = -1

    for ((index, item) in causalList.withIndex()) {
>>>>>>> origin/bolt-patch-continuity-opt-7763142742223188880
        if (item is JulesCause.AgentReportObserved) {
            if (finalReport == null || reportCausalOrder.compare(item, finalReport) > 0) {
                finalReport = item
            }
<<<<<<< HEAD
=======
        } else if (item is JulesCause.AgentReportReviewSelected) {
            reviewIndex = index
>>>>>>> origin/bolt-patch-continuity-opt-7763142742223188880
        }
    }

    if (finalReport == null) return JulesReportSettlementSelection.Unobserved
<<<<<<< HEAD
    val reviewIndex = causalList.indexOfLast { it is JulesCause.AgentReportReviewSelected }
=======
>>>>>>> origin/bolt-patch-continuity-opt-7763142742223188880
    // A report-only disposition is authorized only after every producer
    // artifact currently observed. A later patch invalidates an older no-op
    // review just as a later report does.
    val latestProducerArtifactIndex = causalList.indexOfLast {
        it is JulesCause.AgentReportObserved || it is JulesCause.PatchSnapshotObserved
    }
    val review = causalList.getOrNull(reviewIndex) as? JulesCause.AgentReportReviewSelected
    // Bolt: avoid intermediate List allocations from filterIsInstance by using sequence for terminal ops
    var maxSnapshot: JulesCause.PatchSnapshotObserved? = null
    for (item in causalList) {
        if (item is JulesCause.PatchSnapshotObserved) {
            if (maxSnapshot == null || snapshotCausalOrder.compare(item, maxSnapshot) > 0) {
                maxSnapshot = item
            }
        }
    }
    val latestPatchCid = maxSnapshot?.patchCid
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
 * Returns true for scratch/test/plan paths that Jules subagents include in
 * patches but that must not participate in the file-set monotonicity gate.
 * An earlier snapshot containing these files should not cause a later
 * snapshot that omits them to be review-blocked.
 */
fun isScratchPatchPath(path: String): Boolean {
    val lower = path.replace('\\', '/').lowercase()
    val parts = lower.split('/')
    val base = parts.lastOrNull().orEmpty()
    return path.isBlank() || path.startsWith('/') ||
        lower.startsWith(".jules/") ||
        parts.any { it == ".." || it == ".git" || it == ".gradle" } ||
        parts.firstOrNull() == "build" ||
        base in setOf(
            "test_script.kt", "patch.diff", "plan_script.sh",
            "multiindexcontainer-patch.txt",
        ) ||
        base.startsWith("test_script.") ||
        base.startsWith("test_") ||
        base.startsWith("plan") && base.endsWith(".md") ||
        base.startsWith("fix_") && base.endsWith(".sh") ||
        base == "patch.diff"
}

/** Parse every repository path named by a unified diff without materialization
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
