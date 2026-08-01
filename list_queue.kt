import borg.trikeshed.utils.kanban.*
import java.io.File

suspend fun main() {
    val forgeDir = File(System.getProperty("user.home") + "/.local/forge")
    val store = JulesBoardStore.forForgeDir(forgeDir)
    val queue = store.loadQueue()
    for (entry in queue) {
        println("Work: " + entry.workId + " - " + entry.title + " isDrained=" + entry.isDrained)
    }
}
