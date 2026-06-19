package borg.trikeshed.forge.kanban

import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual
import java.time.Instant

/**
 * Forge Kanban module — self-running tspy, GEPA, kanban with keymux and modelmux.
 * 
 * Provides bidirectional integration with Hermes:
 * - Can share model/usage data with Hermes providers
 * - Can enhance Hermes models via GEPA optimization
 * - Borrows Hermes tool patterns for local execution
 */
@Serializable
data class ForgeKanbanConfig(
    val tspyEnabled: Boolean = true,
    val gepaEnabled: Boolean = true,
    val kanbanEnabled: Boolean = true,
    val keymuxEnabled: Boolean = true,
    val modelmuxEnabled: Boolean = true,
    val shareWithHermes: ShareWithHermes = ShareWithHermes.NONE,
    val hermesEndpoint: String? = null,
)

@Serializable
enum class ShareWithHermes {
    NONE,           // Fully isolated
    MODELS_ONLY,    // Share model registry
    USAGE_ONLY,     // Share usage metrics
    FULL,           // Share both models and usage
    ENHANCE_HERMES, // Actively enhance Hermes models via GEPA
}

/**
 * Key entry for keymux — tracks credential keys with model affinity.
 */
@Serializable
data class KeyEntry(
    val keyId: String,
    val provider: String,
    val label: String,
    val modelUrl: String = "",
    val lastModel: String? = null,
    val lastUsedMs: Long = 0,
    val accessCount: Long = 0,
    val status: KeyStatus = KeyStatus.ACTIVE,
    val leasedTo: String? = null,
    val leaseExpiresAt: Long = 0,
)

@Serializable
enum class KeyStatus { ACTIVE, BACKOFF, BENCHED }

/**
 * Draft mapping — bijective active key -> model.
 */
@Serializable
data class DraftMapping(
    val mapping: Map<String, String>,  // keyId -> model
    val updatedAt: Long = Instant.now().toEpochMilli(),
)

/**
 * Operational metric entry for dashboard/telemetry.
 */
@Serializable
data class OperationalEntry(
    val poolName: String,
    val key: String,
    val labels: Map<String, String> = emptyMap(),
    val value: Double = 0.0,
    val timestampMs: Long = Instant.now().toEpochMilli(),
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Pre-defined operational pool names.
 */
object OperationalPool {
    const val AGENT_HEALTH = "agent_health"
    const val TASK_THROUGHPUT = "task_throughput"
    const val WORKER_UTILIZATION = "worker_utilization"
    const val LATENCY_DISTRIBUTION = "latency_distribution"
    const val ERROR_RATES = "error_rates"
    const val QUEUE_DEPTHS = "queue_depths"
    const val MODEL_USAGE = "model_usage"
    const val RESOURCE_USAGE = "resource_usage"
}

/**
 * Dashboard view types.
 */
@Serializable
enum class DashboardViewType { GAUGE, LINE, BAR, TABLE, HEATMAP, STATUS_GRID }

@Serializable
data class DashboardView(
    val viewId: String,
    val title: String,
    val viewType: DashboardViewType,
    val data: Map<String, @Contextual Any>,
    val thresholds: Map<String, Double> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * GEPA optimization state.
 */
@Serializable
data class GepaState(
    val running: Boolean = false,
    val cycleCount: Int = 0,
    val lastResult: GepaResult? = null,
    val seedPolicy: String = "",
    val maxMetricCalls: Int = 10,
    val intervalSeconds: Int = 300,
)

@Serializable
data class GepaResult(
    val bestCandidate: String,
    val totalMetricCalls: Int,
    val numCandidates: Int,
    val runDir: String,
    val timestampMs: Long = Instant.now().toEpochMilli(),
)

/**
 * Kanban board integration.
 */
@Serializable
data class KanbanBoardRef(
    val boardId: String,
    val workspaceId: String,
    val maxInProgress: Int = 3,
    val maxSpawn: Int = 3,
)

/**
 * Lease info for key coordination.
 */
@Serializable
data class LeaseInfo(
    val keyId: String,
    val leasedTo: String?,
    val leaseStartedAt: Long,
    val leaseExpiresAt: Long,
    val isActiveLease: Boolean,
)

/**
 * Hermes integration config.
 */
@Serializable
data class HermesIntegration(
    val endpoint: String,
    val apiKey: String?,
    val shareModels: Boolean = false,
    val shareUsage: Boolean = false,
    val enhanceModels: Boolean = false,
    val syncIntervalMs: Long = 60_000,
)