package borg.trikeshed.job

import borg.trikeshed.parse.confix.*
import kotlin.test.*

class JobEventIdentityTest {
    @Test fun acceptedAndEveryReducerRefusalCarryTheOriginalRequestIdentity() {
        val reducer = JobReducer()
        val id = JobId.of("identity")
        val accepted = assertIs<JobEvent.Accepted>(reducer.reduce(JobCommand.Submit(id, "submit-key")).event)
        assertEquals(id, accepted.jobId)
        assertEquals(1L, accepted.sequence)
        assertEquals("submit-key", accepted.idempotencyKey)
        val commands = listOf(
            JobCommand.Submit(id, "submit-key"),
            JobCommand.Start(id, "stale-key", 0L),
            JobCommand.Update(JobId.of("unknown"), "unknown-key", 0L),
            JobCommand.State(id, "state-key", 1L, null, ContentId.of(byteArrayOf(1)).value),
        )
        for (command in commands) {
            val event = assertIs<JobEvent.Rejected>(reducer.reduce(command).event)
            assertEquals(command.jobId, event.jobId)
            assertEquals(0L, event.sequence)
            assertEquals(command.idempotencyKey, event.idempotencyKey)
            assertTrue(event.reason.isNotBlank())
        }
        val frame = JobFrame(confixDoc("""{"operation":"update","jobId":"identity","idempotencyKey":"missing-revision"}""".encodeToByteArray(), Syntax.JSON))
        val missing = assertIs<JobEvent.Rejected>(reducer.reduce(frame).event)
        assertEquals("missing-revision", missing.idempotencyKey)
        assertContains(missing.reason, "without expectedRevision")
        assertEquals(1L, reducer.snapshot(id)?.revision)
    }
}
