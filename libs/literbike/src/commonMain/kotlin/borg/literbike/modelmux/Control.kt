package borg.literbike.modelmux

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Gateway control module - runtime control and state management.
 * Ported from literbike/src/modelmux/control.rs (subset: types and state).
 */

@Serializable
@SerialName("gateway_facade_family")
enum class GatewayFacadeFamily {
    @SerialName("openai_compatible") OpenAiCompatible,
    @SerialName("anthropic_compatible") AnthropicCompatible,
    @SerialName("gemini_native") GeminiNative,
    @SerialName("ollama_compatible") OllamaCompatible,
    @SerialName("unknown") Unknown
}

@Serializable
@SerialName("gateway_routing_mode")
enum class GatewayRoutingMode {
    @SerialName("model_prefix_then_priority") ModelPrefixThenPriority
}

@Serializable
data class GatewayProviderStatus(
    val name: String,
    val family: GatewayFacadeFamily,
    val baseUrl: String,
    val keyEnv: String,
    val priority: Int,
    val active: Boolean,
    val tokensUsedToday: Long,
    val estimatedRemainingQuota: Long,
    val quotaConfidence: Double
)

@Serializable
data class GatewayTransportState(
    val bindAddress: String,
    val port: Int,
    val unifiedAgentPort: Boolean,
    val listener: String
)

@Serializable
data class GatewayRoutingState(
    val mode: GatewayRoutingMode,
    val preferredProvider: String?,
    val defaultModel: String?,
    val fallbackModel: String?,
    val failoverEnabled: Boolean
)

@Serializable
data class GatewayStreamingState(
    val enabled: Boolean,
    val openaiChatCompletions: String,
    val ollamaChat: String
)

@Serializable
data class ClaudeModelRewritePolicy(
    val enabled: Boolean,
    val defaultModel: String?,
    val haikuModel: String?,
    val sonnetModel: String?,
    val opusModel: String?,
    val reasoningModel: String?
)

@Serializable
@SerialName("provider_key_precedence")
enum class ProviderKeyPrecedence {
    @SerialName("environment_first") EnvironmentFirst,
    @SerialName("override_first") OverrideFirst,
    @SerialName("environment_only") EnvironmentOnly,
    @SerialName("override_only") OverrideOnly
}

@Serializable
@SerialName("provider_key_source")
enum class ProviderKeySource {
    @SerialName("environment") Environment,
    @SerialName("override") Override,
    @SerialName("missing") Missing
}

@Serializable
data class ProviderKeyPolicy(
    val provider: String,
    val envKey: String?,
    val overrideEnvKey: String?,
    val precedence: ProviderKeyPrecedence
)

@Serializable
data class ProviderKeyResolutionState(
    val provider: String,
    val envKey: String?,
    val overrideEnvKey: String?,
    val precedence: ProviderKeyPrecedence,
    val selectedSource: ProviderKeySource,
    val selectedEnvKey: String?,
    val keyPresent: Boolean
)

@Serializable
data class GatewayKeymuxState(
    val strategy: String,
    val providerKeys: List<ProviderKeyResolutionState>
)

@Serializable
data class GatewayControlState(
    val transport: GatewayTransportState,
    val routing: GatewayRoutingState,
    val streaming: GatewayStreamingState,
    val claudeModelRewrite: ClaudeModelRewritePolicy,
    val keymux: GatewayKeymuxState,
    val providers: List<GatewayProviderStatus>
)

data class GatewayRuntimeControl(
    var preferredProvider: String?,
    var defaultModel: String?,
    var fallbackModel: String?,
    var streamingEnabled: Boolean,
    var claudeModelRewrite: ClaudeModelRewritePolicy,
    val providerKeyPolicies: MutableMap<String, ProviderKeyPolicy> = mutableMapOf()
) {
    companion object {
        fun fromConfig(config: ProxyConfig): GatewayRuntimeControl {
            val rewriteConfigured = listOf(
                "MODELMUX_CLAUDE_DEFAULT_MODEL",
                "MODELMUX_CLAUDE_SONNET_MODEL",
                "MODELMUX_CLAUDE_OPUS_MODEL",
                "MODELMUX_CLAUDE_HAIKU_MODEL",
                "MODELMUX_CLAUDE_REASONING_MODEL",
                "ANTHROPIC_MODEL",
                "ANTHROPIC_DEFAULT_SONNET_MODEL",
                "ANTHROPIC_DEFAULT_OPUS_MODEL",
                "ANTHROPIC_DEFAULT_HAIKU_MODEL",
                "ANTHROPIC_REASONING_MODEL"
            ).any { envString(it) != null }

            val rewrite = ClaudeModelRewritePolicy(
                enabled = envBool("MODELMUX_CLAUDE_REWRITE") ?: rewriteConfigured,
                defaultModel = envStringAny("MODELMUX_CLAUDE_DEFAULT_MODEL", "ANTHROPIC_MODEL"),
                haikuModel = envStringAny("MODELMUX_CLAUDE_HAIKU_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL"),
                sonnetModel = envStringAny("MODELMUX_CLAUDE_SONNET_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL"),
                opusModel = envStringAny("MODELMUX_CLAUDE_OPUS_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL"),
                reasoningModel = envStringAny("MODELMUX_CLAUDE_REASONING_MODEL", "ANTHROPIC_REASONING_MODEL")
            )

            return GatewayRuntimeControl(
                preferredProvider = null,
                defaultModel = config.defaultModel,
                fallbackModel = config.fallbackModel,
                streamingEnabled = config.enableStreaming,
                claudeModelRewrite = rewrite
            )
        }

        private fun envString(vararg keys: String): String? {
            for (key in keys) {
                val value = System.getenv(key)
                if (value != null && value.isNotBlank()) return value
            }
            return null
        }

        private fun envBool(key: String): Boolean? {
            return when (System.getenv(key)?.trim()?.lowercase()) {
                "1", "true", "yes", "on" -> true
                "0", "false", "no", "off" -> false
                else -> null
            }
        }
    }

    fun snapshot(config: ProxyConfig): GatewayControlState {
        return GatewayControlState(
            transport = GatewayTransportState(
                bindAddress = config.bindAddress,
                port = config.port,
                unifiedAgentPort = config.port == 8888,
                listener = "http1"
            ),
            routing = GatewayRoutingState(
                mode = GatewayRoutingMode.ModelPrefixThenPriority,
                preferredProvider = preferredProvider,
                defaultModel = defaultModel,
                fallbackModel = fallbackModel,
                failoverEnabled = fallbackModel != null || System.getenv("OPENROUTER_API_KEY") != null
            ),
            streaming = GatewayStreamingState(
                enabled = streamingEnabled,
                openaiChatCompletions = "disabled",
                ollamaChat = if (streamingEnabled) "ndjson" else "disabled"
            ),
            claudeModelRewrite = claudeModelRewrite,
            keymux = GatewayKeymuxState(
                strategy = "default",
                providerKeys = emptyList()
            ),
            providers = emptyList()
        )
    }

    fun applyAction(action: GatewayControlAction): Result<Unit> {
        return when (action) {
            is GatewayControlAction.SetPreferredProvider -> {
                preferredProvider = action.provider
                Result.success(Unit)
            }
            GatewayControlAction.ClearPreferredProvider -> {
                preferredProvider = null
                Result.success(Unit)
            }
            is GatewayControlAction.SetDefaultModel -> {
                defaultModel = normalizeString(action.model)
                Result.success(Unit)
            }
            GatewayControlAction.ClearDefaultModel -> {
                defaultModel = null
                Result.success(Unit)
            }
            is GatewayControlAction.SetFallbackModel -> {
                fallbackModel = normalizeString(action.model)
                Result.success(Unit)
            }
            GatewayControlAction.ClearFallbackModel -> {
                fallbackModel = null
                Result.success(Unit)
            }
            is GatewayControlAction.SetStreamingEnabled -> {
                streamingEnabled = action.enabled
                Result.success(Unit)
            }
            is GatewayControlAction.SetClaudeRewritePolicy -> {
                claudeModelRewrite = ClaudeModelRewritePolicy(
                    enabled = action.enabled,
                    defaultModel = action.defaultModel,
                    haikuModel = action.haikuModel,
                    sonnetModel = action.sonnetModel,
                    opusModel = action.opusModel,
                    reasoningModel = action.reasoningModel
                )
                Result.success(Unit)
            }
            GatewayControlAction.ClearClaudeRewritePolicy -> {
                claudeModelRewrite = ClaudeModelRewritePolicy(
                    enabled = false,
                    defaultModel = null,
                    haikuModel = null,
                    sonnetModel = null,
                    opusModel = null,
                    reasoningModel = null
                )
                Result.success(Unit)
            }
            is GatewayControlAction.Reset -> {
                // Reset to defaults
                val defaults = fromConfig(ProxyConfig())
                preferredProvider = defaults.preferredProvider
                defaultModel = defaults.defaultModel
                fallbackModel = defaults.fallbackModel
                streamingEnabled = defaults.streamingEnabled
                claudeModelRewrite = defaults.claudeModelRewrite
                Result.success(Unit)
            }
        }
    }

    fun effectiveDefaultModel(): String? = defaultModel

    fun effectiveFallbackModel(): String? = fallbackModel

    fun preferredProviderForModel(model: String): String? {
        return if ('/' !in model) preferredProvider else null
    }

    private fun normalizeString(input: String?): String? {
        return input?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Gateway control actions.
 */
sealed class GatewayControlAction {
    data class SetPreferredProvider(val provider: String) : GatewayControlAction()
    data object ClearPreferredProvider : GatewayControlAction()
    data class SetDefaultModel(val model: String) : GatewayControlAction()
    data object ClearDefaultModel : GatewayControlAction()
    data class SetFallbackModel(val model: String) : GatewayControlAction()
    data object ClearFallbackModel : GatewayControlAction()
    data class SetStreamingEnabled(val enabled: Boolean) : GatewayControlAction()
    data class SetClaudeRewritePolicy(
        val enabled: Boolean,
        val defaultModel: String?,
        val haikuModel: String?,
        val sonnetModel: String?,
        val opusModel: String?,
        val reasoningModel: String?
    ) : GatewayControlAction()
    data object ClearClaudeRewritePolicy : GatewayControlAction()
    data object Reset : GatewayControlAction()
}

private fun envString(vararg keys: String): String? {
    for (key in keys) {
        val value = System.getenv(key)
        if (value != null && value.isNotBlank()) return value
    }
    return null
}

private fun envString(key: String): String? {
    return System.getenv(key)?.takeIf { it.isNotBlank() }
}

private fun envBool(key: String): Boolean? {
    return when (System.getenv(key)?.trim()?.lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> null
    }
}

/**
 * Utility: infer provider family from name and base URL.
 */
fun inferProviderFamily(provider: String, baseUrl: String): GatewayFacadeFamily {
    val providerLower = provider.lowercase()
    val baseLower = baseUrl.lowercase()

    return when {
        "anthropic" in providerLower || "claude" in providerLower -> GatewayFacadeFamily.AnthropicCompatible
        "gemini" in providerLower || "google" in providerLower ||
                "generativelanguage.googleapis.com" in baseLower -> GatewayFacadeFamily.GeminiNative
        "ollama" in providerLower || "lmstudio" in providerLower ||
                "localhost:11434" in baseLower -> GatewayFacadeFamily.OllamaCompatible
        baseLower.isNotEmpty() -> GatewayFacadeFamily.OpenAiCompatible
        else -> GatewayFacadeFamily.Unknown
    }
}

/**
 * Check if a model name looks like a Claude model.
 */
fun isClaudeLikeModel(model: String): Boolean {
    val normalized = model.trim().lowercase()
    return normalized.startsWith("claude-") || normalized.startsWith("anthropic/claude-")
}
