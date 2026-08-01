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
    store.appendWork("rework:synth:14349612850032810027#2", JulesCause.WorkDrained(
        workId = "rework:synth:14349612850032810027#2",
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        receipt = MergeReceipt(
            workId = "rework:synth:14349612850032810027#2",
            producer = "jules",
            producerRef = "necromanced",
            patchCid = ContentId.of(ByteArray(32) { 0 }),
            revision = "HEAD",
            versionTag = "v1",
            lexicalMemory = LexicalMemory("superseded", "superseded", "superseded"),
            claimedAt = System.currentTimeMillis()
        ),
        at = System.currentTimeMillis()
    ))
    println("WorkDrained appended for rework:synth:14349612850032810027#2")
}
