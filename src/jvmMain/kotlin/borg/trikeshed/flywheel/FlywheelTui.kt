/*
 * Copyright (c) 2017 TrikeShed Contributors
 * AGPLv3 — see LICENSE
 */
package borg.trikeshed.flywheel

import borg.trikeshed.jules.JulesRestClient
import borg.trikeshed.utils.kanban.JulesBoardStore
import java.io.File
import java.time.Instant
import java.util.ArrayDeque
import kotlin.math.roundToInt

/**
 * Live throughput view of the flywheel — a fancy colored log reader.
 *
 * Polls Jules REST + local process table + todo queue + WAL, derives
 * paddle counts and per-agent state, and renders a spinning wheel with
 * colored boxes, pulsing arrows, and a rolling event log.
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

    private data class LogEvent(
        val at: Long,
        val stage: String,
        val message: String,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val once = args.contains("--once")
        val intervalMs = args.firstOrNull { it.startsWith("--interval=") }
            ?.substringAfter('=')?.toLongOrNull() ?: 2_000L
        val source = args.firstOrNull { it.startsWith("--source=") }
            ?.substringAfter('=') ?: DEFAULT_SOURCE
        val colors = !args.contains("--no-color") && System.getenv("NO_COLOR") == null
        val repoDir = File(args.firstOrNull { !it.startsWith("--") } ?: System.getProperty("user.dir"))
        val forgeDir = File(System.getenv("TRIKESHED_HOME") ?: File(System.getProperty("user.home"), ".local/forge").path)
        val apiKey = System.getenv("JULES_API_KEY")
        val client = apiKey?.let(::JulesRestClient)
        val pulses = Pulses()
        val events = ArrayDeque<LogEvent>()
        var previous: Snapshot? = null
        var tick = 0

        do {
            val snapshot = capture(client, source, repoDir, forgeDir)
            updatePulses(previous, snapshot, pulses)
            updateEvents(previous, snapshot, events)
            print("\u001b[2J\u001b[H")
            print(render(snapshot, pulses, events, tick++, colors))
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

    private fun updateEvents(previous: Snapshot?, now: Snapshot, events: ArrayDeque<LogEvent>) {
        if (previous == null) {
            addEvent(events, LogEvent(now.at, "BOOT", "queue=${now.queue}  agents=${now.occupied}/${now.capacity}  run=${now.running}  guide=${now.guide}  harvest=${now.harvest}"))
            return
        }
        if (now.queue != previous.queue) {
            val stage = if (now.queue > previous.queue) "QUEUE" else "DISPATCH"
            addEvent(events, LogEvent(now.at, stage, "ready queue ${previous.queue} → ${now.queue}"))
        }
        if (now.land != previous.land) {
            addEvent(events, LogEvent(now.at, "LAND", "merged total ${previous.land} → ${now.land}"))
        }
        if (now.openCodeRunning != previous.openCodeRunning) {
            addEvent(events, LogEvent(now.at, "BRAIN", "OpenCode workers ${previous.openCodeRunning} → ${now.openCodeRunning}"))
        }
        if (now.codexRunning != previous.codexRunning) {
            addEvent(events, LogEvent(now.at, "RUN", "Codex workers ${previous.codexRunning} → ${now.codexRunning}"))
        }
        val before = previous.sessions.associateBy { it.id }
        val after = now.sessions.associateBy { it.id }
        for ((id, session) in after) {
            val old = before[id]
            if (old == null) {
                addEvent(events, LogEvent(now.at, stateLabel(session.state), "jules/${id.takeLast(6)} + ${session.title.take(76)}"))
            } else if (old.state != session.state) {
                addEvent(events, LogEvent(now.at, stateLabel(session.state), "jules/${id.takeLast(6)} ${stateLabel(old.state)} → ${stateLabel(session.state)}  ${session.title.take(62)}"))
            }
        }
        for ((id, session) in before) {
            if (id !in after) addEvent(events, LogEvent(now.at, "RETIRED", "jules/${id.takeLast(6)} - ${session.title.take(76)}"))
        }
    }

    private fun addEvent(events: ArrayDeque<LogEvent>, event: LogEvent) {
        while (events.size >= 10) events.removeFirst()
        events.addLast(event)
    }

    private fun render(s: Snapshot, pulses: Pulses, events: ArrayDeque<LogEvent>, tick: Int, colors: Boolean): String = buildString {
        val bar = saturationBar(s.saturation)
        val wheel = listOf("\u25D0", "\u25D3", "\u25D1", "\u25D2")[tick % 4]
        val saturationColor = when {
            s.saturation >= 85 -> GREEN
            s.saturation >= 50 -> YELLOW
            else -> RED
        }
        val bottleneck = listOf(
            "DISPATCH" to s.dispatch,
            "RUNNING" to s.running,
            "GUIDE" to s.guide,
            "HARVEST" to s.harvest,
        ).maxBy { it.second }
        appendLine(paint(colors, BOLD + CYAN, "$wheel  OROBOROS FLYWHEEL") + "   " + paint(colors, saturationColor, "SATURATION ${s.occupied}/${s.capacity} ${s.saturation}% $bar"))
        appendLine(paint(colors, DIM, "updated ${Instant.ofEpochMilli(s.at)}") + "   bottleneck=" + paint(colors, stageColor(bottleneck.first), "${bottleneck.first}:${bottleneck.second}") + (s.error?.let { "   " + paint(colors, RED, "ERROR: $it") } ?: ""))
        appendLine()

        // Diagram
        appendLine("                                  \u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510")
        appendLine("                                  \u2502 ${paint(colors, PURPLE, "SLICE / INSIGHT")} ${padCount(s.slice)}\u2502")
        appendLine("                                  \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u252C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518")
        appendLine("                              ${diag(pulses.curateSlice, tick, true, colors)}         ${diag(pulses.sliceQueue, tick, false, colors)}")
        appendLine("                 \u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510             \u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510")
        appendLine("                 \u2502 ${paint(colors, PURPLE, "CURATE / REFILL")} ${padCount(s.curate)}\u2502             \u2502 ${paint(colors, CYAN, "READY QUEUE")}     ${padCount(s.queue)}\u2502")
        appendLine("                 \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2532\u2500\u2500\u2500\u2500\u2500\u2518             \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u252C\u2500\u2500\u2500\u2500\u2500\u2518")
        appendLine("                          ${vertical(pulses.landCurate, tick, true, colors)}                            ${vertical(pulses.queueDispatch, tick, false, colors)}")
        appendLine("                 \u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534\u2500\u2500\u2500\u2500\u2500\u2510             \u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2532\u2500\u2500\u2500\u2500\u2500\u2510")
        appendLine("                 \u2502 ${paint(colors, GREEN, "LAND / MERGE")}    ${padCount(s.land)}\u2502             \u2502 ${paint(colors, YELLOW, "DISPATCH")}        ${padCount(s.dispatch)}\u2502")
        appendLine("                 \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2532\u2500\u2500\u2500\u2500\u2500\u2518             \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u252C\u2500\u2500\u2500\u2500\u2500\u2518")
        appendLine("                          ${vertical(pulses.harvestLand, tick, true, colors)}                            ${vertical(pulses.dispatchRun, tick, false, colors)}")
        appendLine("                 \u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534\u2500\u2500\u2500\u2500\u2500\u2510  ${leftArrow(pulses.runHarvest, tick, colors)}  \u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2532\u2500\u2500\u2500\u2500\u2500\u2510")
        appendLine("                 \u2502 ${paint(colors, BLUE, "HARVEST / REVIEW")}${padCount(s.harvest)}\u2502             \u2502 ${paint(colors, GREEN, "RUNNING")}         ${padCount(s.running)}\u2502")
        appendLine("                 \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518  ${leftArrow(pulses.runGuide, tick, colors)}  \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u252C\u2500\u2500\u2500\u2500\u2500\u2518")
        appendLine("                                                \u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2532\u2500\u2500\u2500\u2500\u2500\u2510")
        appendLine("                                                \u2502 ${paint(colors, MAGENTA, "GUIDE / AWAITING")}${padCount(s.guide)}\u2502")
        appendLine("                                                \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518")
        appendLine()

        // Agent pools
        appendLine(paint(colors, BOLD + CYAN, "AGENT POOLS"))
        appendLine("  ${paint(colors, BLUE, "Jules")}      ${poolBar(s.sessions.count { it.state !in TERMINAL }, JULES_CAPACITY, colors)}  latest: ${paint(colors, DIM, latest(s.sessions))}")
        appendLine("  ${paint(colors, PURPLE, "OpenCode")}   ${poolBar(s.openCodeRunning, if (s.openCodeAvailable) OPENCODE_CAPACITY else 0, colors)}  ${if (s.openCodeAvailable) "idle/brain workers" else paint(colors, RED, "unavailable")}")
        appendLine("  ${paint(colors, GREEN, "Codex")}      ${poolBar(s.codexRunning, if (s.codexAvailable) 2 else 0, colors)}  ${if (s.codexAvailable) "local workers" else paint(colors, RED, "unavailable")}")
        appendLine()

        // Latest agents
        appendLine(paint(colors, BOLD + CYAN, "LATEST JULES AGENTS"))
        s.sessions.take(10).forEach { session ->
            val state = stateLabel(session.state)
            appendLine("  " + paint(colors, DIM, "jules/${session.id.takeLast(6)}") + "  " + paint(colors, stageColor(state), state.padEnd(10)) + "  ${session.title.take(68)}")
        }
        if (s.sessions.isEmpty()) appendLine("  (none)")
        appendLine()

        // Event log
        appendLine(paint(colors, BOLD + CYAN, "RECENT MOVEMENT"))
        events.toList().takeLast(8).forEach { event ->
            val time = Instant.ofEpochMilli(event.at).toString().substring(11, 19)
            appendLine("  " + paint(colors, DIM, time) + "  " + paint(colors, stageColor(event.stage), event.stage.padEnd(9)) + " ${event.message}")
        }
        appendLine()
        appendLine(paint(colors, DIM, "\u25CF on an arrow = work crossed that paddle.  Ctrl+C quits.  --no-color disables ANSI."))
    }

    private fun padCount(n: Int): String = n.toString().padStart(3).padEnd(4)
    private fun saturationBar(percent: Int): String {
        val filled = (percent.coerceIn(0, 100) / 5)
        return "[" + "\u2588".repeat(filled) + "\u00B7".repeat(20 - filled) + "]"
    }
    private fun poolBar(running: Int, capacity: Int, colors: Boolean): String =
        if (capacity == 0) "  -/- [unavailable]"
        else "%3d/%-3d [%s%s]".format(running, capacity, "\u2588".repeat(running.coerceAtMost(capacity)), "\u00B7".repeat((capacity - running).coerceAtLeast(0)))

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

    private fun pulse(active: Int, tick: Int): Char = if (active > 0 && tick % 2 == 0) '\u25CF' else ' '
    private fun diag(active: Int, tick: Int, up: Boolean, colors: Boolean): String =
        paint(colors, if (active > 0) YELLOW else DIM, if (up) "\u2196${pulse(active, tick)}" else "${pulse(active, tick)}\u2197")
    private fun vertical(active: Int, tick: Int, up: Boolean, colors: Boolean): String =
        paint(colors, if (active > 0) YELLOW else DIM, if (up) "${pulse(active, tick)}\u2502" else "\u2502${pulse(active, tick)}")
    private fun leftArrow(active: Int, tick: Int, colors: Boolean): String =
        paint(colors, if (active > 0) YELLOW else DIM, "\u25C0\u2500\u2500${pulse(active, tick)}\u2500\u2500\u2500\u2500")

    private fun stageColor(stage: String): String = when (stage) {
        "BOOT", "QUEUE", "DISPATCH" -> CYAN
        "RUN", "RUNNING", "LAND" -> GREEN
        "GUIDE" -> MAGENTA
        "HARVEST", "REVIEW" -> BLUE
        "BRAIN", "SLICE", "CURATE" -> PURPLE
        "FAILED", "ERROR", "RETIRED" -> RED
        else -> YELLOW
    }

    private fun paint(enabled: Boolean, color: String, text: String): String =
        if (enabled) "$color$text$RESET" else text

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
    private const val RESET = "\u001b[0m"
    private const val BOLD = "\u001b[1m"
    private const val DIM = "\u001b[2m"
    private const val RED = "\u001b[31m"
    private const val GREEN = "\u001b[32m"
    private const val YELLOW = "\u001b[33m"
    private const val BLUE = "\u001b[34m"
    private const val MAGENTA = "\u001b[35m"
    private const val CYAN = "\u001b[36m"
    private const val PURPLE = "\u001b[95m"
}
