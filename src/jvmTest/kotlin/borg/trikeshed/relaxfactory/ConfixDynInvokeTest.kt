package borg.trikeshed.relaxfactory

import org.graalvm.polyglot.PolyglotException
import kotlin.test.*

class ConfixDynInvokeTest {
    @Test
    fun indexedReadGuardsFieldOrderAndDeclaredType() {
        ConfixDynInvoke().use { bridge ->
            val first = ConfixBean.from("""{"name":"first","count":3}""")
            val second = ConfixBean.from("""{"count":8,"name":"second"}""")
            assertNotSame(first.shape, second.shape)
            assertEquals(3, bridge.get(first, "count", Int::class.java))
            assertEquals(3, bridge.get(first, "count", Int::class.java))
            assertEquals(8, bridge.get(second, "count", Int::class.java))
            assertEquals("second", bridge.get(second, "name", String::class.java))
            assertTrue(bridge.readRoot.read.hits > 0)
            assertSame(bridge.readRoot, bridge.readRoot.read.parent)
            assertTrue(bridge.readRoot.read.caches.all { it.parent === bridge.readRoot.read })
            assertFailsWith<IllegalArgumentException> { bridge.get(first, "name", Int::class.java) }
        }
    }

    @Test
    fun writeInvalidatesDocumentAndPreservesOtherFields() {
        ConfixDynInvoke().use { bridge ->
            val bean = ConfixBean.from("""{"count":1,"nested":{"name":"before"},"array":[2,3]}""")
            val shape = bean.shape
            val assumption = bean.assumption
            assertEquals(1, bridge.get(bean, "count", Int::class.java))
            bridge.set(bean, "count", 4)
            assertFalse(assumption.isValid)
            assertSame(shape, bean.shape)
            assertEquals(4, bridge.get(bean, "count", Int::class.java))
            assertEquals(mapOf("name" to "before"), bean.fields()["nested"])
            assertEquals(listOf(2L, 3L), bean.fields()["array"])
            bridge.set(bean, "count", "four")
            assertNotSame(shape, bean.shape)
            assertEquals("four", bridge.get(bean, "count", String::class.java))
            assertTrue(bean.remove("count"))
            assertNull(bridge.get(bean, "count"))
        }
    }

    @Test
    fun callableCacheGuardsReplacementAndArgumentShapes() {
        ConfixDynInvoke().use { bridge ->
            bridge.register("number") { (it.single() as Number).toInt() + 1 }
            assertEquals(4, bridge.call("number", 3))
            assertEquals(5, bridge.call("number", 4))
            assertTrue(bridge.callRoot.invoke.hits > 0)
            val prior = bridge.calls.getValue("number")
            bridge.register("number") { (it.single() as Number).toInt() * 2 }
            assertFalse(prior.assumption.isValid)
            assertEquals(6, bridge.call("number", 3))
            assertEquals(10, bridge.call("number", 5L))
            assertSame(bridge.callRoot, bridge.callRoot.invoke.parent)
        }
    }

    class Receiver {
        fun add(value: Int): Int = value + 2
        fun fail(): Nothing = throw IllegalStateException("domain failure")
    }

    @Test
    fun nativeMethodDispatchExecutesTheAdoptedCallNode() {
        ConfixDynInvoke().use { bridge ->
            val receiver = Receiver()
            val method = Receiver::class.java.getMethod("add", Int::class.java)
            assertEquals(5, bridge.invoke(method, receiver, arrayOf(3)))
            assertEquals(7, bridge.invoke(method, receiver, arrayOf(5)))
            assertTrue(bridge.callRoot.invoke.hits > 0)
            assertEquals("domain failure", assertFailsWith<IllegalStateException> {
                bridge.invoke(Receiver::class.java.getMethod("fail"), receiver, emptyArray())
            }.message)
        }
    }

    @Test
    fun scriptMutationUsesLiveConfixNestedProxies() {
        ConfixDynInvoke().use { bridge ->
            val bean = ConfixBean.from("""{"nested":{"count":1},"array":[2,3],"keep":true}""")
            val shape = bean.shape
            val assumption = bean.assumption
            bridge.registerScript("mutate", "js", """
                (function (bean) {
                    bean.nested.count = "changed";
                    bean.array[1] = 9;
                    bean.nested.other = true;
                    return bean;
                })
            """.trimIndent())
            assertSame(bean, bridge.call("mutate", bean))
            assertFalse(assumption.isValid)
            assertNotSame(shape, bean.shape)
            assertEquals(mapOf("count" to "changed", "other" to true), bean.fields()["nested"])
            assertEquals(listOf(2L, 9L), bean.fields()["array"])
            assertEquals(true, bean.get("keep"))
        }
    }

    @Test
    fun numericLexemesAndEscapedStringsPreserveDeclaredValues() {
        ConfixDynInvoke().use { bridge ->
            val text = "quoted \"\\\n café ∀"
            val bean = ConfixBean.from(mapOf("integer" to Long.MAX_VALUE, "text" to text))
            assertEquals(Long.MAX_VALUE, bridge.get(bean, "integer", Long::class.java))
            assertEquals(Long.MAX_VALUE, bean.fields()["integer"])
            assertEquals(text, bridge.get(bean, "text", String::class.java))
            bridge.set(bean, "integer", Long.MAX_VALUE - 1)
            bridge.set(bean, "text", text + " updated")
            assertEquals(Long.MAX_VALUE - 1, bridge.get(bean, "integer", Long::class.java))
            assertEquals(text + " updated", bridge.get(bean, "text", String::class.java))
            val scientific = ConfixBean.from("""{"integer":9.223372036854775807e18}""")
            assertEquals(Long.MAX_VALUE, bridge.get(scientific, "integer", Long::class.java))
            bridge.set(scientific, "other", true)
            assertEquals(Long.MAX_VALUE, bridge.get(scientific, "integer", Long::class.java))
            assertFailsWith<IllegalArgumentException> { bridge.get(scientific, "integer", Int::class.java) }
        }
    }

    @Test
    fun scriptFallbackReturnsStructuredValuesWithoutJsonRoundTrip() {
        ConfixDynInvoke().use { bridge ->
            bridge.registerScript("project", "js", "(function (input) { return {name: input.name, result: [input.value + 1, null]}; })")
            assertEquals(mapOf("name" to "n", "result" to listOf(4, null)),
                bridge.call("project", mapOf("name" to "n", "value" to 3)))
            bridge.registerScript("python", "python", "lambda value: value + 7")
            assertEquals(10, bridge.call("python", 3))
        }
    }

    @Test
    fun scriptFailureAndCloseAreObservable() {
        val bridge = ConfixDynInvoke()
        bridge.registerScript("failure", "js", "(function () { throw new Error('script failure'); })")
        assertContains(assertFailsWith<PolyglotException> { bridge.call("failure") }.message.orEmpty(), "script failure")
        assertFailsWith<IllegalArgumentException> { bridge.registerScript("failure", "js", "42") }
        assertContains(assertFailsWith<PolyglotException> { bridge.call("failure") }.message.orEmpty(), "script failure")
        val script = bridge.calls.getValue("failure").resource as ConfixScript
        bridge.close()
        assertTrue(script.closed)
        assertFailsWith<IllegalStateException> { bridge.call("failure") }
        assertFailsWith<IllegalStateException> { bridge.get(ConfixBean.from("{}"), "name") }
        bridge.close()
    }

    @Test
    fun statementLimitClosesExhaustedScriptContext() {
        ConfixDynInvoke(statementLimit = 1_000).use { bridge ->
            bridge.registerScript("loop", "js", "(function () { while (true) {} })")
            val failure = assertFailsWith<PolyglotException> { bridge.call("loop") }
            assertTrue(failure.isResourceExhausted)
            assertTrue((bridge.calls.getValue("loop").resource as ConfixScript).closed)
            assertFailsWith<IllegalStateException> { bridge.call("loop") }
        }
    }
}
