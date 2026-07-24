/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.jules

import borg.trikeshed.utils.kanban.JulesBoardStore
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
    private val headShaProvider: () -> String,
    private val store: JulesBoardStore? = null,
) {
    /** Cards keyed by session id. The board. Projection of the causal log. */
    val cards: MutableMap<String, JulesSessionCard> = store?.load() ?: mutableMapOf()

    /** One poll cycle: snapshot surroundings, diff, record causes, persist. */
    suspend fun pollOnce() {
        val sessions = client.listSessions()
        val active = sessions.count { it.state == "IN_PROGRESS" || it.state == "PLANNING" || it.state == "QUEUED" }
        val awaiting = sessions.count { it.state == "AWAITING_USER_FEEDBACK" }
        val headSha = headShaProvider()

        for (s in sessions) {
            val existing = cards[s.id]
            val stateChanged = existing != null && existing.snapshot.state != s.state
            // Answer dedup: once a session has any HumanAnswered cause on its card
            // (and the WAL rehydrates causes from disk at boot, so this survives
            // restarts), skip the per-cycle re-answer even if the session is still
            // sitting in AWAITING_USER_FEEDBACK. Jules takes one poll cycle to flip
            // to IN_PROGRESS after the answer lands; without this guard the daemon
            // would re-spam the same answer every poll.
            val alreadyAnswered = existing?.causes?.any { it is JulesCause.HumanAnswered } == true
            // Activities are fetched only when needed: COMPLETED sessions (patch
            // accounting), state transitions (cause anchoring), and unanswered
            // AWAITING sessions (so the GUIDE brain has the inquiry excerpt).
            val acts = if (s.state == "COMPLETED" ||
                (stateChanged && s.state == "AWAITING_USER_FEEDBACK") ||
                (s.state == "AWAITING_USER_FEEDBACK" && !alreadyAnswered))
                client.activities(s.id) else emptyList()
            // changeSets are cumulative per activity — the last non-zero carries the total.
            val patchBytes = acts.lastOrNull { it.patchBytes > 0 }?.patchBytes ?: 0L

            val snap = JulesSnapshot(
                sessionId = s.id,
                state = s.state,
                title = s.title,
                patchBytes = patchBytes,
                headSha = headSha,
                activeCount = active,
                awaitingCount = awaiting,
            )
            if (existing == null) {
                val card = JulesSessionCard.capture(snap)
                cards[s.id] = card
                store?.append(snap, drained = false, cause = card.causes.last())
            } else if (stateChanged || existing.snapshot.patchBytes != snap.patchBytes) {
                val cause: JulesCause = when {
                    snap.patchBytes > existing.snapshot.patchBytes -> {
                        val anchor = acts.lastOrNull { it.patchBytes > 0 }
                        JulesCause.PatchArrived(snap.patchBytes, snap.capturedAt, anchor?.id, anchor?.seq)
                    }
                    s.state == "AWAITING_USER_FEEDBACK" -> {
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

    /** Record a drain outcome on the card and the log. */
    suspend fun recordDrain(sessionId: String, commitSha: String, rejects: Int) {
        val card = cards[sessionId] ?: return
        val updated = card.markDrained(commitSha, rejects)
        cards[sessionId] = updated
        store?.append(updated.snapshot, drained = true, cause = updated.causes.last())
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
            val apiKey = System.getenv("JULES_API_KEY") ?: error("JULES_API_KEY required")
            val once = args.contains("--once")
            val conductor = JulesConductor(
                client = JulesRestClient(apiKey),
                headShaProvider = {
                    ProcessBuilder("git", "rev-parse", "HEAD")
                        .redirectErrorStream(true)
                        .start().inputStream.bufferedReader().readText().trim()
                },
                store = JulesBoardStore(),
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
