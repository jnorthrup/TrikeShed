package borg.trikeshed.pointcut

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SubgraalPointcutLocationTest {

    @Test
    fun testStatementsAndRoots() = runTest(UnconfinedTestDispatcher()) {
        try {
            Class.forName("org.graalvm.polyglot.Engine")
        } catch (e: ClassNotFoundException) {
            assumeTrue(false, "GraalVM not resolvable on classpath")
        }

        val runner = SubgraalPointcutRunner()
        val events = mutableListOf<PointcutEvent>()

        val job = launch {
            runner.events.collect { events.add(it) }
        }

        runner.use { r ->
            r.eval("python", """
                def foo():
                    x = 1
                    y = 2
                    return x + y
                foo()
            """.trimIndent())
        }

        job.cancel()

        assertTrue(events.size >= 2, "Should have at least 2 events")
        val lines = events.map { it.line }.filter { it != -1 }.distinct()
        assertTrue(lines.size >= 2, "Events should be on distinct lines")

        val rootEvents = events.filter { it.isRoot }
        assertTrue(rootEvents.isNotEmpty(), "Should have root events")
        rootEvents.forEach {
            assertTrue(it.isRoot, "Root event must have isRoot=true")
        }
    }
}
