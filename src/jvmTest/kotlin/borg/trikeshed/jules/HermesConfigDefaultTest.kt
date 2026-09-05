package borg.trikeshed.jules

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The ambient rung mirrors Hermes' `resolve_runtime_provider` over the
 * profile's `config.yaml` model block: the shapes below are the real
 * `~/.hermes/profiles/src-trikeshed/config.yaml` (2026-09-04) with the secret
 * replaced, plus the fallbacks Hermes walks when a piece is missing.
 */
class HermesConfigDefaultTest {

    private val profileConfig = """
        model:
          default: mimo-v2.5
          provider: custom
          base_url: https://opencode.ai/zen/go/v1
          api_mode: chat_completions
          api_key: ${'$'}{HERMES_CUSTOM_OPENCODE_AI_API_KEY}
        auxiliary:
          vision:
            provider: custom:api.synthetic.new
            model: syn:small:vision
    """.trimIndent()

    private fun home(config: String?, dotenv: String? = null): String {
        val dir = Files.createTempDirectory("hermes-home").toFile()
        config?.let { File(dir, "config.yaml").writeText(it) }
        dotenv?.let { File(dir, ".env").writeText(it) }
        return dir.path
    }

    @Test
    fun parsesTheFlatModelBlockOnly() {
        val b = HermesConfigDefault.parseModelBlock(profileConfig)!!
        assertEquals("mimo-v2.5", b.model)
        assertEquals("custom", b.provider)
        assertEquals("https://opencode.ai/zen/go/v1", b.baseUrl)
        assertEquals("chat_completions", b.apiMode)
        assertEquals("\${HERMES_CUSTOM_OPENCODE_AI_API_KEY}", b.apiKey)
        assertNull(b.keyEnv)
        assertNull(HermesConfigDefault.parseModelBlock("gateway:\n  port: 1\n"), "no model: block")
        // quotes and trailing comments are YAML scalars, not part of the value
        val q = HermesConfigDefault.parseModelBlock("model:\n  default: \"kimi-k3\" # the coding plan\n  provider: 'custom'\n")!!
        assertEquals("kimi-k3", q.model)
        assertEquals("custom", q.provider)
    }

    @Test
    fun envRefsExpandThroughTheChainOrNotAtAll() {
        val lookup: (String) -> String? = { mapOf("A" to "x", "B" to "y")[it] }
        assertEquals("x", HermesConfigDefault.expandEnvRefs("\${A}", lookup))
        assertEquals("x", HermesConfigDefault.expandEnvRefs("\${env:A}", lookup), "Cursor-style ref")
        assertEquals("x-y", HermesConfigDefault.expandEnvRefs("\${A}-\${B}", lookup))
        assertEquals("literal", HermesConfigDefault.expandEnvRefs("literal", lookup))
        assertNull(HermesConfigDefault.expandEnvRefs("\${MISSING}", lookup), "an unset secret is not a key")
    }

    @Test
    fun hostDerivedKeyNameFollowsHermes() {
        assertEquals("DEEPSEEK_API_KEY", HermesConfigDefault.hostDerivedKeyName("https://api.deepseek.com/v1"))
        assertEquals("GROQ_API_KEY", HermesConfigDefault.hostDerivedKeyName("https://api.groq.com/openai/v1"))
        assertEquals("GOOGLEAPIS_API_KEY", HermesConfigDefault.hostDerivedKeyName("https://generativelanguage.googleapis.com/v1beta/openai/"))
        assertEquals("OPENCODE_API_KEY", HermesConfigDefault.hostDerivedKeyName("https://opencode.ai/zen/go/v1"))
        assertNull(HermesConfigDefault.hostDerivedKeyName("http://localhost:11434/v1"))
        assertNull(HermesConfigDefault.hostDerivedKeyName("http://10.0.0.5/v1"))
    }

    @Test
    fun profileConfigPinsTheLaunchHermesWouldMake() = runBlocking {
        val h = home(profileConfig, dotenv = "HERMES_CUSTOM_OPENCODE_AI_API_KEY=sk-from-profile-dotenv\n")
        val out = HermesConfigDefault.resolve(h, getenv = { null })
        val pinned = assertIs<HermesConfigDefault.Outcome.Pinned>(out)
        assertEquals("mimo-v2.5", pinned.launch.model)
        assertEquals("custom", pinned.launch.provider)
        assertEquals("https://opencode.ai/zen/go/v1", pinned.launch.baseUrl)
        assertEquals("chat_completions", pinned.launch.apiMode)
        assertEquals("sk-from-profile-dotenv", pinned.launch.apiKey)
        assertEquals("config model.api_key", pinned.launch.keySource)
    }

    @Test
    fun processEnvOutranksTheDotenvAndHostDerivedIsLast() = runBlocking {
        val h = home(profileConfig, dotenv = "HERMES_CUSTOM_OPENCODE_AI_API_KEY=sk-dotenv\n")
        val env = HermesConfigDefault.resolve(h, getenv = { if (it == "HERMES_CUSTOM_OPENCODE_AI_API_KEY") "sk-env" else null })
        assertEquals("sk-env", assertIs<HermesConfigDefault.Outcome.Pinned>(env).launch.apiKey)

        // No api_key line at all: Hermes falls to the host-derived <VENDOR>_API_KEY.
        val noRef = profileConfig.lines().filterNot { "api_key" in it }.joinToString("\n")
        val derived = HermesConfigDefault.resolve(home(noRef), getenv = { if (it == "OPENCODE_API_KEY") "sk-derived" else null })
        val p = assertIs<HermesConfigDefault.Outcome.Pinned>(derived)
        assertEquals("sk-derived", p.launch.apiKey)
        assertEquals("OPENCODE_API_KEY", p.launch.keySource)
    }

    @Test
    fun unresolvedSecretIsUnavailableNotALiteral() = runBlocking {
        val out = HermesConfigDefault.resolve(home(profileConfig), getenv = { null })
        val u = assertIs<HermesConfigDefault.Outcome.Unavailable>(out)
        assertTrue("no key resolves for mimo-v2.5" in u.reason, u.reason)
        assertTrue("HERMES_CUSTOM_OPENCODE_AI_API_KEY" in u.reason, "the reason names the ref, never a value")
    }

    @Test
    fun namedProviderUsesTheKeyMuxLanesLikeTheSessionPin() = runBlocking {
        val cfg = "model:\n  default: glm-5.3-flash\n  provider: zai\n"
        val out = HermesConfigDefault.resolve(
            home(cfg), getenv = { null },
            keyLane = { provider, field ->
                when (provider to field) {
                    "zai" to "key" -> "sk-pool"
                    "zai" to "base_url" -> "https://api.z.ai/api/coding/paas/v4/"
                    else -> null
                }
            },
        )
        val p = assertIs<HermesConfigDefault.Outcome.Pinned>(out)
        assertEquals("https://api.z.ai/api/coding/paas/v4", p.launch.baseUrl, "lane base url, trailing slash trimmed")
        assertEquals("sk-pool", p.launch.apiKey)
        assertEquals("llm.zai.key", p.launch.keySource)
        assertEquals("chat_completions", p.launch.apiMode, "unset api_mode defaults like Hermes")
    }

    @Test
    fun missingPiecesAreNamedReasons() = runBlocking {
        assertIs<HermesConfigDefault.Outcome.Unavailable>(HermesConfigDefault.resolve(home(null), getenv = { null }))
        val noProvider = HermesConfigDefault.resolve(home("model:\n  default: x\n"), getenv = { null })
        assertTrue("model.provider is unset" in assertIs<HermesConfigDefault.Outcome.Unavailable>(noProvider).reason)
        val noUrl = HermesConfigDefault.resolve(home("model:\n  default: x\n  provider: custom\n"), getenv = { null })
        assertTrue("no base url" in assertIs<HermesConfigDefault.Outcome.Unavailable>(noUrl).reason)
    }
}
