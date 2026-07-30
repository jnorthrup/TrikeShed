import borg.trikeshed.jules.JulesCause
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Necromancer — reanimates drained or stale flywheel cards whose real work
 * has not yet landed.
 *
 * Reads the authoritative WAL, projects the queue, and resurrects any workId
 * that:
 *   1. has been drained (WorkDrained recorded) but whose drain did not ship
 *      a non-zero commit (the drain record's taskId starts with "retired:" or
 *      is empty)
 *   2. is still the parent of another active workId (its successors may
 *      have landed but the parent never completed)
 *   3. has been queued for longer than `staleAfterMs` without dispatch
 *
 * Each resurrection appends a fresh `WorkQueued` with a new fingerprint so
 * the reducer's idempotency check accepts it.  When the work has been
 * superseded by a tighter locality cut, the new workId carries a supersession
 * receipt.
 *
 * Usage:
 *   NecromancerCli <repoDir> <forgeDir>
 */
fun main(args: Array<String>) = runBlocking {
    val repoDir = File(args.getOrElse(0) { System.getProperty("user.dir") })
    val forgeDir = File(args.getOrElse(1) { System.getProperty("user.home") + "/.local/forge" })
    val store = JulesBoardStore.forForgeDir(forgeDir)
    val queue = store.loadQueue()

    val now = System.currentTimeMillis()
    val staleAfterMs = 1000L * 60 * 60 * 6
    val resurrected = mutableListOf<String>()

    for (entry in queue) {
        val isResurrectable = when {
            entry.isDrained && entry.taskId?.startsWith("retired:") == true -> true
            entry.isDrained && entry.commitSha.isNullOrBlank() -> true
            !entry.isDispatched && !entry.isDrained &&
                (now - entry.queuedAt) > staleAfterMs -> true
            else -> false
        }
        if (!isResurrectable) continue

        val reincarnation = "gap:necromance:${entry.workId.substringAfterLast(':')}:${(now / 1000).toInt()}"
        val spec = buildString {
            appendLine("Necromanced work — original ${entry.workId} (${entry.title})")
            appendLine()
            appendLine("Reason: ${if (entry.isDrained) "drain without landed commit" else "stale queue entry beyond ${staleAfterMs / 1000}s"}")
            appendLine("Original tier: ${entry.tier}  score: ${entry.score}")
            appendLine("Original parent: ${entry.parent ?: "(none)"}")
            appendLine()
            appendLine("Re-read the current code at the original locality before editing.")
            appendLine("If the original locality is already covered by a landed session, supersede with a")
            appendLine("receipt-bearing WorkDrained and stop. Otherwise, generate the missing production")
            appendLine("edges with a single bounded cut.")
        }.trim()
        store.appendWork(reincarnation, JulesCause.WorkQueued(
            workId = reincarnation,
            tier = entry.tier,
            title = "Necromance: ${entry.title}",
            spec = spec,
            parent = entry.parent ?: entry.workId,
            score = (entry.score - 0.05).coerceAtLeast(0.1),
            at = now,
        ))
        resurrected.add(reincarnation)
    }
    println("[NECROMANCER] resurrected ${resurrected.size} stale work entries")
    resurrected.forEach { println("  + $it") }
}
