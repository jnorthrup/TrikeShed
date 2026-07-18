package borg.trikeshed.dag

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

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

    /**
     * Causal graph node fact — pairs a deterministic causal node with the
     * blackboard DAG. Produced by [borg.trikeshed.dag.ReteCausalBridge.project]
     * from a [BlackboardEvent.NodePlanning] event.
     */
    data class NodeFact(
        val nodeId: String,
        val boardId: String,
        val opId: String,
        val opVersion: String,
        val causalKey: String,
    ) : ReteFact() {
        override val factId: String = "node:$boardId:$nodeId"
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

// ==================== IN-MEMORY FABRIC ====================

/**
 * DAG node — a blackboard event plus its parent links in the DAG.
 *
 * @property position ordinal position in the DAG (insertion order)
 * @property event the blackboard event held at this node
 * @property parents positions of DAG nodes this node causally follows;
 *           for events sharing `(className, methodName)` the immediate prior
 *           event on that classfile coordinate is the dominant parent
 */
data class DagNode(
    val position: Int,
    val event: BlackboardEvent,
    val parents: IntArray,
) {
    /** Children positions in the DAG; mutable so the fabric can keep the
     *  parent/child relation symmetric without rebuilding the array. */
    val children: MutableList<Int> = mutableListOf()

    override fun equals(other: Any?): Boolean =
        this === other || (other is DagNode && other.position == position)

    override fun hashCode(): Int = position
}

/**
 * Real DAG fabric — events are published as nodes into a directed acyclic
 * graph keyed by classfile coordinate lineage plus causal timestamp.
 *
 * Edges are deterministic:
 *   - a node inherits the previous node on its `(className, methodName)` lineage
 *     (the last event observed on that classfile pointcut) as its dominant parent;
 *   - it also inherits the most recent node across all lineages whose timestamp
 *     is `<=` its own timestamp, so chronological DAG order matches causal order.
 *
 * This is the Stage-12 "DAG fabric for the blackboard classfile event system"
 * the create() factory returns — not a flat event list.
 *
 * Stage-12 TODO_* methods on other interfaces remain; this closes the create() hollow
 * with a graph-aware fabric that still satisfies the existing publish/subscribe/
 * getEvents contract.
 */
class InMemoryBlackboardFabric : BlackboardFabric {
    private val nodes = mutableListOf<DagNode>()
    private val byLineage = mutableMapOf<Pair<String, String>, Int>()  // (className, methodName) -> last node position
    private val handlers = mutableListOf<Pair<String, (BlackboardEvent) -> Unit>>()

    /** DAG node series (insertion order). */
    val dag: Series<DagNode>
        get() = nodes.size j { nodes[it] }

    /** Number of DAG nodes currently held. */
    val size: Int get() = nodes.size

    /** Root nodes — nodes with no parents (DAG sources). */
    val roots: Series<DagNode>
        get() {
            val r = nodes.filter { it.parents.isEmpty() }
            return r.size j { r[it] }
        }

    /** Parents of a given DAG position, as a Series. */
    fun parentsOf(position: Int): Series<DagNode> {
        val node = nodes[position]
        val ps = node.parents.map { nodes[it] }
        return ps.size j { ps[it] }
    }

    /** Children of a given DAG position, as a Series. */
    fun childrenOf(position: Int): Series<DagNode> {
        val cs = nodes[position].children.map { nodes[it] }
        return cs.size j { cs[it] }
    }

    /** Topologically-ordered events (post-order traversal) — the DAG's causal spine. */
    fun topologicalEvents(): Series<BlackboardEvent> {
        val ordered = ArrayList<BlackboardEvent>(nodes.size)
        val visited = IntArray(nodes.size)
        fun dfs(pos: Int) {
            if (visited[pos] != 0) return
            visited[pos] = 1
            val node = nodes[pos]
            for (p in node.parents) dfs(p)
            ordered.add(node.event)
        }
        for (i in nodes.indices) dfs(i)
        return ordered.size j { ordered[it] }
    }

    override fun publish(event: BlackboardEvent) {
        val position = nodes.size
        val coord = event.coordinate
        val parents = mutableListOf<Int>()

        // Dominant parent: previous event on the same (className, methodName) lineage.
        val lineageKey = coord.className to coord.methodName
        byLineage[lineageKey]?.let { parents.add(it) }

        // Causal parent: the most recent node with timestamp <= this event's timestamp.
        // Linear scan is fine for in-memory; the DAG gives O(1) for lineage queries.
        var causalParent = -1
        var causalParentTs = Long.MIN_VALUE
        for (i in nodes.indices) {
            val ts = nodes[i].event.timestamp
            if (ts <= coord.timestamp && ts >= causalParentTs) {
                causalParentTs = ts
                causalParent = i
            }
        }
        if (causalParent >= 0 && causalParent != parents.lastOrNull()) {
            parents.add(causalParent)
        }

        val parentsArr = parents.toIntArray()
        val node = DagNode(position = position, event = event, parents = parentsArr)
        nodes.add(node)
        for (p in parentsArr) nodes[p].children.add(position)
        byLineage[lineageKey] = position

        val kind = event::class.simpleName ?: "*"
        handlers.filter { it.first == "*" || it.first == kind }.forEach { it.second(event) }
    }

    override fun TODO_subscribe(eventType: String, handler: (BlackboardEvent) -> Unit): Subscription {
        val entry = eventType to handler
        handlers.add(entry)
        val subId = "sub-${handlers.size}-${eventType}"
        return object : Subscription {
            override val id: String = subId
            override val eventType: String = eventType
            override fun unsubscribe() {
                handlers.remove(entry)
            }
        }
    }

    override fun TODO_getEvents(from: DagCoordinate, to: DagCoordinate): Series<BlackboardEvent> {
        val lo = minOf(from.timestamp, to.timestamp)
        val hi = maxOf(from.timestamp, to.timestamp)
        val matched = nodes.filter { it.event.timestamp in lo..hi }.map { it.event }
        return matched.size j { matched[it] }
    }

    override fun TODO_projectToUserSignals(event: BlackboardEvent): Series<Any> =
        1 j { event as Any }
}

// ==================== FACTORY ====================

/**
 * Factory for blackboard fabric.
 */
object BlackboardFabrics {
    /** Create a new DAG-backed blackboard fabric for the classfile event system. */
    fun create(): BlackboardFabric = InMemoryBlackboardFabric()
}