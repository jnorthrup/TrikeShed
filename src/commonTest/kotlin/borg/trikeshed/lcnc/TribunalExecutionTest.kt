package borg.trikeshed.lcnc

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tribunal execution gate. The preset was a document whose node types
 * (mux.chat / kg.ingest / display) had NO in-process runner — the first
 * sweep threw LcncUnknownNodeType. This test proves the fix:
 *
 *  - every tribunal node type is contract-declared AND registry-served,
 *  - the preset EXECUTES end to end through the one executor with a
 *    model dialog (the hermes-env seam in tests is an echo — the daemon
 *    wires BrainClient/KeyMux in production),
 *  - the model dialog is real: seats receive the role system prompt and
 *    the prior seat's content, the verdict is content-addressed,
 *  - the instance is mutable + versionable: opened from the preset's
 *    pre-canned lanes (root data at revision 1), each schema-gated
 *    advance bumps the revision and mints a new snapshot cid, and a
 *    stale expectedRevision is rejected.
 */
class TribunalExecutionTest {

    private fun preset(): LcncProgram =
        LcncProgramConfix.fromJson("preset-tribunal", LcncPresets.all().getValue("preset-tribunal"))

    private fun runner(program: LcncProgram): LcncRunner {
        val registry = mutableMapOf<String, LcncNodeRunner>()
        // The tribunal seat — the model dialog the hermes-env seam stands in for.
        val dialog = TribunalDialog { node, system, prompt ->
            // (content, model) — the dialog contract: the seat's words first,
            // the provenance (the seat's job id stands in for the model that
            // answered) second.
            (system + "::" + prompt) to (node.params["job"] ?: node.id)
        }
        val ingested = mutableListOf<String>()
        registry.putAll(TribunalNodes.registry(dialog) { verdict ->
            ingested.add(verdict)
            verdict
        })
        // The canned motion literal the preset ships (t0/n12 sockets): one
        // shared source, PureNodes' text.value runner.
        registry["text.value"] = PureNodes.registry { 0L }["text.value"]!!
        return LcncRunner(registry)
    }

    @Test
    fun tribunalNodeTypesAreContractDeclaredAndRegistryServed() {
        val program = preset()
        val types = (0 until program.nodes.size).map { program.nodes[it].type }.toSet()
        for (t in types) {
            assertNotNull(LcncContracts.find(t), "preset type '$t' must be contract-declared")
        }
        // The three tribunal seats are the gap this test fixes: each needs an
        // in-process runner, else the first sweep throws LcncUnknownNodeType.
        for (t in setOf("mux.chat", "kg.ingest", "display")) {
            assertTrue(t in types, "preset-tribunal must still use '$t'")
            assertTrue(t in TribunalNodes.servedTypes(), "tribunal type '$t' must have an in-process runner")
        }
    }

    @Test
    fun tribunalRunsEndToEndThroughTheOneExecutor() = runTest {
        val program = preset()
        // The human-oversight brief enters as the ROOT frame binding `brief`
        // (the preset's first seat reads it through the frame chain — the
        // warm base). runProcedure's args ARE the root bindings.
        val result = runner(program).runProcedure(
            program,
            args = mapOf("brief" to "the motion: admit the record as evidence"),
        )

        // Counsel consumes the brief from the root frame (no wire — the warm base).
        val argue = result.nodeOutputs["n2"]!!
        assertEquals("argue", argue["model"])
        val argueText = argue["content"] as String
        assertTrue(argueText.endsWith("the motion: admit the record as evidence"), argueText)
        assertTrue(argueText.contains("You are counsel for the motion"), argueText)

        // Opposing counsel receives counsel's CONTENT on its prompt input.
        val rebut = result.nodeOutputs["n3"]!!
        assertEquals("rebut", rebut["model"])
        val rebutText = rebut["content"] as String
        assertTrue(rebutText.endsWith(argueText), "rebut prompt must be the prior seat's content")

        // The judge receives the record; the verdict is content-addressed.
        val judge = result.nodeOutputs["n4"]!!
        assertEquals("deliberate", judge["model"])
        val verdict = judge["content"] as String
        assertTrue(verdict.endsWith(rebutText), "judge prompt must be the prior seat's content")

        val report = (result.nodeOutputs["n5"]!!["report"]) as Map<*, *>
        assertEquals(verdict, report["text"])
        val cid = report["cid"] as String
        assertTrue(cid.startsWith("sha256:") && cid.length == 71, "verdict cid must be content-addressed")
        assertEquals(verdict.length, report["chars"], "report carries the verdict length")
        // display passed the report through unchanged.
        assertEquals(report, (result.nodeOutputs["n6"]!!["x"]) as Map<*, *>)
    }

    @Test
    fun seatsCarryTheirJobForLifecycleTracking() {
        val program = preset()
        fun jobOf(id: String): String {
            val n = (0 until program.nodes.size).map { program.nodes[it] }.first { it.id == id }
            return n.params["job"] ?: ""
        }
        assertEquals("argue", jobOf("n2"))
        assertEquals("rebut", jobOf("n3"))
        assertEquals("deliberate", jobOf("n4"))
        // The lanes the instance seeds from — root pre-canned data.
        val lanes = (0 until program.kanban!!.lanes.size).map { program.kanban!!.lanes[it].id }
        assertEquals(listOf("brief", "argue", "rebut", "deliberate", "mistrial"), lanes)
    }

    @Test
    fun instanceIsMutableAndVersionableFromPresetRoot() = runTest {
        val plan = TribunalInstance.schemaPlan()
        // The schema gate: a bogus operation is rejected before it reaches the WAL.
        val instance = TribunalInstance.open(
            this, plan, LcncPresets.all().getValue("preset-tribunal"),
        )
        assertEquals(listOf("brief", "argue", "rebut", "deliberate", "mistrial"), instance.laneIds)
        instance.awaitRootSeeds()
        for (lane in instance.laneIds) {
            assertEquals(1L, instance.revision(lane), "lane $lane must seed at root revision 1")
            assertEquals("submitted", instance.lifecycle(lane), "lane $lane seeds at lifecycle submitted")
            assertNotNull(instance.snapshotCid(lane), "lane $lane must carry a content-addressed snapshot cid")
        }

        // Mutate one lane: the schema-gated advance bumps the revision and
        // mints a NEW snapshot cid (versionable, not just mutable).
        val before = instance.snapshotCid("argue")!!
        assertIs<borg.trikeshed.job.JobEvent.Accepted>(
            instance.advance("argue", "complete", "t-test-complete-argue", 1L),
        )
        assertEquals(2L, instance.revision("argue"))
        assertEquals("closed", instance.lifecycle("argue"))
        val after = instance.snapshotCid("argue")!!
        assertTrue(before != after, "a new commit must mint a new snapshot cid")

        // Stale expectedRevision: the optimistic-concurrency guard rejects.
        assertIs<borg.trikeshed.job.JobEvent.Rejected>(
            instance.advance("argue", "fail", "t-test-stale-argue", 1L, reason="stale"),
        )
        instance.nexus.drain()
        assertEquals(2L, instance.revision("argue"), "a stale command must not advance the revision")
    }
}
