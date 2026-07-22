/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.flywheel

import borg.trikeshed.jules.JulesRestClient
import borg.trikeshed.utils.kanban.JulesBoardStore
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Live throughput view of the flywheel.
 *
 * This deliberately does not render causal internals. It renders the control
 * surface an operator needs: queue pressure, workers occupying slots, work on
 * each paddle, recent task per worker, and a pulse when work crosses an arrow.
 */
object FlywheelTui {
    private const val DEFAULT_SOURCE = "sources/github/jnorthrup/TrikeShed"
    private const val JULES_CAPACITY = 15
    private const val OPENCODE_CAPACITY = 2

    private data class Snapshot(
        val at: Long,
        val queue: Int,
        val slice: Int,
        val dispatch: Int,
        val running: Int,
        val guide: Int,
        val harvest: Int,
        val land: Int,
        val curate: Int,
        val sessions: List<JulesRestClient.SessionInfo>,
        val openCodeRunning: Int,
        val codexRunning: Int,
        val openCodeAvailable: Boolean,
        val codexAvailable: Boolean,
        val error: String? = null,
    ) {
        val occupied: Int get() = sessions.count { it.state !in TERMINAL } + openCodeRunning + codexRunning
        val capacity: Int get() = JULES_CAPACITY + (if (openCodeAvailable) OPENCODE_CAPACITY else 0) + (if (codexAvailable) 2 else 0)
        val saturation: Int get() = if (capacity == 0) 0 else ((occupied * 100.0) / capacity).roundToInt()
    }

    private data class Pulses(
        var sliceQueue: Int = 0,
        var queueDispatch: Int = 0,
        var dispatchRun: Int = 0,
        var runGuide: Int = 0,
        var runHarvest: Int = 0,
        var harvestLand: Int = 0,
        var landCurate: Int = 0,
        var curateSlice: Int = 0,
    ) {
        fun decay() {
            sliceQueue = (sliceQueue - 1).coerceAtLeast(0)
            queueDispatch = (queueDispatch - 1).coerceAtLeast(0)
            dispatchRun = (dispatchRun - 1).coerceAtLeast(0)
            runGuide = (runGuide - 1).coerceAtLeast(0)
            runHarvest = (runHarvest - 1).coerceAtLeast(0)
            harvestLand = (harvestLand - 1).coerceAtLeast(0)
            landCurate = (landCurate - 1).coerceAtLeast(0)
            curateSlice = (curateSlice - 1).coerceAtLeast(0)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val once = args.contains("--once")
        val intervalMs = args.firstOrNull { it.startsWith("--interval=") }
            ?.substringAfter('=')?.toLongOrNull() ?: 2_000L
        val source = args.firstOrNull { it.startsWith("--source=") }
            ?.substringAfter('=') ?: DEFAULT_SOURCE
        val repoDir = File(args.firstOrNull { !it.startsWith("--") } ?: System.getProperty("user.dir"))
        val forgeDir = File(System.getenv("TRIKESHED_HOME") ?: File(System.getProperty("user.home"), ".local/forge").path)
        val apiKey = System.getenv("JULES_API_KEY")
        val client = apiKey?.let(::JulesRestClient)
        val pulses = Pulses()
        var previous: Snapshot? = null
        var tick = 0

        do {
            val snapshot = capture(client, source, repoDir, forgeDir)
            updatePulses(previous, snapshot, pulses)
            print("\u001b[2J\u001b[H")
            print(render(snapshot, pulses, tick++))
            previous = snapshot
            pulses.decay()
            if (!once) Thread.sleep(intervalMs)
        } while (!once)
    }

    private fun capture(
        client: JulesRestClient?,
        source: String,
        repoDir: File,
        forgeDir: File,
    ): Snapshot {
        val todo = File(repoDir, "doc/todo.md")
        val queue = if (todo.exists()) todo.readLines().count { it.matches(Regex("^\\s*- \\[ \\].*")) } else 0
        val land = runCatching {
            JulesBoardStore(forgeDir).load().values.count { it.drained }
        }.getOrDefault(0)
        val openCodeRunning = processCount("opencode")
        val codexRunning = processCount("codex")
        val openCodeAvailable = executableExists("/opt/homebrew/bin/opencode") || executableOnPath("opencode")
        val codexAvailable = executableOnPath("codex")

        return try {
            val sessions = client?.listSessions()
                ?.filter { it.source == source }
                ?.sortedByDescending { it.updateTime }
                ?: emptyList()
            val dispatch = sessions.count { it.state == "QUEUED" || it.state == "PLANNING" || it.state == "AWAITING_PLAN_APPROVAL" }
            val running = sessions.count { it.state == "IN_PROGRESS" }
            val guide = sessions.count { it.state == "AWAITING_USER_FEEDBACK" }
            val harvest = sessions.count { it.state == "COMPLETED" || it.state == "FINISHED" }
            val lowWater = JULES_CAPACITY * 2
            Snapshot(
                at = System.currentTimeMillis(),
                queue = queue,
                slice = if (queue < lowWater) lowWater - queue else 0,
                dispatch = dispatch,
                running = running,
                guide = guide,
                harvest = harvest,
                land = land,
                curate = if (queue < lowWater) 1 else 0,
                sessions = sessions,
                openCodeRunning = openCodeRunning,
                codexRunning = codexRunning,
                openCodeAvailable = openCodeAvailable,
                codexAvailable = codexAvailable,
                error = if (client == null) "JULES_API_KEY unset" else null,
            )
        } catch (t: Throwable) {
            Snapshot(
                at = System.currentTimeMillis(), queue = queue, slice = 0, dispatch = 0,
                running = 0, guide = 0, harvest = 0, land = land, curate = 0,
                sessions = emptyList(), openCodeRunning = openCodeRunning,
                codexRunning = codexRunning, openCodeAvailable = openCodeAvailable,
                codexAvailable = codexAvailable, error = t.message ?: t::class.simpleName,
            )
        }
    }

    private fun updatePulses(previous: Snapshot?, now: Snapshot, pulses: Pulses) {
        if (previous == null) return
        if (now.queue > previous.queue) pulses.sliceQueue = 4
        if (now.dispatch > previous.dispatch || now.queue < previous.queue) pulses.queueDispatch = 4
        if (now.running > previous.running) pulses.dispatchRun = 4
        if (now.guide > previous.guide) pulses.runGuide = 4
        if (now.harvest > previous.harvest) pulses.runHarvest = 4
        if (now.land > previous.land) pulses.harvestLand = 4
        if (now.curate > previous.curate) pulses.landCurate = 4
        if (now.slice > previous.slice) pulses.curateSlice = 4
    }

    private fun render(s: Snapshot, pulses: Pulses, tick: Int): String = buildString {
        val bar = saturationBar(s.saturation)
        appendLine("OROBOROS FLYWHEEL   SATURATION ${s.occupied}/${s.capacity} ${s.saturation}% $bar")
        appendLine("updated ${Instant.ofEpochMilli(s.at)}${s.error?.let { "   ERROR: $it" } ?: ""}")
        appendLine()
        appendLine("                                  ┌──────────────────┐")
        appendLine("                                  │ SLICE / INSIGHT ${padCount(s.slice)}│")
        appendLine("                                  └────────┬─────────┘")
        appendLine("                              ${diag(pulses.curateSlice, tick, true)}         ${diag(pulses.sliceQueue, tick, false)}")
        appendLine("                 ┌──────────────────┐             ┌──────────────────┐")
        appendLine("                 │ CURATE / REFILL ${padCount(s.curate)}│             │ READY QUEUE     ${padCount(s.queue)}│")
        appendLine("                 └────────▲─────────┘             └────────┬─────────┘")
        appendLine("                          ${vertical(pulses.landCurate, tick, true)}                            ${vertical(pulses.queueDispatch, tick, false)}")
        appendLine("                 ┌────────┴─────────┐             ┌────────▼─────────┐")
        appendLine("                 │ LAND / MERGE    ${padCount(s.land)}│             │ DISPATCH        ${padCount(s.dispatch)}│")
        appendLine("                 └────────▲─────────┘             └────────┬─────────┘")
        appendLine("                          ${vertical(pulses.harvestLand, tick, true)}                            ${vertical(pulses.dispatchRun, tick, false)}")
        appendLine("                 ┌────────┴─────────┐  ${leftArrow(pulses.runHarvest, tick)}  ┌────────▼─────────┐")
        appendLine("                 │ HARVEST / REVIEW${padCount(s.harvest)}│             │ RUNNING         ${padCount(s.running)}│")
        appendLine("                 └──────────────────┘  ${leftArrow(pulses.runGuide, tick)}  └────────┬─────────┘")
        appendLine("                                                ┌────────▼─────────┐")
        appendLine("                                                │ GUIDE / AWAITING${padCount(s.guide)}│")
        appendLine("                                                └──────────────────┘")
        appendLine()
        appendLine("AGENT POOLS")
        appendLine("  Jules      ${poolBar(s.sessions.count { it.state !in TERMINAL }, JULES_CAPACITY)}  latest: ${latest(s.sessions)}")
        appendLine("  OpenCode   ${poolBar(s.openCodeRunning, if (s.openCodeAvailable) OPENCODE_CAPACITY else 0)}  ${if (s.openCodeAvailable) "idle/brain workers" else "unavailable"}")
        appendLine("  Codex      ${poolBar(s.codexRunning, if (s.codexAvailable) 2 else 0)}  ${if (s.codexAvailable) "local workers" else "unavailable"}")
        appendLine()
        appendLine("LATEST JULES AGENTS")
        s.sessions.take(10).forEach { session ->
            appendLine("  jules/${session.id.takeLast(6)}  ${stateLabel(session.state).padEnd(10)}  ${session.title.take(68)}")
        }
        if (s.sessions.isEmpty()) appendLine("  (none)")
        appendLine()
        appendLine("Arrow pulse: ● = a work item crossed that paddle since the previous refresh.  Ctrl+C quits.")
    }

    private fun padCount(n: Int): String = n.toString().padStart(3).padEnd(4)
    private fun saturationBar(percent: Int): String {
        val filled = (percent.coerceIn(0, 100) / 5)
        return "[" + "█".repeat(filled) + "·".repeat(20 - filled) + "]"
    }
    private fun poolBar(running: Int, capacity: Int): String =
        if (capacity == 0) "  -/- [unavailable]"
        else "%3d/%-3d [%s%s]".format(running, capacity, "█".repeat(running.coerceAtMost(capacity)), "·".repeat((capacity - running).coerceAtLeast(0)))

    private fun latest(sessions: List<JulesRestClient.SessionInfo>): String =
        sessions.firstOrNull()?.title?.take(58) ?: "idle"

    private fun stateLabel(state: String): String = when (state) {
        "AWAITING_USER_FEEDBACK" -> "GUIDE"
        "AWAITING_PLAN_APPROVAL" -> "REVIEW"
        "IN_PROGRESS" -> "RUNNING"
        "PLANNING", "QUEUED" -> "DISPATCH"
        "COMPLETED", "FINISHED" -> "HARVEST"
        "FAILED" -> "FAILED"
        else -> state.take(10)
    }

    private fun pulse(active: Int, tick: Int): Char = if (active > 0 && tick % 2 == 0) '●' else ' '
    private fun diag(active: Int, tick: Int, up: Boolean): String = if (up) "↖${pulse(active, tick)}" else "${pulse(active, tick)}↘"
    private fun vertical(active: Int, tick: Int, up: Boolean): String = if (up) "${pulse(active, tick)}│" else "│${pulse(active, tick)}"
    private fun leftArrow(active: Int, tick: Int): String = "◀──${pulse(active, tick)}────"

    private fun processCount(needle: String): Int = ProcessHandle.allProcesses()
        .filter { p ->
            val executable = File(p.info().command().orElse("")).name
            executable.equals(needle, ignoreCase = true) ||
                executable.startsWith("$needle-", ignoreCase = true)
        }
        .count().toInt()

    private fun executableExists(path: String): Boolean = File(path).canExecute()
    private fun executableOnPath(name: String): Boolean =
        (System.getenv("PATH") ?: "").split(File.pathSeparator).any { File(it, name).canExecute() }

    private val TERMINAL = setOf("COMPLETED", "FINISHED", "FAILED", "PAUSED", "CANCELLED")
}