import borg.trikeshed.job.ContentId
import borg.trikeshed.util.oroboros.MergeReceipt
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.jules.JulesCause
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import java.io.File

fun main() {
    val forgeDir = File(System.getProperty("user.home") + "/.local/forge")
    val store = JulesBoardStore.forForgeDir(forgeDir)

    val suspendClass = Class.forName("kotlin.coroutines.Continuation")

    // We cannot easily run suspend functions from a simple script, but since we are just superseding a work ID,
    // let's create a Kotlin script and use `kotlinc -script`.
}
