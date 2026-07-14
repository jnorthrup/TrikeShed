package borg.trikeshed.job

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * E1 RED — Factory failure injection at every assembly stage.
 *
 * The plan: "A failure injected at each assembly stage closes every previously
 * opened component exactly once in reverse order and leaks no
 * coroutine/channel/file handle."
 *
 * Assembly order: scope → CAS → WAL → index → Rete → projection → checkpoint
 * Each test injects failure at one stage and proves reverse-close of prior stages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FactoryRollbackAtEveryStageTest {

    @Test
    fun failureAtWalClosesScopeAndCas() = runTest {
        val bindings = JobNexusBindings(
            parentScope = this,
            componentFactories = JobNexusComponentFactories(
                walFactory = { FailingJobLogFactory(stage = "wal") },
            ),
        )
        val spec = minimalSpec()

        try {
            JobNexusFactory.open(spec, bindings)
            fail("must fail")
        } catch (e: Exception) {
            assertReverseCloseTrace(bindings, expectedOpened = listOf("scope", "cas"))
        }
    }

    @Test
    fun failureAtIndexClosesScopeCasWal() = runTest {
        val bindings = JobNexusBindings(
            parentScope = this,
            componentFactories = JobNexusComponentFactories(
                indexFactory = { FailingJobIndexFactory(stage = "index") },
            ),
        )
        val spec = minimalSpec()

        try {
            JobNexusFactory.open(spec, bindings)
            fail("must fail")
        } catch (e: Exception) {
            assertReverseCloseTrace(bindings, expectedOpened = listOf("scope", "cas", "wal"))
        }
    }

    @Test
    fun failureAtReteClosesScopeCasWalIndex() = runTest {
        val bindings = JobNexusBindings(
            parentScope = this,
            componentFactories = JobNexusComponentFactories(
                reteFactory = { FailingReteNetworkFactory(stage = "rete") },
            ),
        )
        val spec = minimalSpec()

        try {
            JobNexusFactory.open(spec, bindings)
            fail("must fail")
        } catch (e: Exception) {
            assertReverseCloseTrace(bindings, expectedOpened = listOf("scope", "cas", "wal", "index"))
        }
    }

    @Test
    fun failureAtProjectionClosesScopeCasWalIndexRete() = runTest {
        val bindings = JobNexusBindings(
            parentScope = this,
            componentFactories = JobNexusComponentFactories(
                projectionFactory = { FailingProjectionFactory(stage = "projection") },
            ),
        )
        val spec = minimalSpec()

        try {
            JobNexusFactory.open(spec, bindings)
            fail("must fail")
        } catch (e: Exception) {
            assertReverseCloseTrace(bindings, expectedOpened = listOf("scope", "cas", "wal", "index", "rete"))
        }
    }

    @Test
    fun failureAtCheckpointClosesEverythingPrior() = runTest {
        val bindings = JobNexusBindings(
            parentScope = this,
            componentFactories = JobNexusComponentFactories(
                checkpointFactory = { FailingCheckpointFactory(stage = "checkpoint") },
            ),
        )
        val spec = minimalSpec()

        try {
            JobNexusFactory.open(spec, bindings)
            fail("must fail")
        } catch (e: Exception) {
            assertReverseCloseTrace(bindings, expectedOpened = listOf("scope", "cas", "wal", "index", "rete", "projection"))
        }
    }

    private fun minimalSpec(): JobNexusSpec = JobNexusSpec.builder()
        .channels { commands(64); committed(64); facts(128); activations(64); telemetry(32) }
        .storage { backend(StorageBackend.Memory); durability(Durability.None) }
        .build()

    private fun assertReverseCloseTrace(bindings: JobNexusBindings, expectedOpened: List<String>) {
        val trace = bindings.closeTrace
        assertTrue(trace.isNotEmpty(), "close trace must not be empty on failure")
        val opened = trace.map { it.component }
        assertEquals(expectedOpened, opened, "opened components must match expected order")

        val closedOrder = trace.filter { it.closed }.map { it.component }
        assertEquals(expectedOpened.reversed(), closedOrder,
            "components must be closed in reverse open order")
    }

    private fun assertEquals(a: Any?, b: Any?) {
        kotlin.test.assertEquals(a, b)
    }

    private fun fail(msg: String): Nothing {
        throw AssertionError(msg)
    }
}
