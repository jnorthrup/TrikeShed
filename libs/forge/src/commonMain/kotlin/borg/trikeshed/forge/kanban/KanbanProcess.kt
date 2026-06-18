@file:Suppress("unused")

package borg.trikeshed.forge.kanban

import borg.trikeshed.forge.platform.platformUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable

/**
 * Kanban Process — DSL factory builder edition.
 *
 * CouchDB/ISAM/miniduck/btrfs integration as reified inline builder nodes.
 *
 * Usage:
 *   val process = KanbanProcess { 
 *       boardEngine(boardEngine)
 *       couch { url("http://localhost:5984").db("kanban") }
 *       isam { path(".tspy/isam/kanban") }
 *       miniduck { reducer("kanban/cascade") }
 *   }
 *
 * Each subsystem is a builder node. The process composes them into a typed job graph.
 */

// ---------------------------------------------------------------------------
// Core entity
// ---------------------------------------------------------------------------

@Serializable
enum class ProcessState { IDLE, LOADING, READY, RUNNING, ERROR }

// ---------------------------------------------------------------------------
// Subsystem gateway interfaces (pure, testable)
// ---------------------------------------------------------------------------

interface CouchGateway {
    suspend fun put(key: String, cbor: ByteArray)
    suspend fun get(key: String): ByteArray?
    suspend fun getAllKeys(pattern: String): Series<String>
    suspend fun updateView(name: String, view: String)
    suspend fun queryView(name: String): MapReduceStats
}

interface IsamGateway {
    suspend fun appendPointcut(key: String, wire: ByteArray)
    suspend fun scanPointcuts(prefix: String): Series<ByteArray>
}

interface MiniduckGateway {
    suspend fun reduce(reducer: String, input: String): String
}

interface BtrfsGateway {
    suspend fun snapshot(path: String): String
    suspend fun restore(snapshotId: String)
}

// ---------------------------------------------------------------------------
// Data types
// ---------------------------------------------------------------------------

@Serializable
data class MapReduceStats(
    val totalCards: Int,
    val todo: Int, val doing: Int, val done: Int, val blocked: Int,
    val wipByColumn: Map<String, Int>,
    val utilizationByAgent: Map<String, Double>,
    val dependencyDepth: Int,
    val timestampMs: Long,
)

@Serializable
data class KanbanPointcut(
    val timestampMs: Long,
    val tick: Int,
    val spawned: Int,
    val reclaimed: Int,
    val cardsTotal: Int,
    val doingCount: Int,
    val doneCount: Int,
) {
    fun toWireProto(): ByteArray = kotlinx.serialization.json.Json.encodeToByteArray(this)
    companion object {
        fun fromWireProto(bytes: ByteArray): KanbanPointcut = kotlinx.serialization.json.Json.decodeFromByteArray(KanbanPointcut.serializer(), bytes)
    }
}

// ---------------------------------------------------------------------------
// Reified inline builder DSL
// ---------------------------------------------------------------------------

@JvmInline
value class KanbanProcessConfig private constructor(
    val boardEngine: KanbanBoardEngine,
    val couch: CouchGateway?,
    val isam: IsamGateway?,
    val miniduck: MiniduckGateway?,
    val btrfs: BtrfsGateway?,
) {
    companion object {
        inline fun build(crossinline block: KanbanProcessBuilder.() -> Unit): KanbanProcessConfig =
            KanbanProcessBuilder().apply(block).build()
    }
}

class KanbanProcessBuilder {
    var boardEngine: KanbanBoardEngine? = null
    var couch: CouchGateway? = null
    var isam: IsamGateway? = null
    var miniduck: MiniduckGateway? = null
    var btrfs: BtrfsGateway? = null

    inline fun boardEngine(crossinline config: KanbanBoardEngine.() -> Unit = {}) {
        val engine = KanbanBoardEngine().apply(config)
        boardEngine = engine
    }

    inline fun couch(crossinline config: CouchConfigBuilder.() -> Unit = {}): CouchGateway {
        val gateway = CouchConfigBuilder().apply(config).build()
        couch = gateway
        return gateway
    }

    inline fun isam(crossinline config: IsamConfigBuilder.() -> Unit = {}): IsamGateway {
        val gateway = IsamConfigBuilder().apply(config).build()
        isam = gateway
        return gateway
    }

    inline fun miniduck(crossinline config: MiniduckConfigBuilder.() -> Unit = {}): MiniduckGateway {
        val gateway = MiniduckConfigBuilder().apply(config).build()
        miniduck = gateway
        return gateway
    }

    inline fun btrfs(crossinline config: BtrfsConfigBuilder.() -> Unit = {}): BtrfsGateway {
        val gateway = BtrfsConfigBuilder().apply(config).build()
        btrfs = gateway
        return gateway
    }

    fun build(): KanbanProcessConfig {
        require(boardEngine != null) { "boardEngine is required" }
        return KanbanProcessConfig(boardEngine!!, couch, isam, miniduck, btrfs)
    }
}

// ---------------------------------------------------------------------------
// Subsystem config builders (reified inline, no reflection)
// ---------------------------------------------------------------------------

@JvmInline
value class CouchConfigBuilder(
    var url: String = "http://localhost:5984",
    var db: String = "kanban",
    var username: String? = null,
    var password: String? = null,
) {
    fun build(): CouchGateway = object : CouchGateway {
        override suspend fun put(key: String, cbor: ByteArray) { /* HTTP PUT */ }
        override suspend fun get(key: String): ByteArray? { return null }
        override suspend fun getAllKeys(pattern: String): Series<String> = emptySeries()
        override suspend fun updateView(name: String, view: String) { /* mango view */ }
        override suspend fun queryView(name: String): MapReduceStats = MapReduceStats(0,0,0,0,0,emptyMap(),emptyMap(),0,0)
    }
}

@JvmInline
value class IsamConfigBuilder(
    var path: String = ".tspy/isam/kanban",
    var pageSize: Int = 4096,
) {
    fun build(): IsamGateway = object : IsamGateway {
        override suspend fun appendPointcut(key: String, wire: ByteArray) { /* ISAM append */ }
        override suspend fun scanPointcuts(prefix: String): Series<ByteArray> = emptySeries()
    }
}

@JvmInline
value class MiniduckConfigBuilder(
    var reducer: String = "kanban/cascade",
    var workers: Int = 4,
) {
    fun build(): MiniduckGateway = object : MiniduckGateway {
        override suspend fun reduce(reducer: String, input: String): String = "{}"
    }
}

@JvmInline
value class BtrfsConfigBuilder(
    var mountPoint: String = "/",
    var subvolume: String = ".tspy/btrfs",
) {
    fun build(): BtrfsGateway = object : BtrfsGateway {
        override suspend fun snapshot(path: String): String = ""
        override suspend fun restore(snapshotId: String) { /* btrfs restore */ }
    }
}

// ---------------------------------------------------------------------------
// Process — composes subsystems into a typed job graph
// ---------------------------------------------------------------------------

class KanbanProcess(
    private val config: KanbanProcessConfig,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private val _state = MutableStateFlow(ProcessState.IDLE)
    val state: StateFlow<ProcessState> = _state.asStateFlow()

    suspend fun initialize() {
        _state.value = ProcessState.LOADING
        try {
            val cardJsons = config.couch?.getAllKeys("kanban:card:*") ?: emptySeries()
            val cards = cardJsons.mapNotNull { json ->
                try { kotlinx.serialization.json.Json.decodeFromString(BoardCard.serializer(), json) } catch (_: Exception) { null }
            }.toList()
            cards.forEach { config.boardEngine.addCard(it) }

            _state.value = ProcessState.READY
        } catch (e: Exception) { _state.value = ProcessState.ERROR(e.message ?: "init failed") }
    }

    fun startTickLoop(scope: CoroutineScope = this.scope, policy: DispatchPolicy = parsePolicy(SEED_POLICY)): Job = scope.launch {
        _state.value = ProcessState.RUNNING
        while (isActive) {
            val tickStart = platformUtils.currentTimeMillis()
            val dispatchResult = config.boardEngine.tick()

            // 1. Persist cards to couch (CBOR)
            config.couch?.let { couch ->
                config.boardEngine.cards.value.forEach { card ->
                    couch.put("kanban:card:${card.id}", serializeToCbor(card))
                }
                // 2. Update mapreduce views
                couch.updateView("kanban/stats", buildStatsView(config.boardEngine.cards.value))
                couch.updateView("kanban/by-column", buildColumnView(config.boardEngine.cards.value))
                couch.updateView("kanban/by-agent", buildAgentView(config.boardEngine.cards.value))
                couch.updateView("kanban/dependencies", buildDependencyView(config.boardEngine.cards.value))
            }

            // 3. ISAM pointcut (wireproto)
            config.isam?.let { isam ->
                val pointcut = KanbanPointcut(
                    timestampMs = tickStart,
                    tick = dispatchResult.tick,
                    spawned = dispatchResult.spawned,
                    reclaimed = dispatchResult.reclaimed,
                    cardsTotal = config.boardEngine.cards.value.size,
                    doingCount = config.boardEngine.cards.value.count { it.column == BoardColumn.DOING },
                    doneCount = config.boardEngine.cards.value.count { it.column == BoardColumn.DONE },
                )
                isam.appendPointcut("kanban:tick:${dispatchResult.tick}", pointcut.toWireProto())
            }

            // 4. Miniduck cascade reducer
            if ((dispatchResult.spawned > 0 || dispatchResult.reclaimed > 0) && config.miniduck != null) {
                scope.launch {
                    config.miniduck?.reduce("kanban/cascade", buildCascadeInput(config.boardEngine.cards.value))
                }
            }

            delay(policy.tickIntervalMs)
        }
    }

    suspend fun getMapReduceStats(): MapReduceStats? = config.couch?.queryView("kanban/stats")
    suspend fun getPointcutHistory(prefix: String = "kanban:tick"): Series<KanbanPointcut> =
        config.isam?.scanPointcuts(prefix).map { KanbanPointcut.fromWireProto(it) } ?: emptySeries()
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun serializeToCbor(card: BoardCard): ByteArray = kotlinx.serialization.json.Json.encodeToByteArray(card)

private fun buildStatsView(cards: List<BoardCard>): String {
    val stats = MapReduceStats(
        totalCards = cards.size,
        todo = cards.count { it.column == BoardColumn.TODO },
        doing = cards.count { it.column == BoardColumn.DOING },
        done = cards.count { it.column == BoardColumn.DONE },
        blocked = cards.count { it.column == BoardColumn.BLOCKED },
        wipByColumn = BoardColumn.entries.associate { it.name to cards.count { c -> c.column == it } },
        utilizationByAgent = cards.filter { it.assignee != null }.groupBy { it.assignee!! }.mapValues { (_, cs) -> cs.size.toDouble() / cards.size },
        dependencyDepth = cards.maxOfOrNull { it.dependencies.size } ?: 0,
        timestampMs = platformUtils.currentTimeMillis(),
    )
    return kotlinx.serialization.json.Json.encodeToString(stats)
}

private fun buildColumnView(cards: List<BoardCard>): String = kotlinx.serialization.json.Json.encodeToString(
    BoardColumn.entries.map { col -> mapOf("column" to col.name, "cards" to cards.filter { it.column == col }.map { it.id }) }
)
private fun buildAgentView(cards: List<BoardCard>): String = kotlinx.serialization.json.Json.encodeToString(
    cards.filter { it.assignee != null }.groupBy { it.assignee!! }.mapValues { (_, cs) -> cs.map { it.id } }
)
private fun buildDependencyView(cards: List<BoardCard>): String = kotlinx.serialization.json.Json.encodeToString(
    cards.flatMap { card -> card.dependencies.map { dep -> Pair(dep, card.id) } }
)
private fun buildCascadeInput(cards: List<BoardCard>): String = kotlinx.serialization.json.Json.encodeToString(
    mapOf("cards" to cards.map { card ->
        mapOf("id" to card.id, "column" to card.column.name, "assignee" to card.assignee, "priority" to card.priority, "deps" to card.dependencies)
    })
)