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
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P0 gates (right-emails-are-more-parsed-sunbeam): the three ModelMux defects,
 * each proven by a gate — repair in place, never rewrite.
 *
 *  1. Metering keyed by the key-ID (binding path), never the secret value.
 *     Two keys, same provider → distinct metering rows; the secret string
 *     appears in NO metering key (walked via the standings projection).
 *  2. Cache identity = full content hash. Two requests engineered to collide
 *     under the old 32-bit String.hashCode get DISTINCT cache entries and
 *     DISTINCT replies (the second never returns the first's payload).
 *  3. QuotaLegion constructed + wired: after a fixture call through the mux,
 *     standings show the spend against the right key-ID; an exhausted key is
 *     refused admission by nextKey.
 */
class ModelMuxP0GateTest {

    // ── Gate 1: metering keyed by key-ID, secret in no map key ──────────
    @Test
    fun meteringKeysOnKeyIdNeverTheSecret() = runTest {
        val reactor = MuxReactorElement()
        // Two keys, SAME provider, distinct binding paths.
        reactor.recordAccess("llm.alpha.key", "prov")
        reactor.recordAccess("llm.beta.key", "prov")
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 1000)
        val now = 21_000_000L
        val secretAlpha = "sk-live-ALPHA-do-not-leak"
        val secretBeta = "sk-live-BETA-do-not-leak"

        legion.applyReceipt("llm.alpha.key", "prov", receipt(100, 50), now)
        legion.applyReceipt("llm.beta.key", "prov", receipt(10, 5), now)

        val ranked = legion.standings(reactor.flowState.value, now)
        assertEquals(2, ranked.size, "two keys, same provider → two distinct metering rows")
        val byKey = ranked.toList().associateBy { it.keyId }
        assertNotNull(byKey["llm.alpha.key"])
        assertNotNull(byKey["llm.beta.key"])
        assertEquals(150L, byKey["llm.alpha.key"]!!.spent)
        assertEquals(15L, byKey["llm.beta.key"]!!.spent)
        assertEquals("prov", byKey["llm.alpha.key"]!!.provider)
        assertEquals("prov", byKey["llm.beta.key"]!!.provider)

        // Walk the metering structure (the standings projection names every
        // meter key): the secret value must appear in NO key.
        for (i in 0 until ranked.size) {
            val s = ranked[i]
            assertFalse(secretAlpha in s.keyId, "secret leaked into metering key: ${s.keyId}")
            assertFalse(secretBeta in s.keyId, "secret leaked into metering key: ${s.keyId}")
            assertFalse(s.keyId.startsWith("sk-"), "metering key looks like a credential: ${s.keyId}")
        }
    }

    // ── Gate 2: 32-bit hash collision gets distinct cache entries + replies ──
    @Test
    fun collidingHashCodeRequestsGetDistinctCacheEntriesAndReplies() = runTest {
        val bodies = mutableListOf<String>()
        var calls = 0
        val fakeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                calls++
                val body = request.body.toArray().decodeToString()
                bodies.add(body)
                val response = HtxResponse(
                    status = 200,
                    body = ByteSeries(
                        """{"choices":[{"message":{"content":"resp-$calls"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}""".encodeToByteArray(),
                    ),
                )
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
            }
        }
        val htx = openHtxElement(routeService = fakeService)
        val reactor = MuxReactorElement()
        val keyMux = KeyMux { bind("llm.gpt-4.key", TestKeySource()) }
        val models = ModelMux(keyMux) { model("gpt-4", caps = setOf("chat")) }

        // "Aa" and "BB" have identical String.hashCode(); bodies differing only
        // in that substring collide under the old 32-bit hash but not sha256.
        val r1 = withContext(coroutineContext + htx + reactor) {
            models.chat("gpt-4", _s["user" j "xAa"].toSeries())
        }
        val r2 = withContext(coroutineContext + htx + reactor) {
            models.chat("gpt-4", _s["user" j "xBB"].toSeries())
        }
        assertTrue(r1.isSuccess && r2.isSuccess)

        // The fixture is a REAL 32-bit collision (else this gate proves nothing).
        assertEquals(2, bodies.size, "both requests must reach the provider (no wrongful hit)")
        assertEquals(bodies[0].hashCode(), bodies[1].hashCode(), "fixture must collide under 32-bit hashCode")
        assertTrue(bodies[0] != bodies[1], "but the canonical bytes are distinct")

        // Distinct replies: the second call returned ITS OWN payload, not the first's.
        assertEquals("resp-1", r1.getOrThrow().a)
        assertEquals("resp-2", r2.getOrThrow().a)
        // Distinct cache entries in the reactor-owned cache.
        assertEquals(2, reactor.cache.apiCallCount(), "colliding hashes must still occupy distinct cache slots")
        htx.close()
    }

    // ── Gate 3: legion wired — standings show live spend; exhausted refused ──
    @Test
    fun standingsShowLiveSpendAndExhaustedKeyIsRefused() = runTest {
        val fakeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val response = HtxResponse(
                    status = 200,
                    body = ByteSeries(
                        """{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":9,"completion_tokens":3}}""".encodeToByteArray(),
                    ),
                )
                return HtxExchangeResult(state.copy(lifecycle = HtxExchangeLifecycle.RESPONDED, request = request, response = response))
            }
        }
        val htx = openHtxElement(routeService = fakeService)
        val reactor = MuxReactorElement()
        // Seed the reactor roster with the binding path the mux meters under, so
        // quotaStandings (reactor roster × ledger) can name it.
        reactor.recordAccess("llm.gpt-4.key", "gpt-4")
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 1000)
        val keyMux = KeyMux { bind("llm.gpt-4.key", TestKeySource()) }
        val models = ModelMux(keyMux) {
            model("gpt-4", caps = setOf("chat"))
            quota(legion)
        }

        val result = withContext(coroutineContext + htx + reactor) {
            models.chat("gpt-4", _s["user" j "hi"].toSeries())
        }
        assertTrue(result.isSuccess)

        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val standings = withContext(coroutineContext + reactor) { models.quotaStandings(now) }
        val mine = standings.firstOrNull { it.keyId == "llm.gpt-4.key" }
        assertNotNull(mine, "standings must name the metered binding path")
        assertEquals(12L, mine.spent, "live spend (9+3 tokens) lands against the right key-ID")
        assertTrue(mine.isUsable)

        // Exhaust the key — the provider's word outranks the ledger; nextKey refuses it.
        legion.exhaust("llm.gpt-4.key", "gpt-4", now)
        val after = withContext(coroutineContext + reactor) { models.quotaStandings(now) }
        val exhausted = after.first { it.keyId == "llm.gpt-4.key" }
        assertTrue(exhausted.exhausted)
        assertFalse(exhausted.isUsable)
        assertNull(legion.nextKey(reactor.flowState.value, now, provider = "gpt-4"), "exhausted key must be refused admission")
        htx.close()
    }

    private fun receipt(input: Int, output: Int, status: Int = 200): borg.trikeshed.modelmux.ModelResponseReceipt =
        borg.trikeshed.modelmux.ModelResponseReceipt.mint(
            modelId = "m", providerId = "p", requestHash = "h",
            action = "chat", httpStatus = status, latencyMs = 1,
            inputTokens = input, outputTokens = output,
        )
}
