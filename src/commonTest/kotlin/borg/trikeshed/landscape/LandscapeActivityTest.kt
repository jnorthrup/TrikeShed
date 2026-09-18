package borg.trikeshed.landscape

import kotlin.test.Test
import kotlin.test.assertEquals

class LandscapeActivityTest {

    private fun receipt(
        programKey: String = "lcnc/program/demo",
        programCid: String = "cid-1",
        status: String = "completed",
        startedAtMs: Double = 1000.0,
        sequence: Double = 1.0,
        runId: String = "r1",
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = mapOf(
        "programKey" to programKey, "programCid" to programCid, "status" to status,
        "startedAtMs" to startedAtMs, "sequence" to sequence, "runId" to runId,
    ) + extra

    private fun programEntry(programCid: String = "cid-1", inspectionOnly: Boolean = false): Map<String, Any?> =
        mapOf("programCid" to programCid, "document" to mapOf("controls" to mapOf("inspectionOnly" to inspectionOnly)))

    @Test
    fun latestGroupsRunsNewestFirstWithStaleMarkers() {
        val board = mapOf(
            "lcnc/run/a" to receipt(programCid = "cid-1", startedAtMs = 1000.0, sequence = 1.0, runId = "ra"),
            "lcnc/run/b" to receipt(programCid = "cid-1", startedAtMs = 2000.0, sequence = 2.0, runId = "rb"),
            "lcnc/stale/rb" to mapOf("runId" to "rb", "count" to 2.0),
            "lcnc/run/c" to mapOf("runId" to "rx"),
            "other/fact" to mapOf("x" to 1.0),
        )
        val runs = LandscapeActivity.latest(board)
        assertEquals(1, runs.size)
        val list = runs.getValue("lcnc/program/demo")
        assertEquals(2, list.size)
        assertEquals("lcnc/run/b", list[0].key)
        assertEquals(2.0, list[0].stale?.get("count"))
        assertEquals("lcnc/run/a", list[1].key)
        assertEquals(null, list[1].stale)
    }

    @Test
    fun programEvidenceFollowsReceiptLifecycle() {
        val runs = LandscapeActivity.latest(
            mapOf("lcnc/run/a" to receipt(status = "running", startedAtMs = 1000.0))
        )
        val entry = programEntry()
        val now = 2000.0
        val active = LandscapeActivity.program("demo", entry, runs, draft = false, connected = true, now = now)
        assertEquals(ActivityState.OPERATIONAL, active.state)
        // no timeout budget on the receipt → freshness unconfirmed
        assertEquals("Run freshness unconfirmed; no timely terminal receipt", active.reason)

        val completed = LandscapeActivity.program(
            "demo", entry,
            LandscapeActivity.latest(mapOf("lcnc/run/a" to receipt(status = "completed", startedAtMs = 1000.0))),
            draft = false, connected = true, now = now,
        )
        assertEquals(ActivityState.COMPLETED, completed.state)
        assertEquals("Program completed", completed.reason)
        assertEquals(1000.0, completed.atMs?.toDouble())
    }

    @Test
    fun programGatesDraftsInspectionOnlyDisconnectedAndVersionMismatch() {
        val runs = LandscapeActivity.latest(mapOf("lcnc/run/a" to receipt(status = "running", startedAtMs = 1000.0)))
        val entry = programEntry()
        val draft = LandscapeActivity.program("demo", entry, runs, draft = true, connected = true, now = 2000.0)
        assertEquals(ActivityState.UNKNOWN, draft.state)
        assertEquals("Unpublished draft; published receipts do not describe these edits", draft.reason)

        val inert = LandscapeActivity.program(
            "demo", programEntry(inspectionOnly = true), runs, draft = false, connected = true, now = 2000.0,
        )
        assertEquals(ActivityState.INERT, inert.state)

        val disconnected = LandscapeActivity.program("demo", entry, runs, draft = false, connected = false, now = 2000.0)
        assertEquals(ActivityState.UNKNOWN, disconnected.state)
        assertEquals("Disconnected; last observed run was running", disconnected.reason)

        val mismatched = LandscapeActivity.program(
            "demo", programEntry(programCid = "cid-other"),
            LandscapeActivity.latest(mapOf("lcnc/run/a" to receipt(status = "completed", startedAtMs = 1.0))),
            draft = false, connected = true, now = 2000.0,
        )
        assertEquals(ActivityState.UNKNOWN, mismatched.state)
        assertEquals("No version-matched receipt; last recorded run was completed", mismatched.reason)
    }

    @Test
    fun programMarksStaleWhenInputsMoved() {
        val staleMarker = mapOf("runId" to "r1", "count" to 2.0, "inputs" to listOf(mapOf("id" to "input-7")))
        val board = mapOf(
            "lcnc/run/a" to receipt(status = "completed", startedAtMs = 1000.0),
            "lcnc/stale/r1" to staleMarker,
        )
        val evidence = LandscapeActivity.program(
            "demo", programEntry(), LandscapeActivity.latest(board), draft = false, connected = true, now = 2000.0,
        )
        assertEquals(ActivityState.STALE, evidence.state)
        assertEquals("Completed against inputs that have since changed: input-7", evidence.reason)
    }

    @Test
    fun nodeRefinesProgramEvidence() {
        val timer = LandscapeActivity.node("n1", ProgramEvidence(ActivityState.UNKNOWN, "none"), timerArmed = true)
        assertEquals(ActivityState.OPERATIONAL, timer.state)
        val subscribed = LandscapeActivity.node("n1", ProgramEvidence(ActivityState.UNKNOWN, "none"), eventSubscriptionOpen = true)
        assertEquals(ActivityState.OPERATIONAL, subscribed.state)

        val staleProgram = ProgramEvidence(
            ActivityState.STALE, "stale",
            receipt = mapOf(
                "status" to "completed",
                "stale" to mapOf("inputs" to listOf(mapOf("oldCid" to "cid-moved"))),
                "outputs" to mapOf("n1" to mapOf("ref" to "cid-moved"), "n2" to mapOf("ref" to "cid-still")),
            ),
        )
        val moved = LandscapeActivity.node("n1", staleProgram)
        assertEquals(ActivityState.STALE, moved.state)
        assertEquals("Node n1 read an input that has since changed", moved.reason)
        val untouched = LandscapeActivity.node("n2", staleProgram)
        assertEquals(ActivityState.COMPLETED, untouched.state)

        val violated = ProgramEvidence(
            ActivityState.UNKNOWN, "running",
            receipt = mapOf("phase" to "validation", "violations" to listOf(mapOf("toNode" to "n9"))),
        )
        assertEquals(ActivityState.BLOCKED, LandscapeActivity.node("n9", violated).state)
        assertEquals(ActivityState.UNKNOWN, LandscapeActivity.node("n10", violated).state)
    }

    @Test
    fun factClassifiesRecordedEventsOnly() {
        val committed = LandscapeActivity.fact("kanban/committed/job/1", mapOf("atMs" to 42.0))
        assertEquals(ActivityState.COMPLETED, committed.state)
        assertEquals(42L, committed.atMs)
        assertEquals(ActivityState.COMPLETED, LandscapeActivity.fact("narsese/xyz", mapOf("event" to "minted")).state)
        assertEquals(ActivityState.UNKNOWN, LandscapeActivity.fact("mux/state", mapOf("running" to 3.0)).state)
    }

    @Test
    fun referencesLinkSharedAtomsAndJobs() {
        val board = mapOf(
            "kanban/claim/j1/a" to mapOf("jobId" to "j1"),
            "kanban/committed/j1/2" to mapOf("ok" to true),
            "kanban/review/atom-9" to mapOf("note" to "x"),
            "narsese/curation/atom-9" to mapOf("event" to "minted"),
        )
        val links = LandscapeActivity.references(board)
        val kinds = links.map { it.kind }.toSet()
        assertEquals(setOf("shared atom reference", "shared job reference"), kinds)
        assertEquals(2, links.size)
        val jobLink = links.first { it.kind == "shared job reference" }
        assertEquals("kanban/claim/j1/a", jobLink.from)
        assertEquals("kanban/committed/j1/2", jobLink.to)
    }
}
