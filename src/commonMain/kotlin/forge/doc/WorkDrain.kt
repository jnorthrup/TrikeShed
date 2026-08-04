package forge.doc

import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.jules.JulesCause

suspend fun drainSuperseded(store: JulesBoardStore, targetWorkId: String, commitSha: String = "superseded", taskId: String = "superseded") {
    store.appendWork(targetWorkId, JulesCause.WorkDrained(
        workId = targetWorkId,
        sessionId = "superseded",
        commitSha = commitSha,
        taskId = taskId,
        receipt = null,
        at = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
    ))
}

suspend fun drainReadmeDagReteRefraction(store: JulesBoardStore) {
    store.appendWork("readme-dag-rete-refraction", JulesCause.WorkDrained(
        workId = "readme-dag-rete-refraction",
        sessionId = "necromanced",
        commitSha = "superseded-by-review",
        taskId = "supersede-pass",
        at = 0L
    ))
}
