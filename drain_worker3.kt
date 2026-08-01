import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import borg.trikeshed.jules.JulesCause
import borg.trikeshed.util.oroboros.MergeReceipt
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.job.ContentId
import java.io.File
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val forgeDir = File(System.getProperty("user.home") + "/.local/forge")
    val store = JulesBoardStore.forForgeDir(forgeDir)
    store.appendWork("readme-forge-doc-block-tree", JulesCause.WorkDrained(
        workId = "readme-forge-doc-block-tree",
        sessionId = System.getenv("JULES_SESSION_ID") ?: "15529708606522008753",
        commitSha = "outbox:superseded",
        taskId = "retired:superseded-by-landed-commit",
        receipt = MergeReceipt(
            workId = "readme-forge-doc-block-tree",
            producer = "jules",
            producerRef = System.getenv("JULES_SESSION_ID") ?: "15529708606522008753",
            patchCid = ContentId.of(ByteArray(32) { 0 }),
            revision = "HEAD",
            versionTag = "v1",
            lexicalMemory = LexicalMemory("superseded", "superseded", "superseded"),
            claimedAt = System.currentTimeMillis()
        ),
        at = System.currentTimeMillis()
    ))
    println("WorkDrained appended")
}
