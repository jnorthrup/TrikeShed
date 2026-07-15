package borg.trikeshed.job

import borg.trikeshed.parse.confix.confixDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F0 — ConfixFacetPlan contract tests.
 *
 * Spec (§F0 RED):
 *  - Schema compiles to a facet plan with the canonical set of operations
 *  - Validation rejects missing jobId / idempotencyKey
 *  - Validation rejects unknown operations
 *  - Validation accepts well-formed command docs
 *  - projectToSnapshot derives lifecycle from operation
 */
class ConfixFacetPlanTest {

    private fun plan(): ConfixFacetPlan =
        ConfixFacetPlan.fromSchema("classpath:/confix/job-nexus.schema.json")

    @Test
    fun planCompilesCanonicalOperationSet() {
        val p = plan()
        // Canonical command operations from the schema.
        val expected = setOf(
            "submit", "start", "progress", "block", "complete", "fail",
            "cancel", "retry", "move", "acknowledge", "retract",
        )
        // Plan must contain every canonical operation (it may also contain extras).
        for (op in expected) {
            assertTrue(op in p.commandOperations,
                "plan must include operation '$op', got ${p.commandOperations}")
        }
    }

    @Test
    fun planCarriesSchemaText() {
        val p = plan()
        assertTrue(p.schemaText.isNotEmpty(),
            "plan must carry the loaded schema text")
    }

    @Test
    fun validationAcceptsWellFormedSubmit() {
        val p = plan()
        val doc = confixDoc("""{"operation":"submit","jobId":"j-1","idempotencyKey":"ik-1"}""")
        val r = p.validate(doc)
        assertTrue(r.valid, "submit with jobId+idempotencyKey must validate: ${r.errors}")
        assertEquals(emptyList(), r.errors)
    }

    @Test
    fun validationRejectsMissingJobId() {
        val p = plan()
        val doc = confixDoc("""{"operation":"submit","idempotencyKey":"ik-1"}""")
        val r = p.validate(doc)
        assertFalse(r.valid, "doc without jobId must be invalid")
        assertTrue(r.errors.any { it.contains("jobId") },
            "errors must mention jobId: ${r.errors}")
    }

    @Test
    fun validationRejectsMissingIdempotencyKey() {
        val p = plan()
        val doc = confixDoc("""{"operation":"submit","jobId":"j-1"}""")
        val r = p.validate(doc)
        assertFalse(r.valid)
        assertTrue(r.errors.any { it.contains("idempotencyKey") },
            "errors must mention idempotencyKey: ${r.errors}")
    }

    @Test
    fun validationRejectsUnknownOperation() {
        val p = plan()
        val doc = confixDoc("""{"operation":"hack-the-planet","jobId":"j-1","idempotencyKey":"ik"}""")
        val r = p.validate(doc)
        assertFalse(r.valid)
        assertTrue(r.errors.any { it.contains("operation") },
            "errors must mention operation: ${r.errors}")
    }

    @Test
    fun validationRejectsMissingOperation() {
        val p = plan()
        val doc = confixDoc("""{"jobId":"j-1","idempotencyKey":"ik"}""")
        val r = p.validate(doc)
        assertFalse(r.valid)
        assertTrue(r.errors.any { it.contains("operation") },
            "errors must mention operation: ${r.errors}")
    }

    @Test
    fun validationAcceptsStartCommand() {
        val p = plan()
        val doc = confixDoc("""{"operation":"start","jobId":"j-1","idempotencyKey":"ik","expectedRevision":1}""")
        val r = p.validate(doc)
        assertTrue(r.valid, "start command must validate: ${r.errors}")
    }

    @Test
    fun projectToSnapshotDerivesLifecycleFromOperation() {
        val p = plan()
        val cases = mapOf(
            "submit" to "submitted",
            "start" to "active",
            "complete" to "closed",
            "fail" to "failed",
            "retry" to "submitted",
        )
        for ((op, lifecycle) in cases) {
            val doc = confixDoc("""{"operation":"$op","jobId":"j-1","idempotencyKey":"ik"}""")
            val snap = p.projectToSnapshot(doc)
            assertEquals(lifecycle, snap.lifecycle,
                "operation '$op' must project to lifecycle '$lifecycle'")
            assertEquals(JobId.of("j-1"), snap.jobId)
            assertEquals(1L, snap.revision)
        }
    }

    @Test
    fun projectToSnapshotPreservesCausalKey() {
        val p = plan()
        val doc = confixDoc("""{"operation":"submit","jobId":"j-1","idempotencyKey":"ik","causalKey":"ck-42"}""")
        val snap = p.projectToSnapshot(doc)
        assertEquals("ck-42", snap.causalKey)
    }
}