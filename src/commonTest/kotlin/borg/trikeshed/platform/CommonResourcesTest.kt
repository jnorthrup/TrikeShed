package borg.trikeshed.platform

import borg.trikeshed.job.schema.loadConfixSchemaBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One door for common resources on every target: the baked bundle answers without a filesystem. */
class CommonResourcesTest {
    @Test
    fun bakedBundleAnswersOnEveryTarget() {
        assertTrue("confix/job-nexus.schema.json" in CommonResources.baked, "baked: ${CommonResources.baked}")
        val schema = assertNotNull(CommonResources.text("classpath:/confix/job-nexus.schema.json"))
        assertTrue(schema.contains("Job Nexus Confix Schema"))
        assertEquals(schema, CommonResources.text("confix/job-nexus.schema.json"), "classpath:/ and bare keys are the same resource")
        assertTrue(assertNotNull(CommonResources.text("openapi/jules.openapi.yaml")).contains("openapi"))
        assertEquals(schema.encodeToByteArray().size, loadConfixSchemaBytes("classpath:/confix/job-nexus.schema.json").size)
    }

    @Test
    fun missingResourceIsNullNotAnException() {
        assertNull(CommonResources.bytes("nope/missing.txt"))
        assertFailsWith<IllegalStateException> { loadConfixSchemaBytes("classpath:/nope/missing.json") }
    }

    @Test
    fun bareResourceSourceIsAChokepoint() {
        assertFailsWith<NotImplementedError> { ResourceSource.NONE.bytes("x") }
        assertTrue("resources.bytes" in Discontinued.features)
    }
}
