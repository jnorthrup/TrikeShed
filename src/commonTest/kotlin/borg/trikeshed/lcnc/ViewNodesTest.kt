package borg.trikeshed.lcnc

import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.s_
import borg.trikeshed.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ViewNodesTest {
    @Test
    fun declaredMapAndReduceRunThroughTheExistingViewAlgebra() = runBlocking {
        val program = LcncProgram("view", s_[
            LcncNode("docs", "json.value", mapOf("value" to """[{"_id":"a","group":"x","count":2},{"_id":"b","group":"x","count":3},{"_id":"c","group":"y","count":4}]""")),
            LcncNode("map", "view.emit", mapOf("key" to "doc.group", "value" to "doc.count")),
            LcncNode("reduce", "view.reduce", mapOf("reducer" to "_sum")),
        ], s_[LcncWire("docs", "value", "map", "documents"), LcncWire("map", "rows", "reduce", "rows")])
        val results = LcncRunner(PureNodes.registry { 0 }).runAll(program)
        val reduced = results.getValue("reduce")["reduced"] as List<*>
        assertEquals<Map<*, *>>(mapOf("x" to 5.0, "y" to 4.0), reduced.associate {
            val row = it as Map<*, *>; row["key"] to row["value"]
        })
    }

    @Test
    fun emitEachRetainsSourceDocumentIdentityAndReducerRejectsUnknownOperations() = runBlocking {
        val registry = ViewNodes.registry()
        val rows = registry.getValue("view.emit").run(
            LcncNode("map", "view.emit", mapOf("arrayField" to "tags", "key" to "doc.tags", "value" to "const:1")),
            mapOf("documents" to listOf(mapOf("_id" to "a", "tags" to listOf("x", "y")))),
        )["rows"] as List<*>
        assertEquals(listOf("x", "y"), rows.map { (it as Map<*, *>)["key"] })
        assertEquals(listOf("a", "a"), rows.map { (it as Map<*, *>)["docId"] })
        assertFailsWith<IllegalStateException> {
            registry.getValue("view.reduce").run(LcncNode("r", "view.reduce", mapOf("reducer" to "eval")), mapOf("rows" to rows))
        }
        Unit
    }

    @Test
    fun timerUsesTheInstalledClockInsteadOfTheCapturedDefault() = runBlocking {
        val timer = PureNodes.registry { 1 }.getValue("timer")
        assertEquals(42L, withContext(LcncClockKey { 42L }) {
            timer.run(LcncNode("t", "timer"), emptyMap())["tick"]
        })
        assertEquals(1L, timer.run(LcncNode("t", "timer"), emptyMap())["tick"])
        assertEquals(null, currentCoroutineContext()[LcncClockKey])
    }
}
