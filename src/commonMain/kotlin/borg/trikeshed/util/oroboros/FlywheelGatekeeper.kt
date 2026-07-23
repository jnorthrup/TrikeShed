/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.util.oroboros

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/** Lexical memory retained from an agent call without retaining mutable state. */
data class LexicalMemory(
    val summary: String,
    val title: String,
    val content: String,
) {
    val terms: Set<String> get() = tokenize("$summary $title $content")

    fun overlap(other: LexicalMemory): Int = terms.count { it in other.terms }

    companion object {
        private fun tokenize(text: String): Set<String> = text
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .asSequence()
            .filter { it.length > 2 }
            .toSet()
    }
}

/**
 * Immutable claim over one mutable producer artifact at the instant it landed.
 *
 * [patchCid] names the exact cumulative patch bytes fetched from the producer.
 * [revision] and [versionTag] name the accepted repository state. Follow-up work
 * cites this receipt as parent evidence; it never resumes the mutable producer
 * session after merge.
 */
data class MergeReceipt(
    val workId: String,
    val producer: String,
    val producerRef: String,
    val patchCid: ContentId,
    val revision: String,
    val versionTag: String,
    val lexicalMemory: LexicalMemory,
    val claimedAt: Long,
)

/**
 * Immutable claim over one historical agent artifact stored in Oroboros CAS.
 *
 * Session history is a locator and donor, never repository truth. [artifactCid]
 * names the exact exported transcript bytes; Git revisions and producer refs are
 * evidence anchors extracted from those bytes for later verification against
 * Git objects and paginated producer activity.
 */
data class ExposureReceipt(
    val sourceSession: String,
    val sourceMessage: String?,
    val observedAt: Long,
    val lexicalMemory: LexicalMemory,
    val filePaths: Series<String>,
    val artifactCid: ContentId,
    val gitRevisions: Series<String> = 0 j { "" },
    val producerRefs: Series<String> = 0 j { "" },
    val versionTags: Series<String> = 0 j { "" },
    val parentReceipt: ContentId? = null,
)

/** Point-in-time inputs to the induction gate. */
data class FlywheelGateState(
    val workingTreeClean: Boolean,
    val openPullRequests: Int,
    val localRevision: String,
    val remoteRevision: String,
    val unclaimedDrains: Int,
)

/** Admission is explicit; no caller guesses concurrent interleave order. */
sealed class FlywheelGateVerdict {
    data object Admit : FlywheelGateVerdict()
    data class Block(val reason: String) : FlywheelGateVerdict()
}

/**
 * General Oroboros gatekeeper between drain and induction.
 *
 * Today revisions are Git commits and receipts are annotated Git tags. The
 * contract deliberately speaks in revisions and [ContentId] so a later adapter
 * can prove CAS block ancestry or CRDT convergence without changing flywheel
 * ordering policy.
 */
object FlywheelGatekeeper {
    fun evaluate(state: FlywheelGateState): FlywheelGateVerdict = when {
        !state.workingTreeClean -> FlywheelGateVerdict.Block("working tree is dirty")
        state.openPullRequests != 0 -> FlywheelGateVerdict.Block(
            "${state.openPullRequests} pull request(s) remain undrained")
        state.unclaimedDrains != 0 -> FlywheelGateVerdict.Block(
            "${state.unclaimedDrains} drain(s) lack immutable receipts")
        state.localRevision != state.remoteRevision -> FlywheelGateVerdict.Block(
            "local and remote revisions diverge")
        else -> FlywheelGateVerdict.Admit
    }

    /** Closest historical claim with at least one meaningful shared term. */
    fun closestReceipt(
        query: LexicalMemory,
        receipts: Iterable<MergeReceipt>,
    ): MergeReceipt? = receipts
        .map { it to query.overlap(it.lexicalMemory) }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first
}
