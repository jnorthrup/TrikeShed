package borg.trikeshed.jules

import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.lib.view
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/**
 * Jules conductor: polls Jules, snapshots each session into its card's assumpsis,
 * records the cause of every lane transition, and renders the board.
 *
 * The board is the conductor of development scale; the commits are only the
 * recording of actions it decided. Card state is the only truth in memory —
 * when a [JulesBoardStore] is attached, every mutation is appended to the
 * Confix causal log and the in-memory board is rehydrated from it at boot.
 */
class JulesConductor(
    private val client: JulesRestClient,
    private val headShaProvider: suspend () -> String,
    private val store: JulesBoardStore? = null,
    private val source: String = "sources/github/jnorthrup/TrikeShed",
    private val patchContinuity: JulesPatchContinuityStore? = null,
) {
    /** Cards keyed by session id. The board. Projection of the causal log. */
    val cards: MutableMap<String, JulesSessionCard> = mutableMapOf()

    /** Non-archived session ids returned by the most recent complete API poll. */
    var visibleSessionIds: Set<String> = emptySet()
        private set

    /** States where a 404 on activityTimeline means the session is gone. */
    private val TERMINAL_STATES_FOR_SKIP = setOf("COMPLETED", "FAILED", "CANCELLED")

    /** Complete latest API projection, used only for deterministic dispatch reconciliation. */
    var visibleSessions: List<JulesRestClient.SessionInfo> = emptyList()
        private set

    /** One poll cycle: snapshot surroundings, diff, record causes, persist. */
    suspend fun pollOnce() {
        // The WAL is the reducer's authority, including review selections made
        // by an external operator while the daemon is live. Rehydrate before
        // reading the API so no stale in-memory card can ignore such a cause.
        val durable = withContext(Dispatchers.IO) {
            store?.let { it.load() to it.loadQueue() }
        }
        if (durable != null) {
            cards.clear()
            cards.putAll(durable.first)
        }
        val sessions = client.listSessions(source)
        val settledQueueSessions = durable?.second.orEmpty().asSequence()
            .filter { it.receipt?.isImmutableSettlement() == true }
            .mapNotNull { it.sessionId }
            .toSet()
        fun hasImmutableSettlement(sessionId: String, card: JulesSessionCard?): Boolean =
            card?.causes?.filterIsInstance<JulesCause.WorkDrained>()
                ?.any { it.receipt?.isImmutableSettlement() == true } == true ||
                sessionId in settledQueueSessions
        // API absence is not a lifecycle transition. Keep every WAL-rehydrated
        // card so rotation, pagination changes, or archive visibility cannot
        // erase an active conversation or free a duplicate dispatch slot.
        val authoritativeIds = sessions.mapTo(mutableSetOf()) { it.id }
        visibleSessionIds = authoritativeIds
        visibleSessions = sessions
        val active = sessions.count { it.state == "IN_PROGRESS" || it.state == "PLANNING" || it.state == "QUEUED" }
        val awaiting = sessions.count { it.state == "AWAITING_USER_FEEDBACK" }
        val headSha = headShaProvider()
        for (s in sessions) {
            var existing = cards[s.id]
            val immutableSettlement = hasImmutableSettlement(s.id, existing)
            // A settlement closes one observed artifact, not the producer's
            // future timeline. Continue polling settled sessions so a late API
            // patch/report is CAS-observed and reopens review instead of being
            // hidden forever by an old drained bit.
            //
            // A 404 on activityTimeline (session archived/deleted on the cloud
            // side) must not abort the entire poll — skip that session and
            // continue building cards for the rest so they can be archived.
            val timeline = try {
                client.activityTimeline(s.id)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // Session is gone from the API (archived/deleted). If it's
                // terminal, mark it archived locally and skip.
                if (s.state in TERMINAL_STATES_FOR_SKIP) {
                    val cause = JulesCause.SessionArchived(System.currentTimeMillis())
                    existing = (existing ?: JulesSessionCard.capture(
                        JulesSnapshot(s.id, s.state, s.title, 0L, headSha, 0, 0)
                    )).transition(
                        JulesSnapshot(s.id, s.state, s.title, existing?.snapshot?.patchBytes ?: 0L, headSha, 0, 0),
                        cause,
                    )
                    cards[s.id] = existing
                    store?.append(existing.snapshot, drained = true, cause = cause)
                    println("[FLYWHEEL] SKIP-ARCHIVE ${s.id.takeLast(6)} session 404 on API, marked archived")
                }
                continue
            }
            val acts = timeline.activities.view
            // changeSets are cumulative; outputs-only artifacts are included in
            // timeline.patches and must make a new card visibly patch-bearing.
            val patchBytes = if (timeline.patches.size > 0) {
                timeline.patches[timeline.patches.size - 1].patch.encodeToByteArray().size.toLong()
            } else acts.lastOrNull { it.patchBytes > 0 }?.patchBytes
                ?: existing?.snapshot?.patchBytes
                ?: 0L
            val snap = JulesSnapshot(
                sessionId = s.id,
                state = s.state,
                title = s.title,
                patchBytes = patchBytes,
                headSha = headSha,
                activeCount = active,
                awaitingCount = awaiting,
            )
            // A snapshot must exist before any CAS observation cause. Otherwise
            // a crash after observation but before capture leaves orphan causes
            // that board replay cannot materialize and the next poll duplicates.
            if (existing == null) {
                val captured = JulesSessionCard.capture(snap)
                store?.append(snap, drained = false, cause = captured.causes.last())
                cards[s.id] = captured
                existing = captured
            }
            val patchCauses = patchContinuity?.observe(
                sessionId = s.id,
                patches = timeline.patches,
                priorCauses = existing.causes,
            ).orEmpty()
            if (patchCauses.isNotEmpty()) {
                existing = existing.copy(causes = existing.causes + patchCauses)
                cards[s.id] = existing
            }
            val reportCauses = patchContinuity?.observeReports(
                sessionId = s.id,
                reports = timeline.reports,
                priorCauses = existing.causes,
            ).orEmpty()
            if (reportCauses.isNotEmpty()) {
                existing = existing.copy(causes = existing.causes + reportCauses)
                cards[s.id] = existing
            }
            // Late producer output reopens even a legitimate prior settlement;
            // a hollow legacy retirement reopens unconditionally. Persist the
            // drained=false transition after the new CAS facts so replay sees
            // the exact reason the prior close ceased to be current.
            if (existing.drained &&
                (!immutableSettlement || patchCauses.isNotEmpty() || reportCauses.isNotEmpty())
            ) {
                val cause = JulesCause.StateObserved(
                    from = if (immutableSettlement) "SETTLED_OUTPUT_ADVANCED" else
                        "HOLLOW_RETIRED:${existing.snapshot.state}",
                    to = snap.state,
                    at = snap.capturedAt,
                )
                existing = existing.copy(drained = false).transition(snap, cause)
                cards[s.id] = existing
                store?.append(snap, drained = false, cause = cause)
            }
            val stateChanged = existing != null && existing.snapshot.state != s.state
            val latestInquiry = acts.lastOrNull { it.kind == "agentMessaged" }
                ?: acts.lastOrNull { it.kind == "progressUpdated" && '?' in it.excerpt }
            val unseenInquiry = latestInquiry?.takeIf { inquiry ->
                // CAS-backed report/patch observations share this activity id,
                // but they do not mean GUIDE handled the inquiry.  Only the
                // conversational cause consumes it for answer deduplication.
                existing.causes.none {
                    it is JulesCause.AgentMessaged && it.activityId == inquiry.id
                }
            }
            if (unseenInquiry != null) {
                // A conversation can advance while Jules remains AWAITING. Record
                // the new inquiry by activity id so GUIDE answers it exactly once.
                val cause = JulesCause.AgentMessaged(
                    unseenInquiry.excerpt, snap.capturedAt, unseenInquiry.id, unseenInquiry.seq)
                cards[s.id] = existing.transition(snap, cause)
                store?.append(snap, existing.drained, cause)
            } else if (stateChanged || existing.snapshot.patchBytes != snap.patchBytes) {
                val cause: JulesCause = when {
                    snap.patchBytes > existing.snapshot.patchBytes -> {
                        val anchor = acts.lastOrNull { it.patchBytes > 0 }
                        JulesCause.PatchArrived(snap.patchBytes, snap.capturedAt, anchor?.id, anchor?.seq)
                    }
                    s.state.toJulesState() == JulesSessionState.AwaitingUserFeedback -> {
                        val anchor = acts.lastOrNull { it.kind == "agentMessaged" } ?: acts.lastOrNull()
                        JulesCause.AgentMessaged(anchor?.excerpt ?: "", snap.capturedAt, anchor?.id, anchor?.seq)
                    }
                    else ->
                        JulesCause.StateObserved(existing.snapshot.state, snap.state, snap.capturedAt)
                }
                cards[s.id] = existing.transition(snap, cause)
                store?.append(snap, existing.drained, cause)
            }
        }
    }

    private fun borg.trikeshed.util.oroboros.MergeReceipt.isImmutableSettlement(): Boolean =
        producer != "retired" &&
            revision.isNotBlank() && !revision.startsWith("outbox-") &&
            versionTag.isNotBlank() && versionTag != "retired"

    /** Answer an AWAITING session; the returned activity id anchors the cause. */
    suspend fun answer(sessionId: String, message: String) {
        val activityId = client.sendMessage(sessionId, message)
        val card = cards[sessionId] ?: return
        val cause = JulesCause.HumanAnswered(message, Clock.System.now().toEpochMilliseconds(), activityId)
        cards[sessionId] = card.copy(causes = card.causes + cause)
        store?.append(card.snapshot, card.drained, cause)
    }

    /**
     * Approve the latest plan of an AWAITING_PLAN_APPROVAL session and record
     * the sign-off as [JulesCause.HumanAnswered], so the flywheel approves each
     * plan exactly once even while Jules lingers in the approval state across
     * polls. The integrated JVM build at drain time is the quality barrier;
     * plan review is not a second gate.
     */
    suspend fun approvePlan(sessionId: String) {
        client.approvePlan(sessionId)
        val card = cards[sessionId] ?: return
        val cause = JulesCause.HumanAnswered(
            "plan approved by GUIDE",
            Clock.System.now().toEpochMilliseconds(),
            null,
        )
        cards[sessionId] = card.copy(causes = card.causes + cause)
        store?.append(card.snapshot, card.drained, cause)
    }

    /**
     * Archive a settled session without deleting its Jules conversation.
     * The API transition happens first; the durable cause then makes retries
     * idempotent across daemon restarts.
     */
    suspend fun archive(sessionId: String) {
        val card = cards[sessionId] ?: return
        if (card.causes.any { it is JulesCause.SessionArchived }) return
        client.archiveSession(sessionId)
        val cause = JulesCause.SessionArchived(Clock.System.now().toEpochMilliseconds())
        val updated = card.copy(causes = card.causes + cause)
        cards[sessionId] = updated
        store?.append(updated.snapshot, updated.drained, cause)
    }

    data class DrainRecord(val sessionId: String, val commitSha: String, val rejects: Int)

    /** Persist a whole completion set before publishing any drained card in memory. */
    suspend fun recordDrains(records: List<DrainRecord>) {
        val updates = records.map { record ->
            val card = requireNotNull(cards[record.sessionId]) {
                "missing Jules card ${record.sessionId} during drain close"
            }
            record.sessionId to card.markDrained(record.commitSha, record.rejects)
        }
        store?.appendDrainBatch(updates.map { it.second })
        for ((sessionId, updated) in updates) {
            cards[sessionId] = updated
        }
    }

    /** Record one drain outcome through the same durable batch boundary. */
    suspend fun recordDrain(sessionId: String, commitSha: String, rejects: Int) {
        recordDrains(listOf(DrainRecord(sessionId, commitSha, rejects)))
    }

    /** Record a failed drain without marking the session successfully drained. */
    suspend fun recordDrainFailure(sessionId: String, reason: String, at: Long) {
        val card = cards[sessionId] ?: return
        val cause = JulesCause.DrainFailed(reason, at)
        val updated = card.copy(causes = card.causes + cause)
        cards[sessionId] = updated
        store?.append(updated.snapshot, drained = false, cause = cause)
    }

    /** Run forever at [intervalMs]. */
    suspend fun run(intervalMs: Long = 60_000) {
        while (true) {
            pollOnce()
            print(renderBoard())
            delay(intervalMs)
        }
    }

    /** Board render: blocks grouped blocked → ready → working → todo → done, `$ ---` terminated. */
    fun renderBoard(): String = buildString {
        val groups = cards.values.groupBy { it.lane }
        appendLine("JULES BOARD  (cards: ${cards.size})")
        appendLine("$ ---")
        for (lane in listOf(
            JulesLane.CAUSAL_BLOCKED, JulesLane.CAUSAL_READY, JulesLane.AGENTIC_WORK,
            JulesLane.TO_DO, JulesLane.REVIEW, JulesLane.FAILED, JulesLane.DONE,
        )) {
            val laneCards = groups[lane] ?: continue
            appendLine("lane: ${lane.columnName} (${laneCards.size})")
            appendLine("$ ---")
            for (c in laneCards.sortedByDescending { it.snapshot.capturedAt }) {
                appendLine(c.renderBlock())
            }
        }
    }

}
