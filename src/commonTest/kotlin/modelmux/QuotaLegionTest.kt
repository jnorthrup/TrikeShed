package modelmux

import keymux.KeyMux
import keymux.TestKeySource
import borg.trikeshed.htx.HtxExchangeLifecycle
import borg.trikeshed.htx.HtxExchangeResult
import borg.trikeshed.htx.HtxExchangeState
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.HtxRouteService
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib._s
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.job.ContentId
import borg.trikeshed.modelmux.ModelResponseReceipt
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.userspace.reactor.MuxKeyStatus
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuotaLegionTest {

    private fun receipt(input: Int, output: Int, status: Int = 200): ModelResponseReceipt =
        ModelResponseReceipt.mint(
            modelId = "m", providerId = "p", requestHash = "h",
            action = "chat", httpStatus = status, latencyMs = 1,
            inputTokens = input, outputTokens = output,
        )

    @Test
    fun standingsRankUsableByRemainingQuota() = runTest {
        val reactor = MuxReactorElement()
        reactor.recordAccess("k1", "openai")
        reactor.recordAccess("k2", "openai")
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 1000)
        val now = 1_000_000L

        legion.applyReceipt("k1", "openai", receipt(300, 200), now) // k1 spent 500
        val ranked = legion.standings(reactor.flowState.value, now)
        assertEquals(2, ranked.size)
        assertEquals("k2", ranked[0].keyId, "unspent key must rank first")
        assertEquals(500L, ranked[1].spent)
        assertEquals(500L, ranked[1].remaining)
    }

    @Test
    fun receipt429ExhaustsKey() = runTest {
        val reactor = MuxReactorElement()
        reactor.recordAccess("k1", "openai")
        reactor.recordAccess("k2", "openai")
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 1000)
        val now = 2_000_000L

        legion.applyReceipt("k1", "openai", receipt(0, 0, status = 429), now)
        val ranked = legion.standings(reactor.flowState.value, now)
        val k1 = ranked.toList().first { it.keyId == "k1" }
        assertTrue(k1.exhausted)
        assertFalse(k1.isUsable)
        assertEquals("k2", legion.nextKey(reactor.flowState.value, now, provider = "openai")?.keyId)
    }

    @Test
    fun windowRolloverResetsSpendAndExhaustion() = runTest {
        val reactor = MuxReactorElement()
        reactor.recordAccess("k1", "openai")
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 100)
        val t0 = 5_000_000L

        legion.applyReceipt("k1", "openai", receipt(60, 60), t0) // 120 > 100 → spent out
        assertTrue(legion.standings(reactor.flowState.value, t0)[0].isUsable,
            "an estimate cannot exhaust a provider that has accepted the request")

        val t1 = t0 + 60_001 // next window
        legion.applyReceipt("k1", "openai", receipt(1, 1), t1)
        val standing = legion.standings(reactor.flowState.value, t1)[0]
        assertTrue(standing.isUsable, "fresh window must clear spend and exhaustion")
        assertEquals(2L, standing.spent)
    }

    @Test
    fun nextKeyExcludesTriedKeysAndHonoursProvider() = runTest {
        val reactor = MuxReactorElement()
        reactor.recordAccess("k1", "openai")
        reactor.recordAccess("k2", "anthropic")
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 1000)
        val now = 7_000_000L

        assertNull(legion.nextKey(reactor.flowState.value, now, provider = "openai", excluding = setOf("k1")))
        assertEquals("k2", legion.nextKey(reactor.flowState.value, now, excluding = setOf("k1"))?.keyId)
        assertEquals("k1", legion.nextKey(reactor.flowState.value, now, provider = "openai")?.keyId)
    }

    @Test
    fun providerLimitOverridesDefault() = runTest {
        val reactor = MuxReactorElement()
        reactor.recordAccess("k1", "free-tier")
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 1000, limitsByProvider = mapOf("free-tier" to 50L))
        val now = 9_000_000L
        legion.applyReceipt("k1", "free-tier", receipt(30, 30), now) // 60 > 50
        val standing = legion.standings(reactor.flowState.value, now)[0]
        assertEquals(50L, standing.limit)
        assertTrue(standing.isUsable, "configured estimates rank but do not replace HTTP quota observations")
        assertEquals(1.0, standing.utilization)
    }

    @Test
    fun censusCountsUsableExhaustedUnmetered() = runTest {
        val reactor = MuxReactorElement()
        reactor.recordAccess("k1", "openai")
        reactor.recordAccess("k2", "openai")
        reactor.recordAccess("k3", "anthropic")
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 0) // unmetered legion
        val now = 11_000_000L
        legion.exhaust("k3", "anthropic", now)
        val census = legion.census(reactor.flowState.value, now)
        assertEquals(3, census.total)
        assertEquals(2, census.usable)
        assertEquals(1, census.exhausted)
        assertEquals(3, census.unmetered)
    }

    @Test
    fun reinstateClearsExhaustion() = runTest {
        val reactor = MuxReactorElement()
        reactor.recordAccess("k1", "openai")
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 1000)
        val now = 13_000_000L
        legion.exhaust("k1", "openai", now)
        assertNull(legion.nextKey(reactor.flowState.value, now))
        legion.reinstate("k1")
        assertNotNull(legion.nextKey(reactor.flowState.value, now))
    }

    @Test
    fun httpRemainingAndResetControlAdmissionWithoutDoubleChargingTheReceipt() = runTest {
        val reactor = MuxReactorElement()
        reactor.recordAccess("key", "p")
        val legion = QuotaLegion()
        val now = 20_000_000L
        legion.applyReceipt("key", "p", receipt(20, 10), now)
        legion.observeHttp("key", "p", 200, borg.trikeshed.htx.htxHeaders(
            "X-RateLimit-Limit-Tokens" j "100",
            "X-RateLimit-Remaining-Tokens" j "70",
            "X-RateLimit-Reset-Tokens" j "2s",
        ), now)
        assertEquals(70L, legion.standings(reactor.flowState.value, now)[0].remaining)
        legion.applyReceipt("key", "p", receipt(80, 0), now + 1)
        val estimated = legion.standings(reactor.flowState.value, now + 1)[0]
        assertEquals(0L, estimated.remaining)
        assertTrue(estimated.isUsable, "receipt arithmetic ranks; only the provider can close admission")
        assertTrue(legion.isAvailable("key", now + 1))
        legion.observeHttp("key", "p", 429, borg.trikeshed.htx.htxHeaders("Retry-After" j "2"), now)
        assertFalse(legion.isAvailable("key", now + 1))
        assertTrue(legion.isAvailable("key", now + 2_001))
    }

    @Test
    fun http200LobbyBodyIsNotAcceptedOrCachedAsACompletion() = runTest {
        var exchanges = 0
        val htx = openHtxElement(routeService = KeyMuxTestService {
            exchanges++
            if (exchanges == 1) """{"status":"loading","message":"Model is starting"}"""
            else """{"choices":[{"message":{"content":"ready"}}],"usage":{"prompt_tokens":2,"completion_tokens":1}}"""
        })
        val mux = ModelMux(KeyMux { bind("llm.m.key", TestKeySource()) }) { model("m", caps = setOf("chat")) }
        val reactor = MuxReactorElement()
        withContext(coroutineContext + htx + reactor) {
            assertTrue(mux.chat("m", _s["user" j "answer"].toSeries()).isFailure)
            assertTrue(mux.chat("m", _s["user" j "answer"].toSeries()).isSuccess)
        }
        assertEquals(2, exchanges)
        htx.close()
    }

    @Test
    fun streamAndEmbedObserve429RotatePoolRowsAndRespectBackoff() = runTest {
        for (stream in listOf(true, false)) {
            val auth = """{"credential_pool":{"p":[
                {"id":"first","source":"manual","priority":0,"access_token":"fixture-first","base_url":"https://fixture.invalid/v1"},
                {"id":"second","source":"manual","priority":1,"access_token":"fixture-second","base_url":"https://fixture.invalid/v1"}
            ]}}"""
            val files = keymux.KeyMuxTest.FakeFileOperations(mutableMapOf("/hermes/auth.json" to auth.encodeToByteArray()))
            val source = keymux.HermesCredentialSource("/hermes", files)
            val legion = QuotaLegion()
            val mux = ModelMux(KeyMux { bind("llm.*.*", source) }) {
                model("m", setOf("chat", "embed"), provider = "p")
                quota(legion)
            }
            val secrets = mutableListOf<String?>()
            val htx = openHtxElement(routeService = object : HtxRouteService {
                override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                    secrets += request.headers.let { rows -> (0 until rows.size).map { rows[it] }.firstOrNull { it.a == "Authorization" }?.b }
                    val response = HtxResponse(429, headers = borg.trikeshed.htx.htxHeaders("Retry-After" j "60"))
                    return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
                }
            })
            try {
                withContext(coroutineContext + htx) {
                    repeat(3) {
                        assertTrue(runCatching {
                            if (stream) mux.stream("m", _s["user" j "answer"].toSeries()).collect()
                            else mux.embed("m", _s["text"].toSeries())
                        }.isFailure)
                    }
                }
                assertEquals(listOf<String?>("Bearer fixture-first", "Bearer fixture-second"), secrets,
                    "each actual HTTP429 rotates its pool row; an exhausted pool makes no third request")
                val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                assertFalse(legion.isAvailable("p:first", now))
                assertFalse(legion.isAvailable("p:second", now))
                assertTrue(legion.isAvailable("p:first", now + 60_001))
            } finally { htx.close() }
        }
    }

    @Test
    fun loomCommittedHttp200PreservesPendingAndQuotaStatus() = runTest {
        var body = """{"error":{"code":"invalid_request_error"},"loom":{"status":202,"billing_status":"pending"}}"""
        val htx = openHtxElement(routeService = KeyMuxTestService { body })
        val legion = QuotaLegion()
        val mux = ModelMux(KeyMux { bind("llm.m.key", TestKeySource()) }) {
            model("m", caps = setOf("chat"), baseUrl = "https://api.loom.vsiwest.com/v1")
            quota(legion)
        }
        withContext(coroutineContext + htx) {
            val pending = mux.chat("m", _s["user" j "answer"].toSeries())
            assertEquals(202, (pending.exceptionOrNull() as ModelHttpFailure).status)
            assertTrue(legion.isAvailable("llm.m.key", kotlinx.datetime.Clock.System.now().toEpochMilliseconds()),
                "pending inference does not exhaust a credential")
            body = """{"error":{"code":"rate_limit_error"},"loom":{"status":429,"retry_after_seconds":2}}"""
            val limited = mux.chat("m", _s["user" j "answer"].toSeries())
            assertEquals(429, (limited.exceptionOrNull() as ModelHttpFailure).status)
            val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            assertFalse(legion.isAvailable("llm.m.key", now))
            assertTrue(legion.isAvailable("llm.m.key", now + 2_001))
        }
        htx.close()
    }

    @Test
    fun loomNoticesAreNotCompletionsButPendingBillingCanAccompanyRealOutput() = runTest {
        var body = """{"choices":[{"message":{"content":"⏳ LOOM: starting a GPU\n\n"},"finish_reason":"stop"}]}"""
        val htx = openHtxElement(routeService = KeyMuxTestService { body })
        val mux = ModelMux(KeyMux { bind("llm.m.key", TestKeySource()) }) {
            model("m", caps = setOf("chat"), baseUrl = "https://api.loom.vsiwest.com/v1")
        }
        val reactor = MuxReactorElement()
        withContext(coroutineContext + htx + reactor) {
            assertEquals(202, (mux.chat("m", _s["user" j "answer"].toSeries()).exceptionOrNull() as ModelHttpFailure).status)
            body = """{"choices":[{"message":{"content":"ready"},"finish_reason":"stop"}],"loom":{"billing_status":"pending"}}"""
            assertEquals("ready", mux.chat("m", _s["user" j "answer"].toSeries()).getOrThrow().a)
        }
        htx.close()
    }

    @Test
    fun streamHeartbeatAndLoomNoticesDoNotCompleteModelWork() = runTest {
        var body = ": ⏳ LOOM: starting a GPU\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":null}]}\n\n"
        val htx = openHtxElement(routeService = KeyMuxTestService { body })
        val mux = ModelMux(KeyMux { bind("llm.m.key", TestKeySource()) }) {
            model("m", caps = setOf("chat"), baseUrl = "https://api.loom.vsiwest.com/v1")
        }
        withContext(coroutineContext + htx) {
            var chunks = 0
            val pending = runCatching { mux.stream("m", _s["user" j "answer"].toSeries()).collect { chunks++ } }
            assertTrue(pending.isFailure)
            assertEquals(0, chunks)
            body += "data: {\"choices\":[{\"delta\":{\"content\":\"ready\"},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            var answer = ""
            mux.stream("m", _s["user" j "answer"].toSeries()).collect { answer += it.a }
            assertEquals("ready", answer)
        }
        htx.close()
    }

    private class KeyMuxTestService(val response: () -> String) : HtxRouteService {
        override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult =
            HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request,
                response = HtxResponse(status = 200, body = ByteSeries(response().encodeToByteArray()))))
    }

    @Test
    fun modelMuxChatMetersLegionOnRealCalls() = runTest {
        val fakeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val response = HtxResponse(
                    status = 200,
                    body = ByteSeries(
                        """{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":7,"completion_tokens":3}}""".encodeToByteArray(),
                    ),
                )
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
            }
        }
        val htx = openHtxElement(routeService = fakeService)
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 1000)
        val keyMux = KeyMux { bind("llm.gpt-4.key", TestKeySource()) }
        val models = ModelMux(keyMux) {
            model("gpt-4", caps = setOf("chat"))
            quota(legion)
        }
        val result = withContext(coroutineContext + htx) {
            models.chat("gpt-4", _s["user" j "hi"].toSeries())
        }
        assertTrue(result.isSuccess)
        // metering keys on the resolved key ID (the binding path), never the
        // secret value the source returns
        val reactor = MuxReactorElement()
        reactor.recordAccess("llm.gpt-4.key", "gpt-4")
        val standing = legion.standings(reactor.flowState.value, kotlinx.datetime.Clock.System.now().toEpochMilliseconds())[0]
        assertEquals(10L, standing.spent, "chat receipt tokens must be metered into the legion")
        htx.close()
    }

    @Test
    fun modelMuxChatMetersTaggedProviderUnderProviderTagKey() = runTest {
        // Before the keyId fix, chat() resolved the key VALUE and used it as the
        // ledger key; a provider-tagged card resolves no `llm.<modelId>.key`
        // binding, so tagged providers metered under null — receipts fell on
        // the floor. The fix routes metering through the same providerTag
        // fallback chain session() uses.
        val fakeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val response = HtxResponse(
                    status = 200,
                    body = ByteSeries(
                        """{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":6,"completion_tokens":4}}""".encodeToByteArray(),
                    ),
                )
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
            }
        }
        val htx = openHtxElement(routeService = fakeService)
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 1000)
        val keyMux = KeyMux { bind("llm.nvidia.key", TestKeySource()) }
        val models = ModelMux(keyMux) {
            model("deepseek-ai/deepseek-v4-pro", caps = setOf("chat"), provider = "nvidia")
            quota(legion)
        }
        val result = withContext(coroutineContext + htx) {
            models.chat("deepseek-ai/deepseek-v4-pro", _s["user" j "hi"].toSeries())
        }
        assertTrue(result.isSuccess, "tagged provider must open a session via its pooled credential")
        val reactor = MuxReactorElement()
        reactor.recordAccess("llm.nvidia.key", "nvidia")
        val standing = legion.standings(reactor.flowState.value, kotlinx.datetime.Clock.System.now().toEpochMilliseconds())[0]
        assertEquals(10L, standing.spent, "tagged provider receipts must meter under llm.<providerTag>.key")
        htx.close()
    }

    @Test
    fun requestHashIsContentAddressedNotHashCode() = runTest {
        // Two distinct request bodies must never share a cache slot: the old
        // 32-bit hashCode invited collisions that returned the WRONG cached
        // payload verbatim. ContentId over canonical bytes is sha256.
        val h1 = ContentId.of("""{"model":"a","messages":[]}""".encodeToByteArray()).value
        val h2 = ContentId.of("""{"model":"b","messages":[]}""".encodeToByteArray()).value
        assertTrue(h1.startsWith("sha256:") && h2.startsWith("sha256:"))
        assertTrue(h1 != h2, "distinct requests must hash distinctly")
        assertEquals(
            ContentId.of("""{"model":"a","messages":[]}""".encodeToByteArray()).value,
            h1,
            "identical requests must hash identically (cache affinity depends on it)",
        )
    }
}
