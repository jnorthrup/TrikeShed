package borg.trikeshed.jules

import keymux.KeyMux
import keymux.harness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Council graft #1: BrainClient discovery with an external KeyMux is UN-GATED —
 * the full rosterInto table is admitted (env-present providers first) and
 * per-call KeyMux/harness resolution decides key presence — while standalone
 * (keyMux == null) discovery stays env-gated exactly as before. Plus the pure
 * seat-order rotation, the missing-key classifier, and the BrainNoRoute trail.
 */
class BrainClientRosterTest {

    private fun spec(name: String, envVar: String) =
        BrainClient.EndpointSpec(name, envVar, "https://example.invalid/v1", "$name-model")

    @Test
    fun externalKeyMuxAdmitsTheFullRoster() {
        // Harness-file-only setup: no relevant env vars needed — the roster must
        // still be full, because per-call resolution (not discovery) owns keys.
        val client = BrainClient(keyMux = KeyMux { harness() })
        assertTrue(client.hasEndpoints(), "external-keyMux BrainClient must discover the ungated roster")
        assertTrue(client.endpointSummaries().isNotEmpty())
        // Every roster row reports discovered=true under an external keyMux.
        client.rosterStatus().forEach { row ->
            assertEquals(true, row["discovered"], "ungated discovery must admit ${row["name"]}")
        }
    }

    @Test
    fun standaloneDiscoveryStaysEnvGated() {
        // Standalone (keyMux == null): discovery is unchanged — an endpoint is
        // discovered iff its env var is present, pinned via rosterStatus flags.
        val client = BrainClient()
        client.rosterStatus().forEach { row ->
            assertEquals(
                row["keyPresent"], row["discovered"],
                "standalone discovery must remain env-gated for ${row["name"]}",
            )
        }
    }

    @Test
    fun orderEnvFirstPutsEnvPresentProvidersFirst() {
        val specs = listOf(spec("a", "ENV_A"), spec("b", "ENV_B"), spec("c", "ENV_C"), spec("d", "ENV_D"))

        val soleC = BrainClient.orderEnvFirst(specs) { env -> if (env == "ENV_C") "present" else null }
        assertEquals(listOf("c", "a", "b", "d"), soleC.map { it.name }, "sole env-present provider leads")

        val bAndD = BrainClient.orderEnvFirst(specs) { env -> if (env == "ENV_B" || env == "ENV_D") "x" else null }
        assertEquals(listOf("b", "d", "a", "c"), bAndD.map { it.name }, "stable partition preserves relative order")

        val none = BrainClient.orderEnvFirst(specs) { null }
        assertEquals(specs.map { it.name }, none.map { it.name }, "no env present -> order untouched")

        val blank = BrainClient.orderEnvFirst(specs) { "" }
        assertEquals(specs.map { it.name }, blank.map { it.name }, "blank env values are not presence")
    }

    @Test
    fun seatOrderRotatesToThePreferredModel() {
        val ids = listOf("m1", "m2", "m3", "m4")
        assertEquals(listOf("m3", "m4", "m1", "m2"), BrainClient.seatOrder(ids, "m3"))
        assertEquals(ids, BrainClient.seatOrder(ids, "m1"), "already-first preferred is a no-op rotation")
        assertEquals(ids, BrainClient.seatOrder(ids, "not-in-roster"), "absent preferred falls back cleanly")
        assertEquals(ids, BrainClient.seatOrder(ids, null), "null preferred keeps the lastGood rotation")
        assertEquals(emptyList(), BrainClient.seatOrder(emptyList(), "m1"), "empty roster stays empty")
    }

    @Test
    fun missingKeyMatcherClassifiesKeyAbsenceOnly() {
        assertTrue(BrainClient.isMissingKeyFailure("KeyMux: no key for llm.x.key"))
        assertTrue(BrainClient.isMissingKeyFailure("key not found: llm.groq.key"))
        assertTrue(BrainClient.isMissingKeyFailure("HTTP 401 Unauthorized"))
        assertFalse(BrainClient.isMissingKeyFailure("HTTP 500"))
        assertFalse(BrainClient.isMissingKeyFailure("connection reset by peer"))
        assertFalse(BrainClient.isMissingKeyFailure("timeout waiting for response"))
    }

    @Test
    fun brainNoRouteCarriesEveryAttemptInOrder() {
        val attempts = listOf(
            "groq/llama-3.3-70b-versatile: KeyMux: no key for llm.groq.key",
            "deepseek/deepseek-chat: HTTP 500",
            "nv-glm-52/z-ai/glm-5.2: skipped (no-key verdict cached)",
        )
        val e = BrainNoRoute(attempts)
        assertEquals(attempts, e.attempts)
        assertEquals("no provider answered: " + attempts.joinToString(" -> "), e.message)
    }

    @Test
    fun rateLimitClassifierMatchesZaiShapesOnly() {
        // z.ai/Zhipu shapes seen live: HTTP 429 + code 1302 (rpm) / 1305 (overload)
        kotlin.test.assertTrue(BrainClient.isRateLimitFailure("""ModelMux chat failed with HTTP 429: {"error":{"code":"1302","message":"Rate limit reached for requests"}}"""))
        kotlin.test.assertTrue(BrainClient.isRateLimitFailure("""HTTP 429: {"error":{"code":"1305","message":"The service may be temporarily overloaded, please try again later"}}"""))
        kotlin.test.assertTrue(BrainClient.isRateLimitFailure("Rate limit exceeded"))
        kotlin.test.assertFalse(BrainClient.isRateLimitFailure("401 unauthorized"))
        kotlin.test.assertFalse(BrainClient.isRateLimitFailure("connection refused"))
        // ladder is bounded and ascending — the single-endpoint outer budget depends on it
        kotlin.test.assertEquals(3, BrainClient.RATE_LIMIT_BACKOFF_MS.size)
        kotlin.test.assertTrue(BrainClient.RATE_LIMIT_BACKOFF_MS.toList() == BrainClient.RATE_LIMIT_BACKOFF_MS.sorted())
    }
}
