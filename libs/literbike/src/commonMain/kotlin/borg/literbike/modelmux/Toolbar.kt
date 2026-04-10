package borg.literbike.modelmux

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.io.File

/**
 * Toolbar module - operator surface and state derivation.
 * Ported from literbike/src/modelmux/toolbar.rs.
 */

@Serializable
@SerialName("toolbar_service_status")
enum class ToolbarServiceStatus {
    @SerialName("running") Running,
    @SerialName("degraded") Degraded,
    @SerialName("cold") Cold
}

@Serializable
@SerialName("toolbar_service_manager")
enum class ToolbarServiceManager {
    @SerialName("external_launcher") ExternalLauncher
}

@Serializable
@SerialName("toolbar_confidence_bucket")
enum class ToolbarConfidenceBucket {
    @SerialName("low") Low,
    @SerialName("medium") Medium,
    @SerialName("high") High
}

@Serializable
@SerialName("toolbar_persistence_kind")
enum class ToolbarPersistenceKind {
    @SerialName("volatile") Volatile,
    @SerialName("markdown_todo") MarkdownTodo,
    @SerialName("sqlite") Sqlite
}

@Serializable
@SerialName("toolbar_surface_kind")
enum class ToolbarSurfaceKind {
    @SerialName("control_api") ControlApi,
    @SerialName("openai_compat") OpenAiCompat,
    @SerialName("ollama_compat") OllamaCompat,
    @SerialName("quota_ledger") QuotaLedger
}

@Serializable
data class ToolbarServiceState(
    val status: ToolbarServiceStatus,
    val manager: ToolbarServiceManager,
    val bindAddress: String,
    val port: Int
)

@Serializable
data class ToolbarRouteState(
    val family: GatewayFacadeFamily,
    val provider: String?,
    val model: String?,
    val unifiedAgentPort: Boolean,
    val failoverEnabled: Boolean
)

@Serializable
data class ToolbarEnvKey(
    val name: String,
    val isSet: Boolean
)

@Serializable
data class ToolbarEnvState(
    val recognizedKeys: Int,
    val unknownKeys: Int,
    val confidence: ToolbarConfidenceBucket,
    val keys: List<ToolbarEnvKey>
)

@Serializable
data class ToolbarDebtState(
    val openItems: Int,
    val blockedItems: Int,
    val persistence: ToolbarPersistenceKind,
    val sourcePath: String?
)

@Serializable
data class ToolbarRuntimeState(
    val streamingEnabled: Boolean,
    val claudeRewriteEnabled: Boolean,
    val keymuxStrategy: String,
    val providerKeyOverrides: Int
)

@Serializable
data class ToolbarSurfaceState(
    val kind: ToolbarSurfaceKind,
    val available: Boolean,
    val detail: String
)

@Serializable
data class ToolbarDselLane(
    val title: String,
    val route: String,
    val model: String,
    val host: String,
    val provider: String,
    val key: String?
)

@Serializable
data class ToolbarState(
    val service: ToolbarServiceState,
    val route: ToolbarRouteState,
    val env: ToolbarEnvState,
    val debt: ToolbarDebtState,
    val runtime: ToolbarRuntimeState,
    val surfaces: List<ToolbarSurfaceState>,
    val keymux: GatewayKeymuxState,
    val lanes: List<ToolbarDselLane>,
    val dynamicModels: List<String>,
    val availableActions: List<String>
)

/**
 * Toolbar actions.
 */
sealed class ToolbarAction {
    data object RescanEnv : ToolbarAction()
    data object ResetRuntime : ToolbarAction()
    data class SetStreamingEnabled(val enabled: Boolean) : ToolbarAction()
    data class SetPreferredProvider(val provider: String) : ToolbarAction()
    data object ClearPreferredProvider : ToolbarAction()
    data class SetDefaultModel(val model: String) : ToolbarAction()
    data object ClearDefaultModel : ToolbarAction()
    data class SetFallbackModel(val model: String) : ToolbarAction()
    data object ClearFallbackModel : ToolbarAction()
    data class SetClaudeRewriteEnabled(val enabled: Boolean) : ToolbarAction()
    data class SetClaudeRewritePolicy(
        val enabled: Boolean,
        val defaultModel: String?,
        val haikuModel: String?,
        val sonnetModel: String?,
        val opusModel: String?,
        val reasoningModel: String?
    ) : ToolbarAction()
    data object ClearClaudeRewritePolicy : ToolbarAction()
    data class SetProviderKeyPolicy(
        val provider: String,
        val envKey: String?,
        val overrideEnvKey: String?,
        val precedence: ProviderKeyPrecedence
    ) : ToolbarAction()
    data class ClearProviderKeyPolicy(val provider: String) : ToolbarAction()
    data class ImportCcSwitchKeysAdditive(val path: String?) : ToolbarAction()
}

/**
 * Derive toolbar state from gateway control state.
 */
fun deriveToolbarState(
    gateway: GatewayControlState,
    dynamicModels: List<String> = emptyList()
): ToolbarState {
    val env = scanEnvState()
    val debt = scanDebtState()
    val route = deriveRouteState(gateway)
    val lanes = scanDselLanes()

    val service = ToolbarServiceState(
        status = serviceStatusForMetrics(
            hasProviders = gateway.providers.isNotEmpty(),
            hasRouteHint = route.provider != null || route.model != null,
            unknownEnvKeys = env.unknownKeys,
            blockedItems = debt.blockedItems
        ),
        manager = ToolbarServiceManager.ExternalLauncher,
        bindAddress = gateway.transport.bindAddress,
        port = gateway.transport.port
    )

    val runtime = ToolbarRuntimeState(
        streamingEnabled = gateway.streaming.enabled,
        claudeRewriteEnabled = gateway.claudeModelRewrite.enabled,
        keymuxStrategy = gateway.keymux.strategy,
        providerKeyOverrides = gateway.keymux.providerKeys.count { it.overrideEnvKey != null }
    )

    val surfaces = listOf(
        ToolbarSurfaceState(
            kind = ToolbarSurfaceKind.ControlApi,
            available = true,
            detail = "/control/state + /control/actions"
        ),
        ToolbarSurfaceState(
            kind = ToolbarSurfaceKind.OpenAiCompat,
            available = true,
            detail = "/v1/models + /v1/chat/completions"
        ),
        ToolbarSurfaceState(
            kind = ToolbarSurfaceKind.OllamaCompat,
            available = true,
            detail = "/api/tags + /api/chat"
        ),
        ToolbarSurfaceState(
            kind = ToolbarSurfaceKind.QuotaLedger,
            available = gateway.providers.isNotEmpty(),
            detail = if (gateway.providers.isEmpty()) {
                "no active provider ledgers"
            } else {
                "${gateway.providers.size} provider ledgers active"
            }
        )
    )

    return ToolbarState(
        service = service,
        route = route,
        env = env,
        debt = debt,
        runtime = runtime,
        surfaces = surfaces,
        keymux = gateway.keymux,
        lanes = lanes,
        dynamicModels = dynamicModels,
        availableActions = listOf(
            "rescan_env", "reset_runtime", "set_streaming_enabled",
            "set_preferred_provider", "clear_preferred_provider",
            "set_default_model", "clear_default_model",
            "set_fallback_model", "clear_fallback_model",
            "set_claude_rewrite_enabled", "set_claude_rewrite_policy",
            "clear_claude_rewrite_policy",
            "set_provider_key_policy", "clear_provider_key_policy",
            "import_cc_switch_keys_additive"
        )
    )
}

private fun scanDselLanes(): List<ToolbarDselLane> {
    val dselPath = File("configs/agent-host-free-lanes.dsel")
    if (!dselPath.exists()) return emptyList()

    return dselPath.readLines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('#')) return@mapNotNull null
        parseDselLine(trimmed)
    }
}

private fun parseDselLine(line: String): ToolbarDselLane? {
    val start = line.indexOf('{')
    val end = line.indexOf('}')
    if (start < 0 || end < 0) return null

    val metaStr = line.substring(start + 1, end)
    val model = line.substring(end + 1).trimStart('/')

    var host = "localhost:8888"
    var title = model.split('/').lastOrNull() ?: return null
    var key: String? = null

    val parts = metaStr.split(',').map { it.trim() }
    if (parts.isNotEmpty() && ':' !in parts[0] && '/' !in parts[0]) {
        host = parts[0]
    }

    for (part in parts) {
        if (part.startsWith("modality/")) {
            // modality info
        } else if (part.startsWith("meta:key=")) {
            key = part.substring("meta:key=".length)
        } else if (part.startsWith("note=")) {
            title = part.substring("note=".length)
        }
    }

    val provider = model.split('/').firstOrNull() ?: return null

    return ToolbarDselLane(
        title = title.uppercase(),
        route = line,
        model = model,
        host = host,
        provider = provider,
        key = key
    )
}

private fun deriveRouteState(gateway: GatewayControlState): ToolbarRouteState {
    val model = gateway.routing.defaultModel
        ?: gateway.claudeModelRewrite.defaultModel

    val provider = gateway.routing.preferredProvider
        ?: model?.let { providerFromModel(it) }

    val family = provider?.let { name ->
        gateway.providers.find { it.name == name }?.family
    } ?: model?.let { familyFromModel(it) }
        ?: GatewayFacadeFamily.Unknown

    return ToolbarRouteState(
        family = family,
        provider = provider,
        model = model,
        unifiedAgentPort = gateway.transport.unifiedAgentPort,
        failoverEnabled = gateway.routing.failoverEnabled
    )
}

private fun scanEnvState(): ToolbarEnvState {
    var recognizedKeys = 0
    var unknownKeys = 0
    val keys = mutableListOf<ToolbarEnvKey>()

    for ((key, value) in System.getenv()) {
        val isSet = value.trim().isNotEmpty()
        val normalized = key.trim().uppercase()

        if (isKnownEnvKey(normalized)) {
            if (isSet) recognizedKeys++
            keys.add(ToolbarEnvKey(name = normalized, isSet = isSet))
            continue
        }

        if (normalized.endsWith("_API_KEY") || normalized.endsWith("_BASE_URL")) {
            if (isSet) unknownKeys++
            keys.add(ToolbarEnvKey(name = normalized, isSet = isSet))
        }
    }

    val confidence = when {
        recognizedKeys == 0 -> ToolbarConfidenceBucket.Low
        unknownKeys > 0 -> ToolbarConfidenceBucket.Medium
        else -> ToolbarConfidenceBucket.High
    }

    return ToolbarEnvState(
        recognizedKeys = recognizedKeys,
        unknownKeys = unknownKeys,
        confidence = confidence,
        keys = keys
    )
}

private fun scanDebtState(): ToolbarDebtState {
    val sqlitePath = System.getenv("MODELMUX_LEDGER_DB")
        ?.let { File(it) }
        ?: System.getProperty("user.home")?.let { home ->
            File("$home/.modelmux/toolbar.sqlite")
        }

    sqlitePath?.takeIf { it.exists() }?.let {
        return ToolbarDebtState(
            openItems = 0,
            blockedItems = 0,
            persistence = ToolbarPersistenceKind.Sqlite,
            sourcePath = it.absolutePath
        )
    }

    val todoPath = System.getenv("MODELMUX_TODO_PATH")?.let { File(it) }
        ?: File("TODO.md")

    if (todoPath.exists()) {
        val content = todoPath.readText()
        val summary = parseTodoMarkdown(content)
        return ToolbarDebtState(
            openItems = summary.openItems,
            blockedItems = summary.blockedItems,
            persistence = ToolbarPersistenceKind.MarkdownTodo,
            sourcePath = todoPath.absolutePath
        )
    }

    return ToolbarDebtState(
        openItems = 0,
        blockedItems = 0,
        persistence = ToolbarPersistenceKind.Volatile,
        sourcePath = null
    )
}

data class TodoSummary(
    val openItems: Int,
    val blockedItems: Int
)

private fun parseTodoMarkdown(content: String): TodoSummary {
    var openItems = 0
    var blockedItems = 0

    for (line in content.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("- [ ]")) {
            openItems++
            if ("BLOCKED" in trimmed.uppercase()) {
                blockedItems++
            }
        }
    }

    return TodoSummary(openItems, blockedItems)
}

private fun serviceStatusForMetrics(
    hasProviders: Boolean,
    hasRouteHint: Boolean,
    unknownEnvKeys: Int,
    blockedItems: Int
): ToolbarServiceStatus {
    if (!hasProviders) return ToolbarServiceStatus.Cold
    if (!hasRouteHint || unknownEnvKeys > 0 || blockedItems > 0) {
        return ToolbarServiceStatus.Degraded
    }
    return ToolbarServiceStatus.Running
}

private fun providerFromModel(model: String): String? {
    val prefix = model.split('/').firstOrNull()?.trim() ?: return null
    if (prefix.isEmpty() || prefix.equals(model, ignoreCase = true)) return null
    return prefix.lowercase()
}

private fun familyFromModel(model: String): GatewayFacadeFamily {
    val lower = model.trim().lowercase()
    return when {
        lower.startsWith("anthropic/") || lower.startsWith("claude-") -> GatewayFacadeFamily.AnthropicCompatible
        lower.startsWith("gemini") || lower.startsWith("google/") -> GatewayFacadeFamily.GeminiNative
        lower.startsWith("ollama/") || lower.startsWith("lmstudio/") -> GatewayFacadeFamily.OllamaCompatible
        '/' in lower -> GatewayFacadeFamily.OpenAiCompatible
        else -> GatewayFacadeFamily.Unknown
    }
}

private fun isKnownEnvKey(key: String): Boolean {
    val providerKeyAliases = listOf(
        "KILOCODE_API_KEY", "KILOAI_API_KEY", "KILO_CODE_API_KEY", "KILO_API_KEY",
        "MOONSHOTAI_API_KEY", "KIMI_API_KEY", "MOONSHOT_API_KEY",
        "DEEPSEEK_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY",
        "OPENROUTER_API_KEY", "GROQ_API_KEY", "XAI_API_KEY", "GROK_API_KEY",
        "CEREBRAS_API_KEY", "NVIDIA_API_KEY", "OPENCODE_API_KEY",
        "ZENMUX_API_KEY", "PERPLEXITY_API_KEY", "GEMINI_API_KEY"
    )
    val providerBaseUrls = listOf(
        "KILO_CODE_BASE_URL", "MOONSHOT_BASE_URL", "MOONSHOTAI_BASE_URL",
        "DEEPSEEK_BASE_URL", "OPENAI_BASE_URL", "ANTHROPIC_BASE_URL",
        "OPENROUTER_BASE_URL", "GROQ_BASE_URL", "XAI_BASE_URL",
        "CEREBRAS_BASE_URL", "NVIDIA_BASE_URL", "OPENCODE_BASE_URL",
        "ZENMUX_BASE_URL", "PERPLEXITY_BASE_URL", "GEMINI_BASE_URL"
    )
    val runtimeKeys = listOf(
        "MODELMUX_CLAUDE_REWRITE", "MODELMUX_CLAUDE_DEFAULT_MODEL",
        "MODELMUX_CLAUDE_HAIKU_MODEL", "MODELMUX_CLAUDE_SONNET_MODEL",
        "MODELMUX_CLAUDE_OPUS_MODEL", "MODELMUX_CLAUDE_REASONING_MODEL",
        "MODELMUX_DEFAULT_MODEL", "MODELMUX_FALLBACK_MODEL",
        "MODELMUX_PORT", "MODELMUX_HOST", "MODELMUX_LOG_LEVEL",
        "MODELMUX_TODO_PATH", "MODELMUX_LEDGER_DB",
        "ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL",
        "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL",
        "ANTHROPIC_REASONING_MODEL"
    )

    return key in providerKeyAliases || key in providerBaseUrls || key in runtimeKeys
}
