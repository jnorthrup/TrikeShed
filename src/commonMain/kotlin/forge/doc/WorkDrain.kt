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
}
