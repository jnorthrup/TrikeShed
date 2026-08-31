package borg.trikeshed.mcp

import borg.trikeshed.job.CasStore
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.kanban.JvmBoardWal
import borg.trikeshed.lcnc.LcncKanbanExperience
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KMFSM-010: what happens when two clients race one revision.
 *
 * This is the claim an MCP board actually has to survive, because MCP is how
 * *agents* reach it and agents do not take turns. Everything in
 * [LcncKanbanMcpTest] runs one request at a time, which proves the guards exist
 * but not that they hold when the requests overlap — a compare-and-set that is
 * only ever exercised serially is an untested compare-and-set.
 *
 * The structural reason these should pass: `BoardStoreElement` is a single
 * writer. Commands enter one `Channel` and one consumer applies them, so the
 * reducer never sees interleaved state. These tests are here to prove that the
 * MCP surface does not quietly route around it — no per-request store, no
 * optimistic local echo, no second writer.
 */
class McpKanbanRaceTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "mcp-race-$name-${System.nanoTime()}").apply { mkdirs() }

    /**
     * Peak simultaneous in-flight calls.
     *
     * Without this the tests in this file are theatre: "exactly one winner" and
     * "three running" are ALSO what you get if the runtime quietly ran the eight
     * calls one after another. Every concurrent test here asserts the peak was
     * greater than one, so a green bar means the requests genuinely overlapped
     * rather than that the scheduler happened to take turns.
     */
    private class InFlight {
        private val current = java.util.concurrent.atomic.AtomicInteger()
        private val peak = java.util.concurrent.atomic.AtomicInteger()

        fun enter() {
            val now = current.incrementAndGet()
            peak.updateAndGet { if (now > it) now else it }
        }

        fun exit() {
            current.decrementAndGet()
        }

        fun peak(): Int = peak.get()
    }

    private class Rig(val mcp: LcncKanbanMcp, val store: BoardStoreElement) {
        val inFlight = InFlight()
    }

    private fun rig(name: String): Rig {
        val store = BoardStoreElement(JvmBoardWal(tempDir(name)), CasStore.inMemory(), clock = { 7L })
        val experience = LcncKanbanExperience(store)
        return Rig(
            LcncKanbanMcp(
                tools = experience.registry(),
                reads = BoardKanbanReadPort(store, experience, KanbanReceiptLog()),
            ),
            store,
        )
    }

    /** One tools/call, returning its structuredContent. */
    private suspend fun call(rig: Rig, tool: String, args: Map<String, Any?>, id: Int): Map<*, *> {
        val doc = mapOf(
            "jsonrpc" to "2.0", "id" to id, "method" to "tools/call",
            "params" to mapOf("name" to tool, "arguments" to args),
        )
        rig.inFlight.enter()
        val raw = try {
            rig.mcp.handle(JsonSupport.stringify(doc))
        } finally {
            rig.inFlight.exit()
        }
        val reply = JsonSupport.parse(raw) as Map<*, *>
        val result = reply["result"] as? Map<*, *> ?: error("no result: $reply")
        return result["structuredContent"] as Map<*, *>
    }

    /** The assertion that stops this file from being theatre. */
    private fun assertActuallyRaced(rig: Rig) {
        assertTrue(
            rig.inFlight.peak() > 1,
            "these calls never overlapped (peak in-flight = ${rig.inFlight.peak()}), so this test " +
                "proved nothing about concurrency — it would pass on a serial runtime too",
        )
    }

    private fun accepted(r: Map<*, *>) = r["accepted"] == true

    private fun reason(r: Map<*, *>) = r["reason"]?.toString() ?: ""

    @Test
    fun eightClientsRacingOneRevisionProduceExactlyOneWinner() = runBlocking {
        val rig = rig("one-winner")
        rig.store.open()
        val submitted = call(rig, LcncKanbanMcp.TOOL_SUBMIT, mapOf("title" to "Contested"), 1)
        val jobId = submitted["jobId"] as String
        val revision = (submitted["revision"] as Number).toLong()

        // Eight clients, each quoting the SAME revision, each wanting a different
        // column. Compare-and-set means exactly one may win; the rest must be
        // told why rather than silently losing their move.
        val columns = listOf("ready", "running", "blocked", "done", "archived", "triage", "todo", "ready")
        val outcomes = withContext(Dispatchers.Default) {
            columns.mapIndexed { i, col ->
                async {
                    call(
                        rig, LcncKanbanMcp.TOOL_MOVE,
                        mapOf("jobId" to jobId, "toColumn" to col, "expectedRevision" to revision),
                        100 + i,
                    )
                }
            }.awaitAll()
        }

        val winners = outcomes.filter(::accepted)
        assertActuallyRaced(rig)
        assertEquals(1, winners.size, "exactly one move may win a revision; got ${winners.size}")

        // Every loser is refused for a REASON the client can act on.
        val losers = outcomes.filterNot(::accepted)
        for (l in losers) {
            val why = reason(l)
            assertTrue(
                "stale expectedRevision" in why || "duplicate idempotencyKey" in why,
                "a losing racer must be told why, got: '$why'",
            )
        }

        // The board agrees with the winner, and the revision advanced exactly once.
        val row = rig.store.card(jobId)!!
        assertEquals(revision + 1, row.revision, "one winner means one revision bump, not eight")
        assertEquals((winners.single()["jobId"] as String), row.jobId)
        rig.store.drain()
    }

    @Test
    fun theWipLimitHoldsWhenEveryoneRushesTheColumnAtOnce() = runBlocking {
        // The guard that is easiest to get wrong under concurrency: a check-then-act
        // on a count. If the WIP test read state outside the single writer, eight
        // simultaneous movers would all see "2 running" and all be admitted.
        val rig = rig("wip-rush")
        rig.store.open()
        val cards = (1..8).map { i ->
            val s = call(rig, LcncKanbanMcp.TOOL_SUBMIT, mapOf("title" to "Rusher $i"), i)
            (s["jobId"] as String) to (s["revision"] as Number).toLong()
        }

        val outcomes = withContext(Dispatchers.Default) {
            cards.mapIndexed { i, (jobId, rev) ->
                async {
                    call(
                        rig, LcncKanbanMcp.TOOL_MOVE,
                        mapOf("jobId" to jobId, "toColumn" to "running", "expectedRevision" to rev),
                        200 + i,
                    )
                }
            }.awaitAll()
        }

        assertActuallyRaced(rig)
        assertEquals(3, outcomes.count(::accepted), "running holds 3 no matter how many arrive together")
        assertEquals(3, rig.store.cards().count { it.col.wire == "running" })
        for (l in outcomes.filterNot(::accepted)) {
            assertTrue("WIP limit" in reason(l), "a refused rusher must name the WIP limit, got: '${reason(l)}'")
        }
        rig.store.drain()
    }

    @Test
    fun concurrentSubmitsOfOneTitleMakeOneCardNotEight() = runBlocking {
        // Eight agents independently deciding "there should be a card for this".
        // The id is the title's content hash and the default idempotency key is
        // derived from it, so the board must end with one card — the dedupe has to
        // hold without any client having coordinated a key.
        val rig = rig("same-title")
        rig.store.open()

        val outcomes = withContext(Dispatchers.Default) {
            (1..8).map { i ->
                async { call(rig, LcncKanbanMcp.TOOL_SUBMIT, mapOf("title" to "Only one of me"), 300 + i) }
            }.awaitAll()
        }

        assertActuallyRaced(rig)
        assertEquals(1, outcomes.count(::accepted), "one title, one card")
        assertEquals(1, rig.store.cards().size)
        for (l in outcomes.filterNot(::accepted)) {
            assertTrue(
                "duplicate idempotencyKey" in reason(l),
                "a losing duplicate must say so, got: '${reason(l)}'",
            )
        }
        rig.store.drain()
    }

    @Test
    fun aRacedBoardStillReplaysToTheSameStateAfterRestart() = runBlocking {
        // Concurrency that produced a correct in-memory board is only half the
        // claim: the WAL has to replay to the same place, or the durability story
        // holds right up until the daemon restarts.
        val dir = tempDir("race-replay")
        val cas = CasStore.inMemory()
        val first = BoardStoreElement(JvmBoardWal(dir), cas, clock = { 7L })
        val firstExperience = LcncKanbanExperience(first)
        val firstMcp = LcncKanbanMcp(
            tools = firstExperience.registry(),
            reads = BoardKanbanReadPort(first, firstExperience, KanbanReceiptLog()),
        )
        first.open()
        val rig1 = Rig(firstMcp, first)

        val cards = (1..6).map { i ->
            val s = call(rig1, LcncKanbanMcp.TOOL_SUBMIT, mapOf("title" to "Replay $i"), i)
            (s["jobId"] as String) to (s["revision"] as Number).toLong()
        }
        withContext(Dispatchers.Default) {
            cards.mapIndexed { i, (jobId, rev) ->
                async {
                    call(
                        rig1, LcncKanbanMcp.TOOL_MOVE,
                        mapOf("jobId" to jobId, "toColumn" to "running", "expectedRevision" to rev),
                        400 + i,
                    )
                }
            }.awaitAll()
        }
        assertActuallyRaced(rig1)
        val before = first.cards().sortedBy { it.jobId }.map { it.jobId to (it.col.wire to it.revision) }
        first.drain()

        // Same WAL, fresh process.
        val second = BoardStoreElement(JvmBoardWal(dir), cas, clock = { 7L })
        second.open()
        val after = second.cards().sortedBy { it.jobId }.map { it.jobId to (it.col.wire to it.revision) }
        assertEquals(before, after, "a raced board must replay to the identical state")
        assertEquals(3, after.count { it.second.first == "running" }, "the WIP limit survives replay too")
        second.drain()
    }
}
