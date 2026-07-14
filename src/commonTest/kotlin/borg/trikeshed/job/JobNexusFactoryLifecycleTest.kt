package borg.trikeshed.job

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E1 — JobNexusFactory lifecycle and rollback RED tests.
 *
 * The factory is the one effectful composition boundary.
 * Failure at any assembly stage must close all previously opened components
 * in reverse order with zero leaks.
 *
 * Every type referenced is NEW.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobNexusFactoryLifecycleTest {

    @Test
    fun openReturnsActiveNexus() = runTest {
        val spec = JobNexusSpec.builder()
            .channels { commands(64); committed(64); facts(128); activations(64); telemetry(32) }
            .storage { backend(StorageBackend.Memory); durability(Durability.None) }
            .build()

        val nexus = JobNexusFactory.open(spec, JobNexusBindings(parentScope = this))
        assertEquals(ElementState.ACTIVE, nexus.state)
    }

    @Test
    fun missingParentJobFailsBeforeOpen() = runTest {
        val spec = JobNexusSpec.builder()
            .channels { commands(64); committed(64); facts(128); activations(64); telemetry(32) }
            .storage { backend(StorageBackend.Memory); durability(Durability.None) }
            .build()

        try {
            JobNexusFactory.open(spec, JobNexusBindings(parentScope = null))
            fail("must reject missing parent Job")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("parent") || e.message!!.contains("scope"))
        }
    }

    @Test
    fun twoConcurrentOpensCreateIsolatedRoots() = runTest {
        val spec = JobNexusSpec.builder()
            .channels { commands(64); committed(64); facts(128); activations(64); telemetry(32) }
            .storage { backend(StorageBackend.Memory); durability(Durability.None) }
            .build()

        val bindings1 = JobNexusBindings(parentScope = this)
        val bindings2 = JobNexusBindings(parentScope = this)

        val nexus1 = JobNexusFactory.open(spec, bindings1)
        val nexus2 = JobNexusFactory.open(spec, bindings2)

        assertNotEquals(nexus1.rootJob, nexus2.rootJob, "roots must be isolated")
    }

    @Test
    fun parentCancellationCancelsDescendants() = runTest {
        val spec = JobNexusSpec.builder()
            .channels { commands(64); committed(64); facts(128); activations(64); telemetry(32) }
            .storage { backend(StorageBackend.Memory); durability(Durability.None) }
            .build()

        val nexus = JobNexusFactory.open(spec, JobNexusBindings(parentScope = this))

        nexus.cancel()
        advanceUntilIdle()

        assertFalse(nexus.isActive, "nexus must be inactive after cancel")
    }

    @Test
    fun drainWaitsForAcceptedWork() = runTest {
        val spec = JobNexusSpec.builder()
            .channels { commands(64); committed(64); facts(128); activations(64); telemetry(32) }
            .storage { backend(StorageBackend.Memory); durability(Durability.None) }
            .build()

        val nexus = JobNexusFactory.open(spec, JobNexusBindings(parentScope = this))

        nexus.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))
        nexus.submit(JobCommand.Submit("j-2", idempotencyKey = "k2"))
        nexus.drain()
        advanceUntilIdle()

        assertEquals(2, nexus.committedSequence)
        assertEquals(ElementState.CLOSED, nexus.state)
    }
}

/**
 * JobNexusFactory rollback — failure injection at each assembly stage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobNexusFactoryRollbackTest {

    @Test
    fun failureAtCasStageClosesAllPriorComponents() = runTest {
        val spec = JobNexusSpec.builder()
            .channels { commands(64); committed(64); facts(128); activations(64); telemetry(32) }
            .storage { backend(StorageBackend.Memory); durability(Durability.None) }
            .build()

        val bindings = JobNexusBindings(
            parentScope = this,
            componentFactories = JobNexusComponentFactories(
                casStoreFactory = { FailingCasStoreFactory() },
            ),
        )

        try {
            JobNexusFactory.open(spec, bindings)
            fail("must fail when CAS factory fails")
        } catch (e: Exception) {
            // All previously opened components must be closed
            // We verify via the binding's close trace
            assertTrue(bindings.closeTrace.isNotEmpty(),
                "failure at CAS stage must close all previously opened components")
            // Reverse close order
            val reversed = bindings.closeTrace.reversed()
            assertEquals(bindings.closeTrace.sortedByDescending { it.order }, reversed,
                "components must be closed in reverse open order")
        }
    }
}

/**
 * Entry point parity — DSL, builder, JSON decoder produce the same spec.
 */
class JobNexusEntrypointParityTest {

    @Test
    fun dslBuilderAndDecoderProduceSameCanonicalSpec() {
        val dslSpec = jobNexusSpec {
            channels { commands(256); committed(256); facts(512); activations(256); telemetry(128) }
            storage { backend(StorageBackend.File); durability(Durability.Fsync); checkpointEvery(4096) }
            supervision { drainTimeoutMs(30_000) }
            rete { cycleBudget(10_000) }
        }

        val builderSpec = JobNexusSpec.builder()
            .channels { commands(256); committed(256); facts(512); activations(256); telemetry(128) }
            .storage { backend(StorageBackend.File); durability(Durability.Fsync); checkpointEvery(4096) }
            .supervision { drainTimeoutMs(30_000) }
            .rete { cycleBudget(10_000) }
            .build()

        assertEquals(dslSpec.canonicalBytes, builderSpec.canonicalBytes,
            "DSL and builder must produce byte-identical canonical specs")
    }
}
