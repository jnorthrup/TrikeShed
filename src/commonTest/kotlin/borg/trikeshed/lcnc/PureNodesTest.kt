package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    @Test
    fun sheetProjectionRetainsOnlySuppliedOptionsWithoutMutation() {
        val board = mutableMapOf<String, Any?>("id" to "board", "rows" to mutableListOf("card"))
        val inputs = mutableMapOf<String, Any?>("board" to board, "move" to true, "press" to true, "ended" to true)
        val before = inputs.toMap()
        val primary = run("sheet.concentric", inputs = inputs)
        assertEquals(setOf("rendered"), primary.keys)
        assertSame(board, primary["rendered"])

        for (port in s_["byStatus", "byPriority", "orchestration"].view) {
            for (suffix in s_["", "?"].view) {
                val sheets = mutableListOf(board)
                for (value in s_[sheets, emptyList<Any?>(), null].view) {
                    val supplied = inputs + ("$port$suffix" to value)
                    val snapshot = supplied.toMap()
                    val out = run("sheet.concentric", inputs = supplied)
                    assertEquals(setOf("rendered", port), out.keys)
                    assertSame(board, out["rendered"])
                    assertSame(value, out[port])
                    assertEquals(snapshot, supplied)
                }
                assertEquals(listOf(board), sheets)
            }
            val out = run("sheet.concentric", inputs = inputs + mapOf(port to null, "$port?" to board))
            assertEquals(mapOf("rendered" to board, port to null), out, "the normal key takes precedence")
        }
        assertEquals(before, inputs)
        assertEquals(mapOf<String, Any?>("id" to "board", "rows" to listOf("card")), board)
    }

    @Test
    fun boardProjectionRetainsOnlySuppliedColumnsWithoutMutation() {
        val card = mutableMapOf("id" to "card", "status" to "todo")
        val groups = mutableMapOf("todo" to mutableListOf(card))
        val inputs = mutableMapOf<String, Any?>("groups" to groups, "move" to true, "press" to true, "ended" to true)
        val before = inputs.toMap()
        val primary = run("dom.board", inputs = inputs)
        assertEquals(setOf("rendered"), primary.keys)
        assertSame(groups, primary["rendered"])

        val columns = mutableListOf(mapOf("id" to "todo"), mapOf("id" to "done"))
        for (suffix in s_["", "?"].view) {
            for (value in s_[columns, emptyList<Any?>(), null].view) {
                val supplied = inputs + ("columns$suffix" to value)
                val snapshot = supplied.toMap()
                val out = run("dom.board", inputs = supplied)
                assertEquals(setOf("rendered", "columns"), out.keys)
                assertSame(groups, out["rendered"])
                assertSame(value, out["columns"])
                assertEquals(snapshot, supplied)
            }
        }
        assertEquals(mapOf("rendered" to groups, "columns" to null),
            run("dom.board", inputs = inputs + mapOf("columns" to null, "columns?" to columns)))
        assertEquals(before, inputs)
        assertEquals(mapOf("todo" to listOf(mapOf("id" to "card", "status" to "todo"))), groups)
        assertEquals(listOf(mapOf("id" to "todo"), mapOf("id" to "done")), columns)
    }

    @Test
    fun gaugeProjectionRetainsXWithoutFormattingOrGestures() {
        val payload = mutableMapOf("count" to 12000)
        for (value in s_[0, false, "", 12000, payload, null].view) {
            val inputs = mutableMapOf("x" to value, "move" to true, "press" to true, "ended" to true)
            val before = inputs.toMap()
            val out = run("gauge", inputs = inputs)
            assertEquals(setOf("x"), out.keys)
            assertSame(value, out["x"])
            assertEquals(before, inputs)
        }
        assertEquals(mapOf("count" to 12000), payload)
    }

    @Test
    fun presentationOutputsSurviveCanonicalProgramWalkingWithoutReplayingEffects() = runBlocking {
        val card = mapOf("id" to "card", "status" to "todo")
        val board = mapOf("id" to "board", "rows" to listOf(card))
        val byStatus = listOf(mapOf("id" to "status:todo", "parent" to "board"))
        val byPriority = listOf(mapOf("id" to "priority:1", "parent" to "board"))
        val orchestration = mapOf("lanes" to listOf("todo", "done"))
        val columns = listOf(mapOf("id" to "todo"), mapOf("id" to "done"))
        val data = mapOf("board" to board, "byStatus" to byStatus, "byPriority" to byPriority,
            "orchestration" to orchestration, "columns" to columns, "groups" to mapOf("todo" to listOf(card)))
        for (suffix in s_["", "?"].view) {
            var reads = 0
            val reg = registry + ("http.get" to LcncNodeRunner { _, _ ->
                reads++
                mapOf("json" to data)
            })
            val program = LcncProgram("presentation", s_[
                LcncNode("source", "http.get", mapOf("path" to "/projection")),
                LcncNode("board", "pick", mapOf("path" to "board")),
                LcncNode("status", "pick", mapOf("path" to "byStatus")),
                LcncNode("priority", "pick", mapOf("path" to "byPriority")),
                LcncNode("orchestration", "pick", mapOf("path" to "orchestration")),
                LcncNode("columns", "pick", mapOf("path" to "columns")),
                LcncNode("groups", "pick", mapOf("path" to "groups")),
                LcncNode("sheet", "sheet.concentric"),
                LcncNode("dom", "dom.board"),
                LcncNode("gauge", "gauge"),
                LcncNode("button", "button"),
                // No JS runner is registered: any fabricated gesture fails the walk.
                LcncNode("move", "js"),
                LcncNode("press", "js"),
            ], s_[
                LcncWire("source", "json", "board", "x"),
                LcncWire("source", "json", "status", "x"),
                LcncWire("source", "json", "priority", "x"),
                LcncWire("source", "json", "orchestration", "x"),
                LcncWire("source", "json", "columns", "x"),
                LcncWire("source", "json", "groups", "x"),
                LcncWire("board", "y", "sheet", "board"),
                LcncWire("status", "y", "sheet", "byStatus$suffix"),
                LcncWire("priority", "y", "sheet", "byPriority$suffix"),
                LcncWire("orchestration", "y", "sheet", "orchestration$suffix"),
                LcncWire("groups", "y", "dom", "groups"),
                LcncWire("columns", "y", "dom", "columns$suffix"),
                LcncWire("source", "json", "gauge", "x"),
                LcncWire("dom", "move", "move", "x"),
                LcncWire("button", "press", "press", "x"),
            ])
            val document = LcncProgramConfix.toJson(program)
            val out = LcncRunner(reg).runProcedure(LcncProgramConfix.fromJson(program.name, document)).nodeOutputs
            val sheet = out.getValue("sheet")
            assertEquals(setOf("rendered", "byStatus", "byPriority", "orchestration"), sheet.keys)
            assertSame(board, sheet["rendered"])
            assertSame(byStatus, sheet["byStatus"])
            assertSame(byPriority, sheet["byPriority"])
            assertSame(orchestration, sheet["orchestration"])
            assertEquals(setOf("rendered", "columns"), out.getValue("dom").keys)
            assertSame(data["groups"], out.getValue("dom")["rendered"])
            assertSame(columns, out.getValue("dom")["columns"])
            assertEquals(setOf("x"), out.getValue("gauge").keys)
            assertSame(data, out.getValue("gauge")["x"])
            assertTrue(out.getValue("button").isEmpty())
            assertTrue("move" !in out && "press" !in out, "gesture consumers must remain unfed")
            for (gesture in s_["move", "press", "ended"].view) {
                assertTrue(out.values.none { gesture in it }, "$gesture must not be emitted")
            }
            assertEquals(1, reads)
        }
    }
}
