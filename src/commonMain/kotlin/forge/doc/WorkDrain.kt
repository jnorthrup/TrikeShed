package forge.doc

<<<<<<< ours
import borg.trikeshed.jules.JulesCause
import borg.trikeshed.utils.kanban.JulesBoardStore

suspend fun drainReadmeDagReteRefraction(store: JulesBoardStore) {
    store.appendWork("readme-dag-rete-refraction", JulesCause.WorkDrained(
        workId = "readme-dag-rete-refraction",
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        at = 0L
    ))
}

suspend fun drainMmapCasStoreRework(store: JulesBoardStore) {
    store.appendWork("rework:synth:965389205015589639#2", JulesCause.WorkDrained(
        workId = "rework:synth:965389205015589639#2",
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        at = 0L
    ))
    store.appendWork("synth:965389205015589639", JulesCause.WorkDrained(
        workId = "synth:965389205015589639",
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        at = 0L
    ))
=======
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.jules.JulesCause
import borg.trikeshed.util.oroboros.MergeReceipt
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.job.ContentId

suspend fun drainSupersededTask(store: JulesBoardStore) {
    val targetWorkId = "synth:17160760388671804749#2"
    store.appendWork(
        targetWorkId,
        JulesCause.WorkDrained(
            workId = targetWorkId,
            sessionId = "superseded",
            commitSha = "superseded",
            taskId = targetWorkId,
            receipt = MergeReceipt(
                workId = targetWorkId,
                producer = "jules",
                producerRef = "superseded",
                patchCid = ContentId("sha256:0000000000000000000000000000000000000000000000000000000000000000"),
                revision = "superseded",
                versionTag = "superseded",
                lexicalMemory = LexicalMemory(
                    summary = "superseded",
                    title = "superseded",
                    content = "superseded"
                ),
                claimedAt = 0L
            ),
            at = 0L
        )
    )
>>>>>>> theirs
}
