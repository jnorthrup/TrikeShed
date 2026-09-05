package borg.trikeshed.lcnc

import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LcncArgumentRuntimeTest {
    private fun program() = LcncProgram("construction", listOf(
        LcncNode("arguments", "scope.in", mapOf("name" to "construction")),
        LcncNode("instance", "ccek.incarnate", mapOf("title" to "parameter-title", "maxConcurrency" to "8")),
        LcncNode("result", "scope.out", mapOf("name" to "instance")),
    ).toSeries(), listOf(
        LcncWire("arguments", "value", "instance", "args?"),
        LcncWire("instance", "node", "result", "value"),
    ).toSeries())

    @Test
    fun invocationArgumentsReachRealFactoryAndReceiptValues() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val registry = CcekNodes.registry(CcekSeams.live(scope))
            val walker = LcncRunner(registry)
            val input = mapOf("title" to "argument-title", "maxConcurrency" to 3, "record" to true, "projections" to listOf("DOCUMENT"))
            val result = walker.runProcedure(program(), mapOf("construction" to input))
            val configuration = result.returns["instance"] as Map<*, *>
            assertEquals("argument-title", configuration["handle"])
            assertEquals(3, configuration["maxConcurrency"])
            assertEquals(listOf("DOCUMENT"), configuration["projections"])
            assertEquals("invocation", result.bindings.single()["source"])
            assertEquals(input, result.bindings.single()["value"])
            val arguments = result.nodeOutputs.getValue("instance")["arguments"] as List<*>
            val concurrency = arguments.map { it as Map<*, *> }.single { it["name"] == "maxConcurrency" }
            assertEquals("args", concurrency["source"])
            assertEquals(3, concurrency["value"])
            assertEquals("8", (concurrency["overridden"] as Map<*, *>)["value"])
            assertEquals(result.returns, walker.runProcedure(program(), mapOf("construction" to input)).returns)
            val failure = assertFailsWith<IllegalArgumentException> {
                walker.runProcedure(program(), mapOf("construction" to (input + ("maxConcurrency" to 4))))
            }
            assertTrue(failure.message.orEmpty().contains("incarnation_conflict"))
            assertEquals(result.returns, walker.runProcedure(program(), mapOf("construction" to input)).returns)
            assertEquals(true, registry.getValue("ccek.signal").run(
                LcncNode("signal", "ccek.signal", mapOf("text" to "real instance", "verb" to "append")),
                mapOf("handle" to "argument-title"),
            )["sent"])
        } finally { scope.cancel() }
    }

    @Test
    fun namedArgumentsOverrideEnvelopeAndInvalidSettingsDoNotCreateInstances() = runBlocking {
        val store = InMemoryCcekStore()
        val runner = CcekNodes.registry(CcekSeams.inMemory(store)).getValue("ccek.incarnate")
        val node = LcncNode("factory", "ccek.incarnate", mapOf("title" to "fallback", "maxConcurrency" to "8"))
        val result = runner.run(node, mapOf("args" to mapOf("title" to "test", "maxConcurrency" to 2), "maxConcurrency" to 5))
        assertEquals(5, (result["node"] as Map<*, *>)["maxConcurrency"])
        assertFailsWith<IllegalArgumentException> { runner.run(node, mapOf("title" to "test", "maxConcurrency" to 6)) }
        for (bad in listOf(0, -1, 257, 2.5, "garbage", null)) {
            assertFailsWith<IllegalArgumentException> { runner.run(node, mapOf("title" to "bad", "maxConcurrency" to bad)) }
        }
        assertFailsWith<IllegalArgumentException> { runner.run(node, mapOf("args" to listOf(1, 2))) }
        assertFailsWith<IllegalArgumentException> { runner.run(node, mapOf("args" to mapOf("typo" to 1))) }
        assertFailsWith<IllegalArgumentException> { runner.run(node, mapOf("projections" to listOf("TYPO"))) }
        assertEquals(setOf("test"), store.nodes.keys)
    }

    @Test
    fun coroutineKeyReplacementIsIdempotentAndRestoresEnclosingElement() = runBlocking {
        val outer = LcncScopeFrame(mapOf("text" to "outer"), chain = FrameIdChain.root("test"))
        val inner = LcncScopeFrame(mapOf("text" to null), chain = outer.chain, parent = outer)
        assertSame(inner, (outer + inner)[LcncScopeFrame])
        assertSame(inner, (outer + inner + inner)[LcncScopeFrame])
        withContext(outer) {
            assertFailsWith<IllegalStateException> {
                withContext(inner) {
                    assertSame(inner, currentCoroutineContext()[LcncScopeFrame])
                    assertTrue(inner.hasBinding("text"))
                    assertEquals(null, inner.binding("text"))
                    error("unwind")
                }
            }
            assertSame(outer, currentCoroutineContext()[LcncScopeFrame])
        }
    }

    @Test
    fun unwiredValueStaysAbsentButExplicitNullReachesReturn() = runBlocking {
        val p = LcncProgram("optional", listOf(
            LcncNode("in", "scope.in", mapOf("name" to "value?")),
            LcncNode("out", "scope.out", mapOf("name" to "result")),
        ).toSeries(), listOf(LcncWire("in", "value", "out", "value")).toSeries())
        val walker = LcncRunner(emptyMap())
        val absent = walker.runProcedure(p)
        assertTrue(absent.returns.isEmpty())
        assertEquals("unbound", absent.bindings.single()["status"])
        val present = walker.runProcedure(p, mapOf("value" to null))
        assertTrue(present.returns.containsKey("result"))
        assertEquals(null, present.returns["result"])
        assertEquals("resolved", present.bindings.single()["status"])
    }

    @Test
    fun malformedScopeArgumentMapFailsInsteadOfDisappearing() = runBlocking {
        val p = LcncProgram("bad-args", listOf(
            LcncNode("arg", "scope.in", mapOf("name" to "arg")),
            LcncNode("scope", "scope", children = listOf(LcncNode("in", "scope.in", mapOf("name" to "x", "default" to "fallback"))).toSeries()),
        ).toSeries(), listOf(LcncWire("arg", "value", "scope", "args?")).toSeries())
        for (bad in listOf(listOf(1), "not an object", null)) {
            assertFailsWith<IllegalArgumentException> { LcncRunner(emptyMap()).runProcedure(p, mapOf("arg" to bad)) }
        }
    }
}
