package borg.trikeshed.job

import borg.trikeshed.parse.confix.ConfixDoc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * C01 RED — ConfixFacetPlan compiled FROM schema, not hand-maintained.
 *
 * The plan: "Runtime facet plans are compiled from that schema. No parallel
 * hand-maintained DTO field list may become authoritative."
 *
 * Proves: the schema resource compiles into a typed facet plan that
 * validates, projects, and rejects unknown operations.
 */
class ConfixFacetPlanCompilationTest {

    @Test
    fun schemaCompilesToTypedFacetPlan() {
        val plan = ConfixFacetPlan.fromSchema("classpath:/confix/job-nexus.schema.json")

        assertNotNull(plan)
        assertTrue(plan.commandOperations.containsAll(listOf(
            "submit", "start", "progress", "block", "complete", "fail",
            "cancel", "retry", "move", "acknowledge",
        )), "facet plan must enumerate all command operations from schema")

        assertTrue(plan.eventOperations.containsAll(listOf("accepted", "rejected")))
    }

    @Test
    fun facetPlanValidatesValidCommand() {
        val plan = ConfixFacetPlan.fromSchema("classpath:/confix/job-nexus.schema.json")
        val doc = ConfixDoc.parse(
            """{"operation":"submit","jobId":"j-1","idempotencyKey":"k1"}""".encodeToByteArray()
        )
        val result = plan.validate(doc)
        assertTrue(result.valid, "valid command must pass validation")
    }

    @Test
    fun facetPlanRejectsUnknownOperation() {
        val plan = ConfixFacetPlan.fromSchema("classpath:/confix/job-nexus.schema.json")
        val doc = ConfixDoc.parse(
            """{"operation":"frobnicate","jobId":"j-1"}""".encodeToByteArray()
        )
        val result = plan.validate(doc)
        assertTrue(!result.valid, "unknown operation must be rejected")
        assertTrue(result.errors.any { it.contains("operation") },
            "error must mention 'operation'")
    }

    @Test
    fun facetPlanRejectsMissingRequiredField() {
        val plan = ConfixFacetPlan.fromSchema("classpath:/confix/job-nexus.schema.json")
        val doc = ConfixDoc.parse(
            """{"operation":"submit"}""".encodeToByteArray()
        )
        val result = plan.validate(doc)
        assertTrue(!result.valid, "missing jobId must be rejected")
        assertTrue(result.errors.any { it.contains("jobId") },
            "error must mention the missing field")
    }

    @Test
    fun facetPlanProjectToSnapshotRoundTrips() {
        val plan = ConfixFacetPlan.fromSchema("classpath:/confix/job-nexus.schema.json")
        val doc = ConfixDoc.parse(
            """{"operation":"submit","jobId":"j-1","idempotencyKey":"k1","causalKey":"ck-1","dependencies":["dep-1"]}""".encodeToByteArray()
        )
        val snap = plan.projectToSnapshot(doc)
        assertEquals("j-1", snap.jobId)
        assertEquals("submitted", snap.lifecycle)
        assertEquals("ck-1", snap.causalKey)
        assertEquals(listOf("dep-1"), snap.dependencies)
    }

    @Test
    fun noParallelHandMaintainedFieldList() {
        // The plan must be compiled from the schema, not a Kotlin enum/data class
        // that duplicates the field list. Prove this by checking that the
        // command operations match what's in the JSON schema, not a hardcoded list.
        val plan = ConfixFacetPlan.fromSchema("classpath:/confix/job-nexus.schema.json")
        val schemaText = Thread.currentThread().contextClassLoader!!
            .getResource("confix/job-nexus.schema.json")!!.readText()

        // Every operation the plan knows must come from the schema text.
        for (op in plan.commandOperations) {
            assertTrue(schemaText.contains("\"$op\""),
                "operation '$op' must exist in the schema text, not a hardcoded list")
        }
    }
}
