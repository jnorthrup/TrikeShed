package borg.trikeshed.lcnc

import borg.trikeshed.parse.reify

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.OroborosAttachmentRef
import borg.trikeshed.lib.*
import kotlinx.coroutines.runBlocking
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

    @Test
    fun scopeDefaultPresenceIsPartOfTheServedParameterContract() {
        val payload = publisher(ConfixBlackboard.empty()).vocabularyPayload()
        val contract = (payload["contracts"] as List<*>).map { it as Map<*, *> }
            .single { it["type"] == LcncContracts.SCOPE_IN }
        val params = contract["params"] as Map<*, *>
        assertEquals(true, (params["default"] as Map<*, *>)["optional"],
            "absence must survive an editor load; an empty default is an explicit author choice")
        assertEquals(false, (params["name"] as Map<*, *>)["optional"])
    }

    private fun program(name: String, value: String) = LcncProgram(
        name,
        listOf(LcncNode("t", "text.value", params = mapOf("value" to value)), LcncNode("d", "display")).toSeries(),
        listOf(LcncWire("t", "value", "d", "x")).toSeries(),
    )

    @Test
    fun publishingTheCatalogDoesNotInstallExamples(): Unit = runBlocking {
        val board = ConfixBlackboard.empty()
        val pub = publisher(board)
        pub.publishAll()
        for (name in LcncPresets.all().keys) assertNull(board.get(LcncBlackboard.programKey(name)))
        assertNotNull(board.get("lcnc/vocabulary"))
        pub.load("preset-scope-inner")
        val entry = board.get(LcncBlackboard.programKey("preset-scope-inner")) as Map<*, *>
        assertEquals("preset", entry["sourceKind"])
        pub.publishAll()
        assertEquals(entry, board.get(LcncBlackboard.programKey("preset-scope-inner")))
        assertNull(board.get(LcncBlackboard.programKey("preset-shake")))
    }

    @Test
    fun savedProgramsWinEvenWhenTheirNamesMatchExamples(): Unit = runBlocking {
        val cas = CasStore.inMemory()
        val attachments = CouchAttachmentGateway(CouchStoreFactory.casBacked(cas), cas)
        val board = ConfixBlackboard.empty()
        val pub = LcncPublisher(board, { emptyMap() }, attachments)
        for (name in listOf("preset-scope-inner", "preset-user-work")) {
            val bytes = LcncProgramConfix.toJson(program(name, "saved by user")).encodeToByteArray()
            attachments.putAttachment(OroborosAttachmentRef(
                "panels/$name", "application/json", bytes.size.toLong(), ContentId.of(bytes), "user", "one", 1L,
            ), bytes)
        }
        pub.publishAll()
        for (name in listOf("preset-scope-inner", "preset-user-work")) {
            assertEquals("saved by user", pub.source(name)!!.nodes[0].params["value"])
            assertEquals("saved by user", pub.load(name)!!.nodes[0].params["value"])
            assertNull((board.get(LcncBlackboard.programKey(name)) as Map<*, *>)["sourceKind"])
            assertNotNull(attachments.getAttachment("panels/$name"))
        }
    }

    @Test
    fun loadingAPresetSeedsItsEntryWithTypedCablesAndTheSourceCid(): Unit = runBlocking {
        val board = ConfixBlackboard.empty()
        val loaded = publisher(board).load("preset-scope-inner")
        assertNotNull(loaded)
        val entry = board.get(LcncBlackboard.programKey("preset-scope-inner")) as Map<*, *>
        assertTrue(LcncBlackboard.isReconciled(entry), "$entry")
        assertEquals(LcncBlackboard.cidOf(loaded), LcncBlackboard.sourceCidOf(entry))
        assertEquals(1, (entry["cables"] as List<*>).size)
    }

    @Test
    fun aBoardEditToASourcedProgramIsObeyedNotClobbered(): Unit = runBlocking {
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
    fun aRawAssertedEntryIsReconciledSoTheBoardNeverHoldsAnUntypedCable(): Unit = runBlocking {
        val board = ConfixBlackboard.empty()
        val raw = mapOf(
            "name" to "asserted",
            "document" to reify(LcncProgramConfix.toJson(program("asserted", "from-the-board"))),
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
    fun anEntryWithViolationsIsWhatTheRunSeamObeys(): Unit = runBlocking {
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
    fun anUnchangedPublishWritesNothingAndAChangedOneWritesOnce(): Unit = runBlocking {
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

    @Test
    fun changingANamedSignatureRetypesItsCallerWithoutOverwritingBoardEdits(): Unit = runBlocking {
        val board = ConfixBlackboard.empty()
        val pub = publisher(board)
        val caller = LcncProgram("caller", s_[
            LcncNode("ring", "scope", mapOf("program" to "body")), LcncNode("show", "display"),
        ], s_[LcncWire("ring", "result", "show", "x")])
        fun corpus(kind: String): LcncPublisher.LateBound {
            val body = LcncProgram("body", s_[
                LcncNode("result", "scope.out", mapOf("name" to "result", "kind" to kind)),
            ], emptySeriesOf())
            val corpus = mapOf("body" to body, "caller" to caller)
            val vocabulary = LcncVocabulary.resolve(corpus)
            return LcncPublisher.LateBound(corpus, vocabulary, emptyList(), LcncFacts.of(vocabulary.values))
        }
        val initial = corpus("text")
        pub.publishPrograms(initial)
        val sourceCid = LcncBlackboard.cidOf(caller)
        val edited = caller.copy(nodes = s_[
            caller.nodes[0].copy(params = caller.nodes[0].params + ("note" to "board edit")), caller.nodes[1],
        ])
        pub.publishProgram("caller", edited, initial.vocabulary, sourceCid)
        pub.publishPrograms(corpus("id"))
        val entry = board.get(LcncBlackboard.programKey("caller")) as Map<*, *>
        assertEquals("id", ((entry["cables"] as List<*>).single() as Map<*, *>)["type"])
        assertEquals("board edit", LcncBlackboard.programOf(entry)!!.nodes[0].params["note"])
        assertEquals(sourceCid, LcncBlackboard.sourceCidOf(entry))
    }
}
