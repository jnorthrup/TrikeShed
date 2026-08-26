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
import borg.trikeshed.modelmux.ModelResponseReceipt
import borg.trikeshed.userspace.reactor.MuxReactorElement
import borg.trikeshed.userspace.reactor.MuxKeyStatus
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
        assertFalse(legion.standings(reactor.flowState.value, t0)[0].isUsable)

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
        assertFalse(standing.isUsable)
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
        // the resolved key value ("sk-test") is the legion's ledger key in standalone mode
        val reactor = MuxReactorElement()
        reactor.recordAccess("sk-test", "gpt-4")
        val standing = legion.standings(reactor.flowState.value, kotlinx.datetime.Clock.System.now().toEpochMilliseconds())[0]
        assertEquals(10L, standing.spent, "chat receipt tokens must be metered into the legion")
        htx.close()
    }
}
