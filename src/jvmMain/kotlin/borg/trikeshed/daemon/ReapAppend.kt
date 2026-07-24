package borg.trikeshed.daemon

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import borg.trikeshed.utils.kanban.JulesBoardStore
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * reap-append — post-DELETE WorkDrained causes to the WAL so the conductor's
 * `cards` projection no longer re-adopts the deleted sessions on next poll.
 *
 * Reads a list of session ids from the path passed as arg[0], emits one
 * `JulesCause.WorkDrained` cause per id (with `commitSha="outbox:<short>"`)
 * so the session is permanently retired from the live map.
 */
object ReapAppend {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val listPath = args.getOrNull(0) ?: error("usage: ReapAppend <sid-list.txt> [wal-path]")
        val walPath = args.getOrNull(1)
            ?: File(System.getProperty("user.home"), ".local/forge/jules-board.wal").absolutePath
        val forgeHome = File(walPath).parentFile
        forgeHome.mkdirs()

        val store = JulesBoardStore(JvmAppendWal(File(walPath)))
        val sids = File(listPath).readLines().filter { it.isNotBlank() }
        println("appending WorkDrained for ${sids.size} sessions to $walPath")
        var ok = 0
        for (sid in sids) {
            val commitSha = "outbox-${sid.take(7)}"
            store.appendWork(sid, JulesCause.WorkDrained(
                workId = "reap-${sid}",
                sessionId = sid,
                commitSha = commitSha,
                taskId = "reap-pass",
                at = System.currentTimeMillis(),
            ))
            ok++
        }
        println("appended $ok WorkDrained causes")
    }
}
