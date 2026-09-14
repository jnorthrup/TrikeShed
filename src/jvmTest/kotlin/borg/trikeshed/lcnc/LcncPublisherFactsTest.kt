package borg.trikeshed.lcnc

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteOp
import borg.trikeshed.dag.ReteStoredFact
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.j
import borg.trikeshed.lib.filter
import borg.trikeshed.lib.isEmpty
import borg.trikeshed.lib.firstOrNull
import borg.trikeshed.lib.map
import borg.trikeshed.lib.view
import borg.trikeshed.lib.toList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE PANELS PLANE ([PanelFacts], [PanelFactBridge], the [LcncPublisher] hook).
 * Daemon-free: a [ConfixBlackboard] and a [ReteNetwork] with an observer that
 * records every op. The board stays the authority; the facts are its
 * projection, one per program / node / cable / violation, versioned by the
 * document's cid, retracted when a republish drops them, silent when nothing
 * moved.
 */
class LcncPublisherFactsTest {

    private val panels = BlackboardContext(PlaneFacts.PANELS)

    private class Harness {
        val board = ConfixBlackboard.empty()
        val net = ReteNetwork()
        val ops = ArrayList<Pair<ReteOp, ReteStoredFact>>()
        init { net.observe { op, fact -> ops.add(op to fact) } }
        val publisher = LcncPublisher(board, { emptyMap() }, null, net)
        fun another() = LcncPublisher(board, { emptyMap() }, null, net)
        fun opsOf(op: ReteOp) = ops.filter { it.first == op }
    }

    private fun ReteNetwork.facts(kind: String) = workingMemory.query(BlackboardContext(PlaneFacts.PANELS), PlaneFacts.KIND j kind)
    private fun ReteNetwork.fact(localId: String) = workingMemory.facts(FactId(PlaneFacts.PANELS, localId)).firstOrNull()

    private fun preset(name: String): LcncProgram = LcncProgramConfix.fromJson(name, LcncPresets.all().getValue(name))

    private fun LcncProgram.dropLastWire(): LcncProgram =
        copy(wires = (0 until wires.size - 1).map { wires[it] }.toSeries())

    @Test
    fun cancelledPublicationStopsWaitingForTheNetwork(): Unit = runBlocking {
        val h = Harness()
        val locked = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val holder = launch(Dispatchers.IO) {
            h.net.snapshot {
                locked.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS)) { "Network reader was not released" }
            }
        }
        try {
            withTimeout(5_000) { locked.await() }
            val boardWritten = CompletableDeferred<Unit>()
            val unsubscribe = h.board.subscribe { boardWritten.complete(Unit) }
            val publication = async(Dispatchers.Default) {
                h.publisher.publishProgram("preset-scope-inner", preset("preset-scope-inner"))
            }
            try {
                withTimeout(5_000) {
                    boardWritten.await()
                    publication.cancelAndJoin()
                }
                assertTrue(h.net.facts(PanelFacts.KIND_PROGRAM).isEmpty())
            } finally { unsubscribe() }
        } finally {
            release.countDown()
            holder.join()
        }
        h.publisher.load("preset-scope-inner")
        assertNotNull(h.net.fact(PanelFacts.programLocalId("preset-scope-inner")), "Load must repair an interrupted projection")
    }

    @Test
    fun concurrentPublishersLeaveTheLatestBoardVersionInTheNetwork(): Unit = runBlocking {
        val h = Harness()
        val name = "preset-scope-inner"
        val first = preset(name)
        val latest = first.dropLastWire()
        val locked = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val holder = launch(Dispatchers.IO) {
            h.net.snapshot {
                locked.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS)) { "Network reader was not released" }
            }
        }
        try {
            withTimeout(5_000) { locked.await() }
            supervisorScope {
                val a = async(start = CoroutineStart.UNDISPATCHED) { h.publisher.publishProgram(name, first) }
                val b = async(start = CoroutineStart.UNDISPATCHED) { h.another().publishProgram(name, latest) }
                release.countDown()
                withTimeout(5_000) { a.await(); b.await() }
            }
            assertEquals(LcncBlackboard.cidOf(latest), h.publisher.boardProgramCid(name))
            val facts = h.net.workingMemory.query(panels, PlaneFacts.KEY j name)
            assertTrue(facts.view.all { it.versionCid.value == LcncBlackboard.cidOf(latest) })
            assertTrue(h.net.facts(PanelFacts.KIND_CABLE).isEmpty(), "The removed cable must not survive either publisher")
        } finally {
            release.countDown()
            holder.join()
        }
    }

    @Test
    fun loadingAPresetLandsOneFactPerNodeAndCableWithTheExactType(): Unit = runBlocking {
        val h = Harness()
        val name = "preset-scope-inner"
        val program = h.publisher.load(name)
        assertNotNull(program)
        val entry = h.board.get(LcncBlackboard.programKey(name)) as Map<*, *>

        val cables = h.net.facts(PanelFacts.KIND_CABLE)
        assertEquals(program.wires.size, cables.size, "one cable fact per wire")
        val nodes = h.net.facts(PanelFacts.KIND_NODE)
        assertEquals(PanelFacts.flattenedNodeCount(program), nodes.size, "one node fact per flattened node")
        assertEquals(1, h.net.facts(PanelFacts.KIND_PROGRAM).size)

        // Every cable fact carries the type the entry recorded: the exact CCEK type.
        val entryCables = entry["cables"] as List<*>
        val checked = LcncTypeCheck.cableTypes(program, h.publisher.vocabulary())
        for (i in 0 until program.wires.size) {
            val f = h.net.fact(PanelFacts.cableLocalId(name, i))
            assertNotNull(f, "cable $i")
            val w = program.wires[i]
            assertEquals(w.fromNode, f.fields["fromNode"]); assertEquals(w.fromPort, f.fields["fromPort"])
            assertEquals(w.toNode, f.fields["toNode"]); assertEquals(w.toPort, f.fields["toPort"])
            assertEquals((entryCables[i] as Map<*, *>)["type"], f.fields["type"], "cable $i type is the entry's")
            assertEquals(checked[i], f.fields["type"], "cable $i type is what the checker resolves")
            assertEquals(name, f.fields[PlaneFacts.KEY], "the inverse pointer is the program name")
            assertEquals("lcnc", f.fields[PlaneFacts.ACTOR])
        }
        // scope-inner is a ring BODY published on its own: its scope.in -> scope.out cable has no enclosing
        // ring to resolve against, so the checker's exact answer is null and the fact carries exactly that.
        assertNull(cables.view.single().fields["type"], "an unresolved ring port is null on the entry and null on the fact")

        // The program fact counts what it fanned out to and points back at the source.
        val programFact = h.net.fact(PanelFacts.programLocalId(name))!!
        assertEquals(nodes.size, programFact.fields["nodes"])
        assertEquals(cables.size, programFact.fields["cables"])
        assertEquals(0, programFact.fields["violations"])
        assertEquals(LcncBlackboard.sourceCidOf(entry), programFact.fields["sourceCid"])

        // One version for the whole program: the source's cid.
        val version = ContentId(LcncBlackboard.sourceCidOf(entry)!!)
        val all = h.net.workingMemory.query(panels, PlaneFacts.KEY j name)
        assertEquals(1 + nodes.size + cables.size, all.size)
        assertTrue(all.view.all { it.versionCid == version }, "every fact of the program carries the document's cid")

        // Only asserts happened, exactly one per fact.
        assertEquals(all.size, h.ops.size)
        assertTrue(h.ops.all { it.first == ReteOp.ASSERT })
        assertEquals(all.size.toLong(), h.publisher.panelFacts!!.opsApplied)
    }

    @Test
    fun everyPresetExplodesToItsOwnCountsWithTheCheckersTypes(): Unit = runBlocking {
        val h = Harness()
        val vocabulary = h.publisher.vocabulary()
        for ((name, _) in LcncPresets.all()) {
            val program = preset(name)
            val entry = h.publisher.publishProgram(name, program, vocabulary)
            val cables = h.net.workingMemory.query(panels, PlaneFacts.KEY j name).filter { it.fields[PlaneFacts.KIND] == PanelFacts.KIND_CABLE }
            val nodes = h.net.workingMemory.query(panels, PlaneFacts.KEY j name).filter { it.fields[PlaneFacts.KIND] == PanelFacts.KIND_NODE }
            val violations = h.net.workingMemory.query(panels, PlaneFacts.KEY j name).filter { it.fields[PlaneFacts.KIND] == PanelFacts.KIND_VIOLATION }
            assertEquals(program.wires.size, cables.size, "$name cables")
            assertEquals(PanelFacts.flattenedNodeCount(program), nodes.size, "$name nodes")
            assertEquals((entry["violations"] as List<*>).size, violations.size, "$name violations")
            val checked = LcncTypeCheck.cableTypes(program, vocabulary)
            for (i in 0 until program.wires.size) {
                assertEquals(checked[i], h.net.fact(PanelFacts.cableLocalId(name, i))!!.fields["type"], "$name cable $i")
            }
            val programFact = h.net.fact(name)!!
            assertEquals(violations.size, programFact.fields["violations"], "$name program fact counts its violations")
        }
        // Across the corpus the exact-type rule is watchable: resolved cables carry a real CCEK type, `type=<kind>`.
        val typed = h.net.facts(PanelFacts.KIND_CABLE).view.mapNotNull { it.fields["type"] as? String }
        assertTrue(typed.isNotEmpty(), "no cable in the whole corpus resolved to a type")
        assertTrue(typed.all { it.isNotBlank() }, "$typed")
        // No program's facts leaked into another's: every fact's key is a preset name and every preset has a program fact.
        val presetNames = LcncPresets.all().keys
        assertEquals(presetNames, h.net.facts(PanelFacts.KIND_PROGRAM).map { it.fields[PlaneFacts.KEY] }.toSet())
    }

    @Test
    fun nodesInsideRingsCarryTheirEnclosingRingAsParent(): Unit = runBlocking {
        val h = Harness()
        val name = "preset-scope"
        val program = preset(name)
        h.publisher.publishProgram(name, program)
        fun parentOf(id: String) = h.net.fact(PanelFacts.nodeLocalId(name, id))!!.fields["parent"]
        assertNull(parentOf("n0"), "a root node has no parent")
        assertNull(parentOf("r1"))
        assertEquals("r1", parentOf("r2"), "r2 sits inside r1")
        assertEquals("r1", parentOf("q"))
        assertEquals("r2", parentOf("p"), "p sits inside r2 inside r1")
        assertEquals(LcncContracts.SCOPE, h.net.fact(PanelFacts.nodeLocalId(name, "r2"))!!.fields["type"])
    }

    @Test
    fun aViolationIsAFactWithTheCheckersColumns(): Unit = runBlocking {
        val h = Harness()
        val bad = LcncProgram(
            "bad",
            listOf(LcncNode("n2", "beliefs.introspect"), LcncNode("n3", "beliefs.review")).toSeries(),
            listOf(LcncWire("n2", "field", "n3", "facts")).toSeries(),
        )
        h.publisher.publishProgram("bad", bad)
        val v = h.net.facts(PanelFacts.KIND_VIOLATION)
        assertEquals(1, v.size)
        assertEquals("kind-mismatch", v[0].fields["rule"])
        assertEquals("n2", v[0].fields["fromNode"]); assertEquals("field", v[0].fields["fromPort"])
        assertEquals("n3", v[0].fields["toNode"]); assertEquals("facts", v[0].fields["toPort"])
        assertEquals(0, v[0].fields["violation"])
        assertEquals("bad", v[0].fields[PlaneFacts.KEY])
        assertEquals(1, h.net.fact("bad")!!.fields["violations"])
    }

    @Test
    fun republishingTheSameProgramIsSilent(): Unit = runBlocking {
        val h = Harness()
        val name = "preset-scope-inner"
        val program = h.publisher.load(name)!!
        h.publisher.publishAll()
        val before = h.ops.size
        val opsBefore = h.publisher.panelFacts!!.opsApplied
        assertTrue(before > 0, "the explicitly loaded program reached Rete")
        assertNull(h.net.fact("preset-shake"), "catalog examples are not installed by publishAll")

        h.publisher.load(name)
        h.publisher.publishProgram(name, program)
        h.publisher.publishAll()

        assertEquals(before, h.ops.size, "a board-identical republish reaches no observer: ${h.ops.drop(before)}")
        assertEquals(opsBefore, h.publisher.panelFacts!!.opsApplied)
        assertEquals(0L, h.net.observerFailures)
    }

    @Test
    fun droppingAWireRetractsItsCableAndModifiesTheProgramFact(): Unit = runBlocking {
        val h = Harness()
        val name = "preset-scope-inner"
        val program = h.publisher.load(name)!!
        val entry = h.board.get(LcncBlackboard.programKey(name))
        val sourceCid = LcncBlackboard.sourceCidOf(entry)
        val last = program.wires.size - 1
        val lastCableId = PanelFacts.cableLocalId(name, last)
        assertNotNull(h.net.fact(lastCableId))
        val programVersionBefore = h.net.fact(name)!!.versionCid
        h.ops.clear()

        // The board edit keeps its sourceCid (LcncPublisherTest: obeyed, not clobbered) — the facts follow the entry.
        h.publisher.publishProgram(name, program.dropLastWire(), sourceCid = sourceCid)

        assertNull(h.net.fact(lastCableId), "the dropped wire's cable fact is gone")
        assertEquals(program.wires.size - 1, h.net.facts(PanelFacts.KIND_CABLE).size)
        val retracted = h.opsOf(ReteOp.RETRACT)
        assertEquals(listOf(PlaneFacts.PANELS to lastCableId), retracted.map { it.second.factId.pair })
        assertEquals(program.wires.size - 1, h.net.fact(name)!!.fields["cables"], "the program fact counts one cable fewer")
        assertTrue(h.opsOf(ReteOp.MODIFY).any { it.second.factId.b == name }, "the program fact was modified, not re-asserted")
        assertTrue(h.opsOf(ReteOp.ASSERT).isEmpty(), "no fact was asserted anew: ${h.opsOf(ReteOp.ASSERT)}")
        // Nodes did not change, so no node fact was touched.
        assertTrue(h.ops.none { it.second.fields[PlaneFacts.KIND] == PanelFacts.KIND_NODE }, "${h.ops}")
        // Same sourceCid => same version on the modified fact (the entry's cid is the version by owner decision).
        assertEquals(programVersionBefore, h.net.fact(name)!!.versionCid)
        assertEquals(h.net.workingMemory.query(panels, PlaneFacts.KEY j name).map { it.factId.b }.toSet(), h.publisher.panelFacts!!.knownLocalIds(name))
    }

    @Test
    fun aDifferentDocumentMovesEveryFactsVersionToTheNewCid(): Unit = runBlocking {
        val h = Harness()
        val name = "preset-scope-inner"
        val program = h.publisher.load(name)!!
        val n0 = program.nodes[0]
        val edited = program.copy(nodes = listOf(n0.copy(params = n0.params + ("default" to "edited")), program.nodes[1]).toSeries())
        h.ops.clear()

        // No sourceCid given: the version is the cid of the edited program's own JSON.
        h.publisher.publishProgram(name, edited)

        val version = ContentId(LcncBlackboard.cidOf(edited))
        val all = h.net.workingMemory.query(panels, PlaneFacts.KEY j name)
        assertTrue(all.view.all { it.versionCid == version }, "every fact re-versioned: ${all.map { it.versionCid }}")
        assertEquals(all.size, h.opsOf(ReteOp.MODIFY).size, "each fact modified once")
        assertTrue(h.opsOf(ReteOp.ASSERT).isEmpty() && h.opsOf(ReteOp.RETRACT).isEmpty())
        assertEquals(version.value, h.net.fact(name)!!.fields["sourceCid"])
    }

    @Test
    fun aSecondPublisherOverTheSameNetworkIsSilentOnTheSameEntryAndStillRetracts(): Unit = runBlocking {
        val h = Harness()
        val name = "preset-scope-inner"
        val program = h.publisher.load(name)!!
        val sourceCid = LcncBlackboard.sourceCidOf(h.board.get(LcncBlackboard.programKey(name)))
        h.ops.clear()

        // The daemon holds two publishers over one board (OroborosDaemon, KanbanModule): the second must not double-publish.
        val second = h.another()
        second.publishProgram(name, program, sourceCid = sourceCid)
        assertTrue(h.ops.isEmpty(), "the second publisher re-derived identical facts: ${h.ops}")

        // ...and it retracts what the FIRST publisher asserted, because it seeds its known set from the network.
        second.publishProgram(name, program.dropLastWire(), sourceCid = sourceCid)
        val lastCableId = PanelFacts.cableLocalId(name, program.wires.size - 1)
        assertEquals(listOf(lastCableId), h.opsOf(ReteOp.RETRACT).map { it.second.factId.b })
        assertNull(h.net.fact(lastCableId))
    }

    @Test
    fun retractingAProgramRemovesEveryOneOfItsFactsAndNothingElse(): Unit = runBlocking {
        val h = Harness()
        h.publisher.load("preset-scope-inner")
        h.publisher.load("preset-scope")
        val before = h.net.workingMemory.query(panels, PlaneFacts.KEY j "preset-scope").size
        assertTrue(before > 0)
        h.publisher.panelFacts!!.retract("preset-scope-inner")
        assertTrue(h.net.workingMemory.query(panels, PlaneFacts.KEY j "preset-scope-inner").isEmpty())
        assertEquals(before, h.net.workingMemory.query(panels, PlaneFacts.KEY j "preset-scope").size)
        assertTrue(h.publisher.panelFacts!!.knownLocalIds("preset-scope-inner").isEmpty())
    }

    @Test
    fun aPublisherWithoutANetworkPublishesToTheBoardOnly(): Unit = runBlocking {
        val board = ConfixBlackboard.empty()
        val pub = LcncPublisher(board, { emptyMap() }, null)
        assertNull(pub.panelFacts)
        assertNotNull(pub.load("preset-scope-inner"))
        assertTrue(LcncBlackboard.isReconciled(board.get(LcncBlackboard.programKey("preset-scope-inner"))))
    }

    @Test
    fun explodeIsPureAndDeterministic() {
        val program = preset("preset-scope")
        val entry = LcncBlackboard.programEntry("preset-scope", program, LcncContracts.all().associateBy { it.type })
        val a = PanelFacts.explode("preset-scope", program, entry)
        val b = PanelFacts.explode("preset-scope", program, entry)
        assertEquals(a.size, b.size)
        for (i in 0 until a.size) {
            assertEquals(a[i].factId.pair, b[i].factId.pair)
            assertEquals(a[i].fields, b[i].fields)
            assertEquals(a[i].versionCid, b[i].versionCid)
            assertEquals(a[i].board, b[i].board)
        }
        assertEquals(a.map { it.factId.b }.toSet().size, a.size, "localIds are unique")
        assertTrue(a.view.all { it.factId.a == PlaneFacts.PANELS && it.board.id == PlaneFacts.PANELS })
        assertTrue(a.view.all { it.fields[PlaneFacts.KEY] == "preset-scope" && it.fields[PlaneFacts.KIND] != null })
        // KIF and RDF projections apply to every panels fact: arity-3 tuples, and a (kind <iri> cable) for each cable.
        val kif = a.view.flatMap { PlaneFacts.toKif(it).view }
        assertTrue(kif.all { (it as borg.trikeshed.kif.KifExpr.ListExpr).elements.size == 3 })
        assertEquals(program.wires.size, kif.count { it.toKifString().startsWith("(kind ") && it.toKifString().endsWith(" cable)") })
    }
}
