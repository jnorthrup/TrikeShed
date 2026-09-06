package borg.trikeshed.lcnc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The workspace snapshot: composed from the board and the stores, identity by content, lineage by previousCid. */
class WorkspaceSnapshotTest {

    private val entries: Map<String, Any?> = mapOf(
        "lcnc/program/corpus" to mapOf("programCid" to "sha256:p1", "sourceCid" to "sha256:s1", "document" to mapOf("nodes" to emptyList<Any>())),
        "lcnc/program/preset-brain-mux" to mapOf("programCid" to "sha256:p2", "sourceCid" to "sha256:s2"),
        "lcnc/program/broken" to mapOf("document" to emptyMap<String, Any>()),
        "lcnc/run/r-old" to mapOf("program" to "corpus", "status" to "completed", "receiptCid" to "sha256:old", "runId" to "r-old", "sequence" to 3),
        "lcnc/run/r-new" to mapOf("program" to "corpus", "status" to "completed", "receiptCid" to "sha256:new", "runId" to "r-new", "sequence" to 9),
        "lcnc/run/r-live" to mapOf("program" to "corpus", "status" to "running", "receiptCid" to "sha256:live", "runId" to "r-live", "sequence" to 12),
        "lcnc/run/r-other" to mapOf("program" to "preset-brain-mux", "status" to "failed", "receiptCid" to "sha256:f", "runId" to "r-f", "sequence" to 5),
        "lcnc/prompt/hello" to mapOf("cid" to "ignored here; prompts come from the store"),
        "kanban/claim/x" to mapOf("owner" to "claim:brain"),
    )
    private val prompts = mapOf("summarize" to "sha256:q2", "hello" to "sha256:q1")
    private val dbs = listOf(mapOf("name" to "genesis-notes", "kind" to "assets", "path" to "/x", "updateSeq" to 3L, "docs" to 3))

    @Test
    fun composesProgramsPromptsProjectsAndTheLatestCompletedReceiptPerProgram() {
        val s = WorkspaceSnapshot.compose(atMs = 1000L, note = "before the edit", previousCid = null, entries = entries, prompts = prompts, projectDbs = dbs)
        assertEquals(setOf("corpus", "preset-brain-mux"), s.programs.keys, "an entry without a programCid is not a version")
        assertEquals("sha256:p1", s.programs.getValue("corpus")["programCid"]); assertEquals("sha256:s1", s.programs.getValue("corpus")["sourceCid"])
        assertEquals(mapOf("receiptCid" to "sha256:new", "runId" to "r-new", "status" to "completed", "sequence" to 9L), s.receipts.getValue("corpus"), "newest completed, never the running one")
        assertNull(s.receipts["preset-brain-mux"], "a failed run is not a receipt of record")
        assertEquals(prompts, s.prompts); assertEquals(dbs, s.projectDbs)
        assertEquals(mapOf("programs" to 2, "prompts" to 2, "projectDbs" to 1, "receipts" to 1), s.counts())
    }

    @Test
    fun identityIsByContentAndOrderIndependentAndRoundTrips() {
        val a = WorkspaceSnapshot.compose(1000L, "n", "sha256:prev", entries, prompts, dbs)
        val b = WorkspaceSnapshot.compose(1000L, "n", "sha256:prev", entries.entries.reversed().associate { it.key to it.value }, prompts.entries.reversed().associate { it.key to it.value }, dbs)
        assertEquals(a.cid, b.cid, "entry order never moves the cid")
        assertNotEquals(a.cid, WorkspaceSnapshot.compose(1000L, "n", null, entries, prompts, dbs).cid, "the lineage is part of the identity")
        assertNotEquals(a.cid, WorkspaceSnapshot.compose(1001L, "n", "sha256:prev", entries, prompts, dbs).cid)
        val back = WorkspaceSnapshot.fromJson(a.canonicalJson())!!
        assertEquals(a.programs, back.programs); assertEquals(a.prompts, back.prompts); assertEquals(a.previousCid, back.previousCid)
        assertEquals(a.receipts.getValue("corpus")["receiptCid"], back.receipts.getValue("corpus")["receiptCid"])
        assertEquals(a.cid, back.cid, "the parsed document re-mints the same cid")
        assertNull(WorkspaceSnapshot.fromJson("""{"kind":"other"}"""))
        assertEquals(WorkspaceSnapshot.NOTE_MAX, WorkspaceSnapshot.compose(1L, "x".repeat(2000), null, emptyMap(), emptyMap(), emptyList()).note.length)
    }
}
