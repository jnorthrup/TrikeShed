package borg.trikeshed.openapi.demo

import borg.trikeshed.openapi.*

/**
 * OpenAPI Choreography Demo — demonstrates OpenAPI generated API choreography.
 * 
 * This shows:
 * - Operation choreography contracts
 * - Request cursor contracts
 * - Generated request contracts
 * - Overlay ACL check contracts
 * - Counter/drain hooks contracts
 * - Graph node planning contracts
 */
object OpenApiChoreographyDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        println("📡 OpenAPI Choreography Demo Starting...")
        println()

        // 1. Show operation choreography
        println("1. Operation Choreography")
        println("   Flow: operationId -> request -> cursor -> LCNC facets -> user-signals -> graph nodes")
        println()

        // 2. Show generated request
        println("2. Generated Request Contract")
        val request = GeneratedRequest(
            operationId = "getUser",
            method = HttpMethod.GET,
            path = "/users/{id}",
            pathParams = mapOf("id" to "123"),
            queryParams = mapOf("include" to "profile")
        )
        println("   operationId: ${request.operationId}")
        println("   method: ${request.method}")
        println("   path: ${request.path}")
        println("   pathParams: ${request.pathParams}")
        println("   queryParams: ${request.queryParams}")
        println()

        // 3. Show HTTP methods
        println("3. HTTP Methods")
        println("   ${HttpMethod.entries.joinToString(", ")}")
        println()

        // 4. Show choreography interfaces
        println("4. Choreography Contracts")
        println("   - OperationChoreography: execute, getRequestCursor, applyLcncFacets, emitUserSignals, planGraphNodes")
        println("   - OverlayAclCheck: checkAcl, getAclResult")
        println("   - CounterDrainHooks: recordResolutionAttempt, recordVisibilityCheck, recordAclResult, drainPending")
        println("   - GraphNodePlanning: planNodes, getPlannedNodes")
        println()

        // 5. Show choreography flow
        println("5. Choreography Flow")
        println("   1. operationId -> GeneratedRequest")
        println("   2. GeneratedRequest -> RequestCursor")
        println("   3. RequestCursor + LCNC facets -> Cursor")
        println("   4. Cursor -> user-signalling events")
        println("   5. user-signalling events -> graph nodes")
        println("   6. graph nodes -> Forge/Kanban overlay")
        println()

        // 6. Show ACL check
        println("6. ACL Check")
        println("   - Ensures generated APIs don't bypass overlay ACL")
        println("   - Principal: TODO(OpenAPI choreography): bind to forge overlay principal model")
        println("   - Result: TODO(OpenAPI choreography): bind to ACL result model")
        println()

        // 7. Show counter/drain hooks
        println("7. Counter/Drain Hooks")
        println("   - recordResolutionAttempt: track operation resolution")
        println("   - recordVisibilityCheck: track overlay visibility")
        println("   - recordAclResult: track ACL allow/deny")
        println("   - drainPending: drain pending operations")
        println()

        // 8. Show graph node planning
        println("8. Graph Node Planning")
        println("   - Plan nodes from operation result")
        println("   - Get planned nodes for board")
        println("   - Node: TODO(OpenAPI choreography): bind to root causal graph node model")
        println("   - Overlay: TODO(OpenAPI choreography): bind to kanban/forge overlay projection")
        println()

        // 9. Show factory
        println("9. Factory")
        println("   - OperationChoreographies.create(): OperationChoreography")
        println()

        println("✅ OpenAPI Choreography Demo Complete!")
    }
}