package borg.trikeshed.openapi

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

/**
 * OpenAPI Generated API contracts — choreography for generated APIs.
 * 
 * Stage 11: OpenAPI Generated APIs
 * 
 * @see TODO.md Stage 11
 */

// ==================== PLACEHOLDER TYPES ====================

/**
 * Placeholder for UserSignal from user-signals module.
 */
typealias UserSignal = String

/**
 * Placeholder for GraphNode from forge module.
 */
typealias GraphNode = String

/**
 * Placeholder for GraphOverlay from forge module.
 */
typealias GraphOverlay = String

/**
 * Placeholder for OverlayPrincipal from forge module.
 */
typealias OverlayPrincipal = String

/**
 * Placeholder for AclResult from forge module.
 */
typealias AclResult = String

// ==================== OPERATION CHOREOGRAPHY ====================

/**
 * Operation choreography — flow from operationId to user-signals.
 * 
 * Contract:
 *   - operationId -> generated request type -> request cursor
 *   - request cursor -> LCNC cursor facets -> user-signalling events
 *   - user-signalling events -> Forge/Kanban graph node planning
 */
interface OperationChoreography {
    /**
     * Execute an operation with full choreography.
     */
    fun TODO_executeOperation(
        operationId: String,
        request: GeneratedRequest,
        parentJob: kotlinx.coroutines.Job?
    ): kotlinx.coroutines.Job
    
    /**
     * Get the request cursor for an operation.
     */
    fun TODO_getRequestCursor(operationId: String): Cursor?
    
    /**
     * Apply LCNC facets to the request cursor.
     */
    fun TODO_applyLcncFacets(operationId: String, cursor: Cursor): Cursor
    
    /**
     * Emit user-signalling events from the operation.
     */
    fun TODO_emitUserSignals(operationId: String, cursor: Cursor): Series<UserSignal>
    
    /**
     * Plan graph nodes for the operation result.
     */
    fun TODO_planGraphNodes(operationId: String, cursor: Cursor): Series<GraphNode>
}

// ==================== REQUEST CURSOR ====================

/**
 * Generated request cursor — request cursor per operation.
 */
interface RequestCursor {
    val operationId: String
    val request: GeneratedRequest
    val lcncFacets: Series<LcncFacetGroup>
    val cursor: Cursor
}

// ==================== GENERATED REQUEST ====================

/**
 * Generated request — request type per operation.
 */
data class GeneratedRequest(
    val operationId: String,
    val method: HttpMethod,
    val path: String,
    val queryParams: Map<String, String> = emptyMap(),
    val pathParams: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: Series<Byte>? = null
)

// ==================== HTTP METHOD ====================

/**
 * HTTP method for generated APIs.
 */
enum class HttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
    PATCH
}

// ==================== OVERLAY ACL CHECK ====================

/**
 * Overlay ACL check — ensures generated APIs don't bypass ACL.
 */
interface OverlayAclCheck {
    /**
     * Check if the operation is allowed for the given principal.
     */
    fun TODO_checkAcl(operationId: String, principal: OverlayPrincipal): Boolean
    
    /**
     * Get the ACL result for an operation.
     */
    fun TODO_getAclResult(operationId: String): AclResult
}

// ==================== COUNTER/DRAIN HOOKS ====================

/**
 * Counter/drain hooks — ensure generated APIs don't bypass counters/drains.
 */
interface CounterDrainHooks {
    /**
     * Record a resolution attempt.
     */
    fun TODO_recordResolutionAttempt(operationId: String)
    
    /**
     * Record an overlay visibility check.
     */
    fun TODO_recordVisibilityCheck(operationId: String, visible: Boolean)
    
    /**
     * Record an ACL result.
     */
    fun TODO_recordAclResult(operationId: String, allowed: Boolean)
    
    /**
     * Drain pending operations.
     */
    fun TODO_drainPending(): Int
}

// ==================== GRAPH NODE PLANNING ====================

/**
 * Graph node planning — Forge/Kanban graph node planning.
 */
interface GraphNodePlanning {
    /**
     * Plan graph nodes for an operation.
     */
    fun TODO_planNodes(
        boardId: String,
        operationId: String,
        cursor: Cursor,
        overlays: Series<GraphOverlay>
    ): Series<GraphNode>
    
    /**
     * Get planned nodes for a board.
     */
    fun TODO_getPlannedNodes(boardId: String): Series<GraphNode>
}

// ==================== FACTORY ====================

/**
 * Factory for operation choreography.
 */
object OperationChoreographies {
    /**
     * Create an operation choreography.
     */
    fun create(): OperationChoreography = TODO("OperationChoreographies.create: implement in Stage 11")
}