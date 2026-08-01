package forge.doc

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
