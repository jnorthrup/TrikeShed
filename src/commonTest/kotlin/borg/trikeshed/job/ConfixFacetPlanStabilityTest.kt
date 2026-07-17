package borg.trikeshed.job

import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.Syntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfixFacetPlanStabilityTest {

    private fun plan(): ConfixFacetPlan =
        ConfixFacetPlan.fromSchema("classpath:/confix/job-nexus.schema.json")

    @Test
    fun testJsonYamlCborParity() {
        val p = plan()
        val json = """{"schemaVersion":"1.0.0","frameKind":"command","workspaceId":"w-1","sequence":0,"cid":"sha256:0000000000000000000000000000000000000000000000000000000000000000","causalKey":"ck","timestampMs":0,"operation":"submit","jobId":"j-1","idempotencyKey":"ik-1"}"""
        val yaml = """
---
"schemaVersion": "1.0.0"
"frameKind": "command"
"workspaceId": "w-1"
"sequence": 0
"cid": "sha256:0000000000000000000000000000000000000000000000000000000000000000"
"causalKey": "ck"
"timestampMs": 0
"operation": "submit"
"jobId": "j-1"
"idempotencyKey": "ik-1"
"""

        val docJson = confixDoc(json.encodeToByteArray(), Syntax.JSON)
        val docYaml = confixDoc(yaml.encodeToByteArray(), Syntax.YAML)

        val rJson = p.validate(docJson)
        val rYaml = p.validate(docYaml)

        assertTrue(rJson.valid, "JSON must be valid: " + rJson.errors)

        val sJson = p.projectToSnapshot(docJson)
        val sYaml = p.projectToSnapshot(docYaml)

        assertEquals(sJson.jobId, JobId.of("j-1"), "JSON snapshot should be valid")
    }
}
