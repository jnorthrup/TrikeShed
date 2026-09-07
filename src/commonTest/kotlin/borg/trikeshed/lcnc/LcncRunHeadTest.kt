package borg.trikeshed.lcnc

import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.parse.json.ValueBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The run head, pinned on every target the daemon and the bundle share (AutoTools, Cut B).
 *
 * The lamp cases mirror `src/jvmTest/js/landscape-activity.test.cjs` one for one, because Cut B
 * moves that decision out of the browser and onto the server and the two must not drift. The two
 * deliberate divergences are asserted here as well: the `connected` term is gone (a server is
 * connected to its own board), and landscape's sixth state `unknown` for an overdue active run
 * collapses into Running with the elapsed time in the reason — the server saw no failure and does
 * not claim one.
 *
 * Pure by construction: no runBlocking, so this runs on jsNodeTest too.
 */
class LcncRunHeadTest {

    private val programKey = LcncBlackboard.programKey("corpus")
    private val programCid = "sha256:" + "a".repeat(64)
    private val otherCid = "sha256:" + "b".repeat(64)
    private val inputs = mapOf("project" to "genesis-notes")

    private fun receipt(
        runId: String,
        status: String,
        startedAtMs: Long = 1000L,
        sequence: Long = 1L,
        programCid: String = this.programCid,
        programKey: String? = this.programKey,
        inputs: Any? = this.inputs,
        outputs: Map<String, Any?> = emptyMap(),
        returns: Any? = null,
        extra: Map<String, Any?> = emptyMap(),
    ): Pair<String, Any?> = LcncRunHead.RUN_PREFIX + runId to (mapOf(
        "runId" to runId,
        "status" to status,
        "program" to "corpus",
        "programKey" to programKey,
        "programCid" to programCid,
        "inputs" to inputs,
        "startedAtMs" to startedAtMs,
        "sequence" to sequence,
        "receiptCid" to "sha256:" + runId.padStart(64, '0'),
        "budgets" to mapOf("timeoutMs" to 120000L, "maxNodes" to 10000L),
        "outputs" to outputs,
        "returns" to returns,
    ) + extra)

    private fun headOf(vararg entries: Pair<String, Any?>, wanted: Any? = inputs) =
        LcncRunHead.head(LcncRunHead.rows(mapOf(*entries)), programKey, programCid, LcncRunHead.canonicalInputs(wanted))

    /** A marker the rule could really produce: built through LcncStaleMarker.merge from the rule's own bindings. */
    private fun marker(runId: String, vararg moved: Pair<String, String>): Map<String, Any?> {
        var acc: Any? = null
        for ((docId, newCid) in moved) {
            acc = LcncStaleMarker.merge(
                acc,
                mapOf(
                    "runId" to runId, "receiptKey" to (LcncRunHead.RUN_PREFIX + runId),
                    "receiptCid" to "sha256:" + runId.padStart(64, '0'),
                    "programKey" to programKey, "programCid" to programCid, "project" to "genesis-notes",
                    "kind" to LcncConsumedLedger.PROJECT, "id" to docId,
                    "oldCid" to "sha256:" + "c".repeat(64), "newCid" to newCid,
                    "sequence" to "7", "deleted" to "false",
                ),
                atMs = 9000L,
            )
        }
        @Suppress("UNCHECKED_CAST")
        return acc as Map<String, Any?>
    }

    // ── the target's identity ───────────────────────────────────────────────

    @Test
    fun canonicalInputsIgnoreKeyOrderAndIntegralFloatingPoint() {
        // Every JSON number arrives as a Double, and a WAL-replayed receipt comes back the same way,
        // so {"n":3} and {"n":3.0} are one value and must be one key.
        val a = LcncRunHead.canonicalInputs(JsonSupport.parse("""{"b":1,"a":{"y":2.0,"x":"s"},"n":[3,{"q":1,"p":2}]}"""))
        val b = LcncRunHead.canonicalInputs(JsonSupport.parse("""{"n":[3.0,{"p":2.0,"q":1}],"a":{"x":"s","y":2},"b":1.0}"""))
        assertEquals(a, b)
        assertEquals("""{"a":{"x":"s", "y":2}, "b":1, "n":[3, {"p":2, "q":1}]}""", a)
        // A round trip through the one stringifier and the one parser is a fixpoint, which is what
        // lets a page hand this text to the route and the route re-canonicalise the parsed value.
        assertEquals(a, LcncRunHead.canonicalInputs(JsonSupport.parse(a)))
        // Lists keep their order; a non-integral Double stays a Double.
        assertEquals("""{"xs":[2, 1], "y":1.5}""", LcncRunHead.canonicalInputs(mapOf("y" to 1.5, "xs" to listOf(2, 1))))
        // An absent inputs is the empty object, never null.
        assertEquals("{}", LcncRunHead.canonicalInputs(null))
        assertEquals(LcncRunHead.canonicalInputs(null), LcncRunHead.canonicalInputs(emptyMap<String, Any?>()))
        // The handle is stable and differs with the program name.
        assertEquals(LcncRunHead.inputsKey("corpus", a), LcncRunHead.inputsKey("corpus", b))
        assertNotEquals(LcncRunHead.inputsKey("corpus", a), LcncRunHead.inputsKey("digest", a))
    }

    @Test
    fun onlyRunEntriesThatNameARunAreRows() {
        val rows = LcncRunHead.rows(mapOf(
            receipt("r1", "completed"),
            "lcnc/program/corpus" to mapOf("name" to "corpus"),
            "lcnc/run/broken" to mapOf("status" to "completed"),
            "lcnc/run/text" to "not a map",
        ))
        assertEquals(listOf("r1"), rows.map { it.runId })
        // A WAL replay hands numbers back as Doubles; they still read as Long.
        val replayed = LcncRunHead.rows(mapOf("lcnc/run/r2" to mapOf(
            "runId" to "r2", "status" to "completed", "startedAtMs" to 5.0, "sequence" to 9.0,
        ))).single()
        assertEquals(5L, replayed.startedAtMs)
        assertEquals(9L, replayed.sequence)
    }

    // ── the head ────────────────────────────────────────────────────────────

    @Test
    fun aTargetWithNoRunIsNeverBuilt() {
        val head = headOf()
        assertNull(head.latest)
        val verdict = LcncRunHead.decide(head, null, 10_000L)
        assertEquals(LcncRunHead.Lamp.NEVER_BUILT, verdict.lamp)
        assertEquals("Never built", verdict.word)
        assertEquals("never_built", verdict.lamp.wire)
        assertEquals("never-built", verdict.lamp.slug)
    }

    @Test
    fun differentInputsAndDifferentProgramVersionsAreDifferentTargets() {
        val mine = receipt("r1", "completed")
        // Same program, other inputs: no cross-talk.
        assertNull(headOf(mine, wanted = mapOf("project" to "other")).latest)
        // Same inputs written differently: the same target.
        assertEquals("r1", headOf(mine, wanted = JsonSupport.parse("""{"project":"genesis-notes"}""")).latest?.runId)
        // The program was re-published: the old receipt is not this target's head (invariant 2).
        assertNull(headOf(receipt("r1", "completed", programCid = otherCid)).latest)
        // An inline ring records no programKey and is never a page's head.
        assertNull(headOf(receipt("r1", "completed", programKey = null)).latest)
    }

    @Test
    fun theNewestRunWinsAndTheBoardSequenceBreaksAClockTie() {
        // Two runs under one frozen clock share startedAtMs; only the board's commit sequence
        // orders them, and a rebuild leaves the old receipt on the board for this sort to beat.
        val head = headOf(
            receipt("first", "completed", startedAtMs = 1234L, sequence = 3L),
            receipt("second", "completed", startedAtMs = 1234L, sequence = 8L),
        )
        assertEquals("second", head.latest?.runId)
        assertEquals("second", head.artifact?.runId)
        assertEquals(listOf("second", "first"), head.matching.map { it.runId })
    }

    @Test
    fun aFailedRunKeepsTheLastGoodArtifactVisible() {
        // What make does with the old .o: the lamp describes the newest run, the output stays the
        // newest COMPLETED one.
        val head = headOf(
            receipt("good", "completed", startedAtMs = 1000L, outputs = mapOf("n-show" to mapOf("x" to "the digest"))),
            receipt("bad", "failed", startedAtMs = 2000L, extra = mapOf("phase" to "execution", "error" to "provider refused")),
        )
        assertEquals("bad", head.latest?.runId)
        assertEquals("good", head.artifact?.runId)
        val verdict = LcncRunHead.decide(head, null, 3000L)
        assertEquals(LcncRunHead.Lamp.FAILED, verdict.lamp)
        assertTrue(verdict.reason.contains("failed"), verdict.reason)
        assertTrue(verdict.reason.contains("execution"), verdict.reason)
        assertTrue(verdict.reason.contains("provider refused"), verdict.reason)
        val body = LcncRunHead.headBody("corpus", programKey, programCid, inputs, head, verdict, null, "n-show")
        assertEquals(mapOf("x" to "the digest"), body["shown"], "the last good artifact still paints: $body")
        assertEquals("bad", body["latestRunId"])
        assertEquals("good", body["runId"], "Rebuild names the artifact, not the failure")
    }

    @Test
    fun everyTerminalStatusMapsToOneOfTheFiveWords() {
        // The table landscape.js drives, minus the states a page has no word for.
        for (status in listOf("failed", "refused", "timed_out", "cancelled", "interrupted")) {
            val verdict = LcncRunHead.decide(headOf(receipt("r", status)), null, 5000L)
            assertEquals(LcncRunHead.Lamp.FAILED, verdict.lamp, "$status should read Failed")
            assertTrue(verdict.reason.contains(status), verdict.reason)
        }
        for (status in listOf("validating", "running")) {
            val verdict = LcncRunHead.decide(headOf(receipt("r", status)), null, 5000L)
            assertEquals(LcncRunHead.Lamp.RUNNING, verdict.lamp, "$status should read Running")
        }
        assertEquals(LcncRunHead.Lamp.COMPLETED, LcncRunHead.decide(headOf(receipt("r", "completed")), null, 5000L).lamp)
    }

    @Test
    fun anOverlappingActiveRunWinsTheLampOverAnOlderCompletedOne() {
        // landscape-activity.test.cjs: overlapping runs do not make an older still-running
        // invocation look spent. Here the newest is active, and it takes the lamp.
        val head = headOf(
            receipt("done", "completed", startedAtMs = 1000L),
            receipt("now", "running", startedAtMs = 2000L),
            receipt("also", "running", startedAtMs = 2000L, sequence = 4L),
        )
        val verdict = LcncRunHead.decide(head, null, 2500L)
        assertEquals(LcncRunHead.Lamp.RUNNING, verdict.lamp)
        assertTrue(verdict.reason.contains("2 concurrent runs"), verdict.reason)
        assertEquals("done", head.artifact?.runId, "the artifact under a running rebuild is still the last good one")
    }

    @Test
    fun anOverdueActiveRunStaysRunningAndSaysSo() {
        // The divergence from landscape.js's sixth state: no invented failure. The reason carries
        // the elapsed time against the budget so a reader can see the frame is stuck, not working.
        val head = headOf(receipt("stuck", "running", startedAtMs = 1000L))
        val verdict = LcncRunHead.decide(head, null, 1000L + 120_000L + 30_000L)
        assertEquals(LcncRunHead.Lamp.RUNNING, verdict.lamp)
        assertTrue(verdict.reason.contains("150s"), verdict.reason)
        assertTrue(verdict.reason.contains("120s budget"), verdict.reason)
    }

    // ── stale ───────────────────────────────────────────────────────────────

    @Test
    fun aCompletedRunWithAStaleMarkerReadsStaleAndNamesTheMovedDocuments() {
        val head = headOf(receipt("r1", "completed"))
        val one = marker("r1", "a.md" to "sha256:" + "d".repeat(64))
        val verdict = LcncRunHead.decide(head, one, 10_000L)
        assertEquals(LcncRunHead.Lamp.STALE, verdict.lamp)
        assertEquals("Stale", verdict.word)
        assertEquals(listOf("a.md"), verdict.moved)
        assertTrue(verdict.reason.contains("a.md"), verdict.reason)
        val two = marker("r1", "a.md" to "sha256:" + "d".repeat(64), "b.md" to "sha256:" + "e".repeat(64))
        assertEquals(listOf("a.md", "b.md"), LcncRunHead.decide(head, two, 10_000L).moved)
        // An active run outranks the marker: a rebuild in flight reads Running, not Stale.
        val rebuilding = headOf(receipt("r1", "completed"), receipt("r2", "running", startedAtMs = 2000L))
        assertEquals(LcncRunHead.Lamp.RUNNING, LcncRunHead.decide(rebuilding, one, 2500L).lamp)
    }

    @Test
    fun aMovedListingReadsAsTheProjectsListing() {
        // RunStaleProduction binds an empty id for a PROJECT_INDEX change; landscape.js spells the
        // same fallback and so must this.
        val listing = LcncStaleMarker.merge(
            null,
            mapOf(
                "runId" to "r1", "programKey" to programKey, "programCid" to programCid,
                "project" to "genesis-notes", "kind" to LcncConsumedLedger.PROJECT_INDEX, "id" to "",
                "oldCid" to "sha256:" + "c".repeat(64), "newCid" to "sha256:" + "f".repeat(64),
                "sequence" to "", "deleted" to "false", "files" to "3",
            ),
            atMs = 9000L,
        )
        assertEquals(listOf("genesis-notes/ listing"), LcncRunHead.movedNames(listing))
        assertEquals(emptyList(), LcncRunHead.movedNames(null))
        assertEquals(emptyList(), LcncRunHead.movedNames(mapOf("count" to 1)))
    }

    // ── the projection ──────────────────────────────────────────────────────

    @Test
    fun theBodyIsBoundedAndSaysWhenTheNamedNodeRecordedNothing() {
        val head = headOf(receipt(
            "r1", "completed",
            outputs = mapOf("n-show" to mapOf("x" to "a.md: alpha\n\nb.md: beta")),
            returns = mapOf("summaries" to listOf("alpha", "beta")),
            extra = mapOf("consumed" to (1..100).map { mapOf("kind" to "project", "id" to "d$it.md", "cid" to "sha256:$it", "seq" to it, "rev" to "1-x") }),
        ))
        val verdict = LcncRunHead.decide(head, null, 10_000L)
        val shown = LcncRunHead.headBody("corpus", programKey, programCid, inputs, head, verdict, null, "n-show")
        assertEquals(mapOf("x" to "a.md: alpha\n\nb.md: beta"), shown["shown"])
        assertEquals(false, shown["shownMissing"])
        assertEquals(LcncRunHead.CONSUMED_LIMIT, (shown["consumed"] as List<*>).size)
        assertEquals(setOf("kind", "id", "cid"), ((shown["consumed"] as List<*>).first() as Map<*, *>).keys)
        // No `show`: the run's returns are the artifact.
        val returns = LcncRunHead.headBody("corpus", programKey, programCid, inputs, head, verdict, null, null)
        assertEquals(mapOf("summaries" to listOf("alpha", "beta")), returns["shown"])
        // A node that never ran is said, not guessed at.
        val missing = LcncRunHead.headBody("corpus", programKey, programCid, inputs, head, verdict, null, "n-ghost")
        assertNull(missing["shown"])
        assertEquals(true, missing["shownMissing"])
        // An oversized output degrades to a flag; the lamp still burns.
        val huge = headOf(receipt("r2", "completed", outputs = mapOf("n-show" to "x".repeat(200_000))))
        val hugeBody = LcncRunHead.headBody("corpus", programKey, programCid, inputs, huge, LcncRunHead.decide(huge, null, 1L), null, "n-show")
        assertNull(hugeBody["shown"])
        assertEquals(true, hugeBody["shownTruncated"])
        assertEquals("completed", hugeBody["status"])
    }

    /**
     * THE MARKER THAT OUTGREW THE FRAME. `LcncStaleMarker.merge` folds one firing per moved
     * document into ONE marker and never expires a row, `RunStaleProduction` fires once per
     * consumed document whose cid moved with no cap of its own, and the consumed ledger allows
     * 1024 documents — so a completed run over a busy project really does accumulate hundreds of
     * rows. Projected whole they cost 13 budget nodes and about 200 characters each (two
     * 71-character cids), the route's own `ValueBudget` preflight refuses the body, and the Stale
     * target loses BOTH its lamp and the Rebuild button that is the only thing that would fix it.
     */
    @Test
    fun aMarkerNamingHundredsOfMovedDocumentsStillFitsOneBudget() {
        val moved = (1..700).map { "note-$it.md" }
        var mark: Any? = null
        for ((index, id) in moved.withIndex()) {
            mark = LcncStaleMarker.merge(
                mark,
                mapOf(
                    "runId" to "r1", "receiptKey" to (LcncRunHead.RUN_PREFIX + "r1"),
                    "receiptCid" to "sha256:" + "1".repeat(64),
                    "programKey" to programKey, "programCid" to programCid, "project" to "genesis-notes",
                    "kind" to LcncConsumedLedger.PROJECT, "id" to id,
                    "oldCid" to "sha256:" + "c".repeat(64),
                    "newCid" to "sha256:" + index.toString().padStart(64, '0'),
                    "sequence" to index.toString(), "deleted" to "false",
                ),
                atMs = 9000L,
            )
        }
        // The run read the whole project, so the consumed list is at the ledger's own limit too.
        val head = headOf(receipt(
            "r1", "completed",
            outputs = mapOf("n-show" to mapOf("x" to "a.md: alpha")),
            extra = mapOf("consumed" to moved.map { mapOf("kind" to "project", "id" to it, "cid" to "sha256:" + "e".repeat(64), "seq" to 1, "rev" to "1-x") }),
        ))
        val verdict = LcncRunHead.decide(head, mark, 10_000L)
        assertEquals(LcncRunHead.Lamp.STALE, verdict.lamp)
        assertEquals(700, verdict.moved.size, "the verdict knows all of them")
        // The reason names the first few and counts the rest, rather than 700 file names in one sentence.
        assertTrue(verdict.reason.contains("note-1.md"), verdict.reason)
        assertTrue(verdict.reason.contains("and 692 more"), verdict.reason)
        assertTrue(verdict.reason.length < 500, "the reason is a sentence, not a listing: ${verdict.reason.length}")

        val body = LcncRunHead.headBody("corpus", programKey, programCid, inputs, head, verdict, mark, "n-show")
        assertNull(ValueBudget().violation(body), "the route's own preflight must pass the body it mints")
        assertEquals("stale", body["lamp"])
        val stale = body["stale"] as Map<*, *>
        assertEquals(700, stale["count"], "the count is the marker's own, whole count")
        assertEquals(LcncRunHead.CONSUMED_LIMIT, (stale["inputs"] as List<*>).size)
        assertEquals(true, body["staleTruncated"])
        assertEquals(LcncRunHead.CONSUMED_LIMIT, (body["moved"] as List<*>).size)
        assertEquals(true, body["movedTruncated"])
        assertEquals(LcncRunHead.CONSUMED_LIMIT, (body["consumed"] as List<*>).size)
        assertEquals(true, body["consumedTruncated"])
        assertEquals(mapOf("x" to "a.md: alpha"), body["shown"], "the artifact still paints")
        // Nothing is left for the route to shed: what it mints is what it sends.
        assertEquals(body, LcncRunHead.fitToBudget(body))
    }

    /** Node arithmetic exactly as `ValueBudget.visit` counts it: one per value, keys included. */
    private fun nodes(value: Any?): Int = when (value) {
        is Map<*, *> -> 1 + value.entries.sumOf { nodes(it.key) + nodes(it.value) }
        is List<*> -> 1 + value.sumOf { nodes(it) }
        else -> 1
    }

    /**
     * The shed ladder, in order. A read route that answers 413 leaves a frame with no lamp and no
     * button; one that answers with less says the truth it still has. The lamp, the reason and the
     * run id are the last things to go — they are what the button is drawn from.
     */
    @Test
    fun anOversizedBodyShedsInOrderAndOnlyThenIsRefused() {
        val rows = (1..200).map {
            linkedMapOf<String, Any?>(
                "kind" to "project", "project" to "genesis-notes", "id" to "note-$it.md",
                "oldCid" to "sha256:" + "c".repeat(64), "newCid" to "sha256:" + "d".repeat(64), "deleted" to false,
            )
        }
        val body = linkedMapOf<String, Any?>(
            "ok" to true, "lamp" to "stale", "word" to "Stale", "reason" to "Completed against inputs that have since changed",
            "moved" to (1..64).map { "note-$it.md" }, "movedTruncated" to true,
            "runId" to "r1", "receiptCid" to "sha256:" + "b".repeat(64),
            "stale" to linkedMapOf<String, Any?>("count" to 200, "inputs" to rows), "staleTruncated" to false,
            "shown" to (1..300).map { mapOf("line" to "x".repeat(40)) },
            "shownTruncated" to false,
            "consumed" to (1..64).map { mapOf("kind" to "project", "id" to "note-$it.md", "cid" to "sha256:" + "e".repeat(64)) },
            "consumedTruncated" to false,
        )
        // An ordinary body is handed back untouched.
        assertEquals(body, LcncRunHead.fitToBudget(body))

        val full = nodes(body)
        val afterShown = full - nodes(body["shown"]) + 1
        val afterStale = afterShown - nodes((body["stale"] as Map<*, *>)["inputs"]) + 1
        val afterConsumed = afterStale - nodes(body["consumed"]) + 1
        val afterMoved = afterConsumed - nodes(body["moved"]) + 1

        val shed1 = LcncRunHead.fitToBudget(body, ValueBudget(maxNodes = full - 1))!!
        assertNull(shed1["shown"], "the output goes first: its receipt cid still reads it from /api/lcnc/content")
        assertEquals(true, shed1["shownTruncated"])
        assertEquals(200, ((shed1["stale"] as Map<*, *>)["inputs"] as List<*>).size, "nothing else is shed while shedding the output was enough")

        val shed2 = LcncRunHead.fitToBudget(body, ValueBudget(maxNodes = afterShown - 1))!!
        assertEquals(emptyList<Any?>(), (shed2["stale"] as Map<*, *>)["inputs"])
        assertEquals(200, (shed2["stale"] as Map<*, *>)["count"], "how many moved survives losing which")
        assertEquals(true, shed2["staleTruncated"])
        assertEquals(64, (shed2["consumed"] as List<*>).size)

        val shed3 = LcncRunHead.fitToBudget(body, ValueBudget(maxNodes = afterStale - 1))!!
        assertEquals(emptyList<Any?>(), shed3["consumed"])
        assertEquals(true, shed3["consumedTruncated"])
        assertEquals(64, (shed3["moved"] as List<*>).size)

        val shed4 = LcncRunHead.fitToBudget(body, ValueBudget(maxNodes = afterConsumed - 1))!!
        assertEquals(emptyList<Any?>(), shed4["moved"])
        assertEquals(true, shed4["movedTruncated"])
        for (shed in listOf(shed1, shed2, shed3, shed4)) {
            assertEquals("stale", shed["lamp"], "every shed body still burns its lamp")
            assertEquals("r1", shed["runId"], "and still names the run its button would rebuild")
        }
        // Only when the skeleton alone is over budget is there nothing honest left to answer.
        assertNull(LcncRunHead.fitToBudget(body, ValueBudget(maxNodes = afterMoved - 1)))
    }

    @Test
    fun theBodyCarriesTheVerdictTheStaleRowsAndTheRebuildLineage() {
        val head = headOf(receipt("r2", "completed", extra = mapOf(
            "rebuildOf" to "sha256:" + "9".repeat(64), "rebuildOfRunId" to "r1",
        )))
        val mark = marker("r2", "a.md" to "sha256:" + "d".repeat(64))
        val body = LcncRunHead.headBody("corpus", programKey, programCid, inputs, head, LcncRunHead.decide(head, mark, 10_000L), mark, null)
        assertEquals("stale", body["lamp"])
        assertEquals("Stale", body["word"])
        assertEquals(listOf("a.md"), body["moved"])
        assertEquals("r1", body["rebuildOfRunId"])
        assertEquals("sha256:" + "9".repeat(64), body["rebuildOf"])
        assertEquals(1, (body["stale"] as Map<*, *>)["count"])
        assertEquals("a.md", ((body["stale"] as Map<*, *>)["inputs"] as List<*>).map { (it as Map<*, *>)["id"] }.single())
        assertEquals("""{"project":"genesis-notes"}""", body["inputsCanonical"])
        // The whole body serialises and stays inside one budget.
        assertNull(borg.trikeshed.parse.json.ValueBudget().violation(body))
        assertTrue(JsonSupport.stringify(body).contains("\"lamp\":\"stale\""))
    }
}
