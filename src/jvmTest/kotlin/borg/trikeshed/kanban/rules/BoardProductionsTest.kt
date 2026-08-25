package borg.trikeshed.kanban.rules

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.Activation
import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProduction
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardProductionsTest {

    private val board = BlackboardContext("board")

    private suspend fun card(
        net: ReteNetwork,
        jobId: String,
        column: String,
        deps: List<String> = emptyList(),
        revision: Long = 1L,
        lastMoveMs: Long = 0L,
        version: String = "$jobId-v$revision",
    ) {
        val factId = FactId("board", jobId)
        val fields = mapOf(
            "kind" to "card", "jobId" to jobId, "column" to column,
            "dependencies" to deps, "revision" to revision, "lastMoveMs" to lastMoveMs,
        )
        val cid = ContentId.of(version.encodeToByteArray())
        if (net.workingMemory.facts(factId).isEmpty()) net.assert(factId, fields, cid, board)
        else net.modify(factId, fields, cid)
    }

    private fun sink(net: ReteNetwork): MutableList<Activation> {
        val fired = ArrayList<Activation>()
        net.productionSink = { fired.add(it) }
        return fired
    }

    @Test
    fun cardRuleFiresWithZeroSubmittedJobs_starvationBugDead() = runBlocking {
        val net = ReteNetwork()
        net.register(WipBreachProduction())
        val fired = sink(net)
        // NOT ONE fact carries lifecycle=submitted — the old gate would have starved this rule forever.
        for (i in 1..4) card(net, "r$i", "running")
        assertTrue(fired.any { it.ruleId == BoardRules.WIP_BREACH }, "wip-breach must fire without any submitted job")
        val breach = fired.first { it.ruleId == BoardRules.WIP_BREACH }
        assertEquals("running", breach.bindings["column"])
        assertEquals("4", breach.bindings["count"])
    }

    @Test
    fun dependencyReadyFires_andRetractionTakesItBack() = runBlocking {
        val net = ReteNetwork()
        net.register(DependencyReadyProduction())
        val fired = sink(net)
        card(net, "dep", "done")
        card(net, "child", "todo", deps = listOf("dep"))
        assertEquals(1, fired.count { it.ruleId == BoardRules.DEPENDENCY_READY })
        assertEquals("child", fired.first { it.ruleId == BoardRules.DEPENDENCY_READY }.bindings["jobId"])

        // Retract the dependency: refraction was invalidated by support, so when the
        // situation RECURS (dep re-asserted as done) the rule fires AGAIN — un-fire + re-fire.
        net.retract(FactId("board", "dep"))
        fired.clear()
        card(net, "dep", "done", version = "dep-v2")
        assertEquals(1, fired.count { it.ruleId == BoardRules.DEPENDENCY_READY }, "re-fire after retract+re-assert")
    }

    @Test
    fun stallFiresOnTickFact_refractionHoldsUntilNextMove() = runBlocking {
        val net = ReteNetwork()
        net.register(StallProduction(thresholdMs = 1000L))
        val fired = sink(net)
        card(net, "slow", "running", lastMoveMs = 0L)
        val now = FactId("board", "now")
        net.assert(now, mapOf("kind" to "now", "ms" to 5000L), ContentId.of("t1".encodeToByteArray()), board)
        assertEquals(1, fired.count { it.ruleId == BoardRules.STALL })
        // next tick: same lastMove → same activationId → refraction holds (no re-nag)
        net.modify(now, mapOf("kind" to "now", "ms" to 6000L), ContentId.of("t2".encodeToByteArray()))
        assertEquals(1, fired.count { it.ruleId == BoardRules.STALL }, "stall must not re-nag while the card stands still")
        // the card moves → new lastMoveMs → goes quiet, then stalls afresh later
        card(net, "slow", "running", lastMoveMs = 6000L, revision = 2, version = "slow-v2")
        net.modify(now, mapOf("kind" to "now", "ms" to 8000L), ContentId.of("t3".encodeToByteArray()))
        assertEquals(2, fired.count { it.ruleId == BoardRules.STALL }, "a fresh stall period fires once more")
    }

    @Test
    fun cycleGuardAuditsReplayedCycles() = runBlocking {
        val net = ReteNetwork()
        net.register(CycleGuardProduction())
        val fired = sink(net)
        card(net, "x", "todo", deps = listOf("y"))
        card(net, "y", "todo", deps = listOf("x"))
        val cycles = fired.filter { it.ruleId == BoardRules.CYCLE_GUARD }
        assertTrue(cycles.isNotEmpty(), "replayed cycle must surface")
        assertEquals("x,y", cycles.first().bindings["cycle"])
    }

    @Test
    fun perfGuard_noiseFactsNeverEnterEvaluate() = runBlocking {
        val net = ReteNetwork()
        var evaluations = 0
        net.register(object : ReteProduction {
            override val ruleId = "counting-card-rule"
            override val salience = 10
            override val interests: Series<Join<String, Any?>> = 1 j { _: Int -> "kind" j ("card" as Any?) }
            override fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit) {
                evaluations++
            }
        })
        for (i in 1..1000) {
            net.assert(
                FactId("board", "noise$i"), mapOf("kind" to "noise", "n" to i),
                ContentId.of("noise$i".encodeToByteArray()), board,
            )
        }
        assertEquals(0, evaluations, "a card-interest rule must never evaluate for non-card facts")
        card(net, "one", "todo")
        assertTrue(evaluations > 0)
    }

    @Test
    fun lateRegistrationSeesExistingFacts() = runBlocking {
        val net = ReteNetwork()
        val fired = sink(net)
        for (i in 1..4) card(net, "r$i", "running")
        // module attaches AFTER the facts landed: register must recount, next evaluation fires
        net.register(WipBreachProduction())
        card(net, "r5", "running")
        assertTrue(fired.any { it.ruleId == BoardRules.WIP_BREACH }, "late-registered rule must see pre-existing facts")
    }

    @Test
    fun jobDependencyExtractionStillReachesAgenda() = runBlocking {
        val net = ReteNetwork()
        net.assert(
            FactId("jobs", "j1"),
            mapOf("lifecycle" to "submitted", "jobId" to "j1", "dependencies" to emptyList<String>(), "revision" to 1L),
            ContentId.of("j1v1".encodeToByteArray()),
            BlackboardContext("jobs"),
        )
        val activation = net.agenda.popNext()
        assertEquals("start-job", activation?.ruleId, "the extracted job production must still start dependency-free submitted jobs")
    }
}
