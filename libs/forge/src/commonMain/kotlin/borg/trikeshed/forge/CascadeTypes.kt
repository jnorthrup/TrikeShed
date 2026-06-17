package borg.trikeshed.forge

import borg.trikeshed.forge.platform.PlatformUtils
import kotlinx.serialization.Serializable

/**
 * Operational Cascade — a detected or declared data dependency chain that can be
 * executed as a map/reduce/rereduce pipeline (like CouchDB views).
 *
 * Cascades are the "operational" structure: they describe how data flows through
 * transformation stages, where each stage's output becomes the next stage's input,
 * and reductions cascade up the key hierarchy.
 *
 * Example: readings → [machine_id, date_hour] → reduce(sum/avg/min/max) → rereduce
 */
@Serializable
data class OperationalCascade(
    val id: CascadeId,
    val name: String,
    /** Source data — file IDs, workflow outputs, or inline data */
    val sources: List<CascadeSource>,
    /** The cascade stages (map → reduce → rereduce) */
    val stages: List<CascadeStage>,
    /** Key hierarchy for reduction (e.g., ["infrastructure_id", "machine_id", "date_hour"]) */
    val keyHierarchy: List<String>,
    /** Metadata for inspection/visualization */
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class CascadeId(val value: String) {
    companion object {
        fun generate(): CascadeId = CascadeId(PlatformUtils.randomUuid())
    }
}

/** Source of data for a cascade */
@Serializable
sealed interface CascadeSource {
    @Serializable
    data class FileSource(val fileId: ForgeFileId, val format: DataFormat = DataFormat.AUTO) : CascadeSource

    @Serializable
    data class WorkflowOutput(val executionId: ForgeExecutionId, val stepId: String) : CascadeSource

    @Serializable
    data class InlineData(val data: String, val format: DataFormat) : CascadeSource

    @Serializable
    data class CursorSource(val cursorSpec: String) : CascadeSource  // e.g., "lcnc:grid:readings"
}

@Serializable
enum class DataFormat { AUTO, JSON, CSV, CONFIX, PARQUET, MARKDOWN_TABLE }

/** A single stage in the cascade pipeline */
@Serializable
sealed interface CascadeStage {
    val id: String

    /** Map stage: transforms each input row independently */
    @Serializable
    data class MapStage(
        override val id: String,
        /** JS map function (CouchDB style) or Kotlin lambda reference */
        val transform: MapTransform,
        /** Output schema hint */
        val outputSchema: Map<String, String> = emptyMap(),
    ) : CascadeStage

    /** Reduce stage: aggregates by key */
    @Serializable
    data class ReduceStage(
        override val id: String,
        val reduceFn: ReduceTransform,
        /** Initial value for fold */
        val initialValue: String? = null,
    ) : CascadeStage

    /** Rereduce stage: merges partial reductions (for distributed/sharded execution) */
    @Serializable
    data class RereduceStage(
        override val id: String,
        val rereduceFn: ReduceTransform,
    ) : CascadeStage

    /** Filter stage: predicates on rows */
    @Serializable
    data class FilterStage(
        override val id: String,
        val predicate: String,  // JS expression or Kotlin predicate ref
    ) : CascadeStage

    /** Project stage: select/rename columns */
    @Serializable
    data class ProjectStage(
        override val id: String,
        val columns: List<String>,  // output column names
        val expressions: Map<String, String>,  // column -> expression
    ) : CascadeStage

    /** Join stage: combine with another cascade/source */
    @Serializable
    data class JoinStage(
        override val id: String,
        val otherSource: CascadeSource,
        val onKeys: List<String>,
        val joinType: JoinType = JoinType.INNER,
    ) : CascadeStage
}

@Serializable
sealed interface MapTransform {
    @Serializable
    data class JsFunction(val source: String) : MapTransform  // CouchDB-style function(doc) { emit(key, value) }
    @Serializable
    data class KotlinLambda(val functionRef: String) : MapTransform  // e.g., "borg.trikeshed.forge.cascades.MyMaps::extractMetrics"
    @Serializable
    data class Template(val template: String, val bindings: Map<String, String>) : MapTransform  // Mustache/handlebars
}

@Serializable
sealed interface ReduceTransform {
    @Serializable
    data class JsFunction(val source: String) : ReduceTransform  // function(key, values, rereduce) { return sum }
    @Serializable
    data class KotlinLambda(val functionRef: String) : ReduceTransform
    @Serializable
    data class Builtin(val kind: BuiltinReduce) : ReduceTransform
}

@Serializable
enum class BuiltinReduce { SUM, AVG, MIN, MAX, COUNT, STDDEV, PERCENTILE_50, PERCENTILE_95, PERCENTILE_99, CONCAT, FIRST, LAST }

@Serializable
enum class JoinType { INNER, LEFT, RIGHT, FULL }

/** Result of executing a cascade */
@Serializable
data class CascadeExecutionResult(
    val executionId: CascadeExecutionId,
    val cascadeId: CascadeId,
    /** Final reduced output — Series of (key, reducedValue) */
    val output: List<CascadeOutputRow>,
    /** Intermediate stage outputs for debugging/inspection */
    val stageOutputs: Map<String, List<CascadeOutputRow>>,
    val startedAt: Long,
    val completedAt: Long,
    val status: CascadeExecutionStatus,
)

@Serializable
@JvmInline
value class CascadeExecutionId(val value: String) {
    companion object {
        fun generate(): CascadeExecutionId = CascadeExecutionId(PlatformUtils.randomUuid())
    }
}

@Serializable
data class CascadeOutputRow(
    val key: List<String>,  // composite key from keyHierarchy
    val value: String,      // reduced value (JSON string)
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
enum class CascadeExecutionStatus { PENDING, RUNNING, SUCCESS, FAILED, PARTIAL }

/** Cascade graph for visualization — nodes are stages, edges are data flow */
@Serializable
data class CascadeGraph(
    val cascadeId: CascadeId,
    val nodes: List<CascadeNode>,
    val edges: List<CascadeEdge>,
)

@Serializable
data class CascadeNode(
    val id: String,
    val type: CascadeStageType,
    val label: String,
    val config: Map<String, String> = emptyMap(),
)

@Serializable
enum class CascadeStageType { MAP, REDUCE, REREDUCE, FILTER, PROJECT, JOIN, SOURCE, SINK }

@Serializable
data class CascadeEdge(
    val from: String,
    val to: String,
    val dataFlow: String,  // description of what flows
)

/** Request to detect cascades from data */
@Serializable
data class CascadeDetectionRequest(
    val sources: List<CascadeSource>,
    /** Hint: known key columns to look for hierarchies */
    val candidateKeys: List<String> = emptyList(),
    /** Hint: known metric columns for reduction */
    val candidateMetrics: List<String> = emptyList(),
    /** Max depth of key hierarchy to detect */
    val maxHierarchyDepth: Int = 5,
)

/** Result of cascade detection */
@Serializable
data class CascadeDetectionResult(
    val detectedCascades: List<OperationalCascade>,
    val inferredKeyHierarchies: List<List<String>>,
    val inferredMetrics: List<String>,
    val confidence: Double,  // 0.0 to 1.0
)

/** Progress update during cascade execution. */
@Serializable
sealed interface CascadeProgress {
    @Serializable
    data class StageStarted(val stageId: String, val stageName: String) : CascadeProgress

    @Serializable
    data class StageMessage(val stageId: String, val message: String) : CascadeProgress

    @Serializable
    data class StageCompleted(val stageId: String, val outputRowCount: Int) : CascadeProgress

    @Serializable
    data class StageFailed(val stageId: String, val error: String) : CascadeProgress

    @Serializable
    data class CascadeCompleted(val result: CascadeExecutionResult) : CascadeProgress

    @Serializable
    data class CascadeFailed(val error: String, val partialResults: Map<String, List<CascadeOutputRow>>) : CascadeProgress
}