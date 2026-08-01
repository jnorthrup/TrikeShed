import borg.trikeshed.job.ContentId
import borg.trikeshed.util.oroboros.MergeReceipt
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.jules.JulesCause
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import java.io.File
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val forgeDir = File(System.getProperty("user.home") + "/.local/forge")
    val store = JulesBoardStore.forForgeDir(forgeDir)
    store.appendWork("3803389897151472172#2#2", JulesCause.WorkDrained(
        workId = "3803389897151472172#2#2",
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        receipt = MergeReceipt(
            workId = "3803389897151472172#2#2",
            producer = "jules",
            producerRef = "necromanced",
            patchCid = ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"),
            revision = "superseded-by-review",
            versionTag = "supersede-pass",
            lexicalMemory = LexicalMemory("superseded", "superseded", "superseded"),
            claimedAt = System.currentTimeMillis(),
            prUrl = null
        ),
        at = System.currentTimeMillis()
    ))
    println(forgeDir.absolutePath)
}
