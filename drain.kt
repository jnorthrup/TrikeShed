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