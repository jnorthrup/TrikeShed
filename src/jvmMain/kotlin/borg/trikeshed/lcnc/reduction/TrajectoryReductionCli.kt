package borg.trikeshed.lcnc.reduction

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.reduction.TrajectoryOutcome
import borg.trikeshed.reduction.TrajectoryReduction
import borg.trikeshed.reduction.verdictFor
import java.io.File
import kotlin.system.exitProcess

/**
 * CLI entry point for TrajectoryReduction.
 *
 * Reads a JSON-encoded list of JulesCause from stdin and prints the verdict to stdout.
 * Uses JsonParser from parse/json for JSON parsing.
 *
 * Input format (stdin):
 *   {
 *     "causes": [
 *       {"type": "DrainApplied", "commitSha": "abc123", "rejects": 0, "at": 1000},
 *       {"type": "DrainFailed", "reason": "no patch", "at": 2000}
 *     ],
 *     "taskFingerprint": "abc123def456",
 *     "attemptCount": 3,
 *     "deps": ["job-1", "job-2"]
 *   }
 *
 * Output format (stdout):
 *   fingerprint=abc123def456 attempts=3 category=NoPatch frozen=true depsSatisfied=true
 */
fun main(args: Array<String>) {
    // --watch mode: live TUI that re-renders when ~/.local/forge/trajectory.json changes
    if (args.isNotEmpty() && args[0] == "--watch") {
        val homeDir = System.getenv("TRIKESHED_HOME") ?: System.getProperty("user.home") + "/.local/forge"
        val file = java.io.File("$homeDir/trajectory.json")
        if (!file.exists()) {
            println("Creating $file for watching...")
            file.parentFile?.mkdirs()
            file.writeText("""{"causes":[{"type":"DrainApplied","commitSha":"abc123","rejects":0,"at":1000}],"taskFingerprint":"abc123def456","attemptCount":1,"deps":[]}""")
        }
        runWatchMode(file)
        return
    }

    // Read from: file arg, stdin via "/dev/stdin" or "-", or INPUT_JSON env var, or default sample
    val inputJson = when {
        args.isNotEmpty() && args[0] != "-" && args[0] != "/dev/stdin" ->
            java.io.File(args[0]).readText()
        args.isNotEmpty() && (args[0] == "-" || args[0] == "/dev/stdin") ->
            System.`in`.bufferedReader().readText()
        else -> System.getenv("INPUT_JSON") ?:
            """{"causes":[{"type":"DrainApplied","commitSha":"abc123","rejects":0,"at":1000}],"taskFingerprint":"abc123def456","attemptCount":1,"deps":[]}"""
    }

    // Parse with JsonSupport (the project's own JSON parser via confix)
    val parsed = JsonSupport.parse(inputJson) as? Map<*, *>

    if (parsed == null) {
        println("ERROR: failed to parse JSON")
        exitProcess(1)
    }

    // Extract causes array
    val causesList = mutableListOf<JulesCause>()
    val causesRaw = parsed["causes"]
    if (causesRaw is List<*>) {
        for (causeItem in causesRaw) {
            parseCause(causeItem)?.let { causesList.add(it) }
        }
    }

    // Prefer workId (human-readable slug) over taskFingerprint (SHA1).
    val fingerprint = (parsed["workId"] as? String)
        ?: (parsed["taskFingerprint"] as? String)
        ?: "unknown"

    // Extract attemptCount
    val attemptCount = (parsed["attemptCount"] as? Number)?.toInt() ?: 1

    // Extract deps
    val depsList = mutableListOf<String>()
    val depsRaw = parsed["deps"]
    if (depsRaw is List<*>) {
        for (dep in depsRaw) {
            if (dep is String) depsList.add(dep)
        }
    }

    val verdict = verdictFor(
        cardCauses = causesList,
        taskFingerprint = fingerprint,
        attemptCount = attemptCount,
        deps = depsList
    )

    val category = when (verdict.outcome) {
        is TrajectoryOutcome.NoPatch -> "NoPatch"
        is TrajectoryOutcome.DeletionDominant -> "DeletionDominant"
        is TrajectoryOutcome.Stub -> "Stub"
        is TrajectoryOutcome.GateRed -> "GateRed"
        is TrajectoryOutcome.Landed -> "Landed"
    }

    println("fingerprint=${verdict.taskFingerprint} " +
            "attempts=${verdict.attemptCount} " +
            "category=$category " +
            "frozen=${verdict.frozen} " +
            "depsSatisfied=${verdict.depsSatisfied}")

    exitProcess(0)
}

/** Parse a single cause from a Map */
private fun parseCause(causeItem: Any?): JulesCause? {
    if (causeItem !is Map<*, *>) return null

    val type = (causeItem["type"] ?: causeItem["kind"]) as? String ?: return null
    val at = (causeItem["at"] as? Number)?.toLong() ?: 0L

    return when (type) {
        "DrainApplied" -> {
            val sha = causeItem["commitSha"] as? String ?: ""
            val rejects = (causeItem["rejects"] as? Number)?.toInt() ?: 0
            JulesCause.DrainApplied(sha, rejects, at)
        }
        "DrainFailed" -> {
            val reason = causeItem["reason"] as? String ?: ""
            JulesCause.DrainFailed(reason, at)
        }
        "SessionFailed" -> {
            val reason = causeItem["reason"] as? String ?: ""
            JulesCause.SessionFailed(reason, at)
        }
        "PatchArrived" -> {
            val bytes = (causeItem["bytes"] as? Number)?.toLong() ?: 0L
            JulesCause.PatchArrived(bytes, at)
        }
        "WorkQueued" -> {
            JulesCause.WorkQueued(
                workId = causeItem["workId"] as? String ?: "",
                tier = causeItem["tier"] as? String ?: "task",
                title = causeItem["title"] as? String ?: "",
                spec = causeItem["spec"] as? String ?: "",
                parent = causeItem["parent"] as? String,
                score = (causeItem["score"] as? Number)?.toDouble() ?: 0.5,
                at = at,
            )
        }
        "WorkDispatched" -> {
            JulesCause.WorkDispatched(
                workId = causeItem["workId"] as? String ?: "",
                sessionId = causeItem["sessionId"] as? String ?: "",
                attempt = (causeItem["attempt"] as? Number)?.toInt() ?: 1,
                at = at,
            )
        }
        "WorkDrained" -> {
            JulesCause.WorkDrained(
                workId = causeItem["workId"] as? String ?: "",
                sessionId = causeItem["sessionId"] as? String ?: "",
                commitSha = causeItem["commitSha"] as? String ?: "",
                taskId = causeItem["taskId"] as? String ?: "",
                at = at,
            )
        }
        else -> null
    }
}

/** Live TUI: watch a file, re-render the dashboard on every change. */
private fun runWatchMode(file: java.io.File) {
    val path = file.toPath()
    val dir = path.parent
    val watchService = java.nio.file.FileSystems.getDefault().newWatchService()
    if (dir != null) {
        dir.register(watchService, java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY)
    }

    // Initial render
    renderDashboard(file.readText())

    while (true) {
        val key = watchService.take() ?: continue
        for (event in key.pollEvents()) {
            val changed = event.context() as? java.nio.file.Path
            if (changed == path || changed?.fileName?.toString() == path.fileName?.toString()) {
                Thread.sleep(50) // debounce
                renderDashboard(file.readText())
            }
        }
        if (!key.reset()) break
    }
}

private fun renderDashboard(json: String) {
    // Clear screen, home cursor
    print("\u001b[2J\u001b[H")

    val parsed = JsonSupport.parse(json) as? Map<*, *>
    if (parsed == null) {
        println("ERROR: failed to parse JSON")
        return
    }

    val causesList = mutableListOf<JulesCause>()
    val causesRaw = parsed["causes"]
    if (causesRaw is List<*>) {
        for (causeItem in causesRaw) parseCause(causeItem)?.let { causesList.add(it) }
    }
    // Prefer workId (human-readable slug) over taskFingerprint (SHA1).
    val fingerprint = (parsed["workId"] as? String)
        ?: (parsed["taskFingerprint"] as? String)
        ?: "unknown"
    val attemptCount = (parsed["attemptCount"] as? Number)?.toInt() ?: causesList.size
    val depsList = mutableListOf<String>()
    val depsRaw = parsed["deps"]
    if (depsRaw is List<*>) for (dep in depsRaw) if (dep is String) depsList.add(dep)

    val verdict = verdictFor(causesList, fingerprint, attemptCount, depsList)
    val category = when (verdict.outcome) {
        is TrajectoryOutcome.NoPatch -> "NoPatch"
        is TrajectoryOutcome.DeletionDominant -> "DeletionDominant"
        is TrajectoryOutcome.Stub -> "Stub"
        is TrajectoryOutcome.GateRed -> "GateRed"
        is TrajectoryOutcome.Landed -> "Landed"
    }

    // Look up the human-readable title from the queue projection.
    val title = (parsed["title"] as? String)
        ?: run {
            val dir = File(System.getProperty("user.home"), ".local/forge")
            val store = borg.trikeshed.utils.kanban.JulesBoardStore(dir)
            store.loadQueue().firstOrNull { it.workId == fingerprint }?.title
        }
        ?: fingerprint

    val reset = "\u001b[0m"
    val cyan = "\u001b[36m"
    val yellow = "\u001b[33m"
    val magenta = "\u001b[35m"
    val green = "\u001b[32m"
    val red = "\u001b[31m"

    val catColor = when (category) {
        "Landed" -> green
        "NoPatch", "Stub" -> yellow
        else -> red
    }
    val frozenColor = if (verdict.frozen) red else green
    val depsColor = if (verdict.depsSatisfied) green else red

    println()
    println("${cyan}╔══════════════════════════════════════════════════════════════╗$reset")
    println("${cyan}║              TRAJECTORY REDUCTION DASHBOARD                 ║$reset")
    println("${cyan}╠══════════════════════════════════════════════════════════════╣$reset")
    println("${cyan}║${reset}  Work ID:     ${yellow}${fingerprint}$reset".padEnd(78) + "${cyan}║$reset")
    val titleDisplay = if (title.length > 56) title.take(53) + "..." else title
    println("${cyan}║${reset}  Title:       ${magenta}${titleDisplay}$reset".padEnd(78) + "${cyan}║$reset")
    println("${cyan}║${reset}  Attempts:    ${yellow}${verdict.attemptCount}$reset".padEnd(78) + "${cyan}║$reset")
    println("${cyan}║${reset}  Category:    ${catColor}${category}$reset".padEnd(78) + "${cyan}║$reset")
    println("${cyan}║${reset}  Frozen:      ${frozenColor}${verdict.frozen}$reset".padEnd(78) + "${cyan}║$reset")
    println("${cyan}║${reset}  Deps OK:     ${depsColor}${verdict.depsSatisfied}$reset".padEnd(78) + "${cyan}║$reset")
    println("${cyan}╠══════════════════════════════════════════════════════════════╣$reset")
    println("${cyan}║${reset}  Causes (${magenta}${causesList.size}$reset${cyan}):$reset".padEnd(78) + "${cyan}║$reset")
    for (c in causesList.takeLast(8)) {
        val label = "    ${c.javaClass.simpleName}"
        println("${cyan}║${reset}${magenta}${label}$reset".padEnd(78) + "${cyan}║$reset")
    }
    if (causesList.size > 8) {
        println("${cyan}║${reset}    ... +${causesList.size - 8} more".padEnd(78) + "${cyan}║$reset")
    }
    println("${cyan}╚══════════════════════════════════════════════════════════════╝$reset")
    println()
    println("(edits to ~/.local/forge/trajectory.json refresh the dashboard; Ctrl+C to quit)")
}
