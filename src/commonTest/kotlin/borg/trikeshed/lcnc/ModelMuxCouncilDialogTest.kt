package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.modelmux.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

class ModelMuxCouncilDialogTest {
    class Brain : BrainClient(apiKey = "fixture", base = "https://fixture.invalid/v1", model = "fixture") {
        var selected: BrainChatOptions? = null
        var selectedCalls = 0
        var preferredCalls = 0
        var preferred: String? = null
        var context: String? = null
        var messages: List<Pair<String, String>> = emptyList()
        var failure: Throwable? = null

        override suspend fun chat(options: BrainChatOptions, messages: List<Pair<String, String>>, contextId: String?): Pair<String, String> {
            selectedCalls++; selected = options; this.messages = messages; context = contextId
            failure?.let { throw it }
            return "bounded answer" to "selected-model"
        }

        override suspend fun chatSeat(messages: List<Pair<String, String>>, maxTokens: Int, temperature: Double,
            contextId: String?, preferredModel: String?, timeoutMs: Long): Pair<String, String> {
            preferredCalls++; preferred = preferredModel; this.messages = messages; context = contextId
            selected = BrainChatOptions(maxTokens, temperature)
            failure?.let { throw it }
            return "preferred answer" to "preferred-model"
        }
    }

    fun call(models: String? = null) = SeatCall(
        node = LcncNode("judge", "council.seat", params = if (models == null) emptyMap() else mapOf(
            "models" to models, "maxTokens" to "caller-normalized", "temperature" to "caller-normalized")),
        caseId = "case", panel = "appeal", seat = "judge", role = "judge", round = 2,
        system = "Weigh the record", prompt = "Retained evidence", preferredModel = "outside-allowlist",
        maxTokens = 73, temperature = 0.4, contextId = "case/appeal/judge/2",
    )

    @Test fun explicitRoutesUseOnlyTheBoundedCallEvenWhenAllSelectedRoutesRefuse() = runTest {
        val brain = Brain()
        val dialog = modelMuxCouncilDialog(brain, EmptyCoroutineContext)
        val call = call("""[{"key":"provider-b","model":"model-b"},{"key":"provider-a","model":"model-a"}]""")
        assertEquals(SeatOutcome.Ok("bounded answer", "selected-model"), dialog.seat(call))
        val options = assertNotNull(brain.selected)
        assertEquals(73, options.maxTokens); assertEquals(0.4, options.temperature)
        assertEquals(2, options.models.size)
        assertEquals("provider-b" to "model-b", options.models[0].pair)
        assertEquals("provider-a" to "model-a", options.models[1].pair)
        assertEquals(listOf("system" to call.system, "user" to call.prompt), brain.messages)
        assertEquals(call.contextId, brain.context)
        val attempts = listOf("provider-b/model-b: refused", "provider-a/model-a: refused")
        brain.failure = BrainNoRoute(attempts)
        assertEquals(attempts, assertIs<SeatOutcome.Refused>(dialog.seat(call)).attempted)
        assertEquals(2, brain.selectedCalls)
        assertEquals(0, brain.preferredCalls, "A selected route failure cannot retry through the unrestricted preferred-seat lane")
    }

    @Test fun anUnconfiguredSeatRetainsTheExistingModelPreferenceAndCallLimits() = runTest {
        val brain = Brain()
        val call = call()
        val answer = modelMuxCouncilDialog(brain, EmptyCoroutineContext).seat(call)
        assertEquals(SeatOutcome.Ok("preferred answer", "preferred-model"), answer)
        assertEquals(0, brain.selectedCalls); assertEquals(1, brain.preferredCalls)
        assertEquals(call.preferredModel, brain.preferred)
        assertEquals(call.contextId, brain.context)
        assertEquals(call.maxTokens, brain.selected?.maxTokens)
        assertEquals(call.temperature, brain.selected?.temperature)
    }

    @Test fun malformedRouteRowsRefuseBeforeEitherProviderLaneIsCalled() = runTest {
        val brain = Brain()
        val answer = modelMuxCouncilDialog(brain, EmptyCoroutineContext).seat(call("[{}]"))
        assertIs<SeatOutcome.Refused>(answer)
        assertEquals(0, brain.selectedCalls); assertEquals(0, brain.preferredCalls)
    }

    @Test fun cancellationRemainsCancellationAcrossTheCouncilDialog() = runTest {
        val brain = Brain().apply { failure = CancellationException("fixture canceled") }
        assertFailsWith<CancellationException> {
            modelMuxCouncilDialog(brain, EmptyCoroutineContext).seat(call("""[{"key":"provider","model":"model"}]"""))
        }
        assertEquals(1, brain.selectedCalls); assertEquals(0, brain.preferredCalls)
    }
}
