package borg.trikeshed.lcnc

import borg.trikeshed.job.CasStore
import borg.trikeshed.kanban.BoardApply
import borg.trikeshed.kanban.BoardIntake
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.kanban.JvmBoardWal
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * "Operational" means live, not a frozen document — this exercises the REAL
 * WAL-backed [BoardStoreElement] (the same harness [BoardStoreElementTest]
 * uses), moves a real card, and asserts the sheet taken AFTER the move
 * reflects it. A sheet that only ever showed a snapshot from before the
 * mutation would defeat the entire point of calling it operational.
 */
class LcncOperationalSheetsTest {

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "lcnc-ops-sheet-$name-${System.nanoTime()}").apply { mkdirs() }

    private suspend fun send(el: BoardStoreElement, vararg pairs: Pair<String, Any?>): BoardApply {
        val d = CompletableDeferred<BoardApply>()
        el.intake.send(BoardIntake(mapOf(*pairs), d))
        return d.await()
    }

    private suspend fun submit(el: BoardStoreElement, job: String, key: String): BoardApply =
        send(el, "type" to "submit", "jobId" to job, "idempotencyKey" to key, "title" to "Card $job")

    @Test
    fun operationalSheetReflectsLiveBoardStateAfterAMutation() = runBlocking {
        val el = BoardStoreElement(JvmBoardWal(tempDir("live")), CasStore.inMemory(), clock = { 1L })
        el.open()
        assertIs<BoardApply.Committed>(submit(el, "a", "k1"))
        assertIs<BoardApply.Committed>(submit(el, "b", "k2"))

        val before = LcncOperationalSheets.board(el)
        assertEquals(2, before.rows.size)
        val statusColIdx = before.columns.indexOfFirst { it.name == "columnId" }
        assertTrue(statusColIdx >= 0, "columns: ${before.columns.map { it.name }}")
        val jobIdColIdx = before.columns.indexOfFirst { it.name == "jobId" }
        val cardABeforeStatus = before.rows.first { it[jobIdColIdx] == "a" }[statusColIdx]
        assertEquals("todo", cardABeforeStatus)

        // A REAL move against the REAL store — same command path production traffic uses.
        assertIs<BoardApply.Committed>(
            send(el, "type" to "move", "jobId" to "a", "idempotencyKey" to "k3", "expectedRevision" to 1, "toColumn" to "ready"),
        )

        val after = LcncOperationalSheets.board(el)
        val cardAAfterStatus = after.rows.first { it[jobIdColIdx] == "a" }[statusColIdx]
        assertEquals("ready", cardAAfterStatus, "the operational sheet must show the post-move state, not the frozen `before` snapshot")
        // and the sheet taken BEFORE the move must NOT have silently mutated — it's a frozen SoA freeze at the instant it was built.
        assertEquals("todo", cardABeforeStatus, "an earlier sheet must stay exactly what it was when built")
    }

    @Test
    fun operationalSheetOnAnEmptyBoardIsAnEmptySheetNotAnException() = runBlocking {
        val el = BoardStoreElement(JvmBoardWal(tempDir("empty")), CasStore.inMemory(), clock = { 1L })
        el.open()
        val sheet = LcncOperationalSheets.board(el)
        assertEquals(0, sheet.rows.size)
        assertTrue(sheet.columns.isNotEmpty(), "column schema must still be present on an empty board")
    }

    @Test
    fun lcncKanbanProgramCommitsThenReturnsThePostCommitActiveTreeSheets() = runBlocking {
        val el = BoardStoreElement(JvmBoardWal(tempDir("program")), CasStore.inMemory(), clock = { 1L })
        el.open()
        val experience = LcncKanbanExperience(el)
        val program = LcncProgram(
            "operational-kanban",
            listOf(
                LcncNode(
                    id = "submit",
                    type = "kanban.submit",
                    params = mapOf("jobId" to "tree-card", "title" to "Tree card", "idempotencyKey" to "tree-submit"),
                ),
                LcncNode(id = "sheets", type = "kanban.activeSheets"),
                LcncNode(
                    id = "json",
                    type = "confix.sheets",
                    params = mapOf("id" to "payload", "json" to "{\"nested\":{\"state\":\"live\"}}"),
                ),
                LcncNode(
                    id = "pick-columns",
                    type = "confix.pickPath",
                    params = mapOf(
                        "path" to "columns",
                        "json" to "{\"y\":[{\"wrong\":true}],\"columns\":[{\"id\":\"todo\"}]}",
                    ),
                ),
            ).toSeries(),
            borg.trikeshed.lib.emptySeriesOf(),
        )

        val output = LcncRunner(experience.registry()).runAll(program)
        assertEquals(true, output.getValue("submit")["accepted"])
        @Suppress("UNCHECKED_CAST")
        val board = output.getValue("sheets")["board"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val rows = board["rows"] as List<List<Any?>>
        assertEquals("tree-card", rows.single()[0], "the active tree sheet is projected after the LCNC command commits")
        @Suppress("UNCHECKED_CAST")
        val jsonSheets = output.getValue("json")["sheets"] as List<Map<String, Any?>>
        assertEquals(2, jsonSheets.size, "shown JSON is a concentric Confix sheet family, including its nested object")
        val picked = output.getValue("pick-columns")
        assertEquals(true, picked["found"])
        assertEquals("columns", picked["path"])
        @Suppress("UNCHECKED_CAST")
        val pickedSheets = picked["sheets"] as List<Map<String, Any?>>
        assertEquals(2, pickedSheets.size)
        assertEquals("columns", pickedSheets.first()["title"], "the active tree must show the selected path, never the stale y port object")
    }
}
