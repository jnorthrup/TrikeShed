package borg.trikeshed.agent.modelmux

import borg.trikeshed.parse.confix.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyMap
import kotlin.math.roundToInt
import kotlin.test.*

/** The cases of onegood1 tests/test_error_rate_tracker.py and test_token_allocator.py against the breadcrumb cleaner. */
class BreadcrumbHorizonTest {
    @Test fun bandFollowsTheFiftyTurnOverflowRate() {
        fun band(overflows: Int) = BreadcrumbOverflow().apply { repeat(50) { record(it < overflows) } }.band
        assertEquals(BreadcrumbBand.SCALE_UP, BreadcrumbOverflow().band)
        assertEquals(BreadcrumbBand.SCALE_UP, band(0))
        assertEquals(BreadcrumbBand.HOLD, band(1))
        assertEquals(BreadcrumbBand.HOLD, band(2))
        assertEquals(BreadcrumbBand.SCALE_BACK, band(3))
    }

    @Test fun scaleBackDropsOldFaintRecordsAndScaleUpRecallsThem() {
        var now = 0L
        val context = BreadcrumbContext({ now }, BreadcrumbRetention(1_000_000_000, 30_000))
        val staleFaint = context.put("stale-faint", "a b c")
        val staleHeld = context.put("stale-held", "d e f")
        now = 61_000
        val freshFaint = context.put("fresh-faint", "g h i")
        now = 110_000
        context.resize(staleHeld.id, BreadcrumbPresence(1.0))
        context.nextToolResult()
        now = 120_000
        assertEquals(BreadcrumbBand.SCALE_BACK, context.clean(BreadcrumbOverflow.BUDGET_TOKENS + 1))
        assertEquals(0.0, context.lookup(staleFaint.id)!!.gain)
        assertEquals("a b c", context.lookup(staleFaint.id)!!.content, "a dropped record keeps its original")
        assertEquals(0.9, context.lookup(staleHeld.id)!!.gain, 1e-9)
        assertEquals(0.9, context.lookup(freshFaint.id)!!.gain, 1e-9, "a record younger than a minute is kept")

        var band = BreadcrumbBand.SCALE_BACK
        repeat(BreadcrumbOverflow.WINDOW) { band = context.clean(0) }
        assertEquals(BreadcrumbBand.SCALE_UP, band)
        assertEquals(1.0, context.lookup(staleFaint.id)!!.gain, "scale-up recalls a dropped record among the newest")
    }

    @Test fun allocationSplitsTheBudgetByWeightWithLargestRemainders() {
        assertContentEquals(intArrayOf(4, 3, 3), breadcrumbAllocation(10, doubleArrayOf(1.0, 1.0, 1.0)))
        assertContentEquals(intArrayOf(5, 5), breadcrumbAllocation(10, doubleArrayOf(0.0, 0.0)))
        assertContentEquals(intArrayOf(75, 25), breadcrumbAllocation(100, doubleArrayOf(0.6, 0.2)))
    }

    @Test fun overflowEstimateCountsMessageTextAndToolArguments() {
        val request = confixDoc(jsonOf(mapOf("messages" to listOf(
            mapOf("role" to "user", "content" to "x".repeat(10)),
            mapOf("role" to "assistant", "content" to null, "tool_calls" to listOf(
                mapOf("id" to "c", "function" to mapOf("name" to "f", "arguments" to "y".repeat(6))))),
            mapOf("role" to "tool", "tool_call_id" to "c", "content" to listOf(mapOf("type" to "text", "text" to "zzzz"))),
        ))))
        assertEquals(5L, BreadcrumbModelHost.tokens(request))
    }

    @Test fun theTrailRunsInSequenceWithTurnsCarryingTheirBreadcrumbs() {
        val context = BreadcrumbContext({ 0L })
        context.put(BreadcrumbId("ask"), "input:user", "inspect")
        context.put(BreadcrumbId("turn"), "input:assistant", "calls", children = listOf(BreadcrumbId("a"), BreadcrumbId("b")))
        context.put(BreadcrumbId("a"), "tool:a", "alpha")
        context.put(BreadcrumbId("b"), "tool:b", "beta")
        context.put(BreadcrumbId("answer"), "agent:assistant", "done")
        val trail = (reifyMap(context.horizonJson("c"))["records"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("ask", "turn", "a", "b", "answer"), trail.map { it["id"] })
        assertEquals(listOf(0, 0, 1, 1, 0), trail.map { (it["depth"] as Number).toInt() })
        context.resize(BreadcrumbId("turn"), BreadcrumbPresence(1.0))
        val roots = (reifyMap(context.nextToolResult().breadcrumbJson())["breadcrumbs"] as List<*>).map { it as Map<*, *> }
        assertEquals(listOf("ask", "turn", "answer"), roots.map { it["id"] })
        assertEquals(listOf("alpha", "beta"), (roots[1]["breadcrumbs"] as List<*>).map { (it as Map<*, *>)["content"] })
    }

    @Test fun particlesKeepDistinctWordsInOrderWithinTheirBudget() {
        val context = BreadcrumbContext({ 0L })
        context.put("other", "the build failed the test")
        val record = context.put("tool", "the gradle daemon crashed while the kotlin compiler ran the test")
        val particles = record.particles(0.5, context.lexicon())
        assertTrue(particles.isNotEmpty() && particles.length <= (record.content.length * 0.5).roundToInt())
        var from = 0
        for (word in particles.split(' ').filter { it != BREADCRUMB_GAP }) {
            from = record.content.indexOf(word, from).also { assertTrue(it >= 0, word) } + word.length
        }
        assertFalse("the" in particles.split(' '), "words the trail shares are dropped first")
    }
}
