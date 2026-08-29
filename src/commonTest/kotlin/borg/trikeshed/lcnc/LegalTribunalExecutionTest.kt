package borg.trikeshed.lcnc

import borg.trikeshed.kanban.validate
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `preset-legal-tribunal` had never been run through the one executor —
 * only structurally validated (contract/port/kanban checks). This is the
 * same gate [TribunalExecutionTest] proved for the base preset, applied to
 * the legal variant: it catches wiring mistakes structural validation
 * cannot, because kind tags describe declared shape, not the actual
 * runtime value a node returns.
 *
 * It would have caught the bug already found and fixed by hand: `n1`
 * (`legal.ingest`) originally had no way to receive the document text at
 * all (no wire targets it, no `text` param, no `brief` binding) — running
 * it for real would have thrown `legal.ingest requires text` on the first
 * sweep. `legal.ingest`'s real runner also needs a live [borg.trikeshed.jules.BrainClient],
 * so — same as [TribunalExecutionTest] fakes the model dialog — this test
 * fakes `legal.ingest` with the identical brief-resolution precedence the
 * real [borg.trikeshed.narsese.LegalNodes.ingestRunner] uses (wired input >
 * param > root frame binding), so the wiring itself is what's under test.
 */
class LegalTribunalExecutionTest {

    private fun preset(): LcncProgram =
        LcncProgramConfix.fromJson("preset-legal-tribunal", LcncPresets.all().getValue("preset-legal-tribunal"))

    private fun legalIngestFake(): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val text = ((inputs["text"] as? String)
            ?: (inputs["text?"] as? String)
            ?: node.params["text"]?.takeIf { it.isNotBlank() }
            ?: node.params["brief"]?.takeIf { it.isNotBlank() }?.let { briefName ->
                currentCoroutineContext()[LcncScopeFrame]?.binding(briefName)?.toString()
            }
        )?.takeIf { it.isNotBlank() }
        require(text != null) { "legal.ingest: no text wired, in params, or bound as '${node.params["brief"] ?: "<brief>"}'" }
        mapOf(
            "citations" to listOf(mapOf("case" to "Miranda v. Arizona", "reporter" to "U.S.", "page" to "436")),
            "elements" to mapOf("holdings" to emptyList<Any?>(), "parties" to emptyList<Any?>(), "refused" to emptyList<Any?>()),
            "brief" to "Grounded citations:\n  - Miranda v. Arizona U.S. 436\nsource: $text",
        )
    }

    // legal.evidence's real runner lives in jvmMain (it needs a live
    // KifKnowledgeBase); this commonTest fakes it the same way it fakes
    // legal.ingest — a pass-through is exactly production's own behavior
    // when the shared bank has no facts for this document yet.
    private fun legalEvidenceFake(): LcncNodeRunner = LcncNodeRunner { _, inputs ->
        val brief = ((inputs["brief"] ?: inputs["brief?"]) as? String).orEmpty()
        mapOf("brief" to brief)
    }

    private fun runner(program: LcncProgram): LcncRunner {
        val registry = mutableMapOf<String, LcncNodeRunner>()
        val dialog = TribunalDialog { node, system, prompt -> (system + "::" + prompt) to (node.params["job"] ?: node.id) }
        registry.putAll(TribunalNodes.registry(dialog) { verdict -> verdict })
        registry["legal.ingest"] = legalIngestFake()
        registry["legal.evidence"] = legalEvidenceFake()
        return LcncRunner(registry)
    }

    @Test
    fun everyLegalTribunalNodeTypeIsContractDeclaredAndServed() {
        val program = preset()
        val types = (0 until program.nodes.size).map { program.nodes[it].type }.toSet()
        for (t in types) assertNotNullContract(t)
        assertTrue("legal.ingest" in types)
    }

    private fun assertNotNullContract(type: String) {
        assertTrue(LcncContracts.find(type) != null, "preset type '$type' must be contract-declared")
    }

    @Test
    fun legalTribunalRunsEndToEndThroughTheOneExecutor() = runTest {
        val program = preset()
        // The human-oversight brief enters as the ROOT frame binding `brief`
        // — legal.ingest (n1) has no wired input, so this is the only way
        // the document text reaches it (the bug this test exists to catch).
        val result = runner(program).runProcedure(
            program,
            args = mapOf("brief" to "42 U.S.C. sec 1983 claim: excessive force during arrest."),
        )

        val ingest = result.nodeOutputs["n1"]!!
        val brief = ingest["brief"] as String
        assertTrue(brief.contains("42 U.S.C. sec 1983 claim"), "legal.ingest must see the root-bound brief")

        // n1.brief -> n1b (legal.evidence, evidence-bank query) -> n2.prompt?
        val evidence = result.nodeOutputs["n1b"]!!
        assertEquals(brief, evidence["brief"], "with no prior facts in the bank, legal.evidence passes the brief through unchanged")

        // counsel argues over the (evidence-folded) brief, not raw text.
        val argue = result.nodeOutputs["n2"]!!
        assertEquals("argue", argue["model"])
        val argueText = argue["content"] as String
        assertTrue(argueText.endsWith(brief), "argue's prompt must be legal.ingest's brief output")

        val rebut = result.nodeOutputs["n3"]!!
        assertEquals("rebut", rebut["model"])
        val rebutText = rebut["content"] as String
        assertTrue(rebutText.endsWith(argueText), "rebut prompt must be the prior seat's content")

        val judge = result.nodeOutputs["n4"]!!
        assertEquals("deliberate", judge["model"])
        val verdict = judge["content"] as String
        assertTrue(verdict.endsWith(rebutText), "judge prompt must be the prior seat's content")

        val report = (result.nodeOutputs["n5"]!!["report"]) as Map<*, *>
        assertEquals(verdict, report["text"], "the verdict must actually be recorded, not silently dropped")
        assertEquals(report, (result.nodeOutputs["n6"]!!["x"]) as Map<*, *>)
    }

    @Test
    fun legalTribunalKanbanCarriesClarificationLoopAndValidates() {
        val g = preset().kanban
        assertTrue(g != null, "legal-tribunal ships its orchestration graph")
        val v = g!!.validate(TribunalPredicates.registry())
        assertTrue(v.valid, "legal-tribunal kanban validates: ${v.errors}")
        val clarify = (0 until g.edges.size).map { g.edges[it] }.first { it.id == "deliberate-clarify" }
        assertEquals("deliberate", clarify.from)
        assertEquals("argue", clarify.to)
    }

    @Test
    fun withoutABriefLegalIngestRefusesRatherThanRunningBlind() = runTest {
        val program = preset()
        val thrown = runCatching { runner(program).runProcedure(program, args = emptyMap()) }
        assertTrue(thrown.isFailure, "legal.ingest must refuse to run with no text at all")
        assertIs<IllegalArgumentException>(thrown.exceptionOrNull())
    }
}
