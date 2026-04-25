package borg.trikeshed.parse.kursive

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.concurrency.ParseScope
import borg.trikeshed.userspace.concurrency.ParseLifecycle
import borg.trikeshed.userspace.concurrency.withParseScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for Kursive CCEK fanout — CoroutineContextElement+Key parse scopes.
 */
class KursiveCcekTest {

    /** Basic scope lifecycle: CREATED → OPEN → ACTIVE → DRAINING → CLOSED */
    @Test
    fun testScopeLifecycle() {
        val src = "hello world".toSeries()
        val scope = ParseScope(src, 0 j src.size)

        assertEquals(ParseLifecycle.CREATED, scope.lifecycleState)

        scope.open()
        assertEquals(ParseLifecycle.OPEN, scope.lifecycleState)

        scope.activate()
        assertEquals(ParseLifecycle.ACTIVE, scope.lifecycleState)

        scope.drain()
        assertEquals(ParseLifecycle.DRAINING, scope.lifecycleState)

        scope.close()
        assertEquals(ParseLifecycle.CLOSED, scope.lifecycleState)
    }

    /** Subscriber receives emitted results — subscribe BEFORE activate */
    @Test
    fun testSubscriberFanout() {
        val src = "abc".toSeries()
        val scope = ParseScope(src, 0 j 3)
        scope.open()

        val received = mutableListOf<Any?>()
        scope.subscribe { _, result -> received.add(result) }

        scope.activate()

        scope.emit("result1")
        scope.emit("result2")

        assertEquals(2, received.size)
        assertEquals("result1", received[0])
        assertEquals("result2", received[1])
    }

    /** Child scope inherits source and gets its own supervisor */
    @Test
    fun testChildScope() {
        val src = "parent child".toSeries()
        val parent = ParseScope(src, 0 j 12)
        parent.open()
        parent.activate()

        val child = parent.childScope(7 j 12)
        assertEquals(src, child.source)
        assertEquals(7, child.span.a)
        assertEquals(12, child.span.b)
        assertEquals(ParseLifecycle.CREATED, child.lifecycleState)
        assertEquals(1, parent.childCount)
    }

    /** Fanout: identify children, launch concurrent parsers, collect results */
    @Test
    fun testFanout() = runBlocking {
        val src = "aaa,bbb,ccc".toSeries()
        val scope = ParseScope(src, 0 j src.size)
        scope.open()

        val identify: (Series<Char>, Twin<Int>) -> Series<Twin<Int>> = { source, span ->
            val positions = mutableListOf<Int>()
            positions.add(span.a)
            for (i in span.a until span.b) {
                if (source[i] == ',') positions.add(i + 1)
            }
            positions.add(span.b)
            val n = positions.size - 1
            n j { i: Int ->
                val end = if (i < n - 1) positions[i + 1] - 1 else positions[i + 1]
                positions[i] j end
            }
        }

        val childParser: (Series<Char>, Twin<Int>) -> Any? = { source, span ->
            val chars = CharArray(span.b - span.a)
            for (i in 0 until chars.size) chars[i] = source[span.a + i]
            chars.concatToString()
        }

        val results = scope.fanout(identify, childParser)

        assertEquals(3, results.size)
        assertEquals("aaa", results[0])
        assertEquals("bbb", results[1])
        assertEquals("ccc", results[2])
    }

    /** Fanout with typed child parsers and tag dispatch */
    @Test
    fun testFanoutParsers() = runBlocking {
        val src = "10,20,30".toSeries()
        val scope = ParseScope(src, 0 j src.size)
        scope.open()

        val identify: (Series<Char>, Twin<Int>) -> Series<Join<Twin<Int>, Int>> = { source, span ->
            val positions = mutableListOf<Int>()
            positions.add(span.a)
            for (i in span.a until span.b) {
                if (source[i] == ',') positions.add(i + 1)
            }
            positions.add(span.b)
            val n = positions.size - 1
            n j { i: Int -> (positions[i] j positions[i + 1]) j 0 }
        }

        val numberParser: (Series<Char>, Twin<Int>) -> Int? = { source, span ->
            var value = 0
            for (i in span.a until span.b) {
                val c = source[i]
                if (c in '0'..'9') value = value * 10 + (c - '0')
            }
            value
        }

        val results = scope.fanoutParsers(identify) { _ -> numberParser }

        assertEquals(3, results.size)
        assertEquals(10, results[0])
        assertEquals(20, results[1])
        assertEquals(30, results[2])
    }

    /** withParseScope: entry point for CCEK-driven parsing */
    @Test
    fun testWithParseScope() = runBlocking {
        val src = "hello".toSeries()

        val (result, scope) = withParseScope<String>(src) { source, span ->
            val chars = CharArray(span.b - span.a)
            for (i in 0 until chars.size) chars[i] = source[span.a + i]
            chars.concatToString()
        }

        assertNotNull(result)
        assertEquals("hello", result)
        assertEquals(ParseLifecycle.CLOSED, scope.lifecycleState)
    }

    /** Nested fanout: scope tree is the parse tree */
    @Test
    fun testNestedFanout() = runBlocking {
        val src = "aaa,bbb".toSeries()
        val scope = ParseScope(src, 0 j src.size)
        scope.open()

        val identify: (Series<Char>, Twin<Int>) -> Series<Twin<Int>> = { source, span ->
            val positions = mutableListOf<Int>()
            positions.add(span.a)
            for (i in span.a until span.b) {
                if (source[i] == ',') positions.add(i + 1)
            }
            positions.add(span.b)
            val n = positions.size - 1
            n j { i: Int ->
                val end = if (i < n - 1) positions[i + 1] - 1 else positions[i + 1]
                positions[i] j end
            }
        }

        val results = scope.fanout(identify) { source, span ->
            val chars = CharArray(span.b - span.a)
            for (i in 0 until chars.size) chars[i] = source[span.a + i]
            chars.concatToString().trim()
        }

        assertEquals(2, results.size)
        assertEquals("aaa", results[0])
        assertEquals("bbb", results[1])
    }
}
