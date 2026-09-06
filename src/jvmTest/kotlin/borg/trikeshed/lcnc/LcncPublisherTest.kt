package borg.trikeshed.lcnc

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.json.JsonSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE BOARD IS THE AUTHORITY ([LcncPublisher], [LcncBlackboard]). A source
 * seeds an entry and overwrites it only when the source itself changes; an
 * entry edited on the board is obeyed as edited; an entry with no source is
 * obeyed too, and a raw one is reconciled so the board never holds an untyped
 * cable. No attachments here: the corpus is the presets, and the board.
 */
class LcncPublisherTest {

    private fun publisher(board: ConfixBlackboard) = LcncPublisher(board, { emptyMap() }, null)

    private fun program(name: String, value: String) = LcncProgram(
        name,
        listOf(LcncNode("t", "text.value", params = mapOf("value" to value)), LcncNode("d", "display")).toSeries(),
        listOf(LcncWire("t", "value", "d", "x")).toSeries(),
    )

    @Test
    fun loadingAPresetSeedsItsEntryWithTypedCablesAndTheSourceCid() {
        val board = ConfixBlackboard.empty()
        val loaded = publisher(board).load("preset-scope-inner")
        assertNotNull(loaded)
        val entry = board.get(LcncBlackboard.programKey("preset-scope-inner")) as Map<*, *>
        assertTrue(LcncBlackboard.isReconciled(entry), "$entry")
        assertEquals(LcncBlackboard.cidOf(loaded), LcncBlackboard.sourceCidOf(entry))
        assertEquals(1, (entry["cables"] as List<*>).size)
    }

    @Test
    fun aBoardEditToASourcedProgramIsObeyedNotClobbered() {
        val board = ConfixBlackboard.empty()
        val pub = publisher(board)
        pub.load("preset-scope-inner")
        val key = LcncBlackboard.programKey("preset-scope-inner")
        val entry = board.get(key) as Map<*, *>
        // Edit the entry on the board: the scope.in default becomes "edited"; sourceCid untouched.
        val edited = LcncBlackboard.programOf(entry)!!.let { p ->
            val n0 = p.nodes[0]
            p.copy(nodes = listOf(n0.copy(params = n0.params + ("default" to "edited")), p.nodes[1]).toSeries())
        }
        board.put(key, LcncBlackboard.programEntry("preset-scope-inner", edited, LcncContracts.all().associateBy { it.type }, LcncBlackboard.sourceCidOf(entry)), "ide")
        val reloaded = pub.load("preset-scope-inner")!!
        assertEquals("edited", reloaded.nodes[0].params["default"], "the board's edit survives a load")
    }

    @Test
    fun aRawAssertedEntryIsReconciledSoTheBoardNeverHoldsAnUntypedCable() {
        val board = ConfixBlackboard.empty()
        val raw = mapOf(
            "name" to "asserted",
            "document" to JsonSupport.parse(LcncProgramConfix.toJson(program("asserted", "from-the-board"))),
            "cables" to emptyList<Any?>(),
            "violations" to emptyList<Any?>(),
        )
        board.put(LcncBlackboard.programKey("asserted"), raw, "ide")
        val loaded = publisher(board).load("asserted")
        assertNotNull(loaded)
        val entry = board.get(LcncBlackboard.programKey("asserted")) as Map<*, *>
        assertTrue(LcncBlackboard.isReconciled(entry))
        assertEquals("text", ((entry["cables"] as List<*>)[0] as Map<*, *>)["type"])
        assertNull(LcncBlackboard.sourceCidOf(entry), "no source: nothing can overwrite it")
    }

    @Test
    fun anEntryWithViolationsIsWhatTheRunSeamObeys() {
        val board = ConfixBlackboard.empty()
        val bad = LcncProgram(
            "bad",
            listOf(LcncNode("n2", "beliefs.introspect"), LcncNode("n3", "beliefs.review")).toSeries(),
            listOf(LcncWire("n2", "field", "n3", "facts")).toSeries(),
        )
        publisher(board).publishProgram("bad", bad)
        val v = LcncBlackboard.violationsOf(board.get(LcncBlackboard.programKey("bad")))!!
        assertEquals(1, v.size)
        assertEquals("kind-mismatch", v[0]["rule"])
    }

    @Test
    fun anUnchangedPublishWritesNothingAndAChangedOneWritesOnce() {
        val board = ConfixBlackboard.empty()
        var puts = 0
        val unsubscribe = board.subscribe { puts++ }
        val pub = publisher(board)
        pub.publishProgram("p", program("p", "one"))
        pub.publishProgram("p", program("p", "one"))
        assertEquals(1, puts, "a re-parsed identical entry is not a change")
        pub.publishProgram("p", program("p", "two"))
        assertEquals(2, puts)
        unsubscribe()
    }
}
