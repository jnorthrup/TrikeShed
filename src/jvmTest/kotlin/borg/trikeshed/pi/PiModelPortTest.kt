package borg.trikeshed.pi

import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.keymux.TestKeySource
import borg.trikeshed.agent.modelmux.*
import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.vm.Teleported
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiModelPortTest {
    @Test fun actualPiChildUsesModelMuxAndOnlyTheHostBreadcrumbSession() = runBlocking {
        val sent = mutableListOf<HtxRequest>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                sent.add(request)
                val body = if (sent.size == 1)
                    """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"reasoning_content":"retrieve evidence","tool_calls":[{"id":"pi-recall","type":"function","function":{"name":"breadcrumb","arguments":"{\"id\":\"proof\",\"presence\":1}"}}]}}],"usage":{"prompt_tokens":11,"completion_tokens":3,"total_tokens":14}}"""
                else """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"answer from retained evidence"}}],"usage":{"prompt_tokens":17,"completion_tokens":5,"total_tokens":22}}"""
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = HtxResponse(200, body = ByteSeries(body.encodeToByteArray()))))
            }
        })
        val mux = ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource()) }) {
            model("fixture", caps = _s["chat", "tools"], wireModel = "wire-fixture", baseUrl = "https://fixture.invalid/v1")
        }
        val breadcrumbs = BreadcrumbElement()
        breadcrumbs.transact("pi-host") { it.put(BreadcrumbId("proof"), "fixture", "host evidence") }
        breadcrumbs.transact("pi-foreign") { it.put(BreadcrumbId("proof"), "fixture", "foreign evidence") }
        val generation = BreadcrumbGeneration(this, "pi-host")
        try {
            PiModelPort("fixture", generation, htx + breadcrumbs) { mux }.use { port ->
                assertTrue(port.guest.restrictedJsLeaseCapable)
                val result = port.prompt("Recall proof and answer")
                val transcript = result.canonical()
                assertTrue(transcript.contains("answer from retained evidence"), transcript)
                assertTrue(transcript.contains("host evidence"), transcript)
                assertFalse(transcript.contains("foreign evidence"), transcript)
                assertTrue(transcript.contains("retrieve evidence"), transcript)
                assertTrue(transcript.contains("\"totalTokens\":22"), transcript)
                assertEquals(2, sent.size)
                val wire = sent.last().body.toArray().decodeToString()
                assertTrue(wire.contains("host evidence"), wire)
                assertTrue(wire.contains("wire-fixture"), wire)
                assertFalse(wire.contains("breadcrumb_context"), wire)
                assertEquals(Teleported.Bool(true), port.guest.eval("typeof require === 'undefined' && typeof process === 'undefined' && typeof fetch === 'undefined'"))
                val foreign = port.guest.eval("host.call('pi_completion', '{\"messages\":[],\"breadcrumb_context\":\"pi-foreign\"}')")
                assertTrue(foreign.canonical().contains("bad-args"))
                val injectedSession = port.guest.eval("host.call('pi_breadcrumb', 'pi-foreign', 'search', '{}')")
                assertTrue(injectedSession.canonical().contains("bad-args"))
                // The public host API, rather than guest source, owns admission and prompt limits.
                assertTrue(runCatching { port.prompt("x".repeat(PiModelPort.PROMPT_CHARS + 1)) }.isFailure)
            }
            val submission = breadcrumbs.transact("pi-host", create = false) { it.submission }
            assertTrue(requireNotNull(submission).messageCount >= 1)
        } finally {
            generation.drain()
            htx.close()
        }
    }

    @Test fun missingModelLeaseBecomesPiStreamErrorInsteadOfARejectedPromise() = runBlocking {
        val breadcrumbs = BreadcrumbElement()
        breadcrumbs.transact("pi-airgap") { }
        val generation = BreadcrumbGeneration(this, "pi-airgap")
        try {
            PiModelPort("fixture", generation, breadcrumbs).use { port ->
                val result = port.prompt("air-gapped probe").canonical()
                assertTrue(result.contains("no-mux"), result)
                assertTrue(result.contains("\"stopReason\":\"error\""), result)
                assertFalse(result.contains("\"error\":"), result)
                val before = port.guest.eval("host.call('pi_completion', 'not-json')")
                assertTrue(before.canonical().contains("bad-args"))
                val deep = "{\"messages\":[" + "[".repeat(10_000) + "0" + "]".repeat(10_000) + "]}"
                val denied = port.guest.eval("host.call('pi_completion', " + Teleported.Str(deep).canonical() + ")")
                assertTrue(denied.canonical().contains("bad-args"), denied.canonical())
            }
        } finally { generation.drain() }
    }

    @Test fun depthAdmissionIgnoresQuotedBracesAndRejectsNestingBeforeReification() {
        assertTrue(piJsonDepthAllowed("{\"content\":\"" + "[".repeat(10_000) + "\"}"))
        assertTrue(piJsonDepthAllowed("{\"content\":\"a\\\"[b\"}"))
        assertFalse(piJsonDepthAllowed("[".repeat(33) + "0" + "]".repeat(33)))
        assertFalse(piJsonDepthAllowed("{\"content\":\"unterminated"))
    }
}
