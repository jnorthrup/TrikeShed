package borg.trikeshed.lcnc

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasJsPureNodesTest {
    @Test
    fun pickUsesTheCanvasMethodUnderHostAccessNone(): Unit = runBlocking {
        val method = CanvasJsPureNodes.loadPickMethod()
        assertEquals(
            "async run(n,i){ let v=i.x; for(const k of p(n,\"path\").split(\".\").filter(Boolean)) v=v?.[k]; return {y:v}; },",
            method,
            "the daemon must execute the existing canvas method, not a Kotlin/JS rewrite",
        )

        val runner = CanvasJsPureNodes.pickRunner(method)
        val output = runner.run(
            LcncNode("pick-proof", "pick", params = mapOf("path" to "cards.1.title")),
            mapOf("x" to mapOf("cards" to listOf(mapOf("title" to "first"), mapOf("title" to "second")))),
        )
        assertEquals(mapOf("y" to "second"), output)

        CanvasJsPureNodes.newSandbox().use { context ->
            // GraalJS exposes the `Java` namespace name even with no host
            // capability; the security boundary is that resolving a class is
            // rejected. Assert the operation, not namespace cosmetics.
            val denied = runCatching { context.eval("js", "Java.type('java.lang.System')") }.exceptionOrNull()
            assertTrue(denied != null, "HostAccess.NONE must reject Java.type")
        }
    }

    /**
     * Repeatable context-per-eval measurement. Run with:
     * ./gradlew jvmTest --tests borg.trikeshed.lcnc.CanvasJsPureNodesTest.contextPerEvalBenchmark --rerun-tasks --info
     */
    @Test
    fun contextPerEvalBenchmark(): Unit = runBlocking {
        val warmups = 5
        val samples = 30
        val runner = CanvasJsPureNodes.pickRunner()
        val node = LcncNode("pick-bench", "pick", params = mapOf("path" to "payload.items.2.value"))
        val inputs = mapOf(
            "x" to mapOf("payload" to mapOf("items" to listOf(
                mapOf("value" to 1), mapOf("value" to 2), mapOf("value" to 3),
            ))),
        )

        repeat(warmups) { assertEquals(mapOf("y" to 3.0), runner.run(node, inputs)) }
        val nanos = LongArray(samples)
        repeat(samples) { i ->
            val started = System.nanoTime()
            val output = runner.run(node, inputs)
            nanos[i] = System.nanoTime() - started
            assertEquals(mapOf("y" to 3.0), output)
        }
        nanos.sort()
        val total = nanos.sum()
        val mean = total / samples
        val median = nanos[samples / 2]
        val p95 = nanos[ceil(samples * 0.95).toInt() - 1]
        System.err.println(
            "CANVAS_JS_PICK_CONTEXT_PER_EVAL samples=$samples warmups=$warmups " +
                "meanNs=$mean medianNs=$median p95Ns=$p95 minNs=${nanos.first()} maxNs=${nanos.last()}",
        )
        assertTrue(mean > 0)
    }
}