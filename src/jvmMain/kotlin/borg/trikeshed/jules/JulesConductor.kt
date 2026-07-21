/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.jules

import kotlinx.coroutines.delay

/**
 * Jules conductor: polls Jules, snapshots each session into its card's assumpsis,
 * records the cause of every lane transition, and renders the board.
 *
 * The board is the conductor of development scale; the commits are only the
 * recording of actions it decided. This class owns no durable state beyond the
 * cards — card state is the only truth, snapshots are its world, causes its memory.
 */
class JulesConductor(
    private val client: JulesRestClient,
    private val headShaProvider: () -> String,
) {
    /** Cards keyed by session id. The board. */
    val cards: MutableMap<String, JulesSessionCard> = mutableMapOf()

    /** One poll cycle: snapshot surroundings, diff, record causes. */
    fun pollOnce() {
        val sessions = client.listSessions()
        val active = sessions.count { it.state == "IN_PROGRESS" || it.state == "PLANNING" || it.state == "QUEUED" }
        val awaiting = sessions.count { it.state == "AWAITING_USER_FEEDBACK" }
        val headSha = headShaProvider()

        for (s in sessions) {
            val patchBytes = if (s.state == "COMPLETED") client.patchProbe(s.id) else 0L
            val snap = JulesSnapshot(
                sessionId = s.id,
                state = s.state,
                title = s.title,
                patchBytes = patchBytes,
                headSha = headSha,
                activeCount = active,
                awaitingCount = awaiting,
            )
            val existing = cards[s.id]
            if (existing == null) {
                cards[s.id] = JulesSessionCard.capture(snap)
            } else if (existing.snapshot.state != snap.state || existing.snapshot.patchBytes != snap.patchBytes) {
                val cause: JulesCause = when {
                    snap.patchBytes > existing.snapshot.patchBytes ->
                        JulesCause.PatchArrived(snap.patchBytes, snap.capturedAt)
                    else ->
                        JulesCause.StateObserved(existing.snapshot.state, snap.state, snap.capturedAt)
                }
                cards[s.id] = existing.transition(snap, cause)
            }
        }
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
            )
            if (once) {
                conductor.pollOnce()
                print(conductor.renderBoard())
            } else {
                kotlinx.coroutines.runBlocking { conductor.run() }
            }
        }
    }
}
