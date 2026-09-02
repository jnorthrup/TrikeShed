package keymux

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `llm.<provider>.base_url` resolves the way Hermes resolves it: the provider's
 * own override variable (Hermes' `base_url_env_var` overlay — GLM_BASE_URL for
 * zai, KIMI_BASE_URL for kimi/moonshot, …) wins outright, then the generic
 * `<ID>_BASE_URL`, then the registry default. On 2026-09-01 the missing first
 * rung sent every mux-routed zai call to `api/paas/v4` while Hermes ran the
 * coding plan the operator actually pays for.
 */
class HarnessSourceBaseUrlTest {

    private val coding = "https://api.z.ai/api/coding/paas/v4"

    private fun source(env: Map<String, String>) = HarnessSource(getenv = { env[it] })

    @Test
    fun zaiHonoursGlmBaseUrlLikeHermes() = runTest {
        assertEquals(coding, source(mapOf("GLM_BASE_URL" to coding)).read("llm.zai.base_url".toKeyPath()))
    }

    @Test
    fun hermesVariableOutranksTheGenericOne() = runTest {
        val env = mapOf("GLM_BASE_URL" to coding, "ZAI_BASE_URL" to "https://elsewhere.invalid/v1")
        assertEquals(coding, source(env).read("llm.zai.base_url".toKeyPath()))
    }

    @Test
    fun genericVariableStillAnswersWhenHermesOneIsUnset() = runTest {
        assertEquals("https://elsewhere.invalid/v1", source(mapOf("ZAI_BASE_URL" to "https://elsewhere.invalid/v1")).read("llm.zai.base_url".toKeyPath()))
        assertEquals("https://elsewhere.invalid/v1", source(mapOf("GLM_BASE_URL" to "  ", "ZAI_BASE_URL" to "https://elsewhere.invalid/v1")).read("llm.zai.base_url".toKeyPath()), "a blank override is unset")
    }

    @Test
    fun registryDefaultIsTheLastRung() = runTest {
        assertEquals("https://api.z.ai/api/paas/v4", source(emptyMap()).read("llm.zai.base_url".toKeyPath()))
        assertEquals("https://api.moonshot.ai/v1", source(emptyMap()).read("llm.kimi.base_url".toKeyPath()))
    }

    @Test
    fun kimiAndMoonshotShareHermesKimiBaseUrl() = runTest {
        val env = mapOf("KIMI_BASE_URL" to "https://api.kimi.com/coding/v1")
        assertEquals("https://api.kimi.com/coding/v1", source(env).read("llm.kimi.base_url".toKeyPath()))
        assertEquals("https://api.kimi.com/coding/v1", source(env).read("llm.moonshot.base_url".toKeyPath()))
    }

    @Test
    fun registryCarriesHermesOverlayNames() {
        assertEquals("GLM_BASE_URL", HarnessRegistry.byId("zai")?.baseUrlEnvVar)
        assertEquals("KIMI_BASE_URL", HarnessRegistry.byId("kimi")?.baseUrlEnvVar)
        assertEquals("DEEPSEEK_BASE_URL", HarnessRegistry.byId("deepseek")?.baseUrlEnvVar)
        assertEquals("OPENROUTER_BASE_URL", HarnessRegistry.byId("openrouter")?.baseUrlEnvVar)
        assertNull(HarnessRegistry.byId("nvidia")?.baseUrlEnvVar, "no Hermes overlay names one for nvidia")
        assertNull(HarnessRegistry.byId("anthropic")?.baseUrlEnvVar)
    }
}
