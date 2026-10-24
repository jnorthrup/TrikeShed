package borg.trikeshed.hermes

import borg.trikeshed.forge.server.HermesConsoleWire
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lcnc.media.LcncSignalLane
import borg.trikeshed.lib.*
import borg.trikeshed.htx.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.keymux.TestKeySource
import borg.trikeshed.agent.modelmux.ModelMux
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class HermesVmConsoleTest {
    @Test
    fun queuedGuestToolConversationUsesCurrentMuxAndCommandReceipts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val board = ConfixBlackboard.empty()
        val requests = mutableListOf<String>()
        val htx = openHtxElement(routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                requests.add(request.body.toArray().decodeToString())
                val body = if (requests.size % 2 == 1)
                    """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"lookup","arguments":"{}"}}]}}],"usage":{"prompt_tokens":4,"completion_tokens":2}}"""
                else
                    """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"found µ"}}],"usage":{"prompt_tokens":8,"completion_tokens":3}}"""
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request, response = HtxResponse(200, body = ByteSeries(body.encodeToByteArray()))))
            }
        })
        fun mux(wireModel: String) = ModelMux(KeyMux { bind("llm.fixture.key", TestKeySource()) }) {
            model("fixture", caps = setOf("chat", "tools"), wireModel = wireModel, baseUrl = "https://fixture.invalid/v1")
            defaultModel("fixture")
        }
        val reactor = MuxReactorElement()
        val lease = AtomicReference<ModelMux?>(null)
        val console = HermesVmConsole(Path.of("unused"), Path.of("unused"), blackboard = board,
            modelMuxProvider = lease::get, muxContext = htx + reactor)
        console.inventoryLoader = { port ->
            port.inventorySources(HermesModelProviderTest.resolverSources + mapOf(
                "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                "hermes_cli.main" to ("hermes_cli/main.py" to ""),
                // Entry fixture exercises the real console/guest/ModelMux crossing, not the donor's agent loop.
                "hermes_cli.oneshot" to ("hermes_cli/oneshot.py" to """
                    from hermes_cli.runtime_provider import resolve_runtime_provider
                    from agent.auxiliary_client import resolve_provider_client
                    def completion(provider, model, messages):
                        request = {'messages': messages, 'tools': [{'type':'function', 'function':{'name':'lookup','parameters':{'type':'object'}}}]}
                        client, selected = resolve_provider_client(provider, model)
                        with client:
                            return client.chat.completions.create(model=selected, **request)
                    def run_oneshot(prompt, model=None, provider=None):
                        assert provider == 'trikeshed' and model == 'fixture'
                        runtime = resolve_runtime_provider(requested=provider, target_model=model)
                        assert runtime['api_key'] is None and runtime['source'] == 'trikeshed-host-lease'
                        messages = [{'role':'user','content':prompt}]
                        first = completion(provider, model, messages)
                        assert first['choices'][0]['finish_reason'] == 'tool_calls'
                        assistant = first['choices'][0]['message']
                        messages.append(assistant)
                        messages.append({'role':'tool','tool_call_id':assistant['tool_calls'][0]['id'],'content':'µ'})
                        second = completion(provider, model, messages)
                        assert second['usage']['prompt_tokens'] == 8
                        print(second['choices'][0]['message']['content'])
                        return 0
                """.trimIndent()),
            ))
        }
        val wire = HermesConsoleWire(console, scope)
        suspend fun command(prompt: String, kind: String): String {
            assertEquals(202, wire.route("POST", "/api/hermes/terminal/input", jsonOf(mapOf("command" to prompt)), null)?.status)
            val manual = console.panel.signals().view.last { it.kind == "command" }
            withTimeout(30_000) {
                while (console.panel.signals().view.none {
                    it.causeSignalId == manual.id && it.kind in setOf("completed", "failed")
                }) delay(10)
            }
            val outcome = console.panel.signals().view.single {
                it.causeSignalId == manual.id && it.kind in setOf("completed", "failed")
            }
            assertEquals(kind, outcome.kind, console.panel.terminal.plainText())
            return manual.id
        }
        try {
            val missing = command("no lease", "failed")
            assertTrue(requests.isEmpty())
            val first = mux("wire-1")
            lease.set(first)
            val completed = command("first prompt", "completed")
            assertEquals(completed, first.lastReceipt?.assessmentId)
            assertEquals(8, first.lastReceipt?.inputTokens)
            assertEquals(3, first.lastReceipt?.outputTokens)
            assertEquals(2, requests.size)
            val secondRequest = confixDoc(requests[1])
            assertEquals("wire-1", secondRequest.value("model"))
            assertEquals("call-1", secondRequest.value("messages", 1, "tool_calls", 0, "id"))
            assertEquals("call-1", secondRequest.value("messages", 2, "tool_call_id"))
            assertEquals("µ", secondRequest.value("messages", 2, "content"))
            lease.set(null)
            val revoked = command("revoked lease", "failed")
            assertEquals(2, requests.size)
            val replacement = mux("wire-2")
            lease.set(replacement)
            val rebound = command("replacement prompt", "completed")
            assertEquals(rebound, replacement.lastReceipt?.assessmentId)
            assertEquals(completed, first.lastReceipt?.assessmentId, "no call retains the old lease")
            assertEquals(4, requests.size)
            assertEquals("wire-2", confixDoc(requests.last()).value("model"))
            val receipts = board.snapshot().values.filterKeys { it.startsWith("hermes/python/pen/mux_completion/") }.values
                .map { it as Map<*, *> }
            assertEquals(4, receipts.size)
            val catalogs = board.snapshot().values.filterKeys { it.startsWith("hermes/python/pen/mux_catalog/") }.values
                .map { it as Map<*, *> }
            for (cause in listOf(missing, revoked)) {
                val receipt = catalogs.single { it["contextId"] == cause }
                assertEquals(false, receipt["ok"])
                assertEquals("no-mux", receipt["verdict"])
            }
            for (cause in listOf(completed, rebound)) {
                val turns = receipts.filter { it["contextId"] == cause }
                assertEquals(2, turns.size)
                assertTrue(turns.all { it["ok"] == true })
            }
        } finally {
            try { wire.drain() } finally {
                scope.cancel()
                try { reactor.close() } finally { htx.close() }
            }
        }
    }

    @Test
    fun consoleModelSelectionIsExplicitAndRevalidatedPerTurn() {
        val lease = AtomicReference(ModelMux(KeyMux {}) {
            model("one", caps = setOf("chat"))
            model("two", caps = setOf("chat"))
        })
        HermesVmConsole(Path.of("unused"), Path.of("unused"), modelMuxProvider = lease::get).use { console ->
            console.inventoryLoader = { port ->
                port.inventorySources(HermesModelProviderTest.resolverSources + mapOf(
                    "hermes_cli.main" to ("hermes_cli/main.py" to ""),
                    "hermes_cli.oneshot" to ("hermes_cli/oneshot.py" to """
                        def run_oneshot(prompt, model=None, provider=None):
                            assert provider == 'trikeshed'
                            print('selected=' + model)
                            return 0
                    """.trimIndent()),
                ))
            }
            fun command(text: String, outcome: String) {
                val signal = console.submit(text)
                assertEquals(outcome, console.panel.signals().view.single {
                    it.causeSignalId == signal.id && it.kind in setOf("completed", "failed")
                }.kind, console.panel.terminal.plainText())
            }
            command("prompt", "failed")
            assertTrue(console.panel.terminal.plainText().contains("no default model"))
            command(":models", "completed")
            command(":model two", "completed")
            assertEquals("two", console.snapshotMap()["model"])
            command("prompt", "completed")
            assertTrue(console.panel.terminal.plainText().contains("selected=two"))
            command(":model absent", "failed")
            assertEquals("two", console.snapshotMap()["model"])
            command("prompt", "completed")
            lease.set(ModelMux(KeyMux {}) {
                model("one", caps = setOf("chat"))
                defaultModel("one")
            })
            command("prompt", "failed")
            assertTrue(console.panel.terminal.plainText().contains("not in the current catalog: two"))
            command(":model", "completed")
            assertEquals(null, console.snapshotMap()["model"])
            command("prompt", "completed")
            assertTrue(console.panel.terminal.plainText().contains("selected=one"))
        }
    }

    @Test
    fun oneshotExitStatusDeterminesTheCausalOutcome() {
        val board = ConfixBlackboard.empty()
        val mux = ModelMux(KeyMux {}) {
            model("fixture", caps = setOf("chat"))
            defaultModel("fixture")
        }
        HermesVmConsole(Path.of("unused"), Path.of("unused"), blackboard = board, modelMuxProvider = { mux }).use { console ->
            console.inventoryLoader = { port ->
                port.inventorySources(HermesModelProviderTest.resolverSources + mapOf(
                    "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                    "hermes_cli.main" to ("hermes_cli/main.py" to ""),
                    "hermes_cli.oneshot" to ("hermes_cli/oneshot.py" to """
                        def run_oneshot(prompt, model=None, provider=None):
                            if prompt == 'invalid': return None
                            if prompt == 'bool': return False
                            return int(prompt)
                    """.trimIndent()),
                ))
            }
            for (exit in listOf("1", "2", "130", "invalid", "bool", "0")) {
                val manual = console.submit(":hermes $exit")
                val outcomes = console.panel.signals().view.filter {
                    it.causeSignalId == manual.id && it.kind in setOf("completed", "failed")
                }
                val outcome = outcomes.view.single()
                assertEquals(if (exit == "0") "completed" else "failed", outcome.kind, "exit=$exit")
                assertTrue(board.keys().contains("hermes/console/signal/${outcome.id}"))
            }
            assertEquals(HermesVmConsole.State.READY, console.state, "a failed turn does not close the console")
        }
    }

    @Test
    fun xtermConsoleBootsSleeveCapturesGuestOutputAndBondsCausalSignals() {
        HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 64, rows = 12).use { console ->
            console.inventoryLoader = { port ->
                port.inventorySources(
                    mapOf(
                        "hermes_cli" to ("hermes_cli/__init__.py" to "NAME = 'hermes'"),
                        "hermes_cli.main" to ("hermes_cli/main.py" to "READY = True"),
                    ),
                )
            }

            assertEquals(HermesVmConsole.State.READY, console.open(timestampMs = 1))
            val manual = console.submit(":eval print('\\x1b[31mguest-red\\x1b[0m') or 42", timestampMs = 2)
            val snapshot = console.snapshotMap()

            assertEquals("ready", snapshot["state"])
            val text = console.panel.terminal.plainText()
            assertTrue(text.contains("guest-red"), text)
            assertTrue(text.contains("hermes>"), text)
            val signals = console.panel.signals().view.toList()
            assertTrue(signals.any { it.id == manual.id && it.lane == LcncSignalLane.MANUAL })
            assertTrue(signals.any { it.lane == LcncSignalLane.CAUSAL && it.causeSignalId == manual.id })
            val redCell = console.panel.snapshot().lines.view
                .flatMap { it.view }
                .first { it.text == "g" && it.style.foreground.index == 1 }
            assertEquals("g", redCell.text)
        }
    }

    @Test
    fun supervisedVfsProvidesDevNullWithoutHostFileAccess() {
        HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 40, rows = 8).use { console ->
            console.inventoryLoader = { port ->
                port.inventorySources(
                    mapOf(
                        "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                        "hermes_cli.main" to ("hermes_cli/main.py" to ""),
                    ),
                )
            }
            assertEquals(HermesVmConsole.State.READY, console.open())
            console.submit(":eval open('/dev/null','w').write('discarded')")
            assertTrue(console.panel.terminal.plainText().contains("9"))
        }
    }

    @Test
    fun wireServesXtermPageSnapshotResizeAndAsyncCommand() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            HermesVmConsole(Path.of("unused"), Path.of("unused"), columns = 40, rows = 8).use { console ->
                console.inventoryLoader = { port ->
                    port.inventorySources(mapOf(
                        "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                        "hermes_cli.main" to ("hermes_cli/main.py" to ""),
                    ))
                }
                console.open()
                val wire = HermesConsoleWire(console, scope)
                val page = wire.route("GET", "/hermes", "", null)
                assertEquals(200, page?.status)
                val html = assertNotNull(page?.bytes).decodeToString()
                // The route serves the interactive terminal; its display banner is not the protocol.
                assertTrue(html.contains("role=\"application\""))
                assertTrue(html.contains("aria-label=\"terminal input\""))
                // The bottom command dialog is gone: the terminal itself takes keystrokes.
                assertFalse(html.contains("id=\"command\""))
                val snapshot = wire.route("GET", "/api/hermes/terminal", "", null)
                assertTrue(snapshot?.body?.contains("\"kind\":\"xterm-256color\"") == true)
                val resized = wire.route(
                    "POST", "/api/hermes/terminal/resize",
                    "POST / HTTP/1.1\r\n\r\n{\"columns\":52,\"rows\":9}", null,
                )
                assertEquals(200, resized?.status)
                val submitted = wire.route(
                    "POST", "/api/hermes/terminal/input",
                    "POST / HTTP/1.1\r\n\r\n{\"command\":\":status\"}", null,
                )
                assertEquals(202, submitted?.status)
                withTimeout(5_000) {
                    while (console.panel.signals().view.count { it.kind == "completed" } == 0) delay(10)
                }
                assertTrue(console.panel.terminal.plainText().contains("state=ready"))
                wire.drain()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun wireDrainWaitsForGuestOpenAndAcceptedCommandOnSharedBlackboard() = wireDrain(false)

    @Test
    fun cancelledWireDrainStillFinishesAcceptedCommandAndClosesVm() = wireDrain(true)

    private fun wireDrain(cancelCaller: Boolean) = runBlocking {
        val scope = CoroutineScope(SupervisorJob(coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Default)
        val board = ConfixBlackboard.empty()
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val console = HermesVmConsole(Path.of("unused"), Path.of("unused"), blackboard = board, socketScope = scope)
        console.inventoryLoader = { port ->
            opening.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "test did not release inventory" }
            port.inventorySources(mapOf(
                "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                "hermes_cli.main" to ("hermes_cli/main.py" to ""),
            ))
        }
        val wire = HermesConsoleWire(console, scope)
        try {
            assertTrue(wire.open())
            assertTrue(opening.await(10, TimeUnit.SECONDS))
            val submitted = wire.route("POST", "/api/hermes/terminal/input", """{"command":":eval 6 * 7"}""", null)
            assertEquals(202, submitted?.status)
            val draining = async(start = CoroutineStart.UNDISPATCHED) { wire.drain() }
            assertFalse(draining.isCompleted)
            assertEquals(HermesVmConsole.State.BOOTING, console.state)
            assertEquals(503, wire.route("POST", "/api/hermes/terminal/open", "", null)?.status)
            assertEquals(503, wire.route("POST", "/api/hermes/terminal/input", """{"text":"late"}""", null)?.status)
            if (cancelCaller) {
                draining.cancel()
                delay(20)
                assertFalse(draining.isCompleted, "shutdown retains its join after the caller is cancelled")
            }
            release.countDown()
            withTimeout(30_000) { draining.join() }
            if (!cancelCaller) draining.await()
            assertEquals(HermesVmConsole.State.CLOSED, console.state)
            assertTrue(scope.coroutineContext[kotlinx.coroutines.Job]!!.children.none(), "wire drain joins the VM socket lease too")
            assertTrue(console.panel.terminal.plainText().contains("42"))
            val manual = console.panel.signals().view.single { it.kind == "command" }
            val completed = console.panel.signals().view.single { it.kind == "completed" && it.causeSignalId == manual.id }
            assertTrue(board.keys().contains("hermes/console/signal/${manual.id}"))
            assertTrue(board.keys().contains("hermes/console/signal/${completed.id}"))
            assertFalse(wire.open())
        } finally {
            release.countDown()
            wire.drain()
            scope.cancel()
        }
    }

    @Test
    fun providerRegistrationFailureClosesTheImportedVmAndCanRetry() {
        HermesVmConsole(Path.of("unused"), Path.of("unused")).use { console ->
            console.inventoryLoader = { port ->
                port.inventorySources(mapOf(
                    "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                    "hermes_cli.main" to ("hermes_cli/main.py" to ""),
                    "providers" to ("providers/__init__.py" to "raise RuntimeError('registry unavailable')"),
                    "providers.base" to ("providers/base.py" to ""),
                ))
            }
            assertEquals(HermesVmConsole.State.FAILED, console.open())
            assertTrue(console.detail.contains("registry unavailable"))
            val field = HermesVmConsole::class.java.getDeclaredField("port").apply { isAccessible = true }
            val port = field.get(console) as HermesPythonPort
            assertFalse(port.vmStarted, "entry import success must not retain a VM after registration fails")
            console.inventoryLoader = { p -> p.inventorySources(mapOf(
                "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                "hermes_cli.main" to ("hermes_cli/main.py" to ""),
            )) }
            assertEquals(HermesVmConsole.State.READY, console.open())
            assertTrue(port.vmStarted)
        }
    }

    @Test
    fun bootFailureTerminatesTheAcceptedCommandWithFailureEvidence() {
        val board = ConfixBlackboard.empty()
        HermesVmConsole(Path.of("unused"), Path.of("unused"), blackboard = board).use { console ->
            console.inventoryLoader = { error("required guest module unavailable") }
            val manual = console.submit(":status")
            assertEquals(HermesVmConsole.State.FAILED, console.state)
            val failed = console.panel.signals().view.single { it.kind == "failed" && it.causeSignalId == manual.id }
            assertTrue(board.keys().contains("hermes/console/signal/${failed.id}"))
            assertFalse(console.panel.signals().view.any { it.kind == "completed" && it.causeSignalId == manual.id })
        }
    }

    @Test
    fun failedNestedImportIsNotPublishedAsLoaded() {
        val board = ConfixBlackboard.empty()
        HermesVmConsole(Path.of("unused"), Path.of("unused"), blackboard = board).use { console ->
            console.inventoryLoader = { port ->
                port.inventorySources(mapOf(
                    "hermes_cli" to ("hermes_cli/__init__.py" to ""),
                    "hermes_cli.main" to ("hermes_cli/main.py" to "import dependency"),
                    "dependency" to ("dependency.py" to "exec('import socket')"),
                ))
            }
            assertEquals(HermesVmConsole.State.FAILED, console.open())
            val imports = board.snapshot().values
            assertEquals("loaded", (imports["hermes/python/pointcut/import/hermes_cli"] as Map<*, *>)["status"])
            assertEquals("failed", (imports["hermes/python/pointcut/import/hermes_cli.main"] as Map<*, *>)["status"])
            assertEquals("failed", (imports["hermes/python/pointcut/import/dependency"] as Map<*, *>)["status"])
            assertEquals("refused", (imports["hermes/python/pointcut/import/socket"] as Map<*, *>)["status"])
        }
    }
}
