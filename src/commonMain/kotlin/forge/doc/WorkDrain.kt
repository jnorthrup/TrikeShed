package forge.doc

import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.jules.JulesCause
import kotlinx.datetime.Clock

suspend fun JulesBoardStore.drainWork(workId: String, commitSha: String = "superseded", taskId: String = "superseded") {
    appendWork(
        workId = workId,
        cause = JulesCause.WorkDrained(
            workId = workId,
            sessionId = commitSha,
            commitSha = commitSha,
            taskId = taskId,
            receipt = null,
            at = Clock.System.now().toEpochMilliseconds()
        )
    )
}
