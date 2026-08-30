package borg.trikeshed.wiki

import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.LcncContracts
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mechanics of the two WikiSkill legos against a FAKE dialog — zero spend.
 *
 * What is proven here is exactly what the wire cannot prove cheaply: PATCH
 * application (replace/insert on an existing page, refusal of a span that is
 * not there), the provenance gate, response capture, the RUNNER-emitted read
 * log, and proposal atomicity. The real GLM run proves the model half.
 */
class WikiNodesMechanicsTest {

    private val tmp = File(
        System.getProperty("java.io.tmpdir"),
        "wiki-mechanics-${System.nanoTime()}",
    ).also { it.mkdirs() }

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    private val cidA = ContentId.of("trace-A: the failing run".encodeToByteArray()).value
    private val cidB = ContentId.of("trace-B: the passing run".encodeToByteArray()).value

    private val traces = WikiNodes.WikiTraceLoader { cid ->
        when (cid) {
            cidA -> WikiNodes.WikiTrace(cidA, "trace-A: the failing run", "fake")
            cidB -> WikiNodes.WikiTrace(cidB, "trace-B: the passing run", "fake")
            else -> null
        }
    }

    /** A scripted dialog: replies in order, and records every prompt it saw. */
    private class ScriptedDialog(vararg replies: String) : WikiNodes.WikiDialog {
        val queue = ArrayDeque(replies.toList())
        val prompts = ArrayList<String>()
        val contextIds = ArrayList<String>()
        override suspend fun ask(call: WikiNodes.WikiCall): WikiNodes.WikiReply {
            prompts.add(call.prompt)
            contextIds.add(call.contextId)
            return WikiNodes.WikiReply(queue.removeFirst(), "fake-model-1")
        }
    }

    private fun node(type: String, params: Map<String, String>) =
        LcncNode(id = "test-$type", type = type, params = params)

    @Suppress("UNCHECKED_CAST")
    private fun report(out: Map<String, Any?>): Map<String, Any?> = out["report"] as Map<String, Any?>

    // ── VAL-CROSS-001: both legos are declared vocabulary ──────────────

    @Test
    fun bothLegosAreDeclaredInTheContractPalette() {
        val consolidate = LcncContracts.find(LcncContracts.WIKI_CONSOLIDATE)
        val propose = LcncContracts.find(LcncContracts.WIKI_PROPOSE)
        assertNotNull(consolidate, "wiki.consolidate missing from LcncContracts.all()")
        assertNotNull(propose, "wiki.propose missing from LcncContracts.all()")
        assertEquals(listOf("report"), consolidate.outputs)
        assertEquals(listOf("report"), propose.outputs)
        assertTrue("cids" in consolidate.params.keys)
        assertTrue("summary" in propose.params.keys)
        // The vocabulary is unique — no duplicate type registration.
        assertEquals(1, LcncContracts.all().count { it.type == LcncContracts.WIKI_CONSOLIDATE })
        assertEquals(1, LcncContracts.all().count { it.type == LcncContracts.WIKI_PROPOSE })
    }

    // ── VAL-WIKI-001: iteration 1 creates, iteration 2 PATCHES ─────────

    @Test
    fun iterationOneCreatesAndIterationTwoPatchesAnExistingPattern() = runBlocking {
        val root = File(tmp, "wiki1")
        val dialog1 = ScriptedDialog(
            JsonSupport.stringify(mapOf(
                "analysis" to "root cause on A, strategy from B",
                "edits" to listOf(
                    mapOf(
                        "op" to "create", "file" to "patterns/marker-blind-verdict.md",
                        "text" to "# Marker-blind verdict\n\nRoot cause: no literal marker.\n\n" +
                            "Workaround: emit an explicit marker.\n\nTraces: $cidA\n",
                    ),
                    mapOf("op" to "append", "file" to "index.md", "text" to "- marker-blind-verdict\n"),
                    mapOf("op" to "append", "file" to "logs.md", "text" to "iter1: created 1 pattern\n"),
                ),
            )),
        )
        val runner1 = WikiNodes.consolidateRunner(dialog1, { root }, traces, clock = { 1_000L })
        val r1 = report(runner1.run(node(LcncContracts.WIKI_CONSOLIDATE, mapOf(
            "cids" to "$cidA,$cidB", "iteration" to "1", "contextId" to "ctx-iter-1",
        )), emptyMap()))
        assertEquals(true, r1["ok"])
        val page = File(root, "patterns/marker-blind-verdict.md")
        assertTrue(page.isFile, "iteration 1 did not create the pattern page")
        val beforePatch = page.readText()
        assertTrue(cidA in beforePatch, "pattern page must name its transcript cid")

        // The Maintainer received the traces and the (empty) prior wiki.
        assertTrue(cidA in dialog1.prompts[0] && cidB in dialog1.prompts[0])

        // Iteration 2: a REPLACE and an INSERT on the page iteration 1 wrote,
        // plus one refused span that is not present.
        val dialog2 = ScriptedDialog(
            JsonSupport.stringify(mapOf(
                "analysis" to "sharpen the workaround",
                "edits" to listOf(
                    mapOf(
                        "op" to "replace", "file" to "patterns/marker-blind-verdict.md",
                        "find" to "Workaround: emit an explicit marker.",
                        "text" to "Workaround: emit `[pass]`/`[fail]` in the closing turn.",
                    ),
                    mapOf(
                        "op" to "insert", "file" to "patterns/marker-blind-verdict.md",
                        "after" to "Traces: $cidA",
                        "text" to ", $cidB\n",
                    ),
                    mapOf(
                        "op" to "replace", "file" to "patterns/marker-blind-verdict.md",
                        "find" to "THIS SPAN IS NOT IN THE FILE",
                        "text" to "should never land",
                    ),
                    mapOf("op" to "append", "file" to "logs.md", "text" to "iter2: patched 1 pattern\n"),
                ),
            )),
        )
        val runner2 = WikiNodes.consolidateRunner(dialog2, { root }, traces, clock = { 2_000L })
        val r2 = report(runner2.run(node(LcncContracts.WIKI_CONSOLIDATE, mapOf(
            "cids" to cidB, "iteration" to "2", "contextId" to "ctx-iter-2",
        )), emptyMap()))
        assertEquals(true, r2["ok"])
        val afterPatch = page.readText()
        assertTrue("[pass]`/`[fail]" in afterPatch, "replace op did not apply")
        assertTrue("should never land" !in afterPatch, "a non-matching span must be refused")
        assertTrue(cidB in afterPatch, "insert op did not apply")
        assertTrue(cidA in afterPatch, "patch must be incremental — prior provenance survives")

        @Suppress("UNCHECKED_CAST") val applied = r2["applied"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val refused = r2["refused"] as List<Map<String, Any?>>
        assertEquals(3, applied.size, "replace + insert + logs.md append should apply")
        assertEquals(1, refused.size, "the absent span must be reported as refused")
        assertTrue((refused[0]["reason"] as String).contains("not present"))

        // The wiki is NEVER rolled back: iteration 2 saw iteration 1's page.
        assertTrue("Marker-blind verdict" in dialog2.prompts[0], "prior wiki W(k-1) was not fed to iteration 2")

        // logs.md carries BOTH iterations, each with its response cid.
        val logs = File(root, "logs.md").readText()
        assertTrue("ctx-iter-1" in logs && "ctx-iter-2" in logs)
        assertEquals(2, Regex("responseCid=sha256:[0-9a-f]{64}").findAll(logs).count())

        // Response capture is mandatory and correlated by contextId.
        val capture = File(root, "raw-responses/ctx-iter-2.json")
        assertTrue(capture.isFile, "the model response was not persisted")
        val captured = JsonSupport.parse(capture.readText()) as Map<*, *>
        assertEquals("ctx-iter-2", captured["contextId"])
        assertEquals(r2["responseCid"], captured["responseCid"])
        assertTrue((captured["response"] as String).contains("sharpen the workaround"))
        // …and the captured bytes hash to the recorded cid.
        assertEquals(
            r2["responseCid"],
            ContentId.of((captured["response"] as String).encodeToByteArray()).value,
        )
    }

    @Test
    fun aPatternPageWithoutTraceProvenanceIsRefused() = runBlocking {
        val root = File(tmp, "wiki2")
        val dialog = ScriptedDialog(
            JsonSupport.stringify(mapOf("edits" to listOf(
                mapOf("op" to "create", "file" to "patterns/no-provenance.md", "text" to "# Nothing cited\n"),
            ))),
        )
        val r = report(WikiNodes.consolidateRunner(dialog, { root }, traces, clock = { 3_000L })
            .run(node(LcncContracts.WIKI_CONSOLIDATE, mapOf("cids" to cidA, "contextId" to "ctx-gate")), emptyMap()))
        assertEquals(true, r["ok"])
        assertFalse(File(root, "patterns/no-provenance.md").exists(), "an unprovenanced page must not be written")
        @Suppress("UNCHECKED_CAST") val refused = r["refused"] as List<Map<String, Any?>>
        assertEquals(1, refused.size)
        assertTrue((refused[0]["reason"] as String).contains("provenance"))
    }

    @Test
    fun anEditThatWouldStripAPagesProvenanceIsRefused() = runBlocking {
        val root = File(tmp, "wiki2b")
        File(root, "patterns").mkdirs()
        val page = File(root, "patterns/p1.md")
        // Bare hex, no `sha256:` prefix — the spelling a Maintainer's prose uses.
        page.writeText("# P1\n\nSee trace `${cidA.removePrefix("sha256:")}`\n")
        val dialog = ScriptedDialog(
            JsonSupport.stringify(mapOf("edits" to listOf(
                // (a) an edit that keeps the bare-hex provenance is allowed…
                mapOf("op" to "append", "file" to "patterns/p1.md", "text" to "\nRefined workaround.\n"),
                // (b) …one that strips the only cid is not.
                mapOf(
                    "op" to "replace", "file" to "patterns/p1.md",
                    "find" to "See trace `${cidA.removePrefix("sha256:")}`",
                    "text" to "See trace (dropped)",
                ),
            ))),
        )
        val r = report(WikiNodes.consolidateRunner(dialog, { root }, traces, clock = { 3_500L })
            .run(node(LcncContracts.WIKI_CONSOLIDATE, mapOf("cids" to cidB, "contextId" to "ctx-strip")), emptyMap()))
        @Suppress("UNCHECKED_CAST") val applied = r["applied"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val refused = r["refused"] as List<Map<String, Any?>>
        assertEquals(1, applied.size, "a bare-hex cid is provenance too — the append must apply")
        assertEquals(1, refused.size)
        assertTrue((refused[0]["reason"] as String).contains("no transcript cid"))
        val text = page.readText()
        assertTrue(cidA.removePrefix("sha256:") in text, "the wiki is never rolled back — provenance must survive")
        assertTrue("Refined workaround." in text)
    }

    @Test
    fun anUnparsableMaintainerReplyMutatesNothingButIsStillCaptured() = runBlocking {
        val root = File(tmp, "wiki3")
        val dialog = ScriptedDialog("I thought about it but produced no JSON.")
        val r = report(WikiNodes.consolidateRunner(dialog, { root }, traces, clock = { 4_000L })
            .run(node(LcncContracts.WIKI_CONSOLIDATE, mapOf("cids" to cidA, "contextId" to "ctx-bad")), emptyMap()))
        assertEquals(false, r["ok"])
        assertEquals("unparsable_edit_script", r["error"])
        assertEquals(0, File(root, "patterns").listFiles()?.size ?: 0)
        assertTrue(File(root, "raw-responses/ctx-bad.json").isFile, "even a refused turn is captured")
        assertTrue("REFUSED" in File(root, "logs.md").readText())
    }

    @Test
    fun writesOutsideTheWikiRootAreRefused() = runBlocking {
        val root = File(tmp, "wiki4")
        val dialog = ScriptedDialog(
            JsonSupport.stringify(mapOf("edits" to listOf(
                mapOf("op" to "create", "file" to "../escape.md", "text" to "nope $cidA"),
                mapOf("op" to "create", "file" to "/etc/absolute.md", "text" to "nope $cidA"),
            ))),
        )
        val r = report(WikiNodes.consolidateRunner(dialog, { root }, traces, clock = { 5_000L })
            .run(node(LcncContracts.WIKI_CONSOLIDATE, mapOf("cids" to cidA, "contextId" to "ctx-escape")), emptyMap()))
        @Suppress("UNCHECKED_CAST") val refused = r["refused"] as List<Map<String, Any?>>
        assertEquals(2, refused.size)
        assertFalse(File(tmp, "escape.md").exists())
    }

    // ── VAL-WIKI-002: ReAct reads are runner-performed and runner-logged ─

    @Test
    fun proposerReadsOnDemandAndEmitsOneAtomicProposal() = runBlocking {
        val root = File(tmp, "wiki5")
        File(root, "patterns").mkdirs()
        File(root, "patterns/marker-blind-verdict.md").writeText("# Marker-blind verdict\n\nTraces: $cidA\n")
        File(root, "index.md").writeText("- marker-blind-verdict\n")
        File(root, "skill-impact.md").writeText("(no prior proposals)\n")

        val dialog = ScriptedDialog(
            JsonSupport.stringify(mapOf(
                "action" to "read",
                "targets" to listOf("patterns/marker-blind-verdict.md", "trace:$cidA", "patterns/absent.md"),
                "why" to "diagnose",
            )),
            JsonSupport.stringify(mapOf(
                "action" to "propose",
                "skill" to "verdict-marker-discipline",
                "kind" to "new",
                "skillMd" to "# Verdict marker discipline\n\nAlways close with [pass] or [fail].\n",
                "purposeMd" to "Motivated by patterns/marker-blind-verdict.md (trace $cidA).\n",
                "patterns" to listOf("patterns/marker-blind-verdict.md"),
            )),
        )
        val r = report(WikiNodes.proposeRunner(dialog, { root }, traces, clock = { 6_000L })
            .run(node(LcncContracts.WIKI_PROPOSE, mapOf(
                "summary" to "0 signals over 12 transcripts", "contextId" to "ctx-prop",
            )), emptyMap()))
        assertEquals(true, r["ok"], "proposal was refused: ${r["refusals"]}")
        assertEquals("verdict-marker-discipline", r["skill"])
        assertEquals("new", r["kind"])

        // The paper's initial context, and ONLY it, on turn 1.
        val opening = dialog.prompts[0]
        assertTrue("marker-blind-verdict" in opening, "index.md missing from the opening")
        assertTrue("no prior proposals" in opening, "skill-impact.md missing from the opening")
        assertTrue("0 signals over 12 transcripts" in opening, "outcome summary missing from the opening")
        assertFalse("Traces: $cidA" in opening, "pattern page CONTENT must not be pre-fed — it is read on demand")
        assertFalse("trace-A: the failing run" in opening, "raw traces must not be pre-fed")

        // Turn 2 saw exactly what the RUNNER read back.
        assertTrue("trace-A: the failing run" in dialog.prompts[1], "the runner's read was not fed back")

        // The read log is a machine artifact written by the runner, in order.
        val readLog = File(root, "read-log/ctx-prop.jsonl")
        assertTrue(readLog.isFile, "no runner-emitted read log")
        val lines = readLog.readLines().filter { it.isNotBlank() }.map { JsonSupport.parse(it) as Map<*, *> }
        assertEquals(3, lines.size)
        assertEquals(listOf(1, 2, 3), lines.map { (it["seq"] as Number).toInt() })
        assertEquals("patterns/marker-blind-verdict.md", lines[0]["target"])
        assertEquals("wiki", lines[0]["source"])
        assertEquals("trace:$cidA", lines[1]["target"])
        assertEquals("not_found", lines[2]["source"])

        // ONE atomic proposal: SKILL.md + PURPOSE.md under exactly one skill dir.
        val skillDir = File(root, "skills/verdict-marker-discipline")
        assertTrue(File(skillDir, "SKILL.md").isFile)
        assertTrue(File(skillDir, "PURPOSE.md").isFile)
        assertEquals(1, File(root, "skills").listFiles()?.count { it.isDirectory })
        assertTrue("patterns/marker-blind-verdict.md" in File(skillDir, "PURPOSE.md").readText())

        // skill-impact.md gained the proposal metadata entry.
        val impact = File(root, "skill-impact.md").readText()
        assertTrue("targetSkill=verdict-marker-discipline" in impact)
        assertTrue("validationScore=pending" in impact && "acceptance=pending" in impact)

        // Both turns were captured, correlated by contextId.
        assertTrue(File(root, "raw-responses/ctx-prop_t1.json").isFile)
        assertTrue(File(root, "raw-responses/ctx-prop_t2.json").isFile)
    }

    @Test
    fun aProposalTouchingTwoSkillsIsRefusedAndWritesNothing() = runBlocking {
        val root = File(tmp, "wiki6")
        File(root, "patterns").mkdirs()
        File(root, "patterns/p1.md").writeText("# P1\n$cidA\n")
        val dialog = ScriptedDialog(
            JsonSupport.stringify(mapOf(
                "action" to "propose", "kind" to "new",
                "skill" to "one", "skills" to listOf("two"),
                "skillMd" to "x", "purposeMd" to "patterns/p1.md",
            )),
        )
        val r = report(WikiNodes.proposeRunner(dialog, { root }, traces, clock = { 7_000L })
            .run(node(LcncContracts.WIKI_PROPOSE, mapOf("contextId" to "ctx-multi")), emptyMap()))
        assertEquals(false, r["ok"])
        assertEquals("proposal_refused", r["error"])
        assertEquals(0, File(root, "skills").listFiles()?.size ?: 0)
    }

    @Test
    fun aProposalWhosePurposeMapsToNoPatternIsRefused() = runBlocking {
        val root = File(tmp, "wiki7")
        File(root, "patterns").mkdirs()
        File(root, "patterns/p1.md").writeText("# P1\n$cidA\n")
        val dialog = ScriptedDialog(
            JsonSupport.stringify(mapOf(
                "action" to "propose", "kind" to "new", "skill" to "orphan",
                "skillMd" to "x", "purposeMd" to "Motivated by nothing in particular.",
            )),
        )
        val r = report(WikiNodes.proposeRunner(dialog, { root }, traces, clock = { 8_000L })
            .run(node(LcncContracts.WIKI_PROPOSE, mapOf("contextId" to "ctx-orphan")), emptyMap()))
        assertEquals(false, r["ok"])
        @Suppress("UNCHECKED_CAST") val refusals = r["refusals"] as List<String>
        assertTrue(refusals.any { "PURPOSE" in it })
        assertEquals(0, File(root, "skills").listFiles()?.size ?: 0)
    }
}
