package borg.trikeshed.lcnc

import borg.trikeshed.job.CasStore
import borg.trikeshed.kanban.BoardApply
import borg.trikeshed.kanban.BoardIntake
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.kanban.JvmBoardWal
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `board.get` and `board.view` are UNITS over [BoardStoreElement]: they read
 * the store, not a route. A card committed through the store's own intake is
 * in the node's answer on the next call with no HTTP in between, and
 * `board.view#alerts` is whatever the productions' tail supplies — not a
 * constant `[]`.
 */
class LcncBoardNodesUnitTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "lcnc-board-nodes-$name-${System.nanoTime()}").apply { mkdirs() }

    private suspend fun send(el: BoardStoreElement, vararg pairs: Pair<String, Any?>): BoardApply {
        val d = CompletableDeferred<BoardApply>()
        el.intake.send(BoardIntake(mapOf(*pairs), d))
        return d.await()
    }

    @Suppress("UNCHECKED_CAST")
    private fun items(board: Any?): List<Map<String, Any?>> = (board as Map<String, Any?>)["items"] as List<Map<String, Any?>>

    @Test
    fun boardNodesAnswerFromTheStoreNotARoute() = runBlocking {
        val el = BoardStoreElement(JvmBoardWal(tempDir("unit")), CasStore.inMemory(), clock = { 7L })
        el.open()
        var tailCalls = 0
        val x = LcncKanbanExperience(
            el,
            attention = { mapOf("a" to mapOf("attention" to 0.9f, "contested" to true)) },
            alerts = { tailCalls++; mapOf("breaches" to emptyList<Any>(), "stalls" to listOf(mapOf("jobId" to "a")), "cycles" to emptyList<Any>(), "ready" to emptyList<Any>()) },
        )
        val reg = x.registry()
        val node = LcncNode("n", "board.get")
        assertEquals(0, items(reg.getValue("board.get").run(node, emptyMap())["json"]).size, "empty board, empty items")

        assertIs<BoardApply.Committed>(send(el, "type" to "submit", "jobId" to "a", "idempotencyKey" to "k1", "title" to "Card a", "owner" to "jim", "tags" to listOf("x")))
        val got = items(reg.getValue("board.get").run(node, emptyMap())["json"])
        assertEquals(listOf("a"), got.map { it["id"] })
        assertEquals("jim", got[0]["owner"], "the fields the bare cursor drops ride along")
        assertEquals(listOf("x"), got[0]["tags"])
        assertEquals(0.9f, got[0]["attention"], "garnish lands per card when a bag supplies it")

        assertIs<BoardApply.Committed>(send(el, "type" to "move", "jobId" to "a", "idempotencyKey" to "k2", "expectedRevision" to 1, "toColumn" to "ready"))
        val view = reg.getValue("board.view").run(LcncNode("v", "board.view"), emptyMap())
        assertEquals("ready", items(view["board"])[0]["status"], "the node sees the post-move state")
        @Suppress("UNCHECKED_CAST")
        val alerts = view["alerts"] as Map<String, Any?>
        assertEquals(listOf(mapOf("jobId" to "a")), alerts["stalls"], "alerts are the productions' tail, not a constant []")
        assertTrue(tailCalls >= 1)
    }

    @Test
    fun theSurfaceFamilyNoLongerShimsTheBoard() {
        val surface = SurfaceNodes.registry { _, _, _ -> error("no route may be called") }
        assertFalse("board.get" in surface); assertFalse("board.view" in surface)
        assertFalse("board.get" in SurfaceNodes.servedTypes())
        // …and the contract still exists, served by the kanban experience.
        assertTrue(LcncContracts.find("board.view")!!.outputs.containsAll(listOf("board", "alerts")))
    }
}
