package borg.trikeshed.relaxfactory

import borg.trikeshed.job.CanonicalCbor
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
            repeat(6) { index ->
                bridge.set(first, "field$index", true)
                assertEquals(3, bridge.get(first, "count", Int::class.java))
            }
            assertFalse(bridge.readRoot.read.generic)
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
                    bean.keep = bean.keep && bean.keep;
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
            assertTrue(bridge.roots.values.any { it.readRoot.read.hits > 0 })
        }
    }

    @Test
    fun guestCallbacksOwnTheirTruffleSharingLayer() {
        ConfixDynInvoke().use { bridge ->
            val bean = ConfixBean.from("""{"count":1}""")
            // Warm the host AST before either independent guest context executes it.
            assertEquals(1, bridge.get(bean, "count", Int::class.java))
            bridge.registerScript("first", "js", "(bean) => { const count = bean.count; if (count !== bean.count) throw Error('read'); bean.count = count + 1; return bean.count; }")
            bridge.registerScript("second", "js", "(bean) => { const count = bean.count; if (count !== bean.count) throw Error('read'); bean.count = count + 2; return bean.count; }")
            val first = bridge.calls.getValue("first").resource as ConfixScript
            assertEquals(2, bridge.call("first", bean))
            val firstAst = assertNotNull(bridge.roots[first.current])
            assertNotSame(first.context, first.current)
            first.context.enter()
            try { assertSame(first.current, org.graalvm.polyglot.Context.getCurrent()) } finally { first.context.leave() }
            assertEquals(4, bridge.call("second", bean))
            assertEquals(5, bridge.call("first", bean))
            assertSame(firstAst, bridge.roots[first.current])
            assertEquals(5, bridge.get(bean, "count", Int::class.java))
            val guests = bridge.roots.filterKeys { it != null }
            assertEquals(2, guests.size)
            assertEquals(3, bridge.roots.size)
            assertTrue(guests.values.all { it.readRoot.read.hits > 0 })
            first.close()
            assertFalse(bridge.roots.containsKey(first.current))
            assertEquals(7, bridge.call("second", bean))
            assertEquals(2, bridge.roots.size)
            bridge.registerScript("python", "python", "lambda bean: bean.count + bean.count")
            assertEquals(14, bridge.call("python", bean))
            val python = bridge.calls.getValue("python").resource as ConfixScript
            assertTrue(assertNotNull(bridge.roots[python.current]).readRoot.read.hits > 0)
            assertEquals(3, bridge.roots.size)
        }
    }

    @Test
    fun receiverProxiesDistinguishMissingNullAndContainers() {
        ConfixDynInvoke().use { bridge ->
            val bean = ConfixBean.from("""{"nil":null,"object":{"count":3},"array":[2]}""")
            val proxy = ConfixPolyglot.ObjectProxy(bean, emptyList(), bridge)
            assertFalse(proxy.hasMember("missing"))
            assertTrue(proxy.hasMember("nil"))
            assertNull(proxy.getMember("missing"))
            assertNull(proxy.getMember("nil"))
            assertNull(proxy.getMember("nil"))
            val nested = assertIs<ConfixPolyglot.ObjectProxy>(proxy.getMember("object"))
            val array = assertIs<ConfixPolyglot.ArrayProxy>(proxy.getMember("array"))
            assertEquals(3L, nested.getMember("count"))
            assertEquals(2L, array.get(0))
            assertTrue(bridge.readRoot.read.hits > 0)
            assertFailsWith<IllegalArgumentException> { bridge.get(bean, "nil", Long::class.java) }
            bridge.set(bean, "object", false)
            assertFailsWith<IllegalStateException> { nested.hasMember("count") }
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
    fun arbitraryPrecisionAndDateScalarsSurviveCborStorage() {
        ConfixDynInvoke().use { bridge ->
            val decimal = java.math.BigDecimal("0.10000000000000000000000000000100")
            val integer = java.math.BigInteger("9223372036854775807123456789")
            val date = java.util.Date(1_729_381_123_456L)
            val bean = ConfixBean.from("{}")
            bridge.set(bean, "decimal", decimal)
            bridge.set(bean, "integer", integer)
            bridge.set(bean, "date", date)
            assertEquals(decimal.toString(), bean.fields()["decimal"])
            assertEquals(integer.toString(), bean.fields()["integer"])
            assertEquals(date.time, bean.fields()["date"])
            val loaded = ConfixBean.from(CanonicalCbor.decodeMap(CanonicalCbor.encodeMap(bean.fields())))
            assertEquals(decimal, bridge.get(loaded, "decimal", java.math.BigDecimal::class.java))
            assertEquals(integer, bridge.get(loaded, "integer", java.math.BigInteger::class.java))
            assertEquals(date, bridge.get(loaded, "date", java.util.Date::class.java))
            val numeric = ConfixBean.from("""{"decimal":0.10000000000000000000000000000100,"integer":9223372036854775807123456789,"date":1729381123456}""")
            assertEquals(decimal, bridge.get(numeric, "decimal", java.math.BigDecimal::class.java))
            assertEquals(integer, bridge.get(numeric, "integer", java.math.BigInteger::class.java))
            assertEquals(date, bridge.get(numeric, "date", java.util.Date::class.java))
            bridge.set(numeric, "other", true)
            assertEquals(decimal, bridge.get(numeric, "decimal", java.math.BigDecimal::class.java))
            assertFailsWith<IllegalArgumentException> {
                bridge.get(ConfixBean.from("""{"integer":1.5}"""), "integer", java.math.BigInteger::class.java)
            }
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
            bridge.registerScript("integer", "js", "(function () { return 9223372036854775807123456789n; })")
            assertEquals(java.math.BigInteger("9223372036854775807123456789"), bridge.call("integer"))
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
