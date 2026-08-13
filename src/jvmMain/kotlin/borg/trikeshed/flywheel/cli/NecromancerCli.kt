package borg.trikeshed.flywheel.cli

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.job.ContentId
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.util.oroboros.MergeReceipt
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Report obsolete rework candidates without re-queuing them.
 *
 * A WorkDrained receipt is terminal: resurrecting it under a fresh work id
 * bypasses the queue's idempotency and posts duplicate Jules sessions.
 * Follow-up work must be deliberately produced by a current reducer cut.
 */
fun main(args: Array<String>) = runBlocking {
    val forgeDir = File(args.getOrElse(1) { System.getProperty("user.home") + "/.local/forge" })
    val store = JulesBoardStore.forForgeDir(forgeDir)
    val queue = store.loadQueue()

    val staleAfterMs = 1000L * 60 * 60 * 6
    val suppressed = mutableListOf<String>()

    for (entry in queue.filter { it.workId.startsWith("gap:necromance:") && !it.isDrained }) {
        val now = System.currentTimeMillis()
        store.appendWork(entry.workId, JulesCause.WorkDrained(
            workId = entry.workId,
            sessionId = entry.sessionId ?: "superseded:${entry.workId}",
            commitSha = "outbox-${entry.workId.takeLast(8)}",
            taskId = "superseded",
            receipt = MergeReceipt(
                workId = entry.workId,
                producer = "queue-supersession",
                producerRef = entry.parent ?: "",
                patchCid = ContentId.of("superseded:${entry.workId}".encodeToByteArray()),
                revision = "outbox-${entry.workId.takeLast(8)}",
                versionTag = "superseded",
                lexicalMemory = LexicalMemory(
                    summary = "legacy Necromancer requeue suppressed",
                    title = entry.title,
                    content = "The parent WorkDrained receipt is terminal; a new reducer cut is required for follow-up work.",
                ),
                claimedAt = now,
                prUrl = null,
            ),
            at = now,
        ))
        suppressed.add(entry.workId)
    }

    for (entry in queue) {
        val isSuppressed = when {
            entry.isDrained && entry.taskId?.startsWith("retired") == true -> true
            entry.isDrained && entry.commitSha.isNullOrBlank() -> true
            !entry.isDispatched && !entry.isDrained &&
                (System.currentTimeMillis() - entry.queuedAt) > staleAfterMs -> true
            else -> false
        }
        if (isSuppressed && entry.workId !in suppressed) suppressed.add(entry.workId)
    }
    println("[NECROMANCER] suppressed ${suppressed.size} terminal/stale rework candidate(s); appended 0 WorkQueued entries")
    suppressed.forEach { println("  = $it") }
}
