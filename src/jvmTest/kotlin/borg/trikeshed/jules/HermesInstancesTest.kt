package borg.trikeshed.jules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HermesInstances is a LIST, not a choice: the union of what Hermes ran on
 * (sessions) and what answered (usage), newest first, one row per endpoint,
 * with only routable, chat_completions rows kept. Shapes mirror the
 * trikeshed profile on 2026-09-01.
 */
class HermesInstancesTest {

    private val t0 = 1_788_300_000.0
    private val zaiCoding = "https://api.z.ai/api/coding/paas/v4"
    private val codex = "https://chatgpt.com/backend-api/codex"

    private fun session(id: String, model: String?, provider: String?, baseUrl: String?, apiMode: String?, at: Double) =
        HermesActiveSession.Session(
            id = id, source = "cli", model = model,
            runtime = HermesActiveSession.Runtime(provider, baseUrl, apiMode),
            startedAt = at - 100, endedAt = null, lastActivityAt = at,
            messageCount = 1, apiCallCount = 1, cwd = null, ledger = "x",
        )

    private fun usage(model: String, provider: String, baseUrl: String, task: String, at: Double) =
        HermesModelUsage.Usage(model, provider, baseUrl, task, 1, 0, 0, at)

    @Test
    fun unionNewestFirstOneRowPerEndpoint() {
        val out = HermesInstances.merge(
            sessions = listOf(
                session("live", "glm-5.3-flash", "zai", zaiCoding, "chat_completions", t0 + 100),
                session("older", "glm-5.3", "zai", "$zaiCoding/", "chat_completions", t0 + 10),
            ),
            usage = listOf(
                usage("glm-5.3-flash", "zai", "$zaiCoding/", "", t0 + 120),          // same endpoint, trailing slash: one row
                usage("stealth/ox-alpha", "nous", "https://inference-api.nousresearch.com/v1", "background_review", t0 + 50),
                usage("nvidia/nemotron-3-ultra-550b-a55b", "nvidia", "https://integrate.api.nvidia.com/v1/", "compression", t0 + 40),
            ),
        )
        assertEquals(
            listOf("glm-5.3-flash", "stealth/ox-alpha", "nvidia/nemotron-3-ultra-550b-a55b", "glm-5.3"),
            out.map { it.model },
        )
        assertEquals(1, out.count { it.model == "glm-5.3-flash" })
        assertTrue(out.none { it.baseUrl.endsWith("/") }, "base urls are normalised without a trailing slash")
        assertEquals("usage:main", out[0].source, "the newer record of the same endpoint names its source")
        assertEquals("usage:background_review", out[1].source)
        assertEquals("session:older", out[3].source)
    }

    @Test
    fun unroutableRowsAreListedNowhere() {
        val out = HermesInstances.merge(
            sessions = listOf(
                session("custom", "kimi-k3", "custom:api.kimi.com", "https://api.kimi.com/coding/v1", "chat_completions", t0 + 90),
                session("bare", "m", "custom", "https://x/v1", null, t0 + 80),
                session("no-url", "m2", "zai", null, null, t0 + 70),
                session("no-model", null, "zai", zaiCoding, null, t0 + 60),
                session("ok", "glm-5.3-flash", "zai", zaiCoding, null, t0 + 50),
            ),
            usage = listOf(
                usage("zai-org/GLM-4.7-Flash", "custom:api.synthetic.new", "https://api.synthetic.new/openai/v1/", "approval", t0 + 200),
                usage("google/gemini-3.6-flash", "", "", "prompt-refinement", t0 + 150),
                usage("m3", "auto", "https://y/v1", "", t0 + 140),
            ),
        )
        assertEquals(listOf("glm-5.3-flash"), out.map { it.model })
        assertTrue(HermesInstances.routableProvider("zai"))
        assertTrue(HermesInstances.routableProvider("opencode-go"))
        assertFalse(HermesInstances.routableProvider("custom:api.synthetic.new"))
        assertFalse(HermesInstances.routableProvider("custom"))
        assertFalse(HermesInstances.routableProvider("AUTO"))
        assertFalse(HermesInstances.routableProvider(""))
        assertFalse(HermesInstances.routableProvider(null))
    }

    @Test
    fun aProviderOnAnotherWireProtocolIsOutWithItsUsageRows() {
        val out = HermesInstances.merge(
            sessions = listOf(
                session("sub", "gpt-5.6-sol-900k", "openai-codex", codex, "responses", t0 + 300),
                session("live", "glm-5.3-flash", "zai", zaiCoding, "chat_completions", t0 + 100),
            ),
            usage = listOf(
                usage("gpt-5.6-sol-900k", "openai-codex", codex, "", t0 + 310),
                usage("gpt-5.6-sol-900k", "openai-codex", "$codex/", "approval", t0 + 305),
            ),
        )
        assertEquals(listOf("glm-5.3-flash"), out.map { it.model })
    }

    @Test
    fun specsCarryTheProviderTagTheKeyMuxResolves() {
        val specs = HermesInstances.specs(
            listOf(HermesInstances.Instance("glm-5.3-flash", "zai", zaiCoding, "chat_completions", "session:live", t0)),
        )
        assertEquals(1, specs.size)
        assertEquals("hermes:zai", specs[0].name)
        assertEquals("zai", specs[0].provider)
        assertEquals("glm-5.3-flash", specs[0].model)
        assertEquals(zaiCoding, specs[0].base)
        assertEquals("", specs[0].envVar)
    }

    @Test
    fun emptyTablesListNothing() {
        assertEquals(emptyList(), HermesInstances.merge(emptyList(), emptyList()))
        assertEquals(emptyList(), HermesInstances.known(java.io.File("/nonexistent/state.db")))
    }
}
