package borg.trikeshed.server

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

class OpenApiGeneratorTddTest {
    @Test
    fun server_generator_emits_keys_elements_and_supervisor_placeholders() {
        val projectDir = File(".").canonicalFile
        val generatedRoot = File(projectDir, "libs/server/src/generated/kotlin")
        val pkgPath = "borg/trikeshed/server/generated"
        val keys = File(generatedRoot, "$pkgPath/Keys.kt")
        val elements = File(generatedRoot, "$pkgPath/Elements.kt")
        val supervisor = File(generatedRoot, "$pkgPath/SupervisorJobs.kt")

        assertTrue(keys.exists(), "Expected generated Keys.kt in server generated sources (TODO)")
        assertTrue(elements.exists(), "Expected generated Elements.kt in server generated sources (TODO)")
        assertTrue(supervisor.exists(), "Expected generated SupervisorJobs.kt in server generated sources (TODO)")
    }
}
