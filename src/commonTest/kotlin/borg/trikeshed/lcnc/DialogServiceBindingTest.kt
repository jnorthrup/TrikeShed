package borg.trikeshed.lcnc

import borg.trikeshed.lib.view
import borg.trikeshed.modelmux.BrainChatOptions
import borg.trikeshed.modelmux.BrainClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import borg.trikeshed.agent.modelmux.MuxCallContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DialogServiceBindingTest {
    @Test
    fun modelMuxDialogKeepsNodeOptionsWiredInputsAndCallerContext() = runTest {
        val caller = MuxCallContext(conversationId = 2)
        var calls = 0
        val brain = object : BrainClient(apiKey = "fixture") {
            override suspend fun chat(options: BrainChatOptions, messages: List<Pair<String, String>>, contextId: String?): Pair<String, String> {
                calls++
                assertSame(caller, currentCoroutineContext()[MuxCallContext])
                assertTrue(currentCoroutineContext()[Job]!!.isActive)
                assertEquals(73, options.maxTokens)
                assertEquals(0.4, options.temperature)
                assertEquals(listOf("override" to "fixture"), options.models.view.map { it.pair })
                assertEquals(listOf("system" to "wired rules", "user" to "wired question"), messages)
                return "answer" to "answered-model"
            }
        }
        val default = Job().also { it.cancel() } + MuxCallContext(conversationId = 1)
        val registry = TribunalNodes.registry(modelMuxDialog(brain, default))
        val node = LcncNode("seat", "mux.chat", mapOf("prompt" to "fallback", "system" to "fallback",
            "maxTokens" to "73", "temperature" to "0.4", "models" to """[{"key":"override","model":"fixture"}]"""))
        withContext(caller) {
            val out = registry.getValue("mux.chat").run(node, mapOf("prompt?" to "wired question", "system?" to "wired rules"))
            assertEquals(mapOf("content" to "answer", "model" to "answered-model", "prompt" to "wired question"), out)
            assertFailsWith<IllegalArgumentException> {
                registry.getValue("mux.chat").run(node.copy(params = node.params + ("maxTokens" to "0")), emptyMap())
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun tribunalDialogAndRecordResolveCallerOverrides() = runTest {
        val registry = TribunalNodes.registry(TribunalDialog { _, _, _ -> error("default dialog") }) { error("default ingest") }
        val dialog = TribunalDialog { node, system, prompt ->
            assertEquals("seat", node.id)
            assertEquals("caller system", system)
            assertEquals("wired prompt", prompt)
            "answer" to "caller-model"
        }
        withContext(TribunalDialogKey(dialog) + TribunalRecordKey { "recorded:$it" }) {
            val seat = registry.getValue("mux.chat").run(
                LcncNode("seat", "mux.chat", mapOf("system" to "default system", "prompt" to "default prompt")),
                mapOf("system?" to "caller system", "prompt?" to "wired prompt"),
            )
            assertEquals("answer", seat["content"])
            assertEquals("caller-model", seat["model"])
            val report = registry.getValue("tribunal.record").run(LcncNode("record", "tribunal.record"), mapOf("text?" to seat["content"]))["report"] as Map<*, *>
            assertEquals("recorded:answer", report["recorded"])
            assertSame(dialog, TribunalDialogKey.require())
        }
    }

    @Test
    fun hermesDialogPreservesCallerAttributionAndIgnoresDefaultJob() = runTest {
        val defaultJob = Job().also { it.cancel() }
        val defaultAttribution = MuxCallContext(conversationId = 1)
        val callerAttribution = MuxCallContext(conversationId = 2)
        val dialog = hermesEnvDialog({ _, _ -> {
            assertSame(callerAttribution, currentCoroutineContext()[MuxCallContext])
            assertTrue(currentCoroutineContext()[Job]!!.isActive)
            "response" to "model"
        } }, defaultJob + defaultAttribution)
        withContext(callerAttribution) {
            val registry = TribunalNodes.registry(dialog)
            val out = registry.getValue("mux.chat").run(LcncNode("seat", "mux.chat"), mapOf("prompt" to "prompt"))
            assertEquals("response", out["content"])
            assertSame(callerAttribution, currentCoroutineContext()[MuxCallContext])
        }
    }

    @Test
    fun councilDialogKeepsSeatPolicyAndCancellation() = runTest {
        val registry = CouncilNodes.registry(CouncilDialog { error("default dialog") })
        val node = LcncNode("seat", "council.seat", mapOf(
            "model" to "preferred", "maxTokens" to "73", "temperature" to "0.4", "contextId" to "case-context",
        ))
        withContext(CouncilDialogKey(CouncilDialog { call ->
            assertEquals("preferred", call.preferredModel)
            assertEquals(73, call.maxTokens)
            assertEquals(0.4, call.temperature)
            assertEquals("case-context", call.contextId)
            SeatOutcome.Ok("answer", "actual-model")
        })) {
            val out = registry.getValue("council.seat").run(node, mapOf("prompt?" to "question"))
            assertEquals("actual-model", out["model"])
            assertEquals("ok", (out["record"] as Map<*, *>)["status"])
        }
        withContext(CouncilDialogKey(CouncilDialog { throw CancellationException("cancel seat") })) {
            assertFailsWith<CancellationException> { registry.getValue("council.seat").run(node, mapOf("prompt" to "question")) }
        }
    }

    @Test
    fun councilRecordAndReadbackUseTheSameSelectedStore() = runTest {
        val default = InMemoryRecordStore()
        val selected = InMemoryRecordStore()
        val seams = RecordSeams.inMemory(selected)
        val registry = CouncilNodes.registry(CouncilDialog { SeatOutcome.Ok("unused", "unused") }, RecordSeams.inMemory(default))
        withContext(CouncilRecordKey(seams)) {
            val recorded = registry.getValue("council.record").run(
                LcncNode("record", "council.record"),
                mapOf("caseId" to "bound", "verdict" to mapOf("disposition" to "accepted"), "transcript" to "recorded transcript"),
            )
            assertEquals("ruled", (recorded["report"] as Map<*, *>)["status"])
            val readback = registry.getValue("council.case").run(LcncNode("case", "council.case"), mapOf("caseId" to "bound"))["case"] as Map<*, *>
            assertEquals("recorded transcript", readback["transcript"])
            assertTrue(readback["verdict"].toString().contains("accepted"))
            assertSame(seams, CouncilRecordKey.require())
        }
        assertTrue(default.cas.isEmpty())
        assertTrue(default.couch.isEmpty())
        assertEquals(1, selected.rulings.size)
    }

    @Test
    fun dialogMetadataReportsRealProviderFacets() {
        val tribunal = TribunalNodes.registry(TribunalDialog { _, _, _ -> "answer" to "model" })
        val council = CouncilNodes.registry(CouncilDialog { SeatOutcome.Ok("answer", "model") })
        val expected = mapOf(
            tribunal.getValue("mux.chat") to TribunalDialogKey,
            tribunal.getValue("tribunal.record") to TribunalRecordKey,
            council.getValue("council.seat") to CouncilDialogKey,
            council.getValue("council.record") to CouncilRecordKey,
            council.getValue("council.case") to CouncilRecordKey,
        )
        for ((runner, key) in expected) {
            val binding = runner as LcncServiceBinding
            assertEquals(setOf(key), binding.requiredKeys.view.toSet())
            assertEquals(setOf(key), binding.providedKeys.view.toSet())
        }
        assertFalse(tribunal.getValue("display") is LcncServiceBinding)
        assertFalse(council.getValue("text.fold") is LcncServiceBinding)
    }
}
