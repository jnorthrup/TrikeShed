package borg.trikeshed.lcnc

import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Gates for the daemon-side pure legos ([PureNodes.registry]) — above all
 * `list.format`, the declarative eval-free reshaper: map-shaped outputs
 * (kanban.attention `cards`/`ordered`) become the `lines` list that
 * `read.construct`/`nal.mint` take, with the canvas-only `js` node no longer
 * the sole bridge.
 */
class PureNodesTest {

    private val registry = PureNodes.registry { 1234L }

    private fun run(
        type: String,
        params: Map<String, String> = emptyMap(),
        inputs: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = runBlocking {
        registry.getValue(type).run(LcncNode("n1", type, params), inputs)
    }

    // ── list.format ───────────────────────────────────────────────────────

    @Test
    fun listFormatSubstitutesTemplateFieldsPerItem() {
        val cards = listOf(
            mapOf("id" to "c1", "attention" to 0.9),
            mapOf("id" to "c2", "attention" to 0.4),
        )
        val out = run("list.format",
            params = mapOf("template" to "{id}: attention={attention}"),
            inputs = mapOf("x" to cards))
        assertEquals(listOf("c1: attention=0.9", "c2: attention=0.4"), out["lines"])
    }

    @Test
    fun listFormatMissingFieldsRenderEmpty() {
        val out = run("list.format",
            params = mapOf("template" to "{id}: attention={attention}"),
            inputs = mapOf("x" to listOf(mapOf("id" to "c1"))))
        assertEquals(listOf("c1: attention="), out["lines"])
    }

    @Test
    fun listFormatLimitBoundsTheLines() {
        val xs = listOf(mapOf("id" to "a"), mapOf("id" to "b"), mapOf("id" to "c"))
        val bounded = run("list.format",
            params = mapOf("template" to "{id}", "limit" to "2"),
            inputs = mapOf("x" to xs))
        assertEquals(listOf("a", "b"), bounded["lines"])
        val unbounded = run("list.format",
            params = mapOf("template" to "{id}", "limit" to "not-a-number"),
            inputs = mapOf("x" to xs))
        assertEquals(listOf("a", "b", "c"), unbounded["lines"])
    }

    @Test
    fun listFormatNonMapItemsStringify() {
        val out = run("list.format",
            params = mapOf("template" to "{id}"),
            inputs = mapOf("x" to listOf("plain", 7, null)))
        assertEquals(listOf("plain", "7", ""), out["lines"])
    }

    @Test
    fun listFormatScalarInputBecomesOneLine() {
        val out = run("list.format", inputs = mapOf("x" to "solo"))
        assertEquals(listOf("solo"), out["lines"])
    }

    @Test
    fun listFormatComposesAttentionIntoLinesThroughTheRunner() = runBlocking {
        // The missing lane, end to end: an attention-shaped source feeds
        // list.format, whose `lines` port is what read.construct/nal.mint take.
        val reg = registry + mapOf(
            "attention.stub" to LcncNodeRunner { _, _ ->
                mapOf("cards" to listOf(mapOf("id" to "c1", "attention" to 0.9)))
            },
        )
        val p = LcncProgram("shape",
            listOf(
                LcncNode("att", "attention.stub"),
                LcncNode("fmt", "list.format", mapOf("template" to "{id}: attention={attention}")),
            ).toSeries(),
            listOf(LcncWire("att", "cards", "fmt", "x")).toSeries())
        val out = LcncRunner(reg).runAll(p)
        assertEquals(listOf("c1: attention=0.9"), out["fmt"]?.get("lines"))
    }

    // ── pick / list.groupBy / timer ───────────────────────────────────────

    @Test
    fun pickWalksDotPathsThroughMapsAndLists() {
        val x = mapOf("items" to listOf(mapOf("id" to "c7")))
        val out = run("pick", params = mapOf("path" to "items.0.id"), inputs = mapOf("x" to x))
        assertEquals("c7", out["y"])
        assertNull(run("pick", params = mapOf("path" to "items.9.id"), inputs = mapOf("x" to x))["y"])
    }

    @Test
    fun groupByBucketsByKeyInEncounterOrder() {
        val xs = listOf(
            mapOf("id" to "a", "status" to "todo"),
            mapOf("id" to "b", "status" to "done"),
            mapOf("id" to "c", "status" to "todo"),
            mapOf("id" to "d"),
        )
        val groups = run("list.groupBy", params = mapOf("key" to "status"), inputs = mapOf("x" to xs))["groups"] as Map<*, *>
        assertEquals(listOf("todo", "done", ""), groups.keys.toList())
        assertEquals(2, (groups["todo"] as List<*>).size)
        assertEquals(1, (groups[""] as List<*>).size)
    }

    @Test
    fun timerEmitsOneTickFromTheClock() {
        assertEquals(1234L, run("timer")["tick"])
    }
}
