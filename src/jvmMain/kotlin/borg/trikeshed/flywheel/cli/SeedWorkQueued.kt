package borg.trikeshed.flywheel.cli

import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.jules.JulesCause
import java.io.File
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val forgeHome = File(System.getProperty("user.home"), ".local/forge")
    val store = JulesBoardStore(JvmAppendWal(File(forgeHome, "jules-board.wal")))

    val seedFile = File("/tmp/seed_workqueued.txt")
    if (!seedFile.exists()) {
        println("Seed file not found: /tmp/seed_workqueued.txt")
        return
    }

    runBlocking {
        var count = 0
        seedFile.readLines().forEach { line ->
            val parts = line.split('|', limit = 2)
            if (parts.size != 2) return@forEach
            val (sid, title) = parts[0] to parts[1]
            val workId = "session:${sid}"
            
            val cause = JulesCause.WorkQueued(
                workId = workId,
                tier = "forge",
                title = title,
                spec = "Seed from COMPLETED+drained session $sid: $title",
                parent = null,
                score = 0.5,
                at = System.currentTimeMillis(),
            )
            store.appendWork(workId, cause)
            count++
            if (count % 25 == 0) println("Seeded $count WorkQueued entries...")
        }
        println("Done. Seeded $count WorkQueued entries to WAL.")
    }

}