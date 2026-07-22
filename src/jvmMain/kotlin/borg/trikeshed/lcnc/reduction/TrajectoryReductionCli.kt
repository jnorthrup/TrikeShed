package borg.trikeshed.lcnc.reduction

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.parse.json.JsonSupport
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
    // Read from: file arg, stdin via "/dev/stdin" or "-", or INPUT_JSON env var, or default sample
    val inputJson = when {
        args.isNotEmpty() && args[0] != "-" && args[0] != "/dev/stdin" -> 
            java.io.File(args[0]).readText()
        args.isNotEmpty() && (args[0] == "-" || args[0] == "/dev/stdin") ->
            System.`in`.bufferedReader().readText()
        else -> System.getenv("INPUT_JSON") ?: 
            """{"causes":[{"type":"DrainApplied","commitSha":"abc123","rejects":0,"at":1000}],"taskFingerprint":"abc123def456","attemptCount":3,"deps":["job-1"]}"""
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

    // Extract fingerprint
    val fingerprint = parsed["taskFingerprint"] as? String ?: "unknown"

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

    val type = causeItem["type"] as? String ?: return null
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
        else -> null
    }
}
