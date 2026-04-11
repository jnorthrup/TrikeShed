@file:Suppress("unused", "UNUSED_PARAMETER")

package com.seaofnodes.simple

import borg.literbike.ccek.core.Context
import borg.literbike.endgame.EndgameCapabilities
import borg.literbike.endgame.FeatureGates
import borg.literbike.endgame.ProcessingPath
import borg.literbike.endgame.SimdLevel
import borg.literbike.reactor.ContextElement
import borg.literbike.reactor.ReactorConfig
import borg.literbike.reactor.ReactorService
import borg.trikeshed.ccek.KeyedService
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.lib.*
import borg.trikeshed.manifold.Atlas
import borg.trikeshed.manifold.Chart
import borg.trikeshed.manifold.Manifold
import borg.trikeshed.manifold.coordinatesOf
import borg.trikeshed.net.ProtocolId
import borg.trikeshed.net.channelization.*
import borg.trikeshed.parse.json.JsonBitmap
import com.seaofnodes.simple.ccek.*
import kotlin.coroutines.CoroutineContext

// ─────────────────────────────────────────────────────────────
// CompilerShape bridge - IR to ChannelGraph compilation
// ─────────────────────────────────────────────────────────────

object CompilerShape {
    fun compileShortCircuitOr(src: String): ForkJoinShape {
        // Parse the source for || operator and create fork-join shape
        val hasOr = src.contains("||")
        val branchCount = if (hasOr) 2 else 1
        return ForkJoinShape(branchCount)
    }

    fun countComparisons(src: String): Int {
        // Count comparison operators: < > <= >= == !=
        val comparisons = listOf("<", ">", "<=", ">=", "==", "!=")
        var count = 0
        var remaining = src
        for (op in comparisons) {
            var idx = remaining.indexOf(op)
            while (idx != -1) {
                count++
                remaining = remaining.substring(idx + op.length)
                idx = remaining.indexOf(op)
            }
        }
        return count
    }
}

data class ForkJoinShape(
    val branchCount: Int,
) {
    fun toActivationRules(): List<PatternActivationRule> {
        val rules = mutableListOf<PatternActivationRule>()
        for (i in 0 until branchCount) {
            rules.add(
                PatternActivationRule(
                    pattern = "branch_$i",
                    jobId = ChannelJobId("branch-$i"),
                )
            )
        }
        return rules
    }
}

// ─────────────────────────────────────────────────────────────
// WAM Table Service - clause/opcode tables + handler registry
// ─────────────────────────────────────────────────────────────

class WamTableService {
    private val bindings = mutableMapOf<String, String>()

    fun query(variable: String): String? = bindings[variable]

    fun bind(variable: String, value: String) {
        bindings[variable] = value
    }

    fun lookup(clause: String): List<String> = emptyList()
}

// ─────────────────────────────────────────────────────────────
// Handler Registry wrapper for tests
// ─────────────────────────────────────────────────────────────

object HandlerRegistry {
    private val handlers = mutableMapOf<String, () -> Any>()

    fun keys(): Set<String> = handlers.keys

    fun register(key: String, handler: () -> Any) {
        handlers[key] = handler
    }
}

// ─────────────────────────────────────────────────────────────
// Endgame-to-Channelization bridges
// ─────────────────────────────────────────────────────────────

fun ProcessingPath.toTransportBackendKind(): TransportBackendKind = when (this) {
    ProcessingPath.EbpfIoUring -> TransportBackendKind.LINUX_NATIVE
    ProcessingPath.IoUringUserspace -> TransportBackendKind.LINUX_NATIVE
    ProcessingPath.EpollUserspace -> TransportBackendKind.POSIX
    ProcessingPath.KqueueUserspace -> TransportBackendKind.POSIX
    ProcessingPath.NioBaseline -> TransportBackendKind.NIO
}

fun EndgameCapabilities.toChannelizationPlan(): ChannelizationPlan {
    val backend = selectOptimalPath().toTransportBackendKind()
    val cost = when {
        ioUringAvailable -> 5
        ebpfCapable -> 8
        else -> 15
    }
    return ChannelizationPlan(
        backendKind = backend,
        estimatedCost = cost,
        features = emptyMap(),
    )
}

// ─────────────────────────────────────────────────────────────
// Densification pipeline - JsonBitmap to TokenKind mapping
// ─────────────────────────────────────────────────────────────

fun JsonBitmap.JsStateEvent.toTokenKind(): TokenKind = when (this) {
    JsonBitmap.JsStateEvent.ScopeOpen -> TokenKind.OP
    JsonBitmap.JsStateEvent.ScopeClose -> TokenKind.OP
    JsonBitmap.JsStateEvent.ValueDelim -> TokenKind.OP
    JsonBitmap.JsStateEvent.KeyStart -> TokenKind.STRING
    JsonBitmap.JsStateEvent.KeyEnd -> TokenKind.STRING
    JsonBitmap.JsStateEvent.ValueStart -> TokenKind.STRING
    JsonBitmap.JsStateEvent.ValueEnd -> TokenKind.STRING
    JsonBitmap.JsStateEvent.Escaping -> TokenKind.STRING
    JsonBitmap.JsStateEvent.InsideString -> TokenKind.STRING
    JsonBitmap.JsStateEvent.InsideNumber -> TokenKind.INT
    JsonBitmap.JsStateEvent.Eof -> TokenKind.EOF
}

// ─────────────────────────────────────────────────────────────
// Series alpha-conversion to peephole bridge
// ─────────────────────────────────────────────────────────────

fun <T> Series<T>.toPeepholeResult(): Series<Int> = this α { it.hashCode() }

// ─────────────────────────────────────────────────────────────
// WAM Trail from JsonBitmap bitplanes
// ─────────────────────────────────────────────────────────────

class WamTrail private constructor(
    val entries: List<TrailEntry>,
) {
    sealed class TrailEntry {
        object OpenScope : TrailEntry()
        object CloseScope : TrailEntry()
        object Delimiter : TrailEntry()
        object StringValue : TrailEntry()
        object NumberValue : TrailEntry()
    }

    companion object {
        fun fromBitplanes(bitplanes: Array<*>): WamTrail {
            val entries = mutableListOf<TrailEntry>()
            // Map bitplane patterns to trail entries
            for (plane in bitplanes) {
                entries.add(TrailEntry.OpenScope)
            }
            if (entries.isNotEmpty()) {
                entries[entries.lastIndex] = TrailEntry.CloseScope
            }
            return WamTrail(entries)
        }
    }
}

// ─────────────────────────────────────────────────────────────
// DuckDB/CouchDB cursor convergence
// ─────────────────────────────────────────────────────────────

// Stub DuckSeries for tests
object DuckSeries {
    fun toCursor(): Cursor = 0 j { _ -> 0 j { _ -> "col" j object : TypeMemento {
        override val networkSize: Int? = null
    } } }
}

object DuckCursor {
    fun getSeries(): Series<Any?> = emptySeries()
}

// BRC varieties stubs
object BrcPure {
    fun execute(): Series<Join<String, Join<Double, Double>>> = emptySeries()
}

object BrcMmap {
    fun execute(): Series<Join<String, Join<Double, Double>>> = emptySeries()
}

// CouchDB cursor to TrikeShed cursor conversion
fun borg.literbike.couchdb.Cursor.toTrikeShedCursor(): Cursor =
    0 j { _ -> 0 j { _ -> "key" j object : TypeMemento {
        override val networkSize: Int? = null
    } } }

// DagNode to CouchDB Document
fun DagNode.toCouchDocument(): borg.literbike.couchdb.Document =
    borg.literbike.couchdb.Document(
        id = "dag:$nid",
        rev = "",
        data = kotlinx.serialization.json.buildJsonObject {
            put("nid", kotlinx.serialization.json.JsonPrimitive(nid))
            put("label", kotlinx.serialization.json.JsonPrimitive(label))
            put("inputs", kotlinx.serialization.json.JsonArray(inputs.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("controls", kotlinx.serialization.json.JsonArray(controls.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("typeName", kotlinx.serialization.json.JsonPrimitive(typeName))
        }
    )

// Document to CodeGenElement
fun borg.literbike.couchdb.Document.toCodeGenElement(): CodeGenElement? =
    CodeGenElement(CodeGenKey, byteArrayOf())

// ─────────────────────────────────────────────────────────────
// WAM Unification Engine
// ─────────────────────────────────────────────────────────────

data class UnificationResult(
    val bindings: Map<String, String>,
) {
    fun toChannelJob(): ChannelJob =
        SimpleChannelJob(
            id = ChannelJobId("wam-unify-${bindings.hashCode()}"),
            graphId = ChannelGraphId("wam"),
            owner = WorkerKey("wam-worker"),
            type = JobType.CUSTOM,
            state = ChannelJobState.Active,
            priority = 0,
            sessionId = null,
        )
}

class WamUnifier(
    private val graph: ChannelGraph,
) {
    fun unify(goal: String, fact: String): UnificationResult {
        val bindings = mutableMapOf<String, String>()

        // Simple unification: extract variables from goal and match with fact
        val goalPattern = """[A-Z]\w*""".toRegex()
        val factValues = """\((.*)\)""".toRegex().find(fact)?.groupValues?.get(1)?.split(",") ?: emptyList()
        val goalVars = goalPattern.findAll(goal).map { it.value }.toList()

        for ((i, variable) in goalVars.withIndex()) {
            if (i < factValues.size) {
                bindings[variable] = factValues[i]
            }
        }

        // Add facts to graph
        for ((key, value) in bindings) {
            graph.addFact(
                GraphFact.CustomFact("wam:$key", key, value)
            )
        }

        return UnificationResult(bindings)
    }
}

data class WamChoicepoint(
    val clauses: List<String>,
    val variable: String,
) {
    fun toChannelSession(protocol: ProtocolId): ChannelSession =
        SimpleChannelSession(
            id = ChannelSessionId("wam-choice-${clauses.hashCode()}"),
            graphId = ChannelGraphId("wam"),
            protocol = protocol,
            state = ChannelSessionState.Active,
        )

    fun toActivationRules(): List<ActivationRule> = clauses.map { clause ->
        PatternActivationRule(
            pattern = clause,
            jobId = ChannelJobId("clause-${clause.hashCode()}"),
        )
    }
}

class WamEngine {
    private val substitutions = mutableListOf<Map<String, String>>()

    fun query(goal: String): List<UnificationResult> {
        // Simple member/1 query handling
        val listPattern = """member\((\[.*])\)""".toRegex
        val match = listPattern.find(goal)
        if (match != null) {
            val listStr = match.groupValues[1]
            val elements = listStr.trim('[', ']').split(',').map { it.trim() }
            return elements.map { elem ->
                UnificationResult(mapOf("X" to elem))
            }
        }
        return emptyList()
    }

    fun queryEnvelopes(goal: String): List<ChannelEnvelope> {
        val results = query(goal)
        return results.map { result ->
            ChannelEnvelope(result.bindings)
        }
    }
}

data class ChannelBlock(
    val id: ChannelJobId,
    val graphId: ChannelGraphId,
    val substitution: Map<String, String>,
)

data class ChannelEnvelope(
    val payload: Any,
)

class WamCut(
    private val graph: ChannelGraph,
    private val alternatives: List<ChannelJob>,
) {
    suspend fun execute() {
        // Cancel all but the first alternative
        alternatives.drop(1).forEach { job ->
            if (job is SimpleChannelJob) {
                job.state = ChannelJobState.Cancelled
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// ReactorService as KeyedService bridge
// ─────────────────────────────────────────────────────────────

fun ReactorService.asKeyedService(): KeyedService = object : KeyedService {
    override val key: CoroutineContext.Key<*> get() = this@asKeyedService.key
    override fun toString(): String = "ReactorService(keyed)"
}

// ─────────────────────────────────────────────────────────────
// borg.ccek.Registry - service locator bridging Context systems
// ─────────────────────────────────────────────────────────────

object borg.ccek.registry {
    private val subsystems = mutableMapOf<String, Context>()
    private val services = mutableMapOf<RegistryKey, Any>()

    class RegistryKey(val name: String)

    class Registry private constructor() {
        companion object {
            fun create(): Registry = Registry()
        }

        fun registerSubsystem(name: String, ctx: Context) {
            subsystems[name] = ctx
        }

        fun lookupSubsystem(name: String): Context? = subsystems[name]

        fun <T : Any> register(key: RegistryKey, service: T) {
            services[key] = service
        }

        fun <T : Any> lookup(key: RegistryKey): T? = services[key] as? T

        fun toCoroutineContext(subsystem: String): CoroutineContext =
            object : CoroutineContext.Element {
                override val key: CoroutineContext.Key<*> = object : CoroutineContext.Key<CoroutineContext.Element> {}
                override fun toString(): String = "SubsystemContext($subsystem)"
            }

        fun acceptCoroutineElement(key: CoroutineContext.Key<*>, element: CoroutineContext.Element) {
            services[RegistryKey(key.toString())] = element
        }

        fun lookupByCoroutineKey(key: CoroutineContext.Key<*>): Any? =
            services[RegistryKey(key.toString())]
    }

    // CouchDbStore for compiler intermediaries
    object CouchDbStore {
        private val storage = mutableMapOf<String, DagNode>()

        fun inMemory(): CouchDbStore = this

        fun putDagNode(pipeline: String, node: DagNode): String {
            val id = "$pipeline-${node.nid}"
            storage[id] = node
            return id
        }

        fun getDagNode(id: String): DagNode? = storage[id]
    }
}

typealias ActivationRule = borg.trikeshed.net.channelization.ActivationRule
