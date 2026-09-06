package borg.trikeshed.lcnc

import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `for (item in each) { ring }` — the ring's iteration, beside `when?`'s `if`. */
class LcncEachRingTest {

    private val registry = mapOf(
        "items" to LcncNodeRunner { n, _ -> mapOf("xs" to n.params["v"].orEmpty().split(',').filter { it.isNotEmpty() }) },
        "upper" to LcncNodeRunner { _, inputs -> mapOf("y" to inputs["x"]?.toString()?.uppercase()) },
        "count" to LcncNodeRunner { _, inputs -> mapOf("n" to (inputs["xs"] as List<*>).size) },
    )

    private fun ringProgram(items: String) = LcncProgram(
        "each-ring",
        listOf(
            LcncNode("src", "items", params = mapOf("v" to items)),
            LcncNode("ring", LcncContracts.SCOPE, params = mapOf("item" to "word"), children = listOf(
                LcncNode("w", LcncContracts.SCOPE_IN, params = mapOf("name" to "word", "kind" to "text")),
                LcncNode("u", "upper"),
                LcncNode("out", LcncContracts.SCOPE_OUT, params = mapOf("name" to "loud", "kind" to "text")),
            ).toSeries()),
            LcncNode("n", "count"),
        ).toSeries(),
        listOf(
            LcncWire("src", "xs", "ring", "each?"),
            LcncWire("w", "value", "u", "x"),
            LcncWire("u", "y", "out", "value"),
            LcncWire("ring", "loud", "n", "xs"),
        ).toSeries(),
    )

    @Test
    fun eachRingRunsOncePerElementAndYieldsLists() = runBlocking {
        val res = LcncRunner(registry).runProcedure(ringProgram("a,b,c"))
        val ring = res.nodeOutputs.getValue("ring")
        assertEquals(listOf("A", "B", "C"), ring["loud"])
        assertEquals(3, ring["count"])
        assertEquals(listOf(mapOf("loud" to "A"), mapOf("loud" to "B"), mapOf("loud" to "C")), ring["returns"])
        assertEquals(3, res.nodeOutputs.getValue("n")["n"], "the list yield fed the next statement")
    }

    @Test
    fun anEmptyEachYieldsEmptyListsNotAbsence() = runBlocking {
        val ring = LcncRunner(registry).runProcedure(ringProgram("")).nodeOutputs.getValue("ring")
        assertEquals(emptyList<Any?>(), ring["loud"]); assertEquals(0, ring["count"])
    }

    @Test
    fun eachOnANonListIsLoud() = runBlocking {
        val bad = registry + mapOf("items" to LcncNodeRunner { _, _ -> mapOf("xs" to "not-a-list") })
        val e = assertFailsWith<IllegalArgumentException> { LcncRunner(bad).runProcedure(ringProgram("x")) }
        assertTrue("each must be a list" in e.message!!, e.message)
    }

    @Test
    fun eachRingKeepsTheNavigatorChainPerIterationAndHonoursTheLimit() = runBlocking {
        val runner = LcncRunner(registry)
        val chains = mutableListOf<String>()
        runner.onScopeEnter = { path, chain -> chains.add(path.joinToString("/") + "@" + chain.toString()) }
        val p = ringProgram("a,b,c,d,e")
        val limited = LcncProgram(p.name, p.nodes.toList().map { n -> if (n.id == "ring") n.copy(params = n.params + ("limit" to "2")) else n }.toSeries(), p.wires)
        val ring = runner.runProcedure(limited).nodeOutputs.getValue("ring")
        assertEquals(listOf("A", "B"), ring["loud"], "limit bounds the iterations")
        assertEquals(2, chains.size)
        assertEquals(1, chains.toSet().size, "one chain per ring, not per iteration")
    }
}
