package borg.trikeshed.docs

import borg.trikeshed.lcnc.LcncBlackboard
import borg.trikeshed.lcnc.LcncConsumedLedger
import borg.trikeshed.lcnc.LcncRunHead
import borg.trikeshed.lcnc.LcncStaleMarker
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.parse.json.ValueBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The run block (AutoTools, Cut B): the fence grammar, the frame, and the refusals — pinned on
 * every target, because the page that renders this in the browser and the daemon that could render
 * it anywhere must agree character for character.
 */
class RunBlockTest {

    private val body = """{"program":"corpus","inputs":{"project":"genesis-notes"},"show":"n-show"}"""
    private val page = "# Digest\n\nA note.\n\n```lcnc-run\n$body\n```\n\nafter\n"

    private fun spec(json: String = body, ordinal: Int = 0) = RunBlock.parse(ordinal, json) as RunBlock.Spec

    // ── the fence ───────────────────────────────────────────────────────────

    @Test
    fun theFenceIsFoundAndItsBodyIsTheRequestVerbatim() {
        val blocks = RunBlock.scan(page)
        val one = blocks.single() as RunBlock.Spec
        assertEquals(0, one.ordinal)
        assertEquals("corpus", one.program)
        assertEquals("n-show", one.show)
        assertEquals(mapOf("project" to "genesis-notes"), one.inputs)
        assertEquals("""{"project":"genesis-notes"}""", one.canonicalInputs)
        // Build posts these bytes, so everything the author wrote — timeoutMs, maxNodes — rides along.
        assertEquals(body + "\n", one.body)
        assertEquals(LcncRunHead.inputsKey("corpus", one.canonicalInputs), one.key)
    }

    @Test
    fun onlyLcncRunFencesTakeAnOrdinalAndAQuotedOneIsNotATarget() {
        val many = "```kotlin\nval x = 1\n```\n\n```lcnc-run\n{\"program\":\"a\"}\n```\n\n" +
            "```json\n{}\n```\n\n```lcnc-run\n{\"program\":\"b\"}\n```\n"
        val blocks = RunBlock.scan(many)
        assertEquals(listOf(0, 1), blocks.map { it.ordinal })
        assertEquals(listOf("a", "b"), blocks.map { (it as RunBlock.Spec).program })
        // Quoting a build target is quoting it, not arming it: the blockquote arm eats those lines
        // before the fence arm sees them, in the scan and in the renderer alike.
        assertEquals(emptyList(), RunBlock.scan("> ```lcnc-run\n> {\"program\":\"a\"}\n> ```\n"))
        assertEquals(emptyList(), RunBlock.scan("# no blocks here\n"))
    }

    @Test
    fun theRendererHandsTheRawBodyToTheHandlerAndKeepsUnclaimedFencesLiteral() {
        var seen: Pair<String, String>? = null
        val html = DocumentMarkdown.render(page) { info, raw -> seen = info to raw; if (info == RunBlock.INFO) "<b>frame</b>" else null }
        assertEquals(RunBlock.INFO to body + "\n", seen, "the handler gets the RAW body, not the escaped one")
        assertTrue(html.contains("<b>frame</b>"), html)
        assertFalse(html.contains("<pre><code"), html)
        // With no handler the block is a plain fenced code block, exactly as before.
        val plain = DocumentMarkdown.render(page)
        assertTrue(plain.contains("<pre><code class=\"language-lcnc-run\">"), plain)
        assertTrue(plain.contains("&quot;program&quot;"), plain)
    }

    // ── the refusals ────────────────────────────────────────────────────────

    @Test
    fun aBlockThatIsNotARequestRendersItsOwnBytesBackWithNoButton() {
        val cases = mapOf(
            "not json at all" to "not a JSON object",
            "[1,2]" to "not a JSON object",
            """{"inputs":{}}""" to "names no program",
            """{"program":"","inputs":{}}""" to "names no program",
            """{"program":"corpus","inputs":[]}""" to "inputs_must_be_object",
            """{"program":"corpus","show":3}""" to "show names one node id",
            // An inline document runs with a null programKey, which the head route can never find:
            // refusing at parse time is how the page never grows a button whose result it cannot show.
            """{"document":{"nodes":[]},"program":"x"}""" to "never an inline document",
        )
        for ((json, fragment) in cases) {
            val parsed = RunBlock.parse(0, json)
            val bad = parsed as? RunBlock.Bad ?: fail("$json should be refused, got $parsed")
            assertTrue(bad.detail.contains(fragment), "$json: ${bad.detail}")
            val html = RunBlock.refusalHtml(bad)
            assertFalse(html.contains("<button"), html)
            assertTrue(html.contains("Not a run block"), html)
            // The whole page still renders around a refused block, with the frame in the flow.
            assertTrue(DocumentSurface.documentHtml(
                DocView("p", "d.md", "sha256:x", "1-x", 0, "text/markdown", "```lcnc-run\n$json\n```\n"),
            ).contains("ds-run-refused"))
        }
        // The refused body comes back escaped: a page's bytes are never markup.
        val html = RunBlock.refusalHtml(RunBlock.parse(0, """{"nope":"<script>alert(1)</script>"}""") as RunBlock.Bad)
        assertFalse(html.contains("<script>"), html)
        assertTrue(html.contains("&lt;script&gt;"), html)
        // And so does an accepted one: the program name and the inputs are author-controlled too.
        val framed = RunBlock.frameHtml(spec("""{"program":"<script>x</script>","inputs":{"p":"<b>"}}"""), null)
        assertFalse(framed.contains("<script>"), framed)
        assertTrue(framed.contains("&lt;script&gt;x&lt;/script&gt;"), framed)
        assertTrue(framed.contains("&lt;b&gt;"), framed)
    }

    // ── the read route's answer ─────────────────────────────────────────────

    @Test
    fun theRoutesAnswerBecomesTheFramesState() {
        val answer = JsonSupport.parse("""{"ok":true,"program":"corpus","programCid":"sha256:aa","lamp":"stale","word":"Stale",
            "reason":"Completed against inputs that have since changed: a.md","moved":["a.md"],
            "runId":"r1","receiptCid":"sha256:bb","latestReceiptCid":"sha256:bb","rebuildOf":"sha256:cc",
            "show":"n-show","shown":{"x":"a.md: alpha"},"shownMissing":false,"shownTruncated":false,
            "stale":{"count":1,"inputs":[]},"activeRuns":0}""")
        val state = RunBlock.state(answer)!!
        assertEquals(LcncRunHead.Lamp.STALE, state.lamp)
        assertEquals(listOf("a.md"), state.moved)
        assertEquals("r1", state.runId)
        assertEquals(1, state.staleCount)
        assertEquals(mapOf("x" to "a.md: alpha"), state.shown)
        // A refusal is a lamp-less state that says so, never a Build that would 404.
        val absent = RunBlock.state(JsonSupport.parse("""{"error":"no_such_program","program":"ghost"}"""))!!
        assertNull(absent.lamp)
        assertTrue(absent.reason.contains("ghost"), absent.reason)
        assertFalse(RunBlock.frameHtml(spec(), absent).contains("<button"), "nothing to press when the program is absent")
        // Anything else leaves the page's last state alone.
        assertNull(RunBlock.state(null))
        assertNull(RunBlock.state(JsonSupport.parse("""{"ok":true}""")))
    }

    // ── the contract with the route's own body ──────────────────────────────

    private val programKey = LcncBlackboard.programKey("corpus")
    private val programCid = "sha256:" + "a".repeat(64)
    private val runInputs = mapOf("project" to "genesis-notes")

    private fun receipt(
        runId: String,
        status: String,
        startedAtMs: Long = 1000L,
        sequence: Long = 1L,
        outputs: Map<String, Any?> = emptyMap(),
        extra: Map<String, Any?> = emptyMap(),
    ): Pair<String, Any?> = LcncRunHead.RUN_PREFIX + runId to (mapOf(
        "runId" to runId, "status" to status, "program" to "corpus",
        "programKey" to programKey, "programCid" to programCid, "inputs" to runInputs,
        "startedAtMs" to startedAtMs, "sequence" to sequence,
        "receiptCid" to "sha256:" + runId.padStart(64, '0'),
        "budgets" to mapOf("timeoutMs" to 120000L, "maxNodes" to 10000L),
        "outputs" to outputs,
    ) + extra)

    private fun headOf(vararg entries: Pair<String, Any?>) =
        LcncRunHead.head(LcncRunHead.rows(mapOf(*entries)), programKey, programCid, LcncRunHead.canonicalInputs(runInputs))

    /** A marker the rule could really produce, so the Stale case is the rule's own shape. */
    private fun staleMarker(runId: String, docId: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return LcncStaleMarker.merge(
            null,
            mapOf(
                "runId" to runId, "receiptKey" to (LcncRunHead.RUN_PREFIX + runId),
                "receiptCid" to "sha256:" + runId.padStart(64, '0'),
                "programKey" to programKey, "programCid" to programCid, "project" to "genesis-notes",
                "kind" to LcncConsumedLedger.PROJECT, "id" to docId,
                "oldCid" to "sha256:" + "c".repeat(64), "newCid" to "sha256:" + "d".repeat(64),
                "sequence" to "7", "deleted" to "false",
            ),
            atMs = 9000L,
        ) as Map<String, Any?>
    }

    /** The route's own body, minted and round-tripped through the one stringifier and parser. */
    private fun wire(head: LcncRunHead.Head, marker: Any?, nowMs: Long): Any? {
        val verdict = LcncRunHead.decide(head, marker, nowMs)
        val body = LcncRunHead.headBody("corpus", programKey, programCid, runInputs, head, verdict, marker, "n-show")
        // A 200 body never carries the refusal envelope's word; the run's own message is `runError`.
        assertFalse(body.containsKey("error"), "an answer must not speak the refusal's vocabulary: $body")
        assertEquals(true, body["ok"])
        // And it fits the preflight the route runs over it, or the reader gets a 413 instead of a frame.
        assertNull(ValueBudget().violation(body), "the route's own preflight must pass the body it mints")
        return JsonSupport.parse(JsonSupport.stringify(body))
    }

    /** The route's own body, through the wire and into a frame — which is the only path that counts. */
    private fun frameOf(head: LcncRunHead.Head, marker: Any?, nowMs: Long): Pair<RunBlock.State, String> {
        val state = RunBlock.state(wire(head, marker, nowMs)) ?: fail("the route's own body must become a state")
        return state to RunBlock.frameHtml(spec(), state)
    }

    /**
     * THE BOUNDARY THE SUITE USED TO STRADDLE. Every case above builds a `State` by hand or feeds
     * `state()` a hand-written body; this one takes the bytes [LcncRunHead.headBody] actually mints,
     * round-trips them through the one stringifier and parser the wire uses, and asserts the frame.
     * All five lamps, because the failure it caught was a body field (`runError`, once spelled
     * `error`) that made every Failed frame read as an unavailable route with nothing to press.
     */
    @Test
    fun everyLampTheRouteMintsSurvivesTheWireAndKeepsExactlyOneButton() {
        val wanted = mapOf(
            LcncRunHead.Lamp.NEVER_BUILT to "data-run-build=\"0\">Build<",
            LcncRunHead.Lamp.RUNNING to "disabled>Building",
            LcncRunHead.Lamp.COMPLETED to "data-run-rebuild=\"0\">Rebuild<",
            LcncRunHead.Lamp.STALE to "data-run-rebuild=\"0\">Rebuild<",
            LcncRunHead.Lamp.FAILED to "data-run-build=\"0\">Build<",
        )
        val cases = listOf(
            LcncRunHead.Lamp.NEVER_BUILT to frameOf(headOf(), null, 5000L),
            LcncRunHead.Lamp.RUNNING to frameOf(headOf(receipt("r1", "running")), null, 5000L),
            LcncRunHead.Lamp.COMPLETED to frameOf(headOf(receipt("r1", "completed")), null, 5000L),
            LcncRunHead.Lamp.STALE to frameOf(headOf(receipt("r1", "completed")), staleMarker("r1", "a.md"), 10_000L),
            LcncRunHead.Lamp.FAILED to frameOf(
                headOf(receipt("bad", "failed", startedAtMs = 2000L, extra = mapOf("phase" to "execution", "error" to "provider refused"))),
                null, 5000L,
            ),
        )
        for ((lamp, framed) in cases) {
            val (state, html) = framed
            assertEquals(lamp, state.lamp, "the route's own body must burn $lamp, not ${state.word}")
            assertEquals(lamp.word, state.word)
            assertFalse(state.refused, "$lamp is an answer, never a refusal: ${state.reason}")
            assertEquals(1, Regex("<button").findAll(html).count(), "$lamp: $html")
            assertTrue(html.contains(wanted.getValue(lamp)), "$lamp: $html")
            assertTrue(html.contains("ds-run-lamp-" + lamp.slug), "$lamp: $html")
        }
        assertEquals(listOf("a.md"), cases.first { it.first == LcncRunHead.Lamp.STALE }.second.first.moved)
    }

    /**
     * The restart path, named: every failure status the runner can write carries a message, and
     * `interrupted`/`runtime_restarted` is the one a daemon restart stamps on a run that was in
     * flight. Each of them must be a Failed frame with a Build button — the only way back.
     */
    @Test
    fun everyFailureTheRunnerCanWriteStillOffersBuild() {
        val failures = mapOf(
            "failed" to "the provider refused", "refused" to "type_check_failed",
            "timed_out" to "time_limit", "cancelled" to "cancelled",
            "interrupted" to "runtime_restarted",
        )
        for ((status, message) in failures) {
            // The last good build still on the board, the way make keeps the old .o.
            val head = headOf(
                receipt("good", "completed", startedAtMs = 1000L, outputs = mapOf("n-show" to "the digest")),
                receipt("bad", status, startedAtMs = 2000L, extra = mapOf("phase" to "execution", "error" to message)),
            )
            val (state, html) = frameOf(head, null, 5000L)
            assertEquals(LcncRunHead.Lamp.FAILED, state.lamp, "$status: ${state.word} / ${state.reason}")
            assertFalse(state.refused, "$status must not read as a refused route: ${state.reason}")
            assertTrue(state.reason.contains(message), "$status: ${state.reason}")
            assertEquals(1, Regex("<button").findAll(html).count(), "$status: $html")
            assertTrue(html.contains("data-run-build=\"0\">Build<"), "$status: $html")
            // And the last good artifact still paints beside the failure.
            assertEquals("good", state.runId, "$status: Rebuild would name the artifact, not the failure")
            assertTrue(html.contains("<div class=\"ds-run-output\">"), "$status: $html")
        }
    }

    /**
     * A marker deep enough to have refused the whole frame. The rule merges one row per moved
     * document and never expires one, so this is the shape of a completed run over a project
     * whose documents kept moving — the exact target the cut exists to rebuild, and the one that
     * used to answer 413 and paint "Unavailable" with nothing to press.
     */
    @Test
    fun aMarkerNamingHundredsOfMovedDocumentsStillPaintsOneRebuildButton() {
        var mark: Any? = null
        for (i in 1..700) {
            mark = LcncStaleMarker.merge(
                mark,
                mapOf(
                    "runId" to "r1", "receiptKey" to (LcncRunHead.RUN_PREFIX + "r1"),
                    "receiptCid" to "sha256:" + "1".repeat(64),
                    "programKey" to programKey, "programCid" to programCid, "project" to "genesis-notes",
                    "kind" to LcncConsumedLedger.PROJECT, "id" to "note-$i.md",
                    "oldCid" to "sha256:" + "c".repeat(64), "newCid" to "sha256:" + i.toString().padStart(64, '0'),
                    "sequence" to "$i", "deleted" to "false",
                ),
                atMs = 9000L,
            )
        }
        val (state, html) = frameOf(headOf(receipt("r1", "completed")), mark, 10_000L)
        assertEquals(LcncRunHead.Lamp.STALE, state.lamp, state.reason)
        assertEquals(1, Regex("<button").findAll(html).count(), html)
        assertTrue(html.contains("data-run-rebuild=\"0\">Rebuild<"), html)
        assertTrue(html.contains("ds-run-lamp-stale"), html)
        // The frame shows the names it was given and says how many it was not.
        assertEquals(LcncRunHead.CONSUMED_LIMIT, state.moved.size)
        assertTrue(state.movedTruncated)
        assertEquals(700, state.staleCount)
        assertTrue(html.contains("note-1.md"), html)
        assertTrue(html.contains("and " + (700 - LcncRunHead.CONSUMED_LIMIT) + " more"), html)
    }

    /**
     * WHAT A POLL MAY TOUCH. The page reads every block's head every three seconds, and `#ds-doc`
     * is the scroll container: a re-render on a quiet tick throws the reader back to the top of a
     * long page, drops the text they were selecting, and — for a tick between mousedown and
     * mouseup — replaces the button under the cursor so the click never fires. The page's guard is
     * structural equality on [RunBlock.State], so a head that has not moved MUST read back equal —
     * including a stuck Running one, whose reason carries the elapsed time and is therefore
     * floored to a coarse grain rather than counting seconds under the reader's cursor.
     */
    @Test
    fun aQuietHeadReadsBackAsTheSameStateSoAPollRepaintsNothing() {
        val done = headOf(receipt("r1", "completed"))
        val first = RunBlock.state(wire(done, null, 5_000L))
        assertEquals(first, RunBlock.state(wire(done, null, 8_000L)), "an unchanged completed run is one state")

        val stuck = headOf(receipt("r1", "running", startedAtMs = 1000L))
        val overdue = 1000L + 120_000L + 30_000L
        assertEquals(
            RunBlock.state(wire(stuck, null, overdue)),
            RunBlock.state(wire(stuck, null, overdue + 3_000L)),
            "a stuck run polled three seconds later is still the same frame",
        )
        // A head that DID move is a different state, or the frame would never repaint at all.
        assertNotEquals(first, RunBlock.state(wire(done, staleMarker("r1", "a.md"), 10_000L)))
    }

    /**
     * THE PRESS, PINNED. Which button to draw was already decided in commonMain and asserted on
     * jvm and js; what that button then SENDS used to be three literals inside the browser half,
     * where no test on any target could see them. These are the cut's two MUSTs — Build posts the
     * body to `/api/lcnc/run`, Rebuild posts the run id to `/api/lcnc/run/rebuild` — and the read
     * the frame lives on.
     */
    @Test
    fun theThreeRequestsAFrameCanMakeAreExactlyThese() {
        val one = spec()
        assertEquals("/api/lcnc/runs", RunBlock.HEAD_PATH)
        assertEquals(
            listOf("program" to "corpus", "inputs" to """{"project":"genesis-notes"}""", "show" to "n-show"),
            RunBlock.headQuery(one),
            "the read asks by program and CANONICAL inputs, and names the node only when the block did",
        )
        assertEquals(
            listOf("program" to "corpus", "inputs" to "{}"),
            RunBlock.headQuery(spec("""{"program":"corpus"}""")),
        )
        // Build posts the fence's own bytes, byte for byte: timeoutMs, maxNodes and all. Taken
        // from the page rather than from a hand-built spec, so this is what a reader's press sends.
        val scanned = RunBlock.scan(page).single() as RunBlock.Spec
        assertEquals("/api/lcnc/run" to (body + "\n"), RunBlock.buildRequest(scanned))
        assertEquals("/api/lcnc/run" to one.body, RunBlock.buildRequest(one))
        // Rebuild posts the artifact's run id — and nothing at all when there is none, which is the
        // same guard the button rule makes by drawing Build for a frame with no run.
        assertEquals(
            "/api/lcnc/run/rebuild" to """{"runId":"r1"}""",
            RunBlock.rebuildRequest(state(LcncRunHead.Lamp.COMPLETED)),
        )
        assertNull(RunBlock.rebuildRequest(state(LcncRunHead.Lamp.NEVER_BUILT, runId = null)))
        assertNull(RunBlock.rebuildRequest(null))
        // And the id the page repaints in place is the id the frame renders under.
        assertEquals("ds-run-0", RunBlock.frameId(0))
        assertTrue(RunBlock.frameHtml(one, null).contains("id=\"" + RunBlock.frameId(0) + "\""))
        assertTrue(RunBlock.refusalHtml(RunBlock.parse(1, "{}") as RunBlock.Bad).contains("id=\"" + RunBlock.frameId(1) + "\""))
    }

    @Test
    fun onlyTheRefusalEnvelopeTakesTheButtonAway() {
        // The route's four refusals: `error`, no `ok`, no `lamp`. Nothing to press, and said so.
        for (error in listOf("no_such_program", "program_required", "bad_inputs", "inputs_must_be_object")) {
            val refused = RunBlock.state(JsonSupport.parse("""{"error":"$error","program":"ghost"}"""))!!
            assertTrue(refused.refused, error)
            assertNull(refused.lamp)
            assertFalse(RunBlock.frameHtml(spec(), refused).contains("<button"), error)
            assertTrue(RunBlock.frameHtml(spec(), refused).contains("ds-run-lamp-refused"), error)
        }
        // An ANSWER that happens to carry a run's error text is not a refusal, whatever it is called.
        val answered = RunBlock.state(JsonSupport.parse(
            """{"ok":true,"lamp":"failed","word":"Failed","reason":"The last run failed in execution: boom","runError":"boom","error":"boom","runId":"r1"}""",
        ))!!
        assertEquals(LcncRunHead.Lamp.FAILED, answered.lamp)
        assertFalse(answered.refused)
        assertTrue(RunBlock.frameHtml(spec(), answered).contains("data-run-build=\"0\">Build<"))
    }

    // ── the frame ───────────────────────────────────────────────────────────

    private fun state(lamp: LcncRunHead.Lamp, runId: String? = "r1", receiptCid: String? = null, shown: Any? = null, show: String? = "n-show") =
        RunBlock.State(
            lamp = lamp, word = lamp.word, reason = lamp.word + " reason", runId = runId,
            receiptCid = receiptCid, show = show, shown = shown,
        )

    @Test
    fun oneButtonAndOnlyOne() {
        val s = spec()
        assertTrue(RunBlock.frameHtml(s, state(LcncRunHead.Lamp.NEVER_BUILT, runId = null)).contains("data-run-build=\"0\">Build<"))
        assertTrue(RunBlock.frameHtml(s, state(LcncRunHead.Lamp.FAILED)).contains("data-run-build=\"0\">Build<"))
        assertTrue(RunBlock.frameHtml(s, state(LcncRunHead.Lamp.COMPLETED)).contains("data-run-rebuild=\"0\">Rebuild<"))
        assertTrue(RunBlock.frameHtml(s, state(LcncRunHead.Lamp.STALE)).contains("data-run-rebuild=\"0\">Rebuild<"))
        val running = RunBlock.frameHtml(s, state(LcncRunHead.Lamp.RUNNING))
        assertTrue(running.contains("disabled>Building"), running)
        for (lamp in LcncRunHead.Lamp.entries) {
            val html = RunBlock.frameHtml(s, state(lamp))
            assertEquals(1, Regex("<button").findAll(html).count(), "$lamp: $html")
            assertTrue(html.contains("ds-run-lamp-" + lamp.slug), html)
            assertTrue(html.contains(">" + lamp.word + "<"), html)
        }
        // Before the daemon has answered there is a frame, a disabled button and no invented lamp.
        val unread = RunBlock.frameHtml(s, null)
        assertTrue(unread.contains("ds-run-lamp-unread"), unread)
        assertTrue(unread.contains("Reading the run history"), unread)
        assertTrue(unread.contains("<button class=\"ds-run-button\" disabled>Build</button>"), unread)
    }

    @Test
    fun theFrameShowsTheReceiptCidTheLineageAndTheOutput() {
        val cid = "sha256:" + "a".repeat(64)
        val html = RunBlock.frameHtml(spec(), state(LcncRunHead.Lamp.STALE, receiptCid = cid, shown = mapOf("x" to "# Digest\n\nalpha"))
            .copy(rebuildOf = "sha256:" + "b".repeat(64), moved = listOf("a.md")))
        assertTrue(html.contains("id=\"ds-run-0\" data-run=\"0\""), html)
        assertTrue(html.contains("<code class=\"ds-run-cid\" title=\"$cid\">" + DocumentSurface.shortCid(cid) + "</code>"), html)
        assertTrue(html.contains("rebuildOf"), html)
        assertTrue(html.contains("<dt>moved</dt><dd>a.md</dd>"), html)
        assertTrue(html.contains("<div class=\"ds-run-output\"><h1>Digest</h1>"), "the shown prose renders as markdown: $html")
        // A display sink's key differs by rig, so a one-entry map holding one string IS that string.
        assertEquals("alpha", RunBlock.prose(mapOf("shown" to "alpha")))
        assertEquals("alpha", RunBlock.prose(mapOf("x" to "alpha")))
        assertEquals("alpha", RunBlock.prose("alpha"))
        assertNull(RunBlock.prose(mapOf("a" to "x", "b" to "y")))
        // Everything else is shown as the JSON it is, escaped.
        val listed = RunBlock.frameHtml(spec(), state(LcncRunHead.Lamp.COMPLETED, shown = listOf("<a>", "b"), show = null))
        assertTrue(listed.contains("<pre>[&quot;&lt;a&gt;&quot;, &quot;b&quot;]</pre>"), listed)
    }

    @Test
    fun theFrameIsHonestAboutAnOutputItCannotShow() {
        val missing = RunBlock.frameHtml(spec(), state(LcncRunHead.Lamp.COMPLETED).copy(shownMissing = true))
        assertTrue(missing.contains("No output was recorded for n-show"), missing)
        val truncated = RunBlock.frameHtml(spec(), state(LcncRunHead.Lamp.COMPLETED).copy(shownTruncated = true))
        assertTrue(truncated.contains("too large to show"), truncated)
    }

    @Test
    fun thePressingTabWearsAnOptimisticRunningWithoutLosingTheArtifact() {
        val prior = state(LcncRunHead.Lamp.STALE, receiptCid = "sha256:x", shown = "old digest")
        val pending = RunBlock.pending(prior)
        assertEquals(LcncRunHead.Lamp.RUNNING, pending.lamp)
        assertTrue(pending.pending)
        assertEquals("old digest", pending.shown, "the last good artifact stays on the page while the run holds")
        assertEquals("sha256:x", pending.receiptCid)
        assertEquals(LcncRunHead.Lamp.RUNNING, RunBlock.pending(null).lamp)
    }
}
