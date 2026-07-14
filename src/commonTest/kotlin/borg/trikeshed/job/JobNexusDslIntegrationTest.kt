package borg.trikeshed.job

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E1 — DSL integration RED test.
 *
 * The DSL produces a spec, the factory opens it, and commands flow through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobNexusDslIntegrationTest {

    @Test
    fun dslSpecOpensAndAcceptsCommands() = runTest {
        val spec = jobNexusSpec {
            channels {
                commands(64)
                committed(64)
                facts(128)
                activations(64)
                telemetry(32)
            }
            storage {
                backend(StorageBackend.Memory)
                durability(Durability.None)
            }
            supervision {
                drainTimeoutMs(5_000)
            }
            rete {
                cycleBudget(1_000)
            }
        }

        val nexus = JobNexusFactory.open(spec, JobNexusBindings(parentScope = this))

        nexus.submit(JobCommand.Submit("j-1", idempotencyKey = "k1"))
        advanceUntilIdle()

        assertEquals(1, nexus.committedSequence)
        assertEquals("submitted", nexus.snapshot("j-1")?.lifecycle)
    }
}
