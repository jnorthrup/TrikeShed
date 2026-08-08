package borg.trikeshed.jules

import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import java.io.File
import keymux.KeyMux
import kotlinx.coroutines.delay
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
) {
    /** Cards keyed by session id. The board. Projection of the causal log. */
    val cards: MutableMap<String, JulesSessionCard> = store?.load() ?: mutableMapOf()

    /** One poll cycle: snapshot surroundings, diff, record causes, persist. */
    suspend fun pollOnce() {
        val sessions = client.listSessions(source)
        // WAL-rehydrated cards survive API rotation. The Jules API expires or
        // rotates sessions out of its listing; deleting them here erases
        // COMPLETED-with-patch cards that still need draining, plus drained
        // cards whose receipts anchor settlement. Only drop cards the API
        // actively reports as absent AND that have no pending drain work.
        val authoritativeIds = sessions.mapTo(mutableSetOf()) { it.id }
        cards.keys.retainAll { sid ->
            sid in authoritativeIds ||
                cards[sid]?.drained == true ||
                (cards[sid]?.snapshot?.state == "COMPLETED" &&
                    cards[sid]?.snapshot?.patchBytes ?: 0L > 0L) ||
                // Un-drained terminal failures must survive rotation so SWEEP
                // can retire them; evicting them here orphans their queue
                // entries (dispatched-not-drained) forever.
                cards[sid]?.snapshot?.state in setOf("FAILED", "CANCELLED")
        }
        val active = sessions.count { it.state == "IN_PROGRESS" || it.state == "PLANNING" || it.state == "QUEUED" }
        val awaiting = sessions.count { it.state == "AWAITING_USER_FEEDBACK" }
        val headSha = headShaProvider()
        for (s in sessions) {
            val existing = cards[s.id]
            val stateChanged = existing != null && existing.snapshot.state != s.state
            // COMPLETED and AWAITING sessions can carry cumulative patches.
            // A drained card is immutable: its CAS/tag receipt already closed it,
            // so downloading its activity stream again is duplicate work.
            val acts = if (
                existing?.drained != true &&
                (s.state == "COMPLETED" || s.state == "AWAITING_USER_FEEDBACK")
            )
                client.activities(s.id) else emptyList()
            // changeSets are cumulative per activity — the last non-zero carries the total.
            val patchBytes = acts.lastOrNull { it.patchBytes > 0 }?.patchBytes
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
            val latestInquiry = acts.lastOrNull { it.kind == "agentMessaged" }
                ?: acts.lastOrNull { it.kind == "progressUpdated" && '?' in it.excerpt }
            val unseenInquiry = latestInquiry?.takeIf { inquiry ->
                existing?.causes?.none { it.activityId == inquiry.id } != false
            }
            if (existing == null) {
                val captured = JulesSessionCard.capture(snap)
                val inquiryCause = unseenInquiry?.let {
                    JulesCause.AgentMessaged(it.excerpt, snap.capturedAt, it.id, it.seq)
                }
                val card = if (inquiryCause == null) captured
                    else captured.copy(causes = captured.causes + inquiryCause)
                cards[s.id] = card
                store?.append(snap, drained = false, cause = inquiryCause ?: captured.causes.last())
            } else if (unseenInquiry != null) {
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
     * Tombstone a terminal drain candidate: the failure is recorded as a
     * [JulesCause.DrainFailed] cause AND the card leaves the drain set
     * (drained=true). For sessions that can never produce a landable patch
     * (e.g. concluded with zero patch bytes after repeated probes) — without
     * this the wheel re-probes them every cycle forever.
     */
    suspend fun retireTerminal(sessionId: String, reason: String, at: Long) {
        val card = cards[sessionId] ?: return
        val cause = JulesCause.DrainFailed(reason, at)
        val updated = card.copy(causes = card.causes + cause, drained = true)
        cards[sessionId] = updated
        store?.append(updated.snapshot, drained = true, cause = cause)
        // Close the queue entry so loadQueue() stops seeing this work as
        // dispatched-but-undrained. Without this, a retired session occupies
        // a queue slot forever (isDispatched && !isDrained) and the wheel
        // can't tell it's done. Bond to the ORIGINAL queue workId (gap:/readme:/
        // synth:...) — writing WorkDrained under the bare numeric sessionId
        // orphans the real entry and leaves it stuck dispatched forever.
        // Uses the same outbox: pattern as ReapAppend.
        val bondedWorkId = store?.loadQueue()
            ?.firstOrNull { it.sessionId == sessionId && !it.isDrained }
            ?.workId ?: sessionId
        store?.appendWork(
            workId = bondedWorkId,
            cause = JulesCause.WorkDrained(
                workId = bondedWorkId,
                sessionId = sessionId,
                commitSha = "outbox-${sessionId.take(8)}",
                taskId = "retired",
                receipt = borg.trikeshed.util.oroboros.MergeReceipt(
                    workId = bondedWorkId,
                    producer = "retired",
                    producerRef = sessionId,
                    patchCid = borg.trikeshed.job.ContentId.of(
                        "retired:$sessionId:$reason".encodeToByteArray()
                    ),
                    revision = "outbox-${sessionId.take(8)}",
                    versionTag = "retired",
                    lexicalMemory = borg.trikeshed.util.oroboros.LexicalMemory(summary = reason, title = reason, content = ""),
                    claimedAt = at,
                    prUrl = null,
                ),
                at = at,
            ),
        )
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

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val keyMux = KeyMux { env() }
            val once = args.contains("--once")
            val forgeDir = File(System.getProperty("user.home"), ".local/forge")
            val store = JulesBoardStore.forForgeDir(forgeDir)
            val conductor = JulesConductor(
                client = JulesRestClient(keyMux),
                headShaProvider = {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ProcessBuilder("git", "rev-parse", "HEAD")
                        .redirectErrorStream(true)
                        .start().inputStream.bufferedReader().readText().trim() }
                },
                store = store,
                source = "sources/github/jnorthrup/TrikeShed",
            )
            kotlinx.coroutines.runBlocking {
                if (once) {
                    conductor.pollOnce()
                    print(conductor.renderBoard())
                } else {
                    conductor.run()
                }
            }
        }
    }
}
