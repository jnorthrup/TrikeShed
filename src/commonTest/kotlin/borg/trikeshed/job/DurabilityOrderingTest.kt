package borg.trikeshed.job

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C04 RED — Durable-before-visible step ordering.
 *
 * The plan: a state event becomes visible only after:
 *   1. schema + expected-revision validation
 *   2. canonical CBOR encoding
 *   3. CAS blob write + digest verification
 *   4. WAL append with frame CRC32C
 *   5. durability barrier (fsync)
 *   6. reducer application + index update
 *
 * If any step fails, no live projection advances.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DurabilityOrderingTest {

    @Test
    fun schemaValidationFailurePreventsCasWrite() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 64)
        val instrumentation = nexus.instrumentation

        // Invalid operation must fail at step 1 (schema validation)
        nexus.submitRaw("""{"operation":"frobnicate","jobId":"j-1"}""".encodeToByteArray())
        advanceUntilIdle()

        assertEquals(0, instrumentation.casWriteCount,
            "CAS must not be written when schema validation fails")
        assertEquals(0, instrumentation.walAppendCount,
            "WAL must not be appended when schema validation fails")
        assertEquals(0, nexus.committedSequence,
            "sequence must not advance when schema validation fails")
    }

    @Test
    fun casWriteFailurePreventsWalAppend() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 64)
        nexus.injectCasFailure() // step 3 fails

        nexus.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))
        advanceUntilIdle()

        assertEquals(1, nexus.instrumentation.casWriteAttempts,
            "CAS write must be attempted")
        assertEquals(0, nexus.instrumentation.walAppendCount,
            "WAL must not be appended when CAS write fails")
        assertEquals(0, nexus.committedSequence,
            "sequence must not advance when CAS write fails")
    }

    @Test
    fun walAppendFailurePreventsReducerAndProjection() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 64)
        nexus.injectWalFailure() // step 4 fails

        nexus.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))
        advanceUntilIdle()

        assertEquals(1, nexus.instrumentation.casWriteCount,
            "CAS must succeed before WAL")
        assertEquals(1, nexus.instrumentation.walAppendAttempts,
            "WAL append must be attempted")
        assertEquals(0, nexus.instrumentation.reducerApplyCount,
            "reducer must not be applied when WAL fails")
        assertEquals(0, nexus.committedSequence,
            "sequence must not advance when WAL fails")
    }

    @Test
    fun successfulPathIncrementsAllStepsInOrder() = runTest {
        val nexus = JobSupervisorElement.open(scope = this, capacity = 64)
        val instrumentation = nexus.instrumentation

        nexus.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))
        advanceUntilIdle()

        assertEquals(1, instrumentation.schemaValidationCount)
        assertEquals(1, instrumentation.casWriteCount)
        assertEquals(1, instrumentation.walAppendCount)
        assertEquals(1, instrumentation.durabilityBarrierCount)
        assertEquals(1, instrumentation.reducerApplyCount)
        assertEquals(1, nexus.committedSequence)

        // Ordering proof: CAS attempt must happen before WAL attempt
        assertTrue(
            instrumentation.casWriteSequence < instrumentation.walAppendSequence,
            "CAS write must happen before WAL append"
        )
        assertTrue(
            instrumentation.walAppendSequence < instrumentation.durabilityBarrierSequence,
            "WAL append must happen before durability barrier"
        )
        assertTrue(
            instrumentation.durabilityBarrierSequence < instrumentation.reducerApplySequence,
            "durability barrier must happen before reducer application"
        )
    }
}
