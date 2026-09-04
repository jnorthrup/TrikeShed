package borg.trikeshed.kanban.rules

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.Activation
import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.BoardCol
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The board claims its own READY work — oldest first, under the RUNNING WIP limit, once per revision. */
class ClaimProductionTest {

    private val board = BlackboardContext("board")

    private suspend fun card(
        net: ReteNetwork,
        jobId: String,
        column: String,
        revision: Long = 1L,
        lastMoveMs: Long = 0L,
        owner: String = "",
        version: String = "$jobId-v$revision",
    ) {
        val factId = FactId("board", jobId)
        val fields = mapOf(
            "kind" to "card", "jobId" to jobId, "column" to column, "dependencies" to emptyList<String>(),
            "revision" to revision, "lastMoveMs" to lastMoveMs, "owner" to owner,
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

    private fun claims(fired: List<Activation>) = fired.filter { it.ruleId == BoardRules.CLAIM }

    @Test
    fun readyCardIsClaimedOnceWithTheBrainAsOwner() = runBlocking {
        val net = ReteNetwork()
        net.register(ClaimProduction())
        val fired = sink(net)
        card(net, "a", "ready", revision = 2, lastMoveMs = 10)
        val c = claims(fired)
        assertEquals(1, c.size)
        assertEquals("claim-a-r2", c[0].activationId)
        assertEquals(85, c[0].salience)
        assertEquals(
            mapOf("jobId" to "a", "toColumn" to "running", "expectedRevision" to "2", "owner" to "claim:brain"),
            c[0].bindings,
        )
        assertEquals(listOf(ContentId.of("a-v2".encodeToByteArray())), c[0].supportCids, "support is the card's own cid")

        // an unrelated fact change re-evaluates the rule: refraction holds, no second claim of revision 2
        card(net, "noise", "todo")
        assertEquals(1, claims(fired).size, "one firing per revision")

        // the claim lands: the card is RUNNING at revision 3 — nothing to claim
        card(net, "a", "running", revision = 3, owner = "claim:brain")
        assertEquals(1, claims(fired).size)

        // the reaper sends it back to READY at a NEW revision: claimed afresh
        card(net, "a", "ready", revision = 4, lastMoveMs = 20)
        assertEquals(2, claims(fired).size)
        assertEquals("claim-a-r4", claims(fired)[1].activationId)
    }

    @Test
    fun oldestFirstAndNeverPastTheRunningLimit() = runBlocking {
        val net = ReteNetwork()
        net.register(ClaimProduction())
        val fired = sink(net)
        card(net, "r1", "running", owner = "claim:brain")
        card(net, "r2", "running", owner = "jim")
        fired.clear()
        // three READY cards, one slot left: only the oldest is proposed
        card(net, "young", "ready", lastMoveMs = 300)
        card(net, "old", "ready", lastMoveMs = 100)
        card(net, "middle", "ready", lastMoveMs = 200)
        val c = claims(fired)
        assertEquals(listOf("young", "old"), c.map { it.bindings["jobId"] }, "young was alone at first; then old is the oldest of the rest — never middle")
        // WIP full: nothing more, however many READY cards arrive
        card(net, "r3", "running")
        fired.clear()
        card(net, "later", "ready", lastMoveMs = 50)
        assertEquals(0, claims(fired).size, "RUNNING at its limit claims nothing")
    }

    @Test
    fun onePassClaimsOnlyWhatTheLimitLeaves() = runBlocking {
        val net = ReteNetwork()
        // one activation per evaluation: assert five READY cards, then register — the first
        // evaluation sees all five at once and must propose exactly wipLimit of them
        val fired = sink(net)
        for (i in 1..5) card(net, "c$i", "ready", lastMoveMs = (6 - i).toLong())
        net.register(ClaimProduction())
        card(net, "tick", "todo") // a card-interest fact: the registered rule evaluates
        val c = claims(fired)
        assertEquals(BoardCol.RUNNING.wipLimit, c.size, "claims this pass + RUNNING never exceed the WIP limit")
        assertEquals(listOf("c5", "c4", "c3"), c.map { it.bindings["jobId"] }, "oldest lastMoveMs first")
    }

    @Test
    fun aDrainedRunningColumnReProposesAClaimTheStoreRefused() = runBlocking {
        val net = ReteNetwork()
        net.register(ClaimProduction())
        val fired = sink(net)
        card(net, "busy", "running", revision = 1)
        card(net, "wait", "ready", revision = 1)
        assertEquals(1, claims(fired).size)
        assertTrue(ContentId.of("busy-v1".encodeToByteArray()) in claims(fired)[0].supportCids, "the RUNNING cards are support")
        // suppose the store refused (a human filled RUNNING meanwhile) — the card stands READY at
        // the same revision. When the RUNNING card changes, support invalidation re-proposes it.
        card(net, "busy", "review", revision = 2)
        assertEquals(2, claims(fired).size, "un-refracted by support: proposed again, same activation id")
        assertEquals(claims(fired)[0].activationId, claims(fired)[1].activationId)
    }
}
