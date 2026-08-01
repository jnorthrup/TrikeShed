```kotlin
import borg.trikeshed.jules.JulesCause
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import borg.trikeshed.userspace.nio.file.spi.JvmAppendWal
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
```