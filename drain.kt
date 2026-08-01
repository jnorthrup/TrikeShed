<<<<<<< ours
import borg.trikeshed.jules.JulesCause
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import java.io.File
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val forgeDir = File(System.getProperty("user.home") + "/.local/forge")
    val store = JulesBoardStore.forForgeDir(forgeDir)
    store.appendWork("readme-couch-head-projection-rev", JulesCause.WorkDrained(
        workId = "readme-couch-head-projection-rev",
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        at = System.currentTimeMillis()
    ))
    println(forgeDir.absolutePath)
}
=======
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
import borg.trikeshed.jules.JulesCause
import java.io.File
import kotlinx.coroutines.runBlocking

fun main() {
    val forgeDir = File(System.getProperty("user.home"), ".local/forge")
    forgeDir.mkdirs()
    val walPath = File(forgeDir, "jules-board.wal")
    val store = JulesBoardStore(JvmAppendWal(walPath))

    runBlocking {
        store.appendWork("readme-appendwal-spi-collapse", JulesCause.WorkDrained(
            workId = "readme-appendwal-spi-collapse",
            sessionId = "readme-appendwal-spi-collapse",
            commitSha = "outbox:superseded",
            taskId = "readme-appendwal-spi-collapse",
            at = System.currentTimeMillis()
        ))
    }
    println("Appended WorkDrained for readme-appendwal-spi-collapse")
}
>>>>>>> theirs
