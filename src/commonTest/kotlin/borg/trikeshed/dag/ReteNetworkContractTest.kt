package borg.trikeshed.dag

import borg.trikeshed.cursor.blackboardContext
import borg.trikeshed.graph.CausalGraphNode
import borg.trikeshed.graph.CausalGraphNodeIndex
import borg.trikeshed.graph.causalGraphNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P1 — Production-rule Rete network RED tests.
 *
 * The plan defines these as required:
 *   - Working memory: assert, modify, retract by stable fact ID and version CID
 *   - Alpha network: schema-facet predicate sharing; no repeated full rule scans
 *   - Beta network: indexed joins and token memories keyed by join facets
 *   - Agenda: deterministic total order: salience, recency sequence, activation ID
 *   - Refraction: one rule/fact-version tuple fires once unless support changes
 *   - Truth maintenance: derived facts carry support and retract transitively
 *   - Partitioning: workspace/job partition prevents cross-board joins
 *   - Replay: identical committed input yields identical facts and activation order
 *
 * None of these types exist yet. Every test fails to compile until ReteNetwork
 * is implemented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReteWorkingMemoryTest {

    private fun board(id: String) = blackboardContext(id = id)

    @Test
    fun assertModifyRetractByFactIdAndVersionCid() {
        val wm = ReteWorkingMemory()
        val fid = FactId("board-a", "node-1")
        val cid1 = ContentId.of("v1".encodeToByteArray())

        // assert
        wm.assert(fid, mapOf("status" to "ready"), cid1, board("board-a"))
        assertEquals(1, wm.facts(fid).size)
        assertEquals("ready", wm.facts(fid)[0].fields["status"])

        // modify — new version CID
        val cid2 = ContentId.of("v2".encodeToByteArray())
        wm.modify(fid, mapOf("status" to "active"), cid2)
        assertEquals(1, wm.facts(fid).size, "modify replaces, not appends")
        assertEquals("active", wm.facts(fid)[0].fields["status"])

        // retract
        wm.retract(fid)
        assertEquals(0, wm.facts(fid).size, "retract removes the fact")
    }

    @Test
    fun duplicateAssertDoesNotEnqueueSecondAssertion() {
        val wm = ReteWorkingMemory()
        val fid = FactId("board-a", "node-1")
        val cid = ContentId.of("v1".encodeToByteArray())

        val r1 = wm.assert(fid, mapOf("s" to "x"), cid, board("board-a"))
        val r2 = wm.assert(fid, mapOf("s" to "x"), cid, board("board-a"))

        assertTrue(r1.isNew, "first assert must be new")
        assertTrue(!r2.isNew, "duplicate assert must not be new")
        assertEquals(1, wm.facts(fid).size)
    }

    @Test
    fun partitioningPreventsCrossBoardQueries() {
        val wm = ReteWorkingMemory()
        wm.assert(FactId("board-a", "n1"), mapOf("x" to 1), ContentId.of("a".encodeToByteArray()), board("board-a"))
        wm.assert(FactId("board-b", "n2"), mapOf("x" to 1), ContentId.of("b".encodeToByteArray()), board("board-b"))

        assertEquals(1, wm.query(board("board-a"), "x" to 1).size)
        assertEquals(1, wm.query(board("board-b"), "x" to 1).size)
        assertEquals(0, wm.query(board("board-c"), "x" to 1).size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReteJoinTest {

    @Test
    fun betaJoinMatchesOnFacetAndRemembersTokens() {
        val net = ReteNetwork(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            capacity = 64,
        )

        // Rule: if job depends-on another job that is "closed", then this job is "ready"
        val rule = ReteProductionRule(
            ruleId = "dep-satisfied",
            salience = 100,
            conditions = listOf(
                Condition(Facet("jobId", "depender"), Comparison.DependsOn, Facet("jobId", "dependee")),
                Condition(Facet("status", "dependee"), Comparison.Equals, "closed"),
            ),
            action = { bindings -> ReteAction(
                assertion = FactId(bindings.board.id, bindings["jobId"]!!),
                fields = mapOf("status" to "ready"),
            )},
        )
        net.addRule(rule)

        // Assert dependee is closed
        net.assert(FactId("board-a", "dep-1"), mapOf("jobId" to "dep-1", "status" to "closed"),
            ContentId.of("dep1".encodeToByteArray()), board("board-a"))

        // Assert depender depends-on dependee — this should trigger the join
        net.assert(FactId("board-a", "job-1"), mapOf("jobId" to "job-1", "dependsOn" to "dep-1"),
            ContentId.of("job1".encodeToByteArray()), board("board-a"))

        val activations = net.drainActivations()
        assertEquals(1, activations.size)
        assertEquals("dep-satisfied", activations[0].ruleId)
    }

    @Test
    fun existsAndNotActivationsFireOnChange() {
        val net = ReteNetwork(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            capacity = 64,
        )

        // Rule: if job exists and NOT hasBlocker → fire
        val rule = ReteProductionRule(
            ruleId = "unblocked",
            salience = 50,
            conditions = listOf(
                Condition(Facet("jobId", "*"), Comparison.Exists, null),
                Condition(Facet("hasBlocker", "*"), Comparison.NotExists, null),
            ),
            action = { bindings -> ReteAction(
                assertion = FactId(bindings.board.id, bindings["jobId"]!!),
                fields = mapOf("status" to "unblocked"),
            )},
        )
        net.addRule(rule)

        net.assert(FactId("board-a", "j1"), mapOf("jobId" to "j1"),
            ContentId.of("j1".encodeToByteArray()), board("board-a"))

        val activations = net.drainActivations()
        assertEquals(1, activations.size, "exists+notExists must fire")
    }
}

class ReteAgendaTest {

    @Test
    fun deterministicTotalOrderBySalienceThenRecencyThenId() {
        val agenda = ReteAgenda()

        // Add in reverse salience order
        agenda.add(Activation(
            activationId = "a3", ruleId = "r3", ruleVersionCid = ContentId.of("r3".encodeToByteArray()),
            salience = 10, sequence = 3,
            supportCids = emptyList(), bindings = emptyMap(),
        ))
        agenda.add(Activation(
            activationId = "a1", ruleId = "r1", ruleVersionCid = ContentId.of("r1".encodeToByteArray()),
            salience = 100, sequence = 1,
            supportCids = emptyList(), bindings = emptyMap(),
        ))
        agenda.add(Activation(
            activationId = "a2", ruleId = "r2", ruleVersionCid = ContentId.of("r2".encodeToByteArray()),
            salience = 100, sequence = 2,
            supportCids = emptyList(), bindings = emptyMap(),
        ))

        val order = agenda.popNext()!!.activationId
        assertEquals("a1", order, "highest salience first")
        val order2 = agenda.popNext()!!.activationId
        assertEquals("a2", order2, "same salience, earlier sequence first")
        val order3 = agenda.popNext()!!.activationId
        assertEquals("a3", order3, "lowest salience last")
    }
}

class ReteTruthMaintenanceTest {

    @Test
    fun derivedFactRetractsTransitivelyWhenSupportIsRemoved() {
        val net = ReteNetwork(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            capacity = 64,
        )

        val rule = ReteProductionRule(
            ruleId = "derive-ready",
            salience = 100,
            conditions = listOf(
                Condition(Facet("status", "*"), Comparison.Equals, "closed"),
            ),
            action = { bindings -> ReteAction(
                assertion = FactId(bindings.board.id, "derived-${bindings["status"]}"),
                fields = mapOf("status" to "ready"),
            )},
        )
        net.addRule(rule)

        // Assert support fact → derived fact appears
        net.assert(FactId("board-a", "dep-1"), mapOf("status" to "closed"),
            ContentId.of("v1".encodeToByteArray()), board("board-a"))

        assertEquals(1, net.drainActivations().size, "support fact fires rule")

        // Apply the activation → creates derived fact
        net.applyActivation(Activation(
            activationId = "act-1",
            ruleId = "derive-ready",
            ruleVersionCid = ContentId.of("r".encodeToByteArray()),
            salience = 100,
            sequence = 1,
            supportCids = listOf(ContentId.of("v1".encodeToByteArray())),
            bindings = mapOf("status" to "closed"),
        ))

        val derived = net.workingMemory.facts(FactId("board-a", "derived-closed"))
        assertEquals(1, derived.size, "derived fact must exist after applying activation")

        // Retract support → derived must retract transitively
        net.retract(FactId("board-a", "dep-1"))
        assertEquals(0, net.workingMemory.facts(FactId("board-a", "derived-closed")).size,
            "derived fact must retract transitively when support is removed")
    }

    @Test
    fun refractionPreventsDoubleFireForSameFactVersion() {
        val net = ReteNetwork(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            capacity = 64,
        )

        val rule = ReteProductionRule(
            ruleId = "once",
            salience = 100,
            conditions = listOf(Condition(Facet("x", "*"), Comparison.Equals, "1")),
            action = { bindings -> ReteAction(
                assertion = FactId(bindings.board.id, "out"),
                fields = mapOf("x" to "1"),
            )},
        )
        net.addRule(rule)

        val cid = ContentId.of("v1".encodeToByteArray())
        net.assert(FactId("board-a", "f1"), mapOf("x" to "1"), cid, board("board-a"))
        assertEquals(1, net.drainActivations().size, "first assert fires")

        // Same fact, same version — refraction prevents second activation
        net.assert(FactId("board-a", "f1"), mapOf("x" to "1"), cid, board("board-a"))
        assertEquals(0, net.drainActivations().size, "refraction prevents second fire for same fact+version")

        // New version — refraction resets
        val cid2 = ContentId.of("v2".encodeToByteArray())
        net.modify(FactId("board-a", "f1"), mapOf("x" to "1"), cid2)
        assertEquals(1, net.drainActivations().size, "new version breaks refraction")
    }
}

class ReteReplayTest {

    @Test
    fun identicalCommittedInputYieldsIdenticalActivationOrder() {
        // Run 1
        val net1 = ReteNetwork(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            capacity = 64,
        )
        net1.addRule(ReteProductionRule(
            ruleId = "r1", salience = 100,
            conditions = listOf(Condition(Facet("x", "*"), Comparison.Equals, "1")),
            action = { ReteAction(FactId("b", "o"), emptyMap()) },
        ))
        net1.assert(FactId("board-a", "f1"), mapOf("x" to "1"),
            ContentId.of("c1".encodeToByteArray()), board("board-a"))
        net1.assert(FactId("board-a", "f2"), mapOf("x" to "1"),
            ContentId.of("c2".encodeToByteArray()), board("board-a"))
        val run1 = net1.drainActivations().map { it.activationId }

        // Run 2 — identical input
        val net2 = ReteNetwork(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
            capacity = 64,
        )
        net2.addRule(ReteProductionRule(
            ruleId = "r1", salience = 100,
            conditions = listOf(Condition(Facet("x", "*"), Comparison.Equals, "1")),
            action = { ReteAction(FactId("b", "o"), emptyMap()) },
        ))
        net2.assert(FactId("board-a", "f1"), mapOf("x" to "1"),
            ContentId.of("c1".encodeToByteArray()), board("board-a"))
        net2.assert(FactId("board-a", "f2"), mapOf("x" to "1"),
            ContentId.of("c2".encodeToByteArray()), board("board-a"))
        val run2 = net2.drainActivations().map { it.activationId }

        assertEquals(run1, run2, "identical committed input must yield identical activation order")
    }
}
