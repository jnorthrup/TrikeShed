package borg.trikeshed.pointcut

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/**
 * If no clang on this machine, test is ignored.
 * Fixture generated with: clang -O0 -emit-llvm -c test.c -o test.bc
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubgraalLlvmPointcutTest {

    @Test
    fun testLlvmBitcode() = runTest(UnconfinedTestDispatcher()) {
        try {
            Class.forName("org.graalvm.polyglot.Engine")
        } catch (e: ClassNotFoundException) {
            assumeTrue(false, "GraalVM not resolvable on classpath")
        }
        
        val testBc = File("src/jvmTest/resources/test.bc")
        
        if (!testBc.exists()) {
            System.err.println("REPORT: Fixture test.bc not found. Run: clang -O0 -emit-llvm -c test.c -o test.bc")
            assumeTrue(false, "Missing fixture test.bc")
        }

        val runner = SubgraalPointcutRunner()
        val events = mutableListOf<PointcutEvent>()

        val job = launch {
            runner.events.collect { events.add(it) }
        }

        runner.use { r ->
            val value = r.evalFile("llvm", testBc)
            // Sulong requires us to execute the main function to trigger events properly
            try {
                if (value.hasMember("main")) {
                    value.getMember("main").execute()
                }
            } catch (e: Exception) {
               // ignore
            }
        }

        job.cancel()

        assertTrue(events.any { it.vmFacet == VmFacet.GRAAL_LLVM }, "Should yield >=1 coordinate with facet GRAAL_LLVM")
    }
}
