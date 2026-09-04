package borg.trikeshed.kanban.rules

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.Activation
import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.ContentId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The reaper: a claimed RUNNING card idle past the threshold goes back to READY, strike counted from the receipts. */
class ReaperProductionTest {

    private val board = BlackboardContext("board")

    private suspend fun card(
        net: ReteNetwork,
        jobId: String,
        column: String,
        revision: Long = 1L,
        lastMoveMs: Long = 0L,
        owner: String = "",
        version: String = "$jobId-v$revision-$lastMoveMs",
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

    private suspend fun tick(net: ReteNetwork, ms: Long) {
        val now = FactId("board", "now")
        val fields = mapOf("kind" to "now", "ms" to ms)
        val cid = ContentId.of("now-$ms".encodeToByteArray())
        if (net.workingMemory.facts(now).isEmpty()) net.assert(now, fields, cid, board) else net.modify(now, fields, cid)
    }

    private fun sink(net: ReteNetwork): MutableList<Activation> {
        val fired = ArrayList<Activation>()
        net.productionSink = { fired.add(it) }
        return fired
    }

    private fun reaped(fired: List<Activation>) = fired.filter { it.ruleId == BoardRules.REAPER }

    @Test
    fun onlyClaimedRunningCardsPastTheThresholdAreReaped_onceLastMove() = runBlocking {
        val net = ReteNetwork()
        net.register(ReaperProduction(thresholdMs = 1000L))
        val fired = sink(net)
        card(net, "mine", "running", revision = 3, lastMoveMs = 0L, owner = "claim:brain")
        card(net, "jims", "running", revision = 1, lastMoveMs = 0L, owner = "jim")
        card(net, "nobody", "running", revision = 1, lastMoveMs = 0L)
        card(net, "parked", "review", revision = 1, lastMoveMs = 0L, owner = "claim:brain")

        tick(net, 1000L)
        assertEquals(0, reaped(fired).size, "idle == threshold is not past it")
        tick(net, 1001L)
        val r = reaped(fired)
        assertEquals(1, r.size, "only the claimed RUNNING card: $r")
        assertEquals("reaper-mine-0", r[0].activationId)
        assertEquals(45, r[0].salience)
        assertEquals("mine", r[0].bindings["jobId"])
        assertEquals("ready", r[0].bindings["toColumn"])
        assertEquals("3", r[0].bindings["expectedRevision"])
        assertEquals("1", r[0].bindings["strike"], "no prior receipts: first strike")
        assertEquals("claim:brain", r[0].bindings["owner"])
        assertEquals(listOf(ContentId.of("mine-v3-0".encodeToByteArray())), r[0].supportCids, "the card alone is support")

        // the next tick does not nag: same lastMove, same id, refraction holds
        tick(net, 5000L)
        assertEquals(1, reaped(fired).size)

        // the card moved (READY, then claimed again → RUNNING at a new lastMove): a fresh period
        card(net, "mine", "running", revision = 5, lastMoveMs = 5000L, owner = "claim:brain")
        tick(net, 6001L)
        assertEquals(2, reaped(fired).size)
        assertEquals("reaper-mine-5000", reaped(fired)[1].activationId)
        assertEquals("5", reaped(fired)[1].bindings["expectedRevision"])
    }

    @Test
    fun strikeIsThePriorReceiptCountPlusOne() = runBlocking {
        val bb = ConfixBlackboard.empty()
        // two earlier strikes for "a" on the board (what the sink writes), one for "a-b" — a jobId
        // that "a" prefixes — which must not be counted against "a"
        bb.put("kanban/rule/reaper/reaper-a-100", mapOf("jobId" to "a", "strike" to "1"), "kanban-rete")
        bb.put("kanban/rule/reaper/reaper-a-200", mapOf("jobId" to "a", "strike" to "2"), "kanban-rete")
        bb.put("kanban/rule/reaper/reaper-a-b-300", mapOf("jobId" to "a-b", "strike" to "1"), "kanban-rete")
        bb.put("kanban/rule/stall/stall-a-400", mapOf("jobId" to "a"), "kanban-rete")
        assertEquals(2, ReaperProduction.countPriorStrikes(bb, "a"))
        assertEquals(1, ReaperProduction.countPriorStrikes(bb, "a-b"))
        assertEquals(0, ReaperProduction.countPriorStrikes(bb, "zzz"))

        val net = ReteNetwork()
        net.register(ReaperProduction(thresholdMs = 1000L, priorStrikes = { ReaperProduction.countPriorStrikes(bb, it) }))
        val fired = sink(net)
        card(net, "a", "running", revision = 7, lastMoveMs = 0L, owner = "claim:brain")
        card(net, "a-b", "running", revision = 2, lastMoveMs = 0L, owner = "claim:brain")
        tick(net, 2000L)
        val byJob = reaped(fired).associateBy { it.bindings["jobId"] }
        assertEquals("3", byJob.getValue("a").bindings["strike"], "the third strike: the sink blocks it")
        assertEquals("2", byJob.getValue("a-b").bindings["strike"])
        assertTrue(BoardRules.REAPER_BLOCK_STRIKE == 3)
    }
}
