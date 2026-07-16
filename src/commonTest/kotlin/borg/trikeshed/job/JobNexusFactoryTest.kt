package borg.trikeshed.job

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E1 — JobNexusFactory.open contract tests.
 *
 * Spec (§E1 / §C11 / §C14):
 *  - open is the only effectful assembly boundary; spec validation runs once
 *  - Missing parent scope ⇒ typed error
 *  - File backend without fileOps ⇒ typed error
 *  - Invalid spec (zero channel capacities) ⇒ validation error
 *  - Component-factory failure rolls back already-opened components (close trace)
 *  - On rollback, throw order matches component-failure stage
 *  - Successful open produces an ACTIVE supervisor and a populated close trace
 */
class JobNexusFactoryTest {

    private fun scope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)

    @Test
    fun openRequiresParentScope() {
        val spec = JobNexusSpec()
        val bindings = JobNexusBindings(parentScope = null)
        val ex = assertFailsWith<IllegalArgumentException> {
            JobNexusFactory.open(spec, bindings)
        }
        assertTrue(ex.message?.contains("parent scope") == true,
            "error must name missing parent scope: ${ex.message}")
    }

    @Test
    fun openRejectsFileBackendWithoutFileCapability() {
        val spec = JobNexusSpec(storage = StorageSpec(backend = StorageBackend.File))
        val bindings = JobNexusBindings(parentScope = scope(), fileOps = null)
        val ex = assertFailsWith<IllegalArgumentException> {
            JobNexusFactory.open(spec, bindings)
        }
        assertTrue(ex.message?.contains("file capability") == true,
            "error must mention file capability requirement: ${ex.message}")
    }

    @Test
    fun openRejectsLinuxBtrfsWhenUnavailable() {
        val spec = JobNexusSpec(storage = StorageSpec(backend = StorageBackend.LinuxBtrfs))
        val bindings = JobNexusBindings(parentScope = scope(), linuxStorageAvailable = false)
        val ex = assertFailsWith<IllegalArgumentException> {
            JobNexusFactory.open(spec, bindings)
        }
        assertTrue(ex.message?.contains("linux") == true || ex.message?.contains("btrfs") == true,
            "error must mention linux/btrfs capability: ${ex.message}")
    }

    @Test
    fun openRejectsInvalidChannelCapacities() {
        val spec = JobNexusSpec(channels = ChannelSpec(commands = 0, committed = 0, facts = 0))
        val bindings = JobNexusBindings(parentScope = scope())
        val ex = assertFailsWith<IllegalArgumentException> {
            JobNexusFactory.open(spec, bindings)
        }
        assertTrue(ex.message?.contains("channels") == true,
            "error must mention channel validation: ${ex.message}")
    }

    @Test
    fun rollbackOnCasFailureClosesTrace() {
        val spec = JobNexusSpec()
        val bindings = JobNexusBindings(
            parentScope = scope(),
            componentFactories = JobNexusComponentFactories(
                casStoreFactory = FailingCasStoreFactory(),
            ),
        )
        assertFailsWith<RuntimeException> {
            JobNexusFactory.open(spec, bindings)
        }
        // scope was opened, then CAS attempted and failed — trace must include scope closed
        val scopeEntry = bindings.closeTrace.firstOrNull { it.component == "scope" }
        assertNotNull(scopeEntry, "scope must appear in close trace")
        assertTrue(scopeEntry.closed, "scope must be marked closed after CAS-stage rollback")
    }

    @Test
    fun rollbackOnCheckpointFailureMarksEarlierComponentsClosed() {
        val spec = JobNexusSpec()
        val bindings = JobNexusBindings(
            parentScope = scope(),
            componentFactories = JobNexusComponentFactories(
                checkpointFactory = FailingCheckpointFactory(),
            ),
        )
        assertFailsWith<RuntimeException> {
            JobNexusFactory.open(spec, bindings)
        }
        // Components opened *before* the failure stage must appear in the trace
        // and must be marked closed. The failure stage itself (checkpoint) is
        // never opened, so it must NOT appear.
        for (comp in listOf("scope", "cas", "wal", "index", "rete", "projection")) {
            val entry = bindings.closeTrace.firstOrNull { it.component == comp }
            assertNotNull(entry, "$comp must appear in close trace after rollback")
            assertTrue(entry.closed, "$comp must be closed after rollback")
        }
        assertTrue(
            bindings.closeTrace.none { it.component == "checkpoint" },
            "checkpoint factory failed before it could register — must not appear in trace",
        )
    }

    @Test
    fun rollbackOmitsComponentsAfterFailureStage() {
        val spec = JobNexusSpec()
        val bindings = JobNexusBindings(
            parentScope = scope(),
            componentFactories = JobNexusComponentFactories(
                walFactory = FailingJobLogFactory(stage = "wal"),
            ),
        )
        assertFailsWith<RuntimeException> {
            JobNexusFactory.open(spec, bindings)
        }
        // scope + cas were attempted; wal threw; index/rete/projection/checkpoint must NOT appear
        val names = bindings.closeTrace.map { it.component }.toSet()
        assertTrue("scope" in names && "cas" in names, "earlier components must appear")
        assertTrue("index" !in names && "rete" !in names && "projection" !in names && "checkpoint" !in names,
            "components after the failure stage must not appear in close trace")
    }

    @Test
    fun successfulOpenPopulatesFullCloseTrace() {
        val spec = JobNexusSpec()
        val bindings = JobNexusBindings(parentScope = scope())
        val element = JobNexusFactory.open(spec, bindings)
        try {
            val names = bindings.closeTrace.map { it.component }
            assertEquals(
                listOf("scope", "cas", "wal", "index", "rete", "projection", "checkpoint"),
                names,
                "successful open must record every component in assembly order",
            )
            assertTrue(element.lifecycleState == ElementState.ACTIVE,
                "successful open must transition element to ACTIVE")
        } finally {
            element.cancel()
        }
    }
}