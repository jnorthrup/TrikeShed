package borg.trikeshed.server

import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

class OpenApiGeneratorTddTest {
    @Test
    fun server_generator_emits_keys_elements_and_supervisor_placeholders() {
        val start = File(".").canonicalFile
        fun findInAncestors(startDir: File, relative: String, depth: Int = 8): File? {
            var cur: File? = startDir
            for (i in 0..depth) {
                if (cur == null) break
                val candidate = File(cur, relative)
                if (candidate.exists()) return candidate
                cur = cur.parentFile
            }
            return null
        }

        val pkgPath = "borg/trikeshed/server/generated"
        val keysRelative = "libs/server/src/generated/kotlin/$pkgPath/Keys.kt"
        val elementsRelative = "libs/server/src/generated/kotlin/$pkgPath/Elements.kt"
        val supervisorRelative = "libs/server/src/generated/kotlin/$pkgPath/SupervisorJobs.kt"

        val keys = findInAncestors(start, keysRelative) ?: findInAncestors(start, "src/generated/kotlin/$pkgPath/Keys.kt") ?: File(start, keysRelative)
        val elements = findInAncestors(start, elementsRelative) ?: findInAncestors(start, "src/generated/kotlin/$pkgPath/Elements.kt") ?: File(start, elementsRelative)
        val supervisor = findInAncestors(start, supervisorRelative) ?: findInAncestors(start, "src/generated/kotlin/$pkgPath/SupervisorJobs.kt") ?: File(start, supervisorRelative)

        assertTrue(keys.exists(), "Expected generated Keys.kt in server generated sources (TODO)")
        assertTrue(elements.exists(), "Expected generated Elements.kt in server generated sources (TODO)")
        assertTrue(supervisor.exists(), "Expected generated SupervisorJobs.kt in server generated sources (TODO)")
    }
}
