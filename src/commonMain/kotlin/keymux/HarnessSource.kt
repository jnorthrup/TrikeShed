package keymux

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One provider's cross-harness credential conventions.
 *
 * [envVars] are the canonical process-env / dotenv names in precedence order
 * (OPENAI_API_KEY, ANTHROPIC_API_KEY, …). [defaultBaseUrl] is the provider's
 * OpenAI-compatible endpoint when it has one — `llm.<id>.base_url` answers with
 * it unless <ID>_BASE_URL overrides.
 */
data class HarnessProvider(
    val id: String,
    val envVars: Series<String>,
    val defaultBaseUrl: String? = null,
    /**
     * The cheapest model this provider will answer a live `/chat/completions`
     * probe on, or null when there is no OpenAI-compatible probe for it.
     *
     * This is what makes `bin/mux doctor` a real end-to-end check instead of a
     * key-presence check: a resolved key proves only that a STRING was found,
     * and a string that 401s is indistinguishable from no key at all until
     * something actually spends it. Null is an honest answer, not a gap —
     * anthropic speaks `/v1/messages`, not `/chat/completions`, so probing it
     * through this lane would report a protocol mismatch as a bad credential.
     */
    val probeModel: String? = null,
    /**
     * The env/dotenv variable that overrides this provider's base url — Hermes'
     * own `base_url_env_var` overlay (hermes_cli/providers.py), mirrored so the
     * two resolve the same endpoint: zai reads GLM_BASE_URL, kimi KIMI_BASE_URL,
     * … Consulted before the generic `<ID>_BASE_URL`. Without it, zai answered
     * `api/paas/v4` (the registry default) while Hermes, honouring
     * `GLM_BASE_URL=…/api/coding/paas/v4` in `~/.hermes/.env`, ran the coding
     * plan — and every mux-routed zai call hit "insufficient balance" on a
     * plan the operator does not use.
     */
    val baseUrlEnvVar: String? = null,
)

/**
 * The provider table every harness lane shares. Additive by design: a new provider
 * is a new row, never a code change elsewhere.
 */
object HarnessRegistry {
    val providers: Series<HarnessProvider> = s_[
        HarnessProvider("openai", s_["OPENAI_API_KEY"], "https://api.openai.com/v1", "gpt-4o-mini", baseUrlEnvVar = "OPENAI_BASE_URL"),
        // No defaultBaseUrl and no probeModel: anthropic's wire protocol is
        // /v1/messages, which the ModelMux /chat/completions lane does not speak.
        HarnessProvider("anthropic", s_["ANTHROPIC_API_KEY"]),
        HarnessProvider("xai", s_["XAI_API_KEY"], "https://api.x.ai/v1", "grok-3-mini", baseUrlEnvVar = "XAI_BASE_URL"),
        HarnessProvider("google", s_["GOOGLE_API_KEY", "GEMINI_API_KEY"], "https://generativelanguage.googleapis.com/v1beta"),
        HarnessProvider("gemini", s_["GEMINI_API_KEY", "GOOGLE_API_KEY"], "https://generativelanguage.googleapis.com/v1beta"),
        HarnessProvider("deepseek", s_["DEEPSEEK_API_KEY"], "https://api.deepseek.com/v1", "deepseek-chat", baseUrlEnvVar = "DEEPSEEK_BASE_URL"),
        HarnessProvider("nvidia", s_["NVIDIA_API_KEY", "NGC_API_KEY"], "https://integrate.api.nvidia.com/v1", "nvidia/nemotron-3-super-120b-a12b"),
        HarnessProvider("openrouter", s_["OPENROUTER_API_KEY"], "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", baseUrlEnvVar = "OPENROUTER_BASE_URL"),
        HarnessProvider("groq", s_["GROQ_API_KEY"], "https://api.groq.com/openai/v1", "llama-3.1-8b-instant"),
        HarnessProvider("mistral", s_["MISTRAL_API_KEY"], "https://api.mistral.ai/v1", "mistral-small-latest"),
        HarnessProvider("cerebras", s_["CEREBRAS_API_KEY"], "https://api.cerebras.ai/v1", "gpt-oss-120b"),
        HarnessProvider("moonshot", s_["MOONSHOT_API_KEY", "KIMI_API_KEY"], "https://api.moonshot.ai/v1", "moonshot-v1-8k", baseUrlEnvVar = "KIMI_BASE_URL"),
        HarnessProvider("kimi", s_["KIMI_API_KEY", "MOONSHOT_API_KEY"], "https://api.moonshot.ai/v1", "moonshot-v1-8k", baseUrlEnvVar = "KIMI_BASE_URL"),
        HarnessProvider("glm", s_["GLM_API_KEY", "ZHIPU_API_KEY"]),
        HarnessProvider("zhipu", s_["ZHIPU_API_KEY", "GLM_API_KEY"]),
        HarnessProvider("zai", s_["ZAI_API_KEY", "GLM_API_KEY"], "https://api.z.ai/api/paas/v4", "glm-4.5-air", baseUrlEnvVar = "GLM_BASE_URL"),
        HarnessProvider("perplexity", s_["PERPLEXITY_API_KEY"], "https://api.perplexity.ai", "sonar"),
        HarnessProvider("minimax", s_["MINIMAX_API_KEY"], "https://api.minimax.chat/v1", baseUrlEnvVar = "MINIMAX_BASE_URL"),
        // jules/brain are dispatch identities, not chat endpoints.
        HarnessProvider("jules", s_["JULES_API_KEY"]),
        HarnessProvider("brain", s_["BRAIN_API_KEY"]),
        HarnessProvider("synthetic", s_["SYNTHETIC_API_KEY"], "https://api.synthetic.new/v1"),
        HarnessProvider("opencode", s_["OPENCODE_API_KEY"], "https://api.opencode.ai/v1"),
    ]

    fun byId(id: String): HarnessProvider? {
        for (i in 0 until providers.size) if (providers[i].id == id) return providers[i]
        return null
    }
}

/**
 * HarnessSource — resolves credentials from the environment AND the popular agent
 * harnesses' own credential stores, so a key the operator already gave Hermes,
 * Codex, or OpenCode answers `llm.<provider>.key` without a second copy.
 *
 * Resolution order for a provider key:
 *   1. process env: the provider's canonical names (HarnessRegistry.envVars)
 *   2. Hermes dotenv secrets: $HERMES_HOME/.env, then ~/.hermes/.env, then
 *      ~/.hermes/profiles/<name>/.env (profile-safe: $HERMES_HOME wins, never
 *      hardcoded ~/.hermes)
 *   3. harness credential files: ~/.codex/auth.json (top-level env-var-named
 *      strings), ~/.local/share/opencode/auth.json (per-provider {key|api_key}),
 *      $HERMES_HOME|~/.hermes/auth.json (same generic shapes; OAuth accessToken
 *      fields are deliberately SKIPPED — bearer OAuth is a different auth scheme
 *      and handing it to an x-api-key lane would forge a credential)
 *   4. host-style providers (integrate.api.nvidia.com) via
 *      HERMES_CUSTOM_<HOST_WITH_UNDERSCORES>_API_KEY
 *
 * `llm.<provider>.base_url`: <ID>_BASE_URL env/dotenv, else the registry default.
 * Raw single-segment env names ("JULES_API_KEY") also answer, env then dotenv.
 *
 * Read-only: write() throws so KeyMux.set() skips it. File reads degrade to null
 * when no FileOperations rides the context — env-only resolution still works.
 */
class HarnessSource(
    private val explicitFileOps: FileOperations? = null,
    private val getenv: (String) -> String? = { SystemOperations.default.getenv(it) },
    /** Test seam: pin the hermes home instead of resolving $HERMES_HOME/$HOME. */
    private val hermesHomeOverride: String? = null,
    private val homeDirOverride: String? = null,
) : KeySource() {
    override val name = "harness"

    private val fileLock = Mutex()
    private val dotenvMemo = mutableMapOf<String, Map<String, String>>()

    override suspend fun write(path: KeyPath, value: String) {
        throw UnsupportedOperationException("harness source is read-only — edit the harness's own store")
    }

    override suspend fun invalidate() {
        fileLock.withLock { dotenvMemo.clear() }
    }

    override suspend fun read(path: KeyPath): String? {
        val segs = path.size
        if (segs == 3 && path[0] == "llm" && path[2] == "key") return providerKey(path[1])
        if (segs == 3 && path[0] == "llm" && path[2] == "base_url") return providerBaseUrl(path[1])
        if (segs == 3 && path[1] == "default" && path[2] == "key") return providerKey(path[0])
        if (segs == 1) {
            val name = path[0]
            if (name.isNotEmpty() && name.all { it.isUpperCase() || it.isDigit() || it == '_' }) {
                return envOrDotenv(name)
            }
        }
        return null
    }

    // ── provider keys ────────────────────────────────────────────────

    private suspend fun providerKey(provider: String): String? {
        val entry = HarnessRegistry.byId(provider)
        if (entry != null) {
            for (i in 0 until entry.envVars.size) {
                val v = entry.envVars[i]
                getenv(v)?.takeIf { it.isNotBlank() }?.let { return it }
            }
            for (i in 0 until entry.envVars.size) {
                val v = dotenvValue(entry.envVars[i])
                if (v != null) return v
            }
            harnessFileKey(provider, entry)?.let { return it }
        }
        // Custom integrations (registry miss): dash-form provider id maps to
        // HERMES_CUSTOM_<ID_UPPER_DASHES_TO_UNDERSCORES>_API_KEY — e.g.
        // llm.custom-api-synthetic-new.key → HERMES_CUSTOM_API_SYNTHETIC_NEW_API_KEY.
        // (Dot-host form can't ride a dot-split KeyPath; dashes survive.)
        val customVar = "HERMES_CUSTOM_" + provider.uppercase().replace('-', '_') + "_API_KEY"
        getenv(customVar)?.takeIf { it.isNotBlank() }?.let { return it }
        dotenvValue(customVar)?.let { return it }
        return null
    }

    private suspend fun providerBaseUrl(provider: String): String? {
        // Hermes' own override variable for this provider wins outright — the
        // same rule its _resolve_*_base_url helpers apply ("if the user has
        // explicitly set GLM_BASE_URL, that always wins").
        HarnessRegistry.byId(provider)?.baseUrlEnvVar?.let { hermesVar ->
            getenv(hermesVar)?.takeIf { it.isNotBlank() }?.let { return it }
            dotenvValue(hermesVar)?.let { return it }
        }
        val varName = provider.uppercase().replace('.', '_').replace('-', '_') + "_BASE_URL"
        getenv(varName)?.takeIf { it.isNotBlank() }?.let { return it }
        dotenvValue(varName)?.let { return it }
        HarnessRegistry.byId(provider)?.defaultBaseUrl?.let { return it }
        // Custom integrations pair their key with HERMES_CUSTOM_<ID>_BASE_URL.
        val customVar = "HERMES_CUSTOM_" + provider.uppercase().replace('-', '_') + "_BASE_URL"
        getenv(customVar)?.takeIf { it.isNotBlank() }?.let { return it }
        dotenvValue(customVar)?.let { return it }
        return null
    }

    // ── env + dotenv lanes ───────────────────────────────────────────

    private suspend fun envOrDotenv(name: String): String? {
        getenv(name)?.takeIf { it.isNotBlank() }?.let { return it }
        return dotenvValue(name)
    }

    private suspend fun dotenvValue(name: String): String? {
        val ops = fileOpsOrNull() ?: return null
        val files = dotenvFiles(ops)
        for (i in 0 until files.size) {
            val map = dotenv(ops, files[i]) ?: continue
            map[name]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /** Hermes dotenv secrets in precedence order: $HERMES_HOME, ~/.hermes, profiles. */
    private fun dotenvFiles(ops: FileOperations): Series<String> {
        val home = homeDirOverride ?: getenv("HOME") ?: return emptySeriesOf()
        val defaultHermes = "$home/.hermes"
        val hh = hermesHomeOverride ?: getenv("HERMES_HOME")
        val out = mutableListOf<String>()
        if (hh != null) out += "$hh/.env"
        if (hh != defaultHermes) out += "$defaultHermes/.env"
        val profilesDir = "$defaultHermes/profiles"
        if (runCatching { ops.isDir(profilesDir) }.getOrDefault(false)) {
            for (p in runCatching { ops.listDir(profilesDir) }.getOrDefault(emptyList()).sorted()) {
                val f = "$profilesDir/$p/.env"
                if (f !in out) out += f
            }
        }
        return out.toSeries()
    }

    private suspend fun dotenv(ops: FileOperations, file: String): Map<String, String>? {
        fileLock.withLock { dotenvMemo[file] }?.let { return it }
        if (!runCatching { ops.isFile(file) }.getOrDefault(false)) return null
        val parsed = parseDotenv(runCatching { ops.readString(file) }.getOrNull() ?: return null)
        fileLock.withLock { dotenvMemo[file] = parsed }
        return parsed
    }

    // ── harness credential files ─────────────────────────────────────

    private suspend fun harnessFileKey(provider: String, entry: HarnessProvider): String? {
        val ops = fileOpsOrNull() ?: return null
        val home = homeDirOverride ?: getenv("HOME") ?: return null
        val hermesHome = hermesHomeOverride ?: getenv("HERMES_HOME") ?: "$home/.hermes"

        // Codex: {"OPENAI_API_KEY": "sk-…", …} — top-level env-var-named strings.
        val codex = readJson(ops, "$home/.codex/auth.json")
        if (codex != null) {
            for (i in 0 until entry.envVars.size) {
                (codex[entry.envVars[i]] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        // OpenCode: {"<provider>": {"type": "api", "key": "…"}, …}
        val opencode = readJson(ops, "$home/.local/share/opencode/auth.json")
        extractProviderKey(opencode?.get(provider))?.let { return it }
        // Hermes auth.json — credential pools / generic api-key-shaped fields.
        // OAuth accessToken is intentionally not extracted (different auth scheme).
        for (authFile in listOf("$hermesHome/auth.json", "$home/.hermes/auth.json")) {
            val json = readJson(ops, authFile) ?: continue
            for (i in 0 until entry.envVars.size) {
                (json[entry.envVars[i]] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
            }
            extractProviderKey(json[provider])?.let { return it }
        }
        return null
    }

    /** Extract an api-key-shaped field from a provider entry: object with key/apiKey/api_key, or a bare string. */
    private fun extractProviderKey(node: Any?): String? = when (node) {
        is String -> node.takeIf { it.isNotBlank() }
        is Map<*, *> -> {
            for (field in listOf("key", "apiKey", "api_key")) {
                (node[field] as? String)?.takeIf { it.isNotBlank() }?.let { return it }
            }
            null
        }
        else -> null
    }

    private fun readJson(ops: FileOperations, file: String): Map<String, Any?>? {
        if (!runCatching { ops.isFile(file) }.getOrDefault(false)) return null
        val text = runCatching { ops.readString(file) }.getOrNull() ?: return null
        return runCatching { JsonSupport.parse(text) as? Map<String, Any?> }.getOrNull()
    }

    private suspend fun fileOpsOrNull(): FileOperations? =
        explicitFileOps ?: currentCoroutineContext()[FileOperations.Key]
}

/** Hermes dotenv parser — shared by [HarnessSource] and [HermesCredentialSource]
 *  (both walk the same $HERMES_HOME/.env → ~/.hermes/.env → profiles chain). */
internal fun parseDotenv(text: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (line in text.lineSequence()) {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) continue
        val eq = t.indexOf('=')
        if (eq <= 0) continue
        val k = t.substring(0, eq).trim().removePrefix("export ").trim()
        var v = t.substring(eq + 1).trim()
        if (v.length >= 2 && ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith('\'') && v.endsWith('\'')))) {
            v = v.substring(1, v.length - 1)
        }
        if (k.isNotEmpty()) out[k] = v
    }
    return out
}

/** Builder arm: keys from environment AND the popular harnesses' credential stores. */
fun KeyMuxBuilder.harness(fileOps: FileOperations? = null): KeyMuxBuilder = apply {
    bind("*", HarnessSource(explicitFileOps = fileOps))
}
