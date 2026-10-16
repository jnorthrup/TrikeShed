package modelmux

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.modelmux.BrainClient
import borg.trikeshed.modelmux.BrainErrorSink
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.userspace.reactor.MuxReactorElement
import keymux.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

class ModelMuxAirgapTest {
    class Switches(val delegate: SystemOperations) : SystemOperations by delegate {
        var property: String? = null
        var environment: String? = null
        override fun getProperty(name: String, defaultVal: String?): String? =
            if (name == Airgap.PROPERTY) property else delegate.getProperty(name, defaultVal)
        override fun getenv(name: String, defaultVal: String?): String? =
            if (name == Airgap.ENVIRONMENT) environment else delegate.getenv(name, defaultVal)
    }

    class Spy : KeyResolver, HtxRouteService {
        var keyReads = 0
        var requests = 0
        var onKey: () -> Unit = {}

        override suspend fun resolve(bindings: Series<KeyBinding>, path: KeyPath): KeyResult {
            keyReads++
            onKey()
            return FirstWinsResolver.resolve(bindings, path)
        }

        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
            requests++
            val response = HtxResponse(200, body = ByteSeries(
                """{"choices":[{"message":{"content":"fixture answer"}}],"data":[{"embedding":[0.25,-0.5]}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""".encodeToByteArray(),
            ))
            return HtxExchangeResult(state.copy(
                lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response,
            ))
        }
    }

    class Fixture(val switches: Switches, val base: String) {
        val spy = Spy()
        val keys = KeyMux(s_["llm.default.key".toKeyPath() j FixedKeySource("sk-airgap-fixture")] j spy)
        val mux = ModelMux(keys) {
            model("fixture", caps = setOf("chat", "embed", "conflict-resolve"), baseUrl = base)
        }
        val reactor = MuxReactorElement()
        val errors = mutableListOf<String>()
        val brain = BrainClient(
            apiKey = "sk-airgap-fixture", base = base, model = "fixture",
            keyMux = keys, modelMux = mux, errorSink = BrainErrorSink { errors.add(it) },
        )
        val messages = s_["user" j "chat"]
        val completion = confixDoc("""{"messages":[{"role":"user","content":"completion"}]}""")

        suspend fun rejected() {
            assertIs<Airgap.Rejection>(ModelDiscovery.discover("fixture", base, keys).exceptionOrNull())
            assertIs<Airgap.Rejection>(mux.session("fixture").exceptionOrNull())
            assertIs<Airgap.Rejection>(mux.chat("fixture", messages).exceptionOrNull())
            assertIs<Airgap.Rejection>(mux.completion("fixture", completion).exceptionOrNull())
            assertFailsWith<Airgap.Rejection> { mux.stream("fixture", messages).collect() }
            assertFailsWith<Airgap.Rejection> { mux.embed("fixture", s_["text"]) }
            assertFailsWith<Airgap.Rejection> { brain.chat(listOf("user" to "chat")) }
            assertFailsWith<Airgap.Rejection> { brain.chatSeat(listOf("user" to "chat")) }
            assertEquals(0, spy.keyReads, "configured credentials must not be resolved")
            assertEquals(0, spy.requests, "no HTX exchanges")
            assertTrue(reactor.flowState.value.keys.isEmpty())
            assertTrue(reactor.flowState.value.leases.isEmpty())
            assertEquals(0, reactor.cache.apiCallCount())
            assertTrue(errors.isEmpty(), "policy rejection is not provider failure")
            assertTrue(mux.activity.snapshot().all { it.status == "failed" && it.endedAt != null })
            assertEquals(0, mux.lastReceipt?.httpStatus)
            assertFalse(mux.lastReceipt!!.cachedHit)
        }
    }

    suspend fun fixture(base: String = "https://fixture.invalid/v1", block: suspend Fixture.() -> Unit) {
        val previous = SystemOperations.default
        val switches = Switches(previous)
        SystemOperations.register(switches)
        try {
            val fixture = Fixture(switches, base)
            val htx = openHtxElement(routeService = fixture.spy)
            try {
                withContext(htx + fixture.reactor) { fixture.block() }
            } finally {
                try { fixture.reactor.close() } finally { htx.close() }
            }
        } finally {
            SystemOperations.register(previous)
        }
    }

    @Test
    fun contextRejectsRemoteAndLoopbackBeforeKeysAndHtx() = runTest {
        for (base in s_["https://fixture.invalid/v1", "http://localhost:11434/v1"].view) {
            fixture(base) { withContext(Airgap()) { rejected() } }
        }
    }

    @Test
    fun streamChecksCollectorContext() = runTest {
        fixture {
            val stream = mux.stream("fixture", messages)
            withContext(Airgap()) {
                assertFailsWith<Airgap.Rejection> { stream.collect() }
            }
            assertEquals(0, spy.keyReads)
            assertEquals(0, spy.requests)
        }
    }

    @Test
    fun globalSwitchSurvivesMissingOrDisabledContext() = runTest {
        fixture {
            assertNull(currentCoroutineContext()[Airgap])
            switches.property = "true"
            rejected()
            switches.property = "false"
            switches.environment = "true"
            withContext(Airgap(false)) { rejected() }
        }
    }

    @Test
    fun disabledModeUsesConfiguredCredentialsAndInMemoryTransport() = runTest {
        fixture {
            withContext(Airgap(false)) {
                assertEquals("fixture answer", mux.chat("fixture", messages).getOrThrow().a)
                assertTrue(mux.completion("fixture", completion).isSuccess)
                var chunks = 0
                mux.stream("fixture", messages).collect { chunks++ }
                assertEquals(1, chunks)
                val embedded = mux.embed("fixture", s_["text"])
                assertEquals(1, embedded.size)
                assertEquals(0.25, embedded[0].b[0])
            }
            assertTrue(spy.keyReads > 0)
            assertEquals(4, spy.requests, "fixture reaches each HTTP operation when permitted")
        }
    }

    @Test
    fun httpBoundaryRechecksGlobalSwitchAfterCredentialResolution() = runTest {
        for (operation in 0..3) {
            fixture {
                spy.onKey = { switches.property = "true" }
                assertFailsWith<Airgap.Rejection> {
                    when (operation) {
                        0 -> mux.chat("fixture", messages).getOrThrow()
                        1 -> mux.completion("fixture", completion).getOrThrow()
                        2 -> mux.stream("fixture", messages).collect()
                        else -> mux.embed("fixture", s_["text"])
                    }
                }
                assertTrue(spy.keyReads > 0, "switch changes after admission")
                assertEquals(0, spy.requests)
            }
        }
    }

    @Test
    fun brainPropagatesRejectionWithoutRetryOrProviderFallback() = runTest {
        fixture {
            val rejection = Airgap.Rejection()
            spy.onKey = { throw rejection }
            val pooled = BrainClient(keyMux = keys, errorSink = BrainErrorSink { errors.add(it) })
            assertTrue(pooled.endpointSummaries().size > 1)
            for (client in s_[brain, pooled].view) {
                for (seat in s_[false, true].view) {
                    spy.keyReads = 0
                    val caught = assertFailsWith<Airgap.Rejection> {
                        if (seat) client.chatSeat(listOf("user" to "chat"))
                        else client.chat(listOf("user" to "chat"))
                    }
                    // Coroutine stack-trace recovery may copy the exception.
                    assertEquals(rejection.message, caught.message)
                    assertEquals(1, spy.keyReads, "rejection must stop after one attempt")
                    assertEquals(0, spy.requests)
                    assertTrue(errors.isEmpty())
                }
            }
        }
    }
}
