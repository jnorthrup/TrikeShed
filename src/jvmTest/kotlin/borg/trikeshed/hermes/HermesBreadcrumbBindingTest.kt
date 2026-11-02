package borg.trikeshed.hermes

import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.keymux.TestKeySource
import borg.trikeshed.agent.modelmux.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.vm.Teleported
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Real restricted GraalPy + pinned donor registry. This does not substitute or claim the full donor agent loop. */
class HermesBreadcrumbBindingTest {
    private fun resource(name: String): String = requireNotNull(javaClass.getResourceAsStream("/hermes-registry/$name"))
        .bufferedReader().use { it.readText() }

    private fun donorRegistry() = _m[
        "tools" j ("tools/__init__.py" to resource("tools-init.py")),
        "tools.registry" j ("tools/registry.py" to resource("registry.py")),
        "hermes_constants" j ("hermes_constants.py" to resource("hermes_constants.py")),
    ]

    @Test fun providerAndActualDonorRegistryShareOnlyTheImmutableHostSession() = runBlocking(Dispatchers.IO) {
        val requests = mutableListOf<String>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                requests.add(request.body.toArray().decodeToString())
                val body = if (requests.size == 1)
                    """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"recall-1","type":"function","function":{"name":"breadcrumb","arguments":"{\"id\":\"proof\",\"presence\":1}"}}]}}]}"""
                else """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"retained answer"}}],"usage":{"prompt_tokens":9,"completion_tokens":3}}"""
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request,
                    response = HtxResponse(200, body = ByteSeries(body.encodeToByteArray()))))
            }
        })
        val mux = ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource()) }) {
            model("fixture", provider = "fixture", caps = _s["chat", "tools"], baseUrl = "https://fixture.invalid/v1")
        }
        val contexts = BreadcrumbElement()
        contexts.transact("host-session") { it.put(BreadcrumbId("proof"), "fixture", "HOST retained evidence") }
        contexts.transact("foreign-session") { it.put(BreadcrumbId("proof"), "fixture", "FOREIGN evidence") }
        val generation = BreadcrumbGeneration(this, "host-session")
        val board = ConfixBlackboard.empty()
        try {
            HermesPythonPort(blackboard = board, pen = HermesPen(board, modelMux = mux,
                muxContext = htx + contexts, breadcrumbGeneration = generation, contextId = { "command-1" })).use { port ->
                port.importInVm(port.inventorySources(donorRegistry()), HermesBreadcrumbTools.MODULE)
                assertEquals(Teleported.Bool(true), port.evalInVm("""
                    import json
                    import trikeshed_modelmux as mux
                    import trikeshed_breadcrumbs as memory
                    from tools.registry import registry
                    assert mux.BREADCRUMB_ENABLED
                    assert set(memory.register()) == {'breadcrumb', 'recall', 'search'}
                    definitions = registry.get_definitions({'breadcrumb', 'recall', 'search'}, quiet=True)
                    assert len(definitions) == 3
                    messages = [{'role':'user', 'content':'Recall proof'}]
                    with mux.ModelMuxClient() as client:
                        first = client.create(model='fixture', messages=messages, tools=definitions)
                        call = first.choices[0].message.tool_calls[0]
                        retained = registry.dispatch(call.function.name, json.loads(call.function.arguments), session_id='foreign-session')
                        assert 'HOST retained evidence' in retained and 'FOREIGN evidence' not in retained
                        messages.extend([first.choices[0].message, {'role':'tool','tool_call_id':call.id,'content':retained}])
                        second = client.create(model='fixture', messages=messages, tools=definitions)
                        assert second.choices[0].message.content == 'retained answer'
                        try:
                            mux._call('mux_completion', 'fixture', json.dumps({'messages':messages}), 1000, 'foreign-session')
                        except RuntimeError as error:
                            assert 'forbidden' in str(error)
                        else:
                            raise AssertionError('foreign session was admitted')
                    True
                """.trimIndent()))
            }
            assertEquals(2, requests.size)
            assertTrue(requests.last().contains("HOST retained evidence"))
            assertFalse(requests.last().contains("FOREIGN evidence"))
            assertFalse(requests.last().contains("breadcrumb_context"))
            assertEquals("command-1", mux.lastReceipt?.assessmentId)
            val submission = contexts.transact("host-session", create = false) { it.submission }
            assertEquals(1, requireNotNull(submission).messageCount)
        } finally { generation.drain(); htx.close() }
    }

    @Test fun boundRecallDoesNotRequireAModelLeaseOrAcceptDonorSessionIdentity() = runBlocking(Dispatchers.IO) {
        val contexts = BreadcrumbElement()
        contexts.transact("host-session") { it.put(BreadcrumbId("proof"), "fixture", "HOST retained evidence") }
        val generation = BreadcrumbGeneration(this, "host-session")
        val board = ConfixBlackboard.empty()
        try {
            HermesPythonPort(blackboard = board, pen = HermesPen(board, muxContext = contexts,
                breadcrumbGeneration = generation)).use { port ->
                port.importInVm(port.inventorySources(donorRegistry()), HermesBreadcrumbTools.MODULE)
                assertEquals(Teleported.Bool(true), port.evalInVm("""
                    import json
                    import trikeshed_breadcrumbs as memory
                    from tools.registry import registry
                    memory.register()
                    own = registry.dispatch('breadcrumb', {'id':'proof','presence':1}, session_id='guest-chosen')
                    assert 'HOST retained evidence' in own
                    denied = json.loads(host.call('mux_breadcrumb', 'guest-chosen', 'breadcrumb', '{"id":"proof","presence":1}'))
                    assert denied['verdict'] == 'forbidden'
                    poisoned = registry.dispatch('breadcrumb', {'id':'proof','presence':1,'session':'foreign'})
                    assert 'error' in json.loads(poisoned)
                    True
                """.trimIndent()))
            }
        } finally { generation.drain() }
    }

    @Test fun converseKeepsCommandAttributionOutOfTheHostSessionIdentity() = runBlocking<Unit>(Dispatchers.IO) {
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult =
                HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request,
                    response = HtxResponse(200, body = ByteSeries("""{"choices":[{"message":{"role":"assistant","content":"answer"}}]}""".encodeToByteArray()))))
        })
        val mux = ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource()) }) {
            model("fixture", provider = "fixture", caps = _s["chat"], baseUrl = "https://fixture.invalid/v1")
        }
        val contexts = BreadcrumbElement()
        val generation = BreadcrumbGeneration(this, "host-session")
        val board = ConfixBlackboard.empty()
        try {
            HermesPythonPort(blackboard = board, pen = HermesPen(board, modelMux = mux,
                muxContext = htx + contexts, breadcrumbGeneration = generation, contextId = { "command-1" })).use { port ->
                port.importInVm(port.inventorySources(_m["entry" j ("entry.py" to "READY = True")]), "entry")
                assertEquals(Teleported.Str("answer"), port.evalInVm("import json\njson.loads(host.call('mux_converse', 'fixture', 'hello'))['response']"))
            }
            assertEquals("command-1", mux.lastReceipt?.assessmentId)
            assertEquals("fixture", contexts.transact("host-session", create = false) { it.submission?.model })
            assertFailsWith<BreadcrumbContextNotFoundException> { contexts.transact("command-1", create = false) { } }
        } finally { generation.drain(); htx.close() }
    }
}
