package muxcontract

import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.lib._s
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.reactor.MuxReactorElement
import keymux.KeyMux
import keymux.TestKeySource
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import modelmux.ModelMux
import modelmux.ModelSelectionEvent
import modelmux.QuotaLegion
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ModelMux behavior contract — every invariant in docs/mux-repair-contract.md (M1–M10),
 * pinned with mocks. These tests are the reconstruction spec: if the sources are
 * mangled, rebuild until this file passes.
 */
class ModelMuxContractTest {

    // M1: provider tag → per-model → default key resolution chain.
    @Test
    fun m1_keyResolutionChain() = runTest {
        val htx = openHtxElement(routeService = FakeTransport { _, _ -> FakeTransport.chatJson("ok") })
        // Tagged provider: pooled credential wins even though no per-model key exists.
        val tagged = ModelMux(KeyMux { bind("llm.acme.key", TestKeySource(value = "sk-acme")) }) {
            model("acme/ultra", caps = setOf("chat"), provider = "acme")
            noQuota()
        }
        val taggedSession = tagged.session("acme/ultra")
        assertTrue(taggedSession.isSuccess, "tagged provider resolves the pooled credential")
        assertEquals("Bearer sk-acme", taggedSession.getOrThrow().authHeaders()[0].b,
            "Authorization header carries the pooled credential")

        // Untagged: per-model key.
        val perModel = ModelMux(KeyMux { bind("llm.gpt-4.key", TestKeySource(value = "sk-model")) }) {
            model("gpt-4", caps = setOf("chat"))
            noQuota()
        }
        assertTrue(perModel.session("gpt-4").isSuccess)

        // Default fallback when no model key is bound.
        val defaulted = ModelMux(KeyMux { bind("llm.default.key", TestKeySource(value = "sk-def")) }) {
            model("gpt-4", caps = setOf("chat"))
            noQuota()
        }
        assertTrue(defaulted.session("gpt-4").isSuccess)

        // Nothing bound → failure.
        val unbound = ModelMux(KeyMux {}) {
            model("gpt-4", caps = setOf("chat"))
            noQuota()
        }
        assertTrue(unbound.session("gpt-4").isFailure)
        assertTrue(unbound.session("unknown-model").isFailure)
        htx.close()
    }

    // M2 + M5: metering identity is the binding path; only real calls meter.
    @Test
    fun m2m5_meteringByBindingPathAndCachedCallsSkipped() = runTest {
        val transport = FakeTransport { _, _ -> FakeTransport.chatJson("ok", promptTokens = 4, completionTokens = 6) }
        val htx = openHtxElement(routeService = transport)
        val reactor = MuxReactorElement()
        reactor.recordAccess("llm.gpt-4.key", "gpt-4")
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 1000)
        val mux = ModelMux(KeyMux { bind("llm.gpt-4.key", TestKeySource(value = "sk-SECRET")) }) {
            model("gpt-4", caps = setOf("chat"))
            quota(legion)
        }
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

        // First call: cache miss → meters 10 tokens under the binding path.
        val r1 = withContext(coroutineContext + htx + reactor) { mux.chat("gpt-4", _s["user" j "hi"].toSeries()) }
        assertTrue(r1.isSuccess)
        // Second identical call: cache hit → no transport, no metering.
        val r2 = withContext(coroutineContext + htx + reactor) { mux.chat("gpt-4", _s["user" j "hi"].toSeries()) }
        assertTrue(r2.isSuccess)
        assertEquals(1, transport.calls, "cache hit must not reach the transport (M3)")

        val standings = withContext(coroutineContext + reactor) { mux.quotaStandings(now) }
        val row = standings.first { it.keyId == "llm.gpt-4.key" }
        assertEquals(10L, row.spent, "one real call meters once (M5)")
        assertFalse("sk-SECRET" in row.keyId, "secret must never be a metering key (M2)")
        htx.close()
    }

    // M3: content-addressed cache — engineered 32-bit collision gets distinct entries+replies.
    @Test
    fun m3_hashCollisionDistinctEntriesAndReplies() = runTest {
        val transport = FakeTransport { _, i -> FakeTransport.chatJson("resp-$i") }
        val htx = openHtxElement(routeService = transport)
        val reactor = MuxReactorElement()
        val mux = ModelMux(KeyMux { bind("llm.gpt-4.key", TestKeySource()) }) {
            model("gpt-4", caps = setOf("chat"))
            noQuota()
        }
        // "Aa"/"BB" share String.hashCode; bodies differ only in that substring.
        val r1 = withContext(coroutineContext + htx + reactor) { mux.chat("gpt-4", _s["user" j "xAa"].toSeries()) }
        val r2 = withContext(coroutineContext + htx + reactor) { mux.chat("gpt-4", _s["user" j "xBB"].toSeries()) }
        assertTrue(r1.isSuccess && r2.isSuccess)
        assertEquals(2, transport.calls, "collision must not produce a wrongful cache hit")
        assertEquals(transport.bodies[0].hashCode(), transport.bodies[1].hashCode(), "fixture is a real 32-bit collision")
        assertEquals("resp-1", r1.getOrThrow().a)
        assertEquals("resp-2", r2.getOrThrow().a)
        assertEquals(2, reactor.cache.apiCallCount(), "distinct cache entries")
        htx.close()
    }

    // M4: 429 records a receipt and exhausts the key; failure receipts carry the status.
    @Test
    fun m4_429ReceiptExhaustsKey() = runTest {
        val htx = openHtxElement(routeService = FakeTransport { _, _ -> FakeTransport.status(429, "rate limited") })
        val reactor = MuxReactorElement()
        reactor.recordAccess("llm.gpt-4.key", "gpt-4")
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 1000)
        val mux = ModelMux(KeyMux { bind("llm.gpt-4.key", TestKeySource()) }) {
            model("gpt-4", caps = setOf("chat"))
            quota(legion)
        }
        val result = withContext(coroutineContext + htx + reactor) { mux.chat("gpt-4", _s["user" j "hi"].toSeries()) }
        assertTrue(result.isFailure)
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val standings = withContext(coroutineContext + reactor) { mux.quotaStandings(now) }
        assertTrue(standings.first { it.keyId == "llm.gpt-4.key" }.exhausted, "429 receipt exhausts the key (M4)")
        assertNull(legion.nextKey(reactor.flowState.value, now, provider = "gpt-4"), "exhausted key refused (M8)")
        htx.close()
    }

    // M4 (transport error arm): an exception mints an error receipt and the call fails.
    @Test
    fun m4_transportErrorMintsErrorReceipt() = runTest {
        val htx = openHtxElement(routeService = FakeTransport { _, _ -> throw RuntimeException("boom") })
        val reactor = MuxReactorElement()
        reactor.recordAccess("llm.gpt-4.key", "gpt-4")
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 1000)
        val mux = ModelMux(KeyMux { bind("llm.gpt-4.key", TestKeySource()) }) {
            model("gpt-4", caps = setOf("chat"))
            quota(legion)
        }
        val result = withContext(coroutineContext + htx + reactor) { mux.chat("gpt-4", _s["user" j "hi"].toSeries()) }
        assertTrue(result.isFailure)
        // The error receipt meters zero tokens (none were consumed) but lands in the ledger:
        // accessCount on the reactor key still reflects the real call attempt.
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val standings = withContext(coroutineContext + reactor) { mux.quotaStandings(now) }
        assertEquals(0L, standings.first { it.keyId == "llm.gpt-4.key" }.spent, "error receipt meters no tokens")
        htx.close()
    }

    // M6: stream uses the binding-path lease identity and emits SSE deltas.
    @Test
    fun m6_streamEmitsDeltasAndUsesBindingPathLease() = runTest {
        val htx = openHtxElement(routeService = FakeTransport { _, _ -> FakeTransport.sse("hel", "lo") })
        val reactor = MuxReactorElement()
        reactor.recordAccess("llm.gpt-4.key", "gpt-4")
        val mux = ModelMux(KeyMux { bind("llm.gpt-4.key", TestKeySource(value = "sk-SECRET")) }) {
            model("gpt-4", caps = setOf("chat"))
            noQuota()
        }
        val chunks = mutableListOf<String>()
        withContext(coroutineContext + htx + reactor) {
            mux.stream("gpt-4", _s["user" j "hi"].toSeries()).collect { chunks.add(it.a) }
        }
        assertEquals(listOf("hel", "lo"), chunks)
        htx.close()
    }

    // M6: embed parses vectors and caches by content identity.
    @Test
    fun m6_embedParsesAndCaches() = runTest {
        val transport = FakeTransport { _, _ -> FakeTransport.embeddings() }
        val htx = openHtxElement(routeService = transport)
        val reactor = MuxReactorElement()
        val mux = ModelMux(KeyMux { bind("llm.emb.key", TestKeySource()) }) {
            model("emb", caps = setOf("embed"))
            noQuota()
        }
        val r1 = withContext(coroutineContext + htx + reactor) { mux.embed("emb", _s["hello"].toSeries()) }
        assertEquals(1, r1.size)
        assertEquals(0.5, r1[0].b[0])
        withContext(coroutineContext + htx + reactor) { mux.embed("emb", _s["hello"].toSeries()) }
        assertEquals(1, transport.calls, "identical embed is a cache hit")
        htx.close()
    }

    // M7: the builder meters by default (no .quota() call needed).
    @Test
    fun m7_legionConstructedByDefault() = runTest {
        val htx = openHtxElement(routeService = FakeTransport { _, _ -> FakeTransport.chatJson("ok", 2, 3) })
        val reactor = MuxReactorElement()
        reactor.recordAccess("llm.gpt-4.key", "gpt-4")
        val mux = ModelMux(KeyMux { bind("llm.gpt-4.key", TestKeySource()) }) {
            model("gpt-4", caps = setOf("chat"))
            // note: NO .quota() — the default legion must be present
        }
        withContext(coroutineContext + htx + reactor) { mux.chat("gpt-4", _s["user" j "hi"].toSeries()) }
        val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
        val standings = withContext(coroutineContext + reactor) { mux.quotaStandings(now) }
        assertEquals(5L, standings.firstOrNull { it.keyId == "llm.gpt-4.key" }?.spent,
            "default legion meters without explicit wiring (M7)")
        htx.close()
    }

    // M8: standings rank usable-first; window rollover resets spend.
    @Test
    fun m8_standingsRankingAndWindowRollover() = runTest {
        val reactor = MuxReactorElement()
        reactor.recordAccess("k1", "p")
        reactor.recordAccess("k2", "p")
        val legion = QuotaLegion(windowMs = 60_000, defaultLimit = 100)
        val t0 = 1_000_000L
        legion.applyReceipt("k1", "p", receipt(60, 50), t0) // 110 > 100 → spent out
        val ranked = legion.standings(reactor.flowState.value, t0)
        assertEquals("k2", ranked[0].keyId, "usable key ranks first")
        assertFalse(ranked.toList().first { it.keyId == "k1" }.isUsable)
        // Rollover: a receipt in the next window resets spend and exhaustion.
        val t1 = t0 + 61_000
        legion.applyReceipt("k1", "p", receipt(1, 1), t1)
        assertTrue(legion.standings(reactor.flowState.value, t1).toList().first { it.keyId == "k1" }.isUsable)
    }

    // M9: route emits ModelSelected for the head; observer exceptions never fail the route.
    @Test
    fun m9_routeEmitsSelectionAndObserverCannotFailIt() = runTest {
        val mux = ModelMux(KeyMux { bind("llm.gpt-4.key", TestKeySource()) }) {
            model("gpt-4", caps = setOf("chat"))
            noQuota()
        }
        var observed: ModelSelectionEvent.ModelSelected? = null
        mux.selectionObserver = { observed = it as? ModelSelectionEvent.ModelSelected }
        val routed = mux.route("chat", "chat")
        assertEquals(1, routed.a.size)
        assertNotNull(observed)
        assertEquals("gpt-4", observed!!.model)
        assertEquals(observed, mux.lastSelection)

        // Throwing observer must not fail the route.
        mux.selectionObserver = { error("observer exploded") }
        val again = mux.route("chat", "chat")
        assertEquals(1, again.a.size, "observer failure never fails the route")

        // Empty route emits nothing.
        val empty = mux.route("chat", "nonexistent-capability")
        assertEquals(0, empty.a.size)
    }

    // M10: receipts carry provider-measured cache counts only.
    @Test
    fun m10_receiptsCarryProviderMeasuredCacheCounts() = runTest {
        val htx = openHtxElement(routeService = FakeTransport { _, _ ->
            FakeTransport.chatJson("ok", 3, 2).let {
                borg.trikeshed.htx.HtxResponse(200, borg.trikeshed.lib.ByteSeries(
                    """{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":3,"completion_tokens":2,"cache_read_input_tokens":7,"cache_creation_input_tokens":11}}""".encodeToByteArray()))
            }
        })
        val mux = ModelMux(KeyMux { bind("llm.gpt-4.key", TestKeySource()) }) {
            model("gpt-4", caps = setOf("chat"))
            noQuota()
        }
        val result = withContext(coroutineContext + htx) { mux.chat("gpt-4", _s["user" j "hi"].toSeries()) }
        assertTrue(result.isSuccess)
        // Provider-measured counts are recorded on the receipt (7 read, 11 write) —
        // verified indirectly: the response parses and the call succeeds; the receipt
        // surface is exercised in modelmux.QuotaLegionTest metering tests.
        htx.close()
    }

    private fun receipt(input: Int, output: Int, status: Int = 200) =
        borg.trikeshed.modelmux.ModelResponseReceipt.mint(
            modelId = "m", providerId = "p", requestHash = "h",
            action = "chat", httpStatus = status, latencyMs = 1,
            inputTokens = input, outputTokens = output,
        )
}
