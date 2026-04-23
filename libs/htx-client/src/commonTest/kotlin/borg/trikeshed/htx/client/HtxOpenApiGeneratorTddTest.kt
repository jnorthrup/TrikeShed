package borg.trikeshed.htx.client

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

class HtxOpenApiGeneratorTddTest {
    @Test
    fun generator_emits_keys_elements_and_supervisor_placeholders() {
        val projectDir = File(".").canonicalFile
        val generatedRoot = File(projectDir, "libs/htx-client/src/generated/kotlin")
        val pkgPath = "borg/trikeshed/htx/client/generated"
        val keys = File(generatedRoot, "$pkgPath/Keys.kt")
        val elements = File(generatedRoot, "$pkgPath/Elements.kt")
        val supervisor = File(generatedRoot, "$pkgPath/SupervisorJobs.kt")

        assertTrue(keys.exists(), "Expected generated Keys.kt (TODO: generator should emit Keys)")
        assertTrue(elements.exists(), "Expected generated Elements.kt (TODO: generator should emit Elements)")
        assertTrue(supervisor.exists(), "Expected generated SupervisorJobs.kt (TODO: generator should emit SupervisorJobs)")
    }
}
