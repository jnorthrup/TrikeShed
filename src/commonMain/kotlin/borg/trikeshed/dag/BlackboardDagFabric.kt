package borg.trikeshed.dag

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.Series

/**
 * Blackboard and Classfile DAG Fabric — event fabric for the entire system.
 * 
 * Stage 12: Blackboard and Classfile DAG Fabric
 * 
 * This is the overarching pointcutting fabric that coordinates:
 * - Classfile events
 * - Cursor facet transitions
 * - Rete production activations
 * - Graph node planning
 * - User-signal emissions
 * 
 * @see TODO.md Stage 12
 */

// ==================== DAG COORDINATE ====================

/**
 * DAG coordinate — position in the blackboard classfile DAG.
 * 
 * This is the primary event fabric coordinate used throughout the system.
 */
data class DagCoordinate(
    val className: String,
    val methodName: String,
    val bytecodeOffset: Int,
    val timestamp: Long,
    val threadId: Long
)

// ==================== BLACKBOARD EVENT ====================

/**
 * Blackboard event — an event in the blackboard DAG.
 */
sealed class BlackboardEvent {
    abstract val coordinate: DagCoordinate
    abstract val timestamp: Long
    
    /** Classfile load event. */
    data class ClassLoad(
        override val coordinate: DagCoordinate,
        val className: String,
        val classLoader: String
    ) : BlackboardEvent() {
        override val timestamp: Long get() = coordinate.timestamp
    }
    
    /** Method enter event. */
    data class MethodEnter(
        override val coordinate: DagCoordinate,
        val className: String,
        val methodName: String,
        val descriptor: String
    ) : BlackboardEvent() {
        override val timestamp: Long get() = coordinate.timestamp
    }
    
    /** Method exit event. */
    data class MethodExit(
        override val coordinate: DagCoordinate,
        val className: String,
        val methodName: String,
        val descriptor: String,
        val returnValue: Any?
    ) : BlackboardEvent() {
        override val timestamp: Long get() = coordinate.timestamp
    }
    
    /** Field access event. */
    data class FieldAccess(
        override val coordinate: DagCoordinate,
        val className: String,
        val fieldName: String,
        val fieldType: String,
        val value: Any?
    ) : BlackboardEvent() {
        override val timestamp: Long get() = coordinate.timestamp
    }
    
    /** Cursor facet transition event. */
    data class FacetTransition(
        override val coordinate: DagCoordinate,
        val fromCursor: Cursor,
        val toCursor: Cursor,
        val transitionType: FacetTransitionType
    ) : BlackboardEvent() {
        override val timestamp: Long get() = coordinate.timestamp
    }
    
    /** Rete production activation event. */
    data class ProductionActivation(
        override val coordinate: DagCoordinate,
        val productionName: String,
        val matchedFacts: Series<Any>
    ) : BlackboardEvent() {
        override val timestamp: Long get() = coordinate.timestamp
    }
    
    /** Graph node planning event. */
    data class NodePlanning(
        override val coordinate: DagCoordinate,
        val boardId: String,
        val nodeId: String,
        val overlays: Series<Any>
    ) : BlackboardEvent() {
        override val timestamp: Long get() = coordinate.timestamp
    }
    
    /** User-signal emission event. */
    data class UserSignalEmission(
        override val coordinate: DagCoordinate,
        val signalType: String,
        val signalData: Any?
    ) : BlackboardEvent() {
        override val timestamp: Long get() = coordinate.timestamp
    }
}

/**
 * Type of facet transition.
 */
enum class FacetTransitionType {
    LOGIC,
    COMPUTATION,
    NOTIFICATION,
    COUPLING,
    LAYOUT_HINT,
    DAG_COORDINATE,
    WTK_HINT
}

// ==================== POINTCUT COORDINATE ====================

/**
 * Classfile pointcut coordinate — where in the classfile to intercept.
 */
data class PointcutCoord(
    val className: String,
    val methodName: String,
    val descriptor: String,
    val insertionPoint: BlackboardInsertionPoint
)

/**
 * Insertion point for blackboard pointcuts.
 */
enum class BlackboardInsertionPoint {
    ON_CLASS_LOAD,
    BEFORE_METHOD,
    AFTER_METHOD,
    ON_FIELD_READ,
    ON_FIELD_WRITE
}

// ==================== VM OBJECT HARNESSING ====================

/**
 * VM object handle — reference to an object in the VM.
 */
interface VmObjectHandle {
    val id: String
    val className: String
    val value: Any?
}

/**
 * VM object harness — insert/extract objects into/from the VM.
 */
interface VmHarness {
    /**
     * Create a handle for a Kotlin object.
     */
    fun createHandle(kotlinObject: Any): VmObjectHandle
    
    /**
     * Get the Kotlin object from a handle.
     */
    fun getObject(handle: VmObjectHandle): Any?
    
    /**
     * Insert a TrikeShed object into the VM.
     */
    fun TODO_insertIntoVm(handle: VmObjectHandle, trikeShedObject: Any)
    
    /**
     * Extract a TrikeShed object from the VM.
     */
    fun TODO_extractFromVm(handle: VmObjectHandle): Any
}

// ==================== RETE FACTS ====================

/**
 * Rete fact — a fact in the Rete network.
 */
sealed class ReteFact {
    abstract val factId: String
    
    data class BoardFact(
        val boardId: String,
        val boardName: String
    ) : ReteFact() {
        override val factId: String = "board:$boardId"
    }
    
    data class CardFact(
        val cardId: String,
        val boardId: String,
        val columnId: String,
        val label: String
    ) : ReteFact() {
        override val factId: String = "card:$cardId"
    }
    
    data class DependencyFact(
        val fromCardId: String,
        val toCardId: String
    ) : ReteFact() {
        override val factId: String = "dep:$fromCardId->$toCardId"
    }
    
    data class OverlayFact(
        val overlayId: String,
        val nodeId: String,
        val label: String,
        val principal: String
    ) : ReteFact() {
        override val factId: String = "overlay:$overlayId"
    }
    
    data class DagFact(
        val coordinate: DagCoordinate,
        val event: BlackboardEvent
    ) : ReteFact() {
        override val factId: String = "dag:${coordinate.className}@${coordinate.bytecodeOffset}"
    }
}

// ==================== RETE PROJECTION ====================

/**
 * Rete projection — project facts from DAG events.
 */
interface ReteProjection {
    /**
     * Project a DAG event into Rete facts.
     */
    fun TODO_projectFromDagEvent(event: BlackboardEvent): Series<ReteFact>
    
    /**
     * Project Rete facts into overlay facets.
     */
    fun TODO_projectToOverlayFacets(facts: Series<ReteFact>): Series<Any>
    
    /**
     * Get active productions.
     */
    fun TODO_getActiveProductions(): Series<String>
}

// ==================== ACL GATING ====================

/**
 * ACL gate — controls visibility of DAG overlay.
 */
interface AclGate {
    /**
     * Check if a principal can see an overlay.
     */
    fun TODO_canSeeOverlay(principal: String, overlayId: String): Boolean
    
    /**
     * Check if a principal can see a DAG coordinate.
     */
    fun TODO_canSeeDagCoordinate(principal: String, coordinate: DagCoordinate): Boolean
    
    /**
     * Filter overlays by principal.
     */
    fun TODO_filterOverlays(principal: String, overlays: Series<Any>): Series<Any>
}

// ==================== BLACKBOARD FABRIC ====================

/**
 * Blackboard fabric — the main event fabric.
 */
interface BlackboardFabric {
    /**
     * Publish an event to the blackboard.
     */
    fun publish(event: BlackboardEvent)
    
    /**
     * Subscribe to events of a specific type.
     */
    fun TODO_subscribe(eventType: String, handler: (BlackboardEvent) -> Unit): Subscription
    
    /**
     * Get events within a coordinate range.
     */
    fun TODO_getEvents(from: DagCoordinate, to: DagCoordinate): Series<BlackboardEvent>
    
    /**
     * Project DAG events to user-signals.
     */
    fun TODO_projectToUserSignals(event: BlackboardEvent): Series<Any>
}

// ==================== SUBSCRIPTION ====================

/**
 * Subscription to blackboard events.
 */
interface Subscription {
    val id: String
    val eventType: String
    
    fun unsubscribe()
}

// ==================== GRAPH NODE PLANNING ====================

/**
 * Graph node planning — plan nodes from blackboard events.
 */
interface GraphPlanning {
    /**
     * Plan nodes from a DAG event.
     */
    fun TODO_planNodes(event: BlackboardEvent): Series<String>
    
    /**
     * Get planned nodes for a board.
     */
    fun TODO_getPlannedNodes(boardId: String): Series<String>
    
    /**
     * Apply overlays to planned nodes.
     */
    fun TODO_applyOverlays(boardId: String, nodes: Series<String>, overlays: Series<Any>): Series<String>
}

// ==================== CURSOR FACET TRANSITION ====================

/**
 * Cursor facet transition handler.
 */
interface CursorFacetTransition {
    /**
     * Handle a facet transition.
     */
    fun TODO_handleTransition(from: Cursor, to: Cursor, type: FacetTransitionType): Cursor
    
    /**
     * Get the current cursor with all facets.
     */
    fun TODO_getCurrentCursor(): Cursor
    
    /**
     * Get the facet series for a cursor.
     */
    fun TODO_getFacets(cursor: Cursor): Series<Any>
}

// ==================== FACTORY ====================

/**
 * Factory for blackboard fabric.
 */
object BlackboardFabrics {
    /**
     * Create a new blackboard fabric.
     */
    fun create(): BlackboardFabric = TODO("BlackboardFabrics.create: implement in Stage 12")
}