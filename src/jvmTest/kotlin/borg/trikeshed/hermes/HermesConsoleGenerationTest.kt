package borg.trikeshed.hermes

import borg.trikeshed.agent.keymux.*
import borg.trikeshed.agent.modelmux.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.vm.Teleported
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

class HermesConsoleGenerationTest {
    private class Port(val emit: (String) -> Unit, val closePort: () -> Unit) : AutoCloseable {
        var closed = false
        override fun close() { closePort(); closed = true }
    }

    @Test fun retirementDrainsEffectsBeforeClosingAndFencesLatePortCallbacks() = runBlocking<Unit>(Dispatchers.IO) {
        val parent = SupervisorJob()
        val admitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val output = mutableListOf<String>()
        val old = HermesConsoleGeneration(EmptyCoroutineContext, parent) { owner ->
            Port({ text -> owner.callback { output.add(text) } }, { assertTrue(finished.isCompleted) })
        }
        val operation = async { old.crossings.execute { admitted.complete(Unit); release.await(); finished.complete(Unit) } }
        withTimeout(5_000) { admitted.await() }
        val closing = async { old.close() }
        try {
            withTimeout(5_000) { while (old.callback { true } != null) yield() }
            assertFalse(closing.isCompleted)
            assertFalse(old.resource.closed)
            old.resource.emit("late during drain")
            release.complete(Unit)
            withTimeout(5_000) { operation.await(); closing.await() }
            assertFails { old.crossings.execute { error("retired work admitted") } }
            val fresh = HermesConsoleGeneration(EmptyCoroutineContext, parent) { owner ->
                Port({ text -> owner.callback { output.add(text) } }, {})
            }
            try {
                assertNotEquals(old.session, fresh.session)
                assertNotSame(old.resource, fresh.resource)
                old.resource.emit("late after replacement")
                fresh.resource.emit("fresh")
                assertEquals(_l["fresh"], output)
                assertTrue(old.scope.coroutineContext[Job]!!.isCompleted)
            } finally { fresh.close() }
            assertTrue(parent.children.none())
        } finally { release.complete(Unit); old.close(); parent.cancel() }
    }

    @Test fun constructorFailureRetiresItsEffectOwner() {
        val parent = SupervisorJob()
        var owner: Job? = null
        assertFailsWith<IllegalStateException> {
            HermesConsoleGeneration<Port>(EmptyCoroutineContext, parent) { generation ->
                owner = generation.scope.coroutineContext[Job]
                assertNull(generation.callback { error("partially constructed port callback") })
                error("port construction failed")
            }
        }
        assertTrue(assertNotNull(owner).isCompleted)
        assertTrue(parent.children.none())
        parent.cancel()
    }

    private fun fixture(memory: Boolean, verify: suspend (HermesVmConsole, MutableList<HermesPythonPort>, MutableList<String>, ConfixBlackboard, BreadcrumbElement) -> Unit) = runBlocking<Unit>(Dispatchers.IO) {
        val requests = mutableListOf<String>()
        val ports = mutableListOf<HermesPythonPort>()
        val contexts = BreadcrumbElement()
        val board = ConfixBlackboard.empty()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                requests.add(request.body.toArray().decodeToString())
                val response = HtxResponse(200, body = ByteSeries(
                    """{"choices":[{"message":{"role":"assistant","content":"fixture answer"}}],"usage":{"prompt_tokens":10,"completion_tokens":3}}""".encodeToByteArray()))
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
            }
        })
        val mux = ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource()) }) {
            model("fixture", caps = _s["chat", "tools"], contextWindow = 131072, baseUrl = "https://fixture.invalid/v1")
            defaultModel("fixture")
        }
        try {
            HermesVmConsole(Path.of("unused"), Path.of("unused"), modelMuxProvider = { mux }, blackboard = board,
                muxContext = htx + contexts, socketScope = owner, breadcrumbs = memory).use { console ->
                console.inventoryLoader = { port ->
                    ports.add(port)
                    port.inventorySources(_m[
                        "hermes_cli" j ("hermes_cli/__init__.py" j "").pair,
                        "hermes_cli.main" j ("hermes_cli/main.py" j "").pair,
                    ])
                }
                verify(console, ports, requests, board, contexts)
            }
            assertTrue(owner.coroutineContext[Job]!!.children.none())
        } finally { htx.close(); owner.cancel() }
    }

    @Test fun modelCallsRemainOwnedAndMemoryDisabledAcrossFreshOpen() = fixture(false) { console, ports, requests, board, contexts ->
        repeat(2) {
            assertEquals(HermesVmConsole.State.READY, console.open())
            val port = ports.last()
            assertEquals(Teleported.Bool(true), port.evalInVm("""
                import json
                import trikeshed_modelmux as mux
                assert mux.BREADCRUMB_ENABLED is False
                assert json.loads(host.call('mux_breadcrumb', 'search', '{"query":"owned"}'))['verdict'] == 'no-context'
                result = mux.ModelMuxClient().create(model='fixture', messages=[{'role':'user','content':'hello'}])
                assert result.choices[0].message.content == 'fixture answer'
                True
            """.trimIndent()))
            console.close()
            assertFalse(port.vmStarted)
        }
        assertEquals(2, requests.size)
        assertEquals(0, contexts.snapshot().size, "disabled memory must not create an implicit retained context")
        assertEquals(2, board.keys().count { it.startsWith("hermes/python/pen/mux_completion/") })
        assertNotSame(ports[0], ports[1])
        assertTrue(requests.none { it.contains("breadcrumb_context") || it.contains("recall") })
    }

    @Test fun explicitResumeRetainsHostMemoryAndFreshResetRotatesIt() = fixture(true) { console, ports, _, _, contexts ->
        assertEquals(HermesVmConsole.State.READY, console.open())
        assertEquals(Teleported.Bool(true), ports.last().evalInVm("""
            import trikeshed_modelmux as mux
            assert mux.BREADCRUMB_ENABLED is True
            mux.ModelMuxClient().create(model='fixture', messages=[{'role':'user','content':'hello'}])
            True
        """.trimIndent()))
        // Host-seeded retained evidence: binding alone observes model input, without opting it into projection.
        val session = contexts.snapshot().view.single()["context"] as String
        contexts.transact(session, create = false) { it.put(BreadcrumbId("proof"), "fixture", "OWNED amber") }
        assertEquals(Teleported.Bool(true), ports.last().evalInVm("""
            import json
            search = json.loads(mux._call('mux_breadcrumb', 'search', '{"query":"amber"}')['content'])
            assert search['matches'], search
            match = search['matches'][0]
            found = json.loads(mux._call('mux_breadcrumb', 'recall', json.dumps({'ref':match['ref'],'cid':match['cid'],'presence':1}))['content'])
            assert found['ranges'][0]['content']=='OWNED amber'
            assert json.loads(host.call('mux_breadcrumb','arbitrary-client-context','search','{}'))['verdict']=='forbidden'
            True
        """.trimIndent()))
        val first = ports.last()
        console.reset(resume = true)
        assertFalse(first.vmStarted)
        assertNotSame(first, ports.last())
        assertEquals(Teleported.Bool(true), ports.last().evalInVm("""
            import json
            import trikeshed_modelmux as mux
            assert json.loads(mux._call('mux_breadcrumb', 'search', '{"query":"amber"}')['content'])['matches']
            True
        """.trimIndent()))
        val resumed = ports.last()
        console.reset(resume = false)
        assertFalse(resumed.vmStarted)
        assertNotSame(resumed, ports.last())
        assertEquals(Teleported.Bool(true), ports.last().evalInVm("""
            import json
            assert json.loads(host.call('mux_breadcrumb', 'search', '{"query":"amber"}'))['verdict']=='no-context'
            True
        """.trimIndent()))
    }
}
