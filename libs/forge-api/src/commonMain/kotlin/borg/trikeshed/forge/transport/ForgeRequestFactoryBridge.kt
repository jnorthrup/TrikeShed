package borg.trikeshed.forge.transport

import borg.trikeshed.couch.requestfactory.*
import borg.trikeshed.cursor.*
import borg.trikeshed.lib.Series
import borg.trikeshed.parse.confix.ConfixCell
import borg.trikeshed.parse.confix.ConfixDoc
import kotlinx.serialization.Serializable

// ============================================================================
// REQUESTFACTORY → CURSOR SELECTOR BRIDGE
// Uses root project's cursor selector algebra (borg.trikeshed.cursor.*)
// ============================================================================

/**
 * Maps RequestFactory schema to Forge cursor selectors.
 * Each EntityProxySpec becomes a cursor path + projection spec.
 */
@Serializable
data class ForgeRequestFactorySchema(
    val factory: RequestFactorySpec,
    val entities: Map<String, ForgeEntityMapping>,
    val contexts: Map<String, ForgeContextMapping>
)

@Serializable
data class RequestFactorySpec(
    val name: String = "ForgeRequestFactory",
    val servletPath: String = "/forgeRequest",
    val eventBusType: String = "ForgeEventBus"
)

/**
 * Maps an entity type to cursor operations.
 * Replaces AutoBean proxy with Confix JsElement navigation.
 */
@Serializable
data class ForgeEntityMapping(
    /** Entity type name (e.g., "ForgeFile", "ForgeWorkflow") */
    val entityName: String,
    /** Root cursor path: ["forge", "files"] */
    val rootPath: List<String>,
    /** ID column path within entity */
    val idPath: List<String> = listOf("id"),
    /** Version column for optimistic locking */
    val versionPath: List<String> = listOf("version"),
    /** Facet filters to apply */
    val facetFilters: List<String> = emptyList(),
    /** Child relations → nested cursor paths */
    val relations: Map<String, ForgeRelationMapping> = emptyMap(),
    /** Default projection for list operations */
    val listProjection: ProjectionSpec = ProjectionSpec(include = listOf("id", "name", "updatedAt")),
    /** Default projection for get operations */
    val getProjection: ProjectionSpec = ProjectionSpec(maxDepth = 5)
)

@Serializable
data class ForgeRelationMapping(
    /** Relation name (e.g., "steps", "artifacts") */
    val name: String,
    /** Target entity type */
    val targetEntity: String,
    /** Path from parent entity to child cursor */
    val path: List<String>,
    /** Inverse relation name on target */
    val inverse: String? = null
)

/**
 * Maps a RequestContext to cursor operations.
 */
@Serializable
data class ForgeContextMapping(
    val contextName: String,
    /** Available methods → cursor pipelines */
    val methods: Map<String, ForgeMethodMapping>
)

@Serializable
data class ForgeMethodMapping(
    val methodName: String,
    val kind: RequestMethodKind,
    /** Input: how to build cursor pipeline from arguments */
    val inputMapper: String,  // KSP-generated function reference
    /** Output: how to shape response */
    val outputMapper: String,
    /** Default projection */
    val projection: ProjectionSpec? = null
)

// ============================================================================
// CODEC — RequestFactoryCall/Response ↔ ForgeTransportCall/Response
// ============================================================================

/**
 * Bridge: RequestFactory transport ↔ Cursor selector transport
 */
object ForgeRequestFactoryBridge {

    /**
     * Decode legacy RequestFactoryCall → ForgeTransportCall
     * Used for migration / compat layer
     */
    fun decode(call: RequestFactoryCall, schema: ForgeRequestFactorySchema): ForgeTransportCall {
        val contextMapping = schema.contexts[call.context]
            ?: error("Unknown context: ${call.context}")
        val methodMapping = contextMapping.methods[call.method]
            ?: error("Unknown method: ${call.method} in context ${call.context}")

        // Build pipeline from method mapping + arguments
        val pipeline = buildPipeline(methodMapping, call.arguments)
        
        return ForgeTransportCall(
            pipeline = pipeline,
            projection = methodMapping.projection,
            context = call.arguments.associate { it.index to it.value?.toString() ?: "" }
        )
    }

    /**
     * Encode ForgeTransportResponse → RequestFactoryResponse
     */
    fun encode(response: ForgeTransportResponse, methodMapping: ForgeMethodMapping): RequestFactoryResponse {
        // Convert ConfixCell series to transport values
        val transportValues = response.elements.map { cell ->
            cellToTransportValue(cell)
        }
        
        return RequestFactoryResponse(
            success = true,
            value = TransportValue.ArrayValue(transportValues),
            updatedEntities = emptyList(),
            validationErrors = emptyList()
        )
    }

    private fun buildPipeline(mapping: ForgeMethodMapping, args: List<TransportValue>): CursorPipeline {
        // KSP-generated: mapping.inputMapper(args) → CursorPipeline
        // For now, return empty — real impl is codegen
        return emptyList()
    }

    private fun cellToTransportValue(cell: ConfixCell): TransportValue {
        val reified = cell.reify()
        return when (reified) {
            null -> TransportValue.NullValue
            is String -> TransportValue.StringValue(reified)
            is Boolean -> TransportValue.BooleanValue(reified)
            is Number -> TransportValue.NumberValue(reified.toDouble())
            is List<*> -> TransportValue.ArrayValue(reified.map(::cellToTransportValue))
            is Map<*, *> -> TransportValue.ObjectValue(
                reified.entries.associate { (k, v) -> k.toString() to cellToTransportValue(v as Any) }
            )
            else -> TransportValue.StringValue(reified.toString())
        }
    }
}

// ============================================================================
// KSP CODEGEN ANNOTATIONS — for compile-time pipeline generation
// ============================================================================

/**
 * Annotate entity classes to generate ForgeEntityMapping
 * 
 * Usage:
 * @ForgeEntity(rootPath = ["forge", "files"], id = "id", version = "version")
 * interface ForgeFile : EntityProxy { ... }
 */
@kotlin.annotation.AnnotationTarget.ANNOTATION_CLASS
annotation class ForgeEntity(
    val rootPath: Array<String>,
    val id: String = "id",
    val version: String = "version",
    val facetFilters: Array<String> = [],
    val listProjection: Array<String> = [],
    val getProjection: Array<String> = []
)

/**
 * Annotate context interfaces to generate ForgeContextMapping
 */
@kotlin.annotation.AnnotationTarget.ANNOTATION_CLASS
annotation class ForgeContext(
    val name: String,
    val methods: Array<ForgeMethod>
)

@kotlin.annotation.AnnotationTarget.ANNOTATION_CLASS
annotation class ForgeMethod(
    val name: String,
    val kind: RequestMethodKind = RequestMethodKind.Request,
    val projection: Array<String> = []
)

// ============================================================================
// GENERATED STUB EXAMPLE (what KSP produces)
// Uses root project's CursorOp algebra
// ============================================================================

/*
// GENERATED by KSP from @ForgeEntity
object ForgeFileMapping : ForgeEntityMapping(
    entityName = "ForgeFile",
    rootPath = listOf("forge", "files"),
    idPath = listOf("id"),
    versionPath = listOf("version"),
    facetFilters = listOf(),
    relations = mapOf(
        "snapshots" to ForgeRelationMapping(
            name = "snapshots",
            targetEntity = "ForgeSnapshot",
            path = listOf("snapshots"),
            inverse = "file"
        )
    ),
    listProjection = ProjectionSpec(include = listOf("id", "path", "mimeType", "updatedAt")),
    getProjection = ProjectionSpec(maxDepth = 3)
)

// GENERATED by KSP from @ForgeContext
object FileContextMapping : ForgeContextMapping(
    contextName = "FileContext",
    methods = mapOf(
        "put" to ForgeMethodMapping(
            methodName = "put",
            kind = RequestMethodKind.InstanceRequest,
            inputMapper = "FileContextMappers.putPipeline",
            outputMapper = "FileContextMappers.putResponse",
            projection = ProjectionSpec(include = listOf("id"))
        ),
        "get" to ForgeMethodMapping(
            methodName = "get",
            kind = RequestMethodKind.Request,
            inputMapper = "FileContextMappers.getPipeline",
            outputMapper = "FileContextMappers.getResponse",
            projection = ProjectionSpec(maxDepth = 3)
        ),
        "list" to ForgeMethodMapping(
            methodName = "list",
            kind = RequestMethodKind.Request,
            inputMapper = "FileContextMappers.listPipeline",
            outputMapper = "FileContextMappers.listResponse"
        ),
        "search" to ForgeMethodMapping(
            methodName = "search",
            kind = RequestMethodKind.Request,
            inputMapper = "FileContextMappers.searchPipeline",
            outputMapper = "FileContextMappers.searchResponse"
        )
    )
)

// GENERATED mappers using root project's CursorOp
object FileContextMappers {
    fun putPipeline(args: List<TransportValue>): CursorPipeline =
        listOf(
            CursorOp.PathStep.Key("forge"),
            CursorOp.PathStep.Key("files"),
            CursorOp.ProjectColumns(listOf("id", "path", "content", "mimeType", "metadata"))
        )

    fun getPipeline(args: List<TransportValue>): CursorPipeline {
        val id = args.firstOrNull()?.let { it as? TransportValue.StringValue }?.value ?: ""
        return listOf(
            CursorOp.PathStep.Key("forge"),
            CursorOp.PathStep.Key("files"),
            CursorOp.FilterFacet("active"),
            CursorOp.ProjectColumns(listOf("id", "path", "content", "mimeType", "metadata", "createdAt", "updatedAt"))
        ).also { pipeline ->
            if (id.isNotBlank()) {
                pipeline.add(0, CursorOp.PathStep.Key(id))
            }
        }
    }

    fun listPipeline(args: List<TransportValue>): CursorPipeline =
        listOf(
            CursorOp.PathStep.Key("forge"),
            CursorOp.PathStep.Key("files"),
            CursorOp.FilterFacet("active"),
            CursorOp.ProjectColumns(listOf("id", "path", "mimeType", "updatedAt")),
            CursorOp.Range(0, 50)  // take 50
        )

    fun searchPipeline(args: List<TransportValue>): CursorPipeline {
        val query = args.firstOrNull()?.let { it as? TransportValue.StringValue }?.value ?: ""
        return listOf(
            CursorOp.PathStep.Key("forge"),
            CursorOp.PathStep.Key("files"),
            CursorOp.FilterFacet("active"),
            CursorOp.ProjectColumns(listOf("id", "path", "mimeType", "updatedAt"))
        )
    }

    fun putResponse(cell: ConfixCell): TransportValue = cellToTransportValue(cell)
    fun getResponse(cell: ConfixCell): TransportValue = cellToTransportValue(cell)
    fun listResponse(cells: Series<ConfixCell>): TransportValue = 
        TransportValue.ArrayValue(cells.map { cellToTransportValue(it) }.toList())
    fun searchResponse(cells: Series<ConfixCell>): TransportValue = listResponse(cells)
}
*/

// ============================================================================
// PURE EXAMPLETACH — FieldSynapse pointcut attachment for heatmaps
// Uses root project's CursorOp and Cursor.execute
// ============================================================================

/**
 * Attach pointcut emitter to cursor pipeline execution.
 * Produces Series<PointcutEvent> for heatmap visualization.
 */
interface PointcutEmitter {
    fun onCursorAccess(cursorId: String, operation: String, rowCount: Int, ns: Long)
    fun onPathTraversal(path: JsPath, depth: Int, ns: Long)
    fun onReify(cell: ConfixCell, ns: Long)
    fun onFilter(facet: String, inputRows: Int, outputRows: Int, ns: Long)
}

@Serializable
data class PointcutEvent(
    val timestampNs: Long,
    val cursorId: String,
    val operation: String,  // "select", "filter", "path", "reify", "project"
    val rowCount: Int,
    val pathDepth: Int,
    val facet: String?
)

/**
 * Wrap cursor pipeline with pointcut emission — pure, no side effects in pipeline
 * Uses root project's Cursor.execute(pipeline)
 */
fun Cursor.executeWithPointcuts(
    pipeline: CursorPipeline,
    emitter: PointcutEmitter,
    cursorId: String = "default"
): Pair<Cursor, Series<PointcutEvent>> {
    val events = mutableListOf<PointcutEvent>()
    val startNs = System.nanoTime()
    
    var current = this
    var depth = 0
    
    for (op in pipeline) {
        val opStart = System.nanoTime()
        val inputRows = current.size
        
        current = when (op) {
            is CursorOp.FilterFacet -> {
                val out = current.execute(listOf(op))
                events.add(PointcutEvent(
                    timestampNs = System.nanoTime(),
                    cursorId = cursorId,
                    operation = "filter",
                    rowCount = out.size,
                    pathDepth = depth,
                    facet = op.facet
                ))
                out
            }
            is CursorOp.PathStep.Key -> {
                depth++
                val out = current.execute(listOf(op))
                events.add(PointcutEvent(
                    timestampNs = System.nanoTime(),
                    cursorId = cursorId,
                    operation = "path",
                    rowCount = out.size,
                    pathDepth = depth,
                    facet = null
                ))
                out
            }
            is CursorOp.PathStep.Idx -> {
                depth++
                val out = current.execute(listOf(op))
                events.add(PointcutEvent(
                    timestampNs = System.nanoTime(),
                    cursorId = cursorId,
                    operation = "path",
                    rowCount = out.size,
                    pathDepth = depth,
                    facet = null
                ))
                out
            }
            is CursorOp.SelectIndices -> {
                val out = current.execute(listOf(op))
                events.add(PointcutEvent(
                    timestampNs = System.nanoTime(),
                    cursorId = cursorId,
                    operation = "select",
                    rowCount = out.size,
                    pathDepth = depth,
                    facet = null
                ))
                out
            }
            is CursorOp.ProjectColumns -> {
                val out = current.execute(listOf(op))
                events.add(PointcutEvent(
                    timestampNs = System.nanoTime(),
                    cursorId = cursorId,
                    operation = "project",
                    rowCount = out.size,
                    pathDepth = depth,
                    facet = null
                ))
                out
            }
            is CursorOp.Range -> {
                val out = current.execute(listOf(op))
                events.add(PointcutEvent(
                    timestampNs = System.nanoTime(),
                    cursorId = cursorId,
                    operation = "range",
                    rowCount = out.size,
                    pathDepth = depth,
                    facet = null
                ))
                out
            }
            else -> current.execute(listOf(op))
        }
    }
    
    val totalNs = System.nanoTime() - startNs
    events.add(PointcutEvent(
        timestampNs = System.nanoTime(),
        cursorId = cursorId,
        operation = "total",
        rowCount = current.size,
        pathDepth = depth,
        facet = null
    ))
    
    return current to events.size j { events[it] }
}