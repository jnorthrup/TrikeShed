package borg.trikeshed.keymux.toolbar

import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

/**
 * Service status for toolbar display.
 */
@Serializable
enum class ToolbarServiceStatus {
    RUNNING,
    DEGRADED,
    COLD,
}

/**
 * Service manager type.
 */
@Serializable
enum class ToolbarServiceManager {
    EXTERNAL_LAUNCHER,
}

/**
 * Confidence bucket for environment state.
 */
@Serializable
enum class ToolbarConfidenceBucket {
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * Persistence kind for debt tracking.
 */
@Serializable
enum class ToolbarPersistenceKind {
    VOLATILE,
    MARKDOWN_TODO,
    SQLITE,
}

/**
 * Surface kind for gateway interfaces.
 */
@Serializable
enum class ToolbarSurfaceKind {
    CONTROL_API,
    OPENAI_COMPAT,
    OLLAMA_COMPAT,
    QUOTA_LEDGER,
}

/**
 * Service state.
 */
@Serializable
data class ToolbarServiceState(
    val status: ToolbarServiceStatus,
    val manager: ToolbarServiceManager,
    val bindAddress: String,
    val port: Int,
)

/**
 * Route state.
 */
@Serializable
data class ToolbarRouteState(
    val family: String,
    val provider: String?,
    val model: String?,
    val unifiedAgentPort: Boolean,
    val failoverEnabled: Boolean,
)

/**
 * Environment key state.
 */
@Serializable
data class ToolbarEnvKey(
    val name: String,
    val isSet: Boolean,
)

/**
 * Environment state.
 */
@Serializable
data class ToolbarEnvState(
    val recognizedKeys: Int,
    val unknownKeys: Int,
    val confidence: ToolbarConfidenceBucket,
    val keys: List<ToolbarEnvKey>,
)

/**
 * Debt state (TODO items).
 */
@Serializable
data class ToolbarDebtState(
    val openItems: Int,
    val blockedItems: Int,
    val persistence: ToolbarPersistenceKind,
    val sourcePath: String?,
)

/**
 * Runtime state.
 */
@Serializable
data class ToolbarRuntimeState(
    val streamingEnabled: Boolean,
    val claudeRewriteEnabled: Boolean,
    val keymuxStrategy: String,
    val providerKeyOverrides: Int,
)

/**
 * Surface state.
 */
@Serializable
data class ToolbarSurfaceState(
    val kind: ToolbarSurfaceKind,
    val available: Boolean,
    val detail: String,
)

/**
 * DSEL lane state.
 */
@Serializable
data class ToolbarDselLane(
    val title: String,
    val route: String,
    val model: String,
    val host: String,
    val provider: String,
    val key: String?,
)

/**
 * Complete toolbar state.
 */
@Serializable
data class ToolbarState(
    val service: ToolbarServiceState,
    val route: ToolbarRouteState,
    val env: ToolbarEnvState,
    val debt: ToolbarDebtState,
    val runtime: ToolbarRuntimeState,
    val surfaces: List<ToolbarSurfaceState>,
    val keymux: Map<String, Any>, // GatewayKeymuxState - simplified as Map
    val lanes: List<ToolbarDselLane>,
    val dynamicModels: List<String>,
    val availableActions: List<String>,
)

/**
 * Toolbar actions.
 */
@Serializable
sealed class ToolbarAction {
    @Serializable
    data class RescanEnv() : ToolbarAction()
    @Serializable
    data class ResetRuntime() : ToolbarAction()
    @Serializable
    data class SetStreamingEnabled(val enabled: Boolean) : ToolbarAction()
    @Serializable
    data class SetPreferredProvider(val provider: String) : ToolbarAction()
    @Serializable
    data class ClearPreferredProvider() : ToolbarAction()
    @Serializable
    data class SetDefaultModel(val model: String) : ToolbarAction()
    @Serializable
    data class ClearDefaultModel() : ToolbarAction()
    @Serializable
    data class SetFallbackModel(val model: String) : ToolbarAction()
    @Serializable
    data class ClearFallbackModel() : ToolbarAction()
    @Serializable
    data class SetClaudeRewriteEnabled(val enabled: Boolean) : ToolbarAction()
    @Serializable
    data class SetClaudeRewritePolicy(
        val enabled: Boolean,
        val defaultModel: String?,
        val haikuModel: String?,
        val sonnetModel: String?,
        val opusModel: String?,
        val reasoningModel: String?,
    ) : ToolbarAction()
    @Serializable
    data class ClearClaudeRewritePolicy() : ToolbarAction()
    @Serializable
    data class SetProviderKeyPolicy(
        val provider: String,
        val envKey: String?,
        val overrideEnvKey: String?,
        val precedence: String,
    ) : ToolbarAction()
    @Serializable
    data class ClearProviderKeyPolicy(val provider: String) : ToolbarAction()
    @Serializable
    data class ImportCcSwitchKeysAdditive(val path: String?) : ToolbarAction()
}
