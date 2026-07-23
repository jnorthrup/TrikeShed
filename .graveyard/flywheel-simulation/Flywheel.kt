/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.flywheel

import borg.trikeshed.context.lcnc.LcncFanoutElement
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.NuidFanoutElement
import borg.trikeshed.jules.JulesCause
import borg.trikeshed.reduction.ReducerRegistry
import borg.trikeshed.reduction.TrajectoryOutcome
import borg.trikeshed.reduction.TrajectoryVerdict
import borg.trikeshed.reduction.category
import borg.trikeshed.reduction.verdictFor
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.KanbanEventCodec
import borg.trikeshed.utils.kanban.QueueEntry
import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import kotlin.coroutines.coroutineContext

/**
 * Oroboros Flywheel — Kotlin orchestration loop.
 *
 * Reads defaults from CCEK [FlywheelElement] so sibling reactors (Codex,
 * OpenCode, Jules, Claude) share one source of truth for poll interval,
 * tend timeout, and the simulation budget.
 *
 * Cycle: ingest → reduce → dispatch → tend → harvest → persist
 */
class Flywheel(
    private val store: JulesBoardStore,
    private val workDir: File,
    val element: FlywheelElement = FlywheelElement()
) {
    val maxLive: Int get() = element.defaults.maxLive
    val pollIntervalMs: Long get() = element.defaults.pollIntervalMs
    val sessionSimulationMs: Long get() = element.defaults.sessionSimulationMs

    private var live = mutableMapOf<String, LiveSession>()
    private var landed = mutableSetOf<String>()
    private var outcomes = mutableListOf<Outcome>()

    data class LiveSession(
        val workId: String,
        val sessionId: String,
        val agent: String,
        val attempt: Int,
        val dispatchedAt: Long
    )

    data class Outcome(
        val workId: String,
        val ok: Boolean,
        val reason: String,
        val fingerprint: String,
        val at: Long = Instant.now().toEpochMilli()
    )

    /** Single cycle: ingest → reduce → dispatch → tend → harvest → persist */
    suspend fun cycle(): CycleResult = coroutineScope {
        element.currentCycle = FlywheelElement.CycleBudget(cycleStartedAtMs = System.currentTimeMillis())
        val queue = store.loadQueue()
        val available = queue.filter { entry ->
            !entry.isDrained && entry.workId !in landed && entry.workId !in live.keys
        }

        var dispatched = 0
        for (entry in available) {
            if (live.size >= maxLive) break
            val decision = reduceAndDecide(entry)
            if (decision?.shouldDispatch == true) {
                spawnAgent(decision)
                dispatched++
                element.currentCycle.dispatchesThisCycle++
            }
        }

        val harvested = tendLiveSessions()
        element.currentCycle.harvestsThisCycle = harvested
        writeTuiFeed()

        CycleResult(dispatched, harvested, live.size, landed.size)
    }

    private fun reduceAndDecide(entry: QueueEntry): DispatchDecision? {
        // Replay this workId's WAL causes into the trajectory reducer.
        val causes = store.replayCauses(entry.workId)
        val verdict = verdictFor(
            cardCauses = causes,
            taskFingerprint = entry.workId,
            attemptCount = 1,
            deps = emptyList()
        )
        val capability = pickCapability(entry.tier)
        return DispatchDecision(entry.workId, capability, verdict, entry)
    }

    private fun pickCapability(tier: String): Capability {
        return when (tier) {
            "epic", "feature" -> Capability.Process(tier)
            "task", "test", "chore" -> Capability.Trajectory
            else -> Capability.Process(element.defaults.defaultAgent)
        }
    }

    private fun spawnAgent(decision: DispatchDecision) {
        val sessionId = "${decision.agent}-${decision.workId}-${System.currentTimeMillis()}"

        println("[FLYWHEEL] Dispatch ${decision.agent} for ${decision.workId} (${decision.entry.title})")
        println("[FLYWHEEL]   Verdict: ${decision.verdict.outcome}, frozen=${decision.verdict.frozen}, depsOK=${decision.verdict.depsSatisfied}")

        runBlocking {
            store.appendWork(decision.workId, JulesCause.WorkDispatched(
                workId = decision.workId,
                sessionId = sessionId,
                attempt = 1,
                at = Instant.now().toEpochMilli()
            ))
        }

        live[decision.workId] = LiveSession(
            workId = decision.workId,
            sessionId = sessionId,
            agent = decision.agent.toString(),
            attempt = 1,
            dispatchedAt = Instant.now().toEpochMilli()
        )
        element.recordDispatch()
    }

    private suspend fun tendLiveSessions(): Int {
        var harvested = 0
        val toRemove = mutableListOf<String>()
        val budget = sessionSimulationMs

        for ((workId, session) in live) {
            if (Instant.now().toEpochMilli() - session.dispatchedAt > budget) {
                println("[FLYWHEEL] Session ${session.sessionId} completed (simulated, budget=${budget}ms)")
                element.recordTend()

                runBlocking {
                    store.appendWork(workId, JulesCause.WorkDrained(
                        workId = workId,
                        sessionId = session.sessionId,
                        commitSha = "simulated-${System.currentTimeMillis()}",
                        taskId = "task-${workId}",
                        at = Instant.now().toEpochMilli()
                    ))
                }
                element.recordHarvest()

                val causes = store.replayCauses(workId)
                val verdict = verdictFor(causes, workId, session.attempt, emptyList())

                val ok = verdict.outcome == TrajectoryOutcome.Landed && !verdict.frozen
                outcomes.add(Outcome(workId, ok, verdict.outcome.toString(), workId))

                if (ok) landed.add(workId)
                toRemove.add(workId)
                harvested++
            }
        }

        toRemove.forEach { live.remove(it) }
        return harvested
    }

    /** Quote-escape a string for embedding inside a JSON value. */
    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun writeTuiFeed() {
        val latestWork = live.values.firstOrNull()?.workId
            ?: outcomes.lastOrNull()?.workId
            ?: "idle"

        // Build proper JulesCause objects so the TUI's parser renders them correctly.
        val causes = live.values.map { session ->
            JulesCause.WorkDispatched(
                workId = session.workId,
                sessionId = session.sessionId,
                attempt = session.attempt,
                at = session.dispatchedAt
            )
        }.toMutableList<JulesCause>()

        // Surface outcomes as well so the dashboard reflects history.
        for (outcome in outcomes.takeLast(8)) {
            val verdict = when {
                outcome.ok -> TrajectoryOutcome.Landed
                "NoPatch" in outcome.reason -> TrajectoryOutcome.NoPatch
                "GateRed" in outcome.reason -> TrajectoryOutcome.GateRed(listOf(outcome.reason))
                else -> TrajectoryOutcome.Stub(outcome.reason)
            }
            causes.add(JulesCause.DrainApplied(
                commitSha = "out-${outcome.fingerprint.take(7)}",
                rejects = if (outcome.ok) 0 else 1,
                at = outcome.at
            ))
        }

        // Look up the actual work title from the queue projection. The trajectory.json
        // exposes both workId (slug) and taskFingerprint (the legacy SHA1). We
        // surface workId as the human-readable identifier.
        val workSlug = live.values.firstOrNull()?.workId
            ?: outcomes.lastOrNull()?.workId
            ?: "idle"

        // Try to resolve a human-readable title for the workSlug from the queue.
        val queue = store.loadQueue()
        val title = queue.firstOrNull { it.workId == workSlug }?.title ?: ""

        val firstCause = causes.firstOrNull()
        val json = buildString {
            append("""{"causes":[""")
            causes.forEachIndexed { i, c ->
                if (i > 0) append(",")
                append(KanbanEventCodec.encodeCause(workSlug, c))
            }
            append("""
],"taskFingerprint":"$workSlug","workId":"$workSlug","title":${jsonString(title)},"attemptCount":${live.values.maxOfOrNull { it.attempt } ?: 1},"deps":[],"outcome":"${firstCause?.javaClass?.simpleName ?: "idle"}"}""")
        }

        File(workDir, "trajectory.json").writeText(json)
    }

    data class DispatchDecision(
        val workId: String,
        val capability: Capability,
        val verdict: TrajectoryVerdict,
        val entry: QueueEntry
    ) {
        /** Dispatch when not frozen and deps OK. NoPatch means "still in flight" — that's fine for dispatching. */
        val shouldDispatch: Boolean get() =
            !verdict.frozen &&
            verdict.depsSatisfied

        val agent: String get() = when (capability) {
            is Capability.Process -> "codex"
            is Capability.Trajectory -> "jules"
            else -> "opencode"
        }
    }

    data class CycleResult(
        val dispatched: Int,
        val harvested: Int,
        val liveCount: Int,
        val landedCount: Int
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val workDir = File(args.getOrElse(0) { "." })
            val wal = JvmAppendWal(File(workDir, "jules-board.wal"))
            val store = JulesBoardStore(wal)
            val element = FlywheelElement()
            val flywheel = Flywheel(store, workDir, element)

            println("[FLYWHEEL] Starting on $workDir (maxLive=${flywheel.maxLive}, pollInterval=${flywheel.pollIntervalMs}ms, simBudget=${flywheel.sessionSimulationMs}ms)")

            // Run the loop inside the CCEK scope so sibling reactors can read these defaults.
            runBlocking(element) {
                while (true) {
                    val start = System.currentTimeMillis()
                    val result = flywheel.cycle()
                    println("[FLYWHEEL] Cycle: dispatched=${result.dispatched} harvested=${result.harvested} live=${result.liveCount} landed=${result.landedCount}")
                    val elapsed = System.currentTimeMillis() - start
                    val delayMs = (flywheel.pollIntervalMs - elapsed).coerceAtLeast(1000)
                    delay(delayMs)
                }
            }
        }
    }
}