package borg.trikeshed.flywheel.cli

import borg.trikeshed.jules.JulesCause
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import kotlinx.coroutines.runBlocking
import java.io.File

/** Seeds the WAL with sample work items. */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { System.getProperty("user.home") + "/.local/forge" })
    val wal = JvmAppendWal(File(dir, "jules-board.wal"))
    val store = JulesBoardStore(wal)
    runBlocking {
        store.appendWork("w-flywheel", JulesCause.WorkQueued(
            workId = "w-flywheel", tier = "feature", title = "Wire flywheel to CCEK",
            spec = "Make flywheel read defaults from FlywheelElement via coroutine context",
            at = System.currentTimeMillis()
        ))
        store.appendWork("w-bifurcate", JulesCause.WorkQueued(
            workId = "w-bifurcate", tier = "task", title = "Bifurcate reducer outcomes",
            spec = "Split reducer outputs into Landed/GateRed/NoPatch and route to agents",
            at = System.currentTimeMillis()
        ))
        store.appendWork("w-tui", JulesCause.WorkQueued(
            workId = "w-tui", tier = "chore", title = "Live trajectory TUI",
            spec = "TUI dashboard at ~/.local/forge/trajectory.json with file-watch refresh",
            at = System.currentTimeMillis()
        ))
        val queue = store.loadQueue()
        println("[SEED] ${queue.size} entries:")
        queue.forEach { println("  ${it.workId} ${it.tier}: ${it.title}") }
    }
}
