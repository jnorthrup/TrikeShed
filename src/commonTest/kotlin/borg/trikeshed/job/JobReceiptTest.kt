package borg.trikeshed.job

import kotlin.test.*

class JobReceiptTest {
    @Test fun evidenceAcknowledgmentPreservesStateWithoutChangingOrdinaryAcknowledgment() {
        fun reducer(): JobReducer = JobReducer().apply {
            reduce(JobCommand.Submit(JobId("job"), "submit"))
            reduce(JobCommand.Start(JobId("job"), "start", 1))
        }
        val ordinary = reducer().reduce(JobCommand.Acknowledge(JobId("job"), "ack", 2))
        assertEquals("acknowledged", ordinary.snapshot?.lifecycle)
        val receiptCommand = JobCommand.Acknowledge(JobId("job"), "receipt", 2, receipt = true)
        assertEquals(receiptCommand, CanonicalCbor.decodeJobCommand(CanonicalCbor.encode(receiptCommand)))
        val evidence = reducer().reduce(receiptCommand)
        assertEquals("active", evidence.snapshot?.lifecycle)
        assertEquals(3L, evidence.snapshot?.revision)
    }
}
