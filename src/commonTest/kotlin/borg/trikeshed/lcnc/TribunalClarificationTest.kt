package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** The shipped LCNC programs, not injected Kanban flags, must request the extra work. */
class TribunalClarificationTest {
    private val presets = listOf("preset-tribunal", "preset-legal-tribunal")
    private val question = """Which retained exhibit proves {notice at "Depot\A"?"""

    // Match the daemon's installed families. These tests never call a Council model seat.
    private fun registry(dialog: TribunalDialog, record: suspend (String) -> String): Map<String, LcncNodeRunner> =
        CouncilNodes.registry(CouncilDialog { error("unexpected Council seat") }) +
            TribunalNodes.registry(dialog, record) +
            PureNodes.registry { 0L } + mapOf(
                "legal.ingest" to LcncNodeRunner { node, inputs ->
                    val brief = node.params["brief"]?.let {
                        currentCoroutineContext()[LcncScopeFrame]?.binding(it)?.toString()
                    } ?: (inputs["text?"] ?: inputs["text"])?.toString()
                    require(!brief.isNullOrBlank())
                    mapOf("brief" to "Retained legal evidence: $brief",
                        "documentCid" to ContentId.of(brief.encodeToByteArray()).value)
                },
                "legal.evidence" to LcncNodeRunner { _, inputs ->
                    mapOf("brief" to (inputs["brief?"] ?: inputs["brief"]))
                },
            )

    private fun ruling(clarify: Boolean, mistrial: Boolean = false): String = jsonOf(linkedMapOf(
        "disposition" to if (clarify) "pending" else "resolved",
        "needsClarification" to clarify,
        "clarificationQuestion" to if (clarify) question else "",
        "mistrial" to mistrial,
    ))

    @Test
    fun shippedPresetsClarifyOnceWithTheSameSeatsAndRecordOnlyTheFinalRuling() = runTest {
        val trials = presets.map { it to mapOf("brief" to "Operator exhibit: notice sent on May 3.") } +
            ("preset-tribunal" to emptyMap())
        for ((name, args) in trials) {
            val document = LcncPresets.all().getValue(name)
            val program = LcncProgramConfix.fromJson(name, document)
            val instance = TribunalInstance.open(this, TribunalInstance.schemaPlan(), document)
            val calls = ArrayList<Triple<LcncNode, String, String>>()
            var judgeCalls = 0
            var recordings = 0
            var synced = false
            var writes = 0
            val cas = object : CasStore() {
                override fun put(bytes: ByteArray): ContentId { writes++; return super.put(bytes) }
                override fun sync() { synced = true }
            }
            val pending = ruling(true)
            val final = ruling(false)
            val dialog = TribunalDialog { node, system, prompt ->
                calls.add(Triple(node, system, prompt))
                val job = node.params.getValue("job")
                val round = calls.count { it.first.params["job"] == job }
                val content = if (job == "deliberate") {
                    judgeCalls++
                    if (judgeCalls == 1) pending else final
                } else "$job answer $round"
                content to "actual-$job-model-$round"
            }
            try {
                instance.awaitRootSeeds()
                val record = tribunalRecord(cas) { cid ->
                    recordings++
                    assertTrue(synced, "$name must sync before lifecycle completion")
                    assertEquals(final, cas.get(cid)!!.decodeToString())
                    instance.record(cid)
                }
                val ledger = LcncInvocationLedger()
                val walker = LcncRunner(registry(dialog, record)).also { it.invocations = ledger }
                val result = walker.runProcedure(
                    program, args = args,
                )
                assertEquals(listOf("argue", "rebut", "deliberate", "argue", "rebut", "deliberate"),
                    calls.map { it.first.params["job"] }, name)
                val brief = args["brief"] ?: program.nodes.view.single { it.id == "n2" }.params.getValue("prompt")
                assertTrue(calls[0].third.contains(brief), calls[0].third)
                assertEquals(calls[0].third, result.nodeOutputs.getValue("n2")["prompt"])
                assertEquals("text", LcncContracts.find("mux.chat")!!.outputKinds["prompt"])
                assertEquals("argue answer 1", calls[1].third)
                assertEquals("rebut answer 1", calls[2].third)
                for (index in 3..5) {
                    val first = calls[index - 3]
                    val second = calls[index]
                    assertNotEquals(first.first.id, second.first.id, "rounds have distinct actual node identities")
                    assertEquals(first.first.params, second.first.params, "$name preserves authored routing and model options")
                    assertEquals(first.second, second.second, "$name preserves the actual seat system prompt")
                    assertTrue(second.third.contains(calls[0].third), "the actual effective brief must survive intact: ${second.third}")
                    assertTrue(second.third.contains("argue answer 1"), second.third)
                    assertTrue(second.third.contains("rebut answer 1"), second.third)
                    assertTrue(second.third.contains(pending), second.third)
                    assertTrue(second.third.contains(question), "the decoded structured question must reach the seat: ${second.third}")
                    if (index >= 4) assertTrue(second.third.contains("argue answer 2"), second.third)
                    if (index >= 5) assertTrue(second.third.contains("rebut answer 2"), second.third)
                    val observed = ((ledger.snapshot()["entries"] as List<*>).map { it as Map<*, *> }
                        .single { it["nodeId"] == second.first.id }["outputs"] as Map<*, *>)
                    assertEquals("actual-${second.first.params.getValue("job")}-model-2", observed["model"])
                    assertEquals(second.third, observed["prompt"], "the actual selected prompt is retained as evidence")
                }
                val report = result.nodeOutputs.getValue("n5")["report"] as Map<*, *>
                assertEquals(final, report["text"])
                assertEquals(final, cas.get(ContentId(report["cid"] as String))!!.decodeToString())
                assertFalse(cas.holds(ContentId.of(pending.encodeToByteArray())), "a question is not a retained terminal verdict")
                assertEquals(1, recordings)
                assertEquals(1, writes)
                assertEquals("closed", instance.lifecycle("deliberate"))
                assertEquals(2L, instance.revision("deliberate"), "only one completion reaches the real nexus")
            } finally { instance.nexus.drain() }
        }
    }

    @Test
    fun unresolvedFinalRoundStopsAtOneClarificationWithoutAnyVerdictWriteOrCompletion() = runTest {
        for (name in presets) {
            val document = LcncPresets.all().getValue(name)
            val program = LcncProgramConfix.fromJson(name, document)
            val instance = TribunalInstance.open(this, TribunalInstance.schemaPlan(), document)
            val jobs = ArrayList<String>()
            var writes = 0
            var recordings = 0
            val cas = object : CasStore() {
                override fun put(bytes: ByteArray): ContentId { writes++; return super.put(bytes) }
            }
            val dialog = TribunalDialog { node, _, _ ->
                val job = node.params.getValue("job")
                jobs.add(job)
                (if (job == "deliberate") ruling(true) else "$job evidence") to "actual-$job-model"
            }
            try {
                instance.awaitRootSeeds()
                val record = tribunalRecord(cas) { cid -> recordings++; instance.record(cid) }
                val failure = assertFailsWith<IllegalArgumentException> {
                    LcncRunner(registry(dialog, record)).runProcedure(program,
                        args = mapOf("brief" to "Operator exhibit"))
                }
                assertTrue(failure.message.orEmpty().contains("unresolved clarification"), failure.message)
                assertEquals(listOf("argue", "rebut", "deliberate", "argue", "rebut", "deliberate"), jobs, name)
                assertEquals(0, writes, "no verdict CAS writes at an unresolved bound")
                assertEquals(0, recordings, "no lifecycle callback at an unresolved bound")
                assertEquals("submitted", instance.lifecycle("deliberate"))
                assertEquals(1L, instance.revision("deliberate"))
            } finally { instance.nexus.drain() }
        }
    }

    @Test
    fun explicitMistrialDoesNotRunClarificationOrReplaceTheOriginalVerdict() = runTest {
        for (name in presets) {
            val document = LcncPresets.all().getValue(name)
            val program = LcncProgramConfix.fromJson(name, document)
            val instance = TribunalInstance.open(this, TribunalInstance.schemaPlan(), document)
            val jobs = ArrayList<String>()
            val verdict = ruling(clarify = true, mistrial = true)
            val cas = CasStore.inMemory()
            var recordings = 0
            val dialog = TribunalDialog { node, _, _ ->
                val job = node.params.getValue("job")
                jobs.add(job)
                (if (job == "deliberate") verdict else "$job evidence") to "actual-$job-model"
            }
            try {
                instance.awaitRootSeeds()
                val result = LcncRunner(registry(dialog, tribunalRecord(cas) { cid ->
                    recordings++; instance.record(cid)
                })).runProcedure(program, args = mapOf("brief" to "Operator exhibit"))
                assertEquals(listOf("argue", "rebut", "deliberate"), jobs)
                val report = result.nodeOutputs.getValue("n5")["report"] as Map<*, *>
                assertEquals(verdict, report["text"])
                assertEquals(verdict, cas.get(ContentId(report["cid"] as String))!!.decodeToString())
                assertEquals(1, recordings)
                // The legacy callback completes deliberate; ABORT/mistrial FSM routing is separate.
                assertEquals(2L, instance.revision("deliberate"))
            } finally { instance.nexus.drain() }
        }
    }

    @Test
    fun recordRefusesOnlyExplicitStructuredClarificationAndMistrialRemainsTerminal() = runTest {
        val recorded = ArrayList<String>()
        val runner = TribunalNodes.registry(TribunalDialog { _, _, _ -> error("no model") },
            { text -> recorded.add(text); ContentId.of(text.encodeToByteArray()).value }).getValue("tribunal.record")
        val node = LcncNode("record", "tribunal.record")
        for (flag in listOf<Any>(true, "true")) {
            val text = jsonOf(mapOf("needsClarification" to flag, "mistrial" to false))
            assertFailsWith<IllegalArgumentException> { runner.run(node, mapOf("text?" to text)) }
        }
        assertTrue(recorded.isEmpty())
        val prose = "The ruling quotes needsClarification but makes no structured request."
        runner.run(node, mapOf("text?" to prose))
        val mistrial = ruling(clarify = true, mistrial = true)
        runner.run(node, mapOf("text?" to mistrial))
        assertEquals(listOf(prose, mistrial), recorded, "strict parser semantics, including mistrial precedence")
    }
}
