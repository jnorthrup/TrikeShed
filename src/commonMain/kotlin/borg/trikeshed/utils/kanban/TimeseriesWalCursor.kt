@file:Suppress("NOTHING_TO_INLINE")

package borg.trikeshed.utils.kanban

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.α
import borg.trikeshed.lib.j
import borg.trikeshed.lib.joins

/**
 * Unified timeseries WAL projection — Series-shaped.
 *
 * One row per cause + identity record, ordered by `at` ms. The projection is
 * a [Series] of Join-typed rows (a [WalCursor]), not a [List]. Per
 * PRELOAD.md:81-101, the cursor stays Series-class from projection to the
 * stdlib boundary so downstream consumers can run `α` / `get(range)` without
 * forcing the type demotion that PRELOAD.md forbids.
 *
 * Identity synonym accumulation (gitBranch, prUrl, gitTag, commitSha)
 * happens through [TimeseriesWalCursor.foldReplay], which threads an
 * accumulator and emits rows where each row's synonym state has the
 * carry-forward of prior same-key rows merged in.
 *
 * Replaces three earlier discovery paths:
 *   - git for-each-ref on `origin/jules-*` branches (33 ghosts)
 *   - Jules API `/sessions` poll (no synonyms in API surface)
 *   - WAL `loadQueue()` (only work-cause surface)
 */

/**
 * WalRow = Join<IdentityKey, SynonymOps>, the canonical cursor row.
 *
 * Like [borg.trikeshed.cursor.ColumnMeta], defined as a Join interface
 * (not a `data class`) with a factory `invoke()` so callers compose with
 * the project's `a j b` constructor grammar instead of positional `data
 * class` constructors. The Split-Storage precedent ([Cursor.kt:81-93])
 * uses Join interfaces for every column.
 */
interface WalRow : Join<String /* IdentityKey = sessionId (first) */, SynonymOps> {
    override val a: String
    override val b: SynonymOps
    val identityKey: String get() = this.a
    val synonyms: SynonymOps get() = this.b

    companion object {
        operator fun invoke(key: String, ops: SynonymOps): WalRow = object : WalRow {
            override val a: String = key
            override val b: SynonymOps = ops
        }
    }
}

/** WalCursor = Series<WalRow> — the unified projection. */
typealias WalCursor = Series<WalRow>

/**
 * Split storage of the projection — keys (Series<String>) joined with ops
 * (Series<SynonymOps>) by the same index space. Mirrors [borg.trikeshed.cursor.RowVec]
 * split storage; consumers pick the side they want without paying for both.
 *
 * WalCursorSplit is constructed via the canonical `series joins series`
 * operator from `Join.kt:37`, returning `Series<Join<A, B>>`.
 */
typealias WalCursorSplit = Series<Join<String, SynonymOps>>

/**
 * Synonym state per identity. Five-carry-forward fields; "synonym for once
 * when needed, never duped." A `data class` here is the leaf value, not a
 * collection — analogous to [borg.trikeshed.util.oroboros.LexicalMemory],
 * which is also a `data class` leaf value inside the receipt algebra.
 */
data class SynonymOps(
    val sessionUrl: String? = null,
    val gitBranch: String? = null,
    val prUrl: String? = null,
    val gitTag: String? = null,
    val commitSha: String? = null,
) {
    /** True iff this row carries a coalesced landing (commitSha or gitTag set). */
    val isLanded: Boolean get() = commitSha != null || gitTag != null

    /** Non-null-wins merge — `this ?? other` for every field. */
    fun merge(other: SynonymOps): SynonymOps = SynonymOps(
        sessionUrl = sessionUrl ?: other.sessionUrl,
        gitBranch = gitBranch ?: other.gitBranch,
        prUrl = prUrl ?: other.prUrl,
        gitTag = gitTag ?: other.gitTag,
        commitSha = commitSha ?: other.commitSha,
    )

    companion object {
        /** Empty synonym state — no fields set. */
        val Empty = SynonymOps()
    }
}

/**
 * Cursor over a `JulesBoardStore` projection into the unified timeseries.
 *
 * Position advances through the WAL in causal order (file offset is
 * monotonic, since each `append` writes a strict tail segment). Each row
 * in [replay] carries the carry-forward of synonym fields from prior
 * same-key rows.
 *
 * The cursor's [replay] returns a [WalCursor] — Series-shaped. The
 * stdlib-boundary moment is in [pairsToSeries], but the cursor is
 * Series-typed everywhere else.
 */
class TimeseriesWalCursor(
    private val store: JulesBoardStore,
) {

    /**
     * Full ordered projection from the WAL as [WalCursor]. Each row's
     * synonym state is the merge of itself with prior carry-forward rows
     * for the same identity key.
     */
    fun replay(): WalCursor {
        val pairs = foldReplay()
        return pairsToSeries(pairs)
    }

    /**
     * Same projection in split storage — keys and ops ride separate Series
     * joined by the same index space, so consumers `α`/`.left`/`.right`
     * whichever side they need without paying for both.
     */
    fun replaySplit(): WalCursorSplit {
        val cursor = replay()
        val keys = cursor α { it.a }
        val ops = cursor α { it.b }
        return keys joins ops
    }

    /**
     * Fold the WAL once. The accumulator is a [MutableMap] because it is
     * a join-side cache, NOT a published projection. It threads the
     * carry-forward synonyms through to each row, but the published result
     * is a Series of (key, mergedSynonyms) pairs, materialized once at
     * the stdlib boundary via [pairsToSeries].
     *
     * Internal so tests can call it without paying for the Series cast.
     */
    internal fun foldReplay(): List<Pair<String, SynonymOps>> {
        val out = mutableListOf<Pair<String, SynonymOps>>()
        val accumulator: MutableMap<String, SynonymOps> = mutableMapOf()
        for ((workId, payload) in store.replayAll()) {
            val event = KanbanEventCodec.decode(payload.decodeToString()) ?: continue
            val rows: List<Pair<String, SynonymOps>> = when (event) {
                is KanbanEventCodec.CauseEvent ->
                    foldCause(workId, event)
                is KanbanEventCodec.SnapEvent -> listOf(
                    event.snapshot.sessionId to SynonymOps(
                        sessionUrl = "https://jules.google.com/session/${event.snapshot.sessionId}",
                    )
                )
            }
            for ((key, ops) in rows) {
                val prior = accumulator[key]
                val merged = (prior?.merge(ops)) ?: ops
                accumulator[key] = merged
                out += key to merged
            }
        }
        return out
    }

    /** Internal fold per-cause. Returns 0+ (IdentityKey, SynonymOps) pairs. */
    private fun foldCause(
        @Suppress("UNUSED_PARAMETER") workId: String,
        event: KanbanEventCodec.CauseEvent,
    ): List<Pair<String, SynonymOps>> {
        val c = event.cause
        return when (c) {
            is JulesCause.WorkDispatched -> listOf(
                c.sessionId to SynonymOps(sessionUrl = "https://jules.google.com/session/${c.sessionId}")
            )
            is JulesCause.WorkIdentitySynthesized -> listOf(
                c.identity.sessionId to SynonymOps(
                    sessionUrl = c.identity.sessionUrl,
                    gitBranch = c.identity.gitBranch,
                    prUrl = c.identity.prUrl,
                    gitTag = c.identity.gitTag,
                    commitSha = c.identity.commitSha,
                )
            )
            is JulesCause.WorkDrained -> listOf(
                c.sessionId to SynonymOps(
                    sessionUrl = "https://jules.google.com/session/${c.sessionId}",
                    gitTag = c.receipt?.versionTag,
                    prUrl = c.receipt?.prUrl,
                    commitSha = c.commitSha,
                )
            )
            is JulesCause.DrainApplied -> listOf(
                event.sid to SynonymOps(commitSha = c.commitSha)
            )
            is JulesCause.AgentMessaged,
            is JulesCause.HumanAnswered,
            is JulesCause.PatchArrived,
            is JulesCause.DrainFailed,
            is JulesCause.PredicateFlipped,
            is JulesCause.SessionFailed,
            is JulesCause.StateObserved -> listOf(event.sid to SynonymOps.Empty)
            is JulesCause.WorkQueued -> listOf(c.workId to SynonymOps.Empty)
        }
    }

    /**
     * Materialize (key, ops) pairs into a Series using the canonical
     * `n.size j { i -> … }` constructor grammar. The single stdlib-
     * boundary moment on the projection path.
     */
    private fun pairsToSeries(pairs: List<Pair<String, SynonymOps>>): WalCursor =
        pairs.size j { i: Int -> WalRow(pairs[i].first, pairs[i].second) }
}
