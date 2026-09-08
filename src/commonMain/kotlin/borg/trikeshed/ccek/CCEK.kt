package borg.trikeshed.ccek

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.forge.ForgeBlockKind
import borg.trikeshed.forge.ForgeDoc
import borg.trikeshed.forge.ForgeDocument

import borg.trikeshed.forge.toKanbanBoard
import borg.trikeshed.kanban.KanbanBoard
import borg.trikeshed.isam.synchronizedLock
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlin.coroutines.CoroutineContext

/**
 * CCEK — **CoroutineContext.Element.Key**: named for the exact Kotlin type
 * path (`kotlin.coroutines.CoroutineContext` / `.Element` / `.Key`). The
 * substrate IS that mechanism — services, reactors, and channels are context
 * Elements addressed by typed Keys, composed with `+`, resolved with
 * `[Key]`, removed with `minusKey` — which is what gives every CCEK citizen
 * block compatibility with none of lexical scoping's inclusion/exclusion
 * quirks. Began as a WAM adaptation; it outgrew that origin.
 */
object CCEK {
    fun initialize(context: CoroutineContext): CcekReactorBinding = CcekReactorBinding(context)

    fun <T> inputChannel(capacity: Int = Channel.BUFFERED): Channel<T> = Channel(capacity)
    fun <T> fanOutChannel(capacity: Int = 64): Channel<T> = Channel(capacity)

    fun childScope(name: String, parentScope: CoroutineScope): CoroutineScope {
        val parent = parentScope.coroutineContext[Job]
        return CoroutineScope(
            parentScope.coroutineContext + SupervisorJob(parent) + CoroutineName("CCEK-$name")
        )
    }

    class CcekReactorBinding(context: CoroutineContext) {
        val reactor: MuxReactorElement = requireNotNull(context[MuxReactorElement.Key]) {
            "CCEK binding requires MuxReactorElement in its coroutine context"
        }

        /** Retain every supplied element; the reactor owns the binding's lifetime. */
        val reactorScope: CoroutineScope = CoroutineScope(
            context + reactor.supervisor + CoroutineName("CCEK-reactor")
        )

        fun choreograph(
            doc: ForgeDocument,
            record: Boolean = false,
            enabledProjections: Set<ProjectionKind> = ProjectionKind.ALL,
            maxConcurrency: Int = 8,
        ): ArticulatedNode = ArticulatedNode(
            initialDoc = doc,
            scope = reactorScope,
            record = record,
            enabledProjections = enabledProjections,
            maxConcurrency = maxConcurrency,
        )

        fun createUserContext(name: String): UserContext = UserContext(name, reactorScope)
    }
}

enum class ProjectionKind {
    DOCUMENT,
    BOARD,
    MARKDOWN;

    companion object {
        val ALL: Set<ProjectionKind> = entries.toSet()
    }
}

sealed class ForgeSignal {
    data class AppendBlock(
        val kind: ForgeBlockKind,
        val text: String,
        val properties: Map<String, String> = emptyMap(),
    ) : ForgeSignal()

    data class UpdateText(val blockId: String, val text: String) : ForgeSignal()
    data class DeleteBlock(val blockId: String) : ForgeSignal()
    data class MoveCard(val cardId: String, val toColumnId: String) : ForgeSignal()

    // W4.1: control verbs so the FSM speaks CCEK's language, not a parallel one.
    data class Continue(val cardId: String) : ForgeSignal()
    data class Repeat(val cardId: String, val edgeId: String) : ForgeSignal()
    data class Abort(val cardId: String, val reason: String) : ForgeSignal()
    data class Fork(val cardId: String, val targetLane: String) : ForgeSignal()
    data class Join(val cardId: String, val group: String, val requiredBranches: Int) : ForgeSignal()
    data class Vote(val cardId: String, val verdict: String, val tally: Map<String, Int> = emptyMap()) : ForgeSignal()
}

sealed class ForgeProjection {
    data class DocumentChanged(val doc: ForgeDocument) : ForgeProjection()
    data class BoardChanged(val board: KanbanBoard) : ForgeProjection()
    data class MarkdownChanged(val markdown: String) : ForgeProjection()
    data class Error(val cause: Throwable) : ForgeProjection()
}

sealed class AgentStatusEvent {
    data class Started(val agentName: String, val signal: ForgeSignal) : AgentStatusEvent()
    data class Completed(val agentName: String) : AgentStatusEvent()
    data class Failed(val agentName: String, val cause: Throwable) : AgentStatusEvent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ArticulatedNode(
    initialDoc: ForgeDocument,
    scope: CoroutineScope,
    private val record: Boolean = false,
    private val enabledProjections: Set<ProjectionKind> = ProjectionKind.ALL,
    maxConcurrency: Int = 8,
    autoStart: Boolean = true,
) : AsyncContextElement(parentJob = scope.coroutineContext[Job].also {
    require(maxConcurrency > 0) { "maxConcurrency must be positive" }
}) {
    companion object Key : CoroutineContext.Key<ArticulatedNode>
    override val key: CoroutineContext.Key<ArticulatedNode> get() = Key

    private val gate = Any()
    val executionScope: CoroutineScope = CoroutineScope(
        scope.coroutineContext + supervisor + this + CoroutineName("CCEK-node")
    )
    override val lifecycleState: ElementState get() = synchronizedLock(gate) { state }

    private var doc: ForgeDocument = initialDoc
    val signalIn: Channel<ForgeSignal> = Channel(Channel.BUFFERED)

    private val _documentProjections = MutableSharedFlow<ForgeDocument>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val documentProjections: SharedFlow<ForgeDocument> = _documentProjections.asSharedFlow()

    private val _boardProjections = MutableSharedFlow<KanbanBoard>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val boardProjections: SharedFlow<KanbanBoard> = _boardProjections.asSharedFlow()

    private val _markdownProjections = MutableSharedFlow<String>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val markdownProjections: SharedFlow<String> = _markdownProjections.asSharedFlow()

    private val _projections = MutableSharedFlow<ForgeProjection>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val projections: SharedFlow<ForgeProjection> = _projections.asSharedFlow()

    private val childScopes = linkedMapOf<String, CoroutineScope>()
    private class Agent(
        val subscriber: AsyncContextElement?,
        val handler: suspend (ForgeSignal) -> Unit,
    )
    private val agents = linkedMapOf<String, Agent>()
    override val fanoutSubscribers: List<AsyncContextElement>
        get() = synchronizedLock(gate) { agents.values.mapNotNull { it.subscriber }.distinct() }
    val agentCount: Int get() = synchronizedLock(gate) { agents.size }
    private val agentSemaphore = Semaphore(maxConcurrency)
    private val _agentStatus = MutableSharedFlow<AgentStatusEvent>(replay = 1, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val agentStatus: SharedFlow<AgentStatusEvent> = _agentStatus.asSharedFlow()
    private val recordedSignals = mutableListOf<ForgeSignal>()
    private var fanOutJob: Job? = null
    private var completionCause: Throwable? = null

    val markdownProjectionCount: Int get() = _markdownProjections.replayCache.size
    val childScopeCount: Int get() = synchronizedLock(gate) { childScopes.size }
    val isActive: Boolean get() = synchronizedLock(gate) {
        state < ElementState.DRAINING && fanOutJob?.isActive == true && supervisor.isActive
    }

    init {
        signalIn.invokeOnClose {
            val job = synchronizedLock(gate) {
                val running = if (supervisor.isActive) startLocked() else null
                if (state < ElementState.DRAINING) state = ElementState.DRAINING
                running
            }
            job?.start()
        }
        supervisor.invokeOnCompletion { cause ->
            signalIn.cancel()
            synchronizedLock(gate) {
                childScopes.clear()
                agents.clear()
                completionCause = cause
                state = ElementState.CLOSED
            }
        }
        if (autoStart) start()
    }

    override suspend fun open() = start()

    fun start() {
        val job = synchronizedLock(gate) {
            if (state >= ElementState.DRAINING || !supervisor.isActive) return
            if (state == ElementState.CREATED) state = ElementState.OPEN
            startLocked()
        }
        job.start()
    }

    private fun startLocked(): Job = fanOutJob ?: executionScope.launch(
        CoroutineExceptionHandler { _, cause -> _projections.tryEmit(ForgeProjection.Error(cause)) },
        start = CoroutineStart.LAZY,
    ) {
        fanOutAll()
        for (signal in signalIn) {
            val snapshot = synchronizedLock(gate) {
                if (state == ElementState.OPEN) state = ElementState.ACTIVE
                if (record) recordedSignals += signal
                agents.toMap()
            }
            supervisorScope {
                for ((name, agent) in snapshot) {
                    // Acquire before launch: queued input cannot create unbounded waiting jobs.
                    agentSemaphore.acquire()
                    launch(CoroutineExceptionHandler { _, cause ->
                        _projections.tryEmit(ForgeProjection.Error(cause))
                        _agentStatus.tryEmit(AgentStatusEvent.Failed(name, cause))
                    }) {
                        _agentStatus.emit(AgentStatusEvent.Started(name, signal))
                        agent.handler(signal)
                        _agentStatus.emit(AgentStatusEvent.Completed(name))
                    }.invokeOnCompletion { cause ->
                        agentSemaphore.release()
                        if (cause is CancellationException) cancel(cause)
                    }
                }
            }
            synchronizedLock(gate) { doc = applySignal(signal) }
            registerChildScopes(doc)
            fanOutAll()
        }
    }.also { job ->
        fanOutJob = job
        job.invokeOnCompletion { cause ->
            signalIn.close(cause)
            val children = synchronizedLock(gate) {
                if (state < ElementState.DRAINING) state = ElementState.DRAINING
                childScopes.values.toList()
            }
            children.forEach { (it.coroutineContext[Job] as CompletableJob).complete() }
            if (cause == null) supervisor.complete()
            else supervisor.cancel(CancellationException("ArticulatedNode execution failed", cause))
        }
    }

    /** Reject new input now; queued signals and their fanout finish asynchronously. */
    fun cancel() {
        val job = synchronizedLock(gate) {
            if (state >= ElementState.DRAINING) return
            if (state == ElementState.CREATED) state = ElementState.OPEN
            val running = startLocked()
            state = ElementState.DRAINING
            running
        }
        signalIn.close()
        job.start()
    }

    fun stop() = cancel()

    override suspend fun drain() {
        cancel()
        supervisor.join()
        synchronizedLock(gate) { completionCause }?.let { throw it }
    }

    override suspend fun close() = drain()

    /** Immediate cancellation, including when the caller's parent is already cancelled. */
    fun abort(cause: CancellationException? = null) {
        synchronizedLock(gate) {
            if (state < ElementState.DRAINING) state = ElementState.DRAINING
        }
        signalIn.cancel(cause)
        supervisor.cancel(cause)
    }

    suspend fun sendSignal(signal: ForgeSignal) {
        start()
        synchronizedLock(gate) {
            check(state < ElementState.DRAINING && supervisor.isActive) { "ArticulatedNode is not accepting signals" }
        }
        signalIn.send(signal)
    }

    fun subscribeAgent(name: String, handler: suspend (ForgeSignal) -> Unit) {
        subscribe(name, Agent(null, handler))
    }

    fun subscribeAgent(name: String, subscriber: AsyncContextElement, handler: suspend (ForgeSignal) -> Unit) {
        subscribe(name, Agent(subscriber, handler))
    }

    private fun subscribe(name: String, agent: Agent) = synchronizedLock(gate) {
        check(state < ElementState.DRAINING && supervisor.isActive) { "ArticulatedNode is not accepting subscribers" }
        if (state == ElementState.CREATED) state = ElementState.OPEN
        agents[name] = agent
        state = ElementState.ACTIVE
    }

    fun recording(): List<ForgeSignal> = synchronizedLock(gate) { recordedSignals.toList() }

    /**
     * Apply a signal synchronously and return the resulting document.
     * Updates internal state so repeated applications are idempotent.
     * Test seams only — production callers should use `sendSignal`.
     */
    fun applySignalForTest(signal: ForgeSignal): ForgeDocument = synchronizedLock(gate) {
        doc = applySignal(signal)
        doc
    }

    private fun applySignal(signal: ForgeSignal): ForgeDocument = when (signal) {
        is ForgeSignal.AppendBlock -> ForgeDoc.appendBlock(doc, doc.rootPageId, signal.kind, signal.text, signal.properties)
        is ForgeSignal.UpdateText -> {
            val blockId = doc.blocks[signal.blockId]?.id ?: return doc
            ForgeDoc.updateText(doc, blockId, signal.text)
        }
        is ForgeSignal.DeleteBlock -> {
            val blockId = doc.blocks[signal.blockId]?.id ?: return doc
            ForgeDoc.deleteBlock(doc, blockId)
        }
        is ForgeSignal.MoveCard -> {
            // In-place property edit, NOT a board round-trip: the projection
            // layer has a fixed 3-column schema (backlog/inprog/done), so any
            // custom column id (e.g. col-b) would be lost on re-projection.
            // We update the underlying heading block's `kanban.column.id`
            // (preferred) and `kanban.status` (legacy heuristic) so the
            // round-trip is identity-stable. The status fold is BoardTaxonomy's
            // — one author for col-* AND the seven wire ids; a private switch
            // here let every canonical id silently fall to "backlog".
            val targetBlock = doc.blocks[signal.cardId]?.takeIf { block ->
                block.kind == ForgeBlockKind.HEADING_2 ||
                    block.kind == ForgeBlockKind.HEADING_1 ||
                    block.kind == ForgeBlockKind.HEADING_3
            } ?: return doc
            val status = borg.trikeshed.kanban.BoardCol.statusFor(signal.toColumnId)
            val withId = ForgeDoc.setProperty(doc, targetBlock.id, "kanban.column.id", signal.toColumnId)
            ForgeDoc.setProperty(withId, targetBlock.id, "kanban.status", status)
        }
        // W4.1: control verbs are board-level signals, not document edits.
        // They carry no document mutation — they fan out to subscribed agents
        // and projections without touching the ForgeDocument. Return doc as-is.
        is ForgeSignal.Continue, is ForgeSignal.Repeat, is ForgeSignal.Abort,
        is ForgeSignal.Fork, is ForgeSignal.Join, is ForgeSignal.Vote -> doc
    }

    private suspend fun fanOutAll() {
        if (ProjectionKind.DOCUMENT in enabledProjections) {
            _documentProjections.emit(doc)
            _projections.emit(ForgeProjection.DocumentChanged(doc))
        }
        if (ProjectionKind.BOARD in enabledProjections) {
            val board = doc.toKanbanBoard()
            _boardProjections.emit(board)
            _projections.emit(ForgeProjection.BoardChanged(board))
        }
        if (ProjectionKind.MARKDOWN in enabledProjections) {
            val markdown = ForgeDoc.renderMarkdown(doc)
            _markdownProjections.emit(markdown)
            _projections.emit(ForgeProjection.MarkdownChanged(markdown))
        }
    }

    private fun registerChildScopes(doc: ForgeDocument) = synchronizedLock(gate) {
        val rootId = doc.rootPageId.value
        // Bolt: avoid Sequence overhead before terminal operations by iterating directly
        doc.blocks.values
            .forEach { block ->
                if (block.id.value != rootId) {
                    childScopes.getOrPut(block.id.value) {
                        CCEK.childScope(block.id.value, executionScope)
                    }
                }
            }

        val liveIds = doc.blocks.keys - rootId
        val stale = childScopes.keys - liveIds
        stale.forEach { id ->
            (childScopes.remove(id)?.coroutineContext?.get(Job) as? CompletableJob)?.complete()
        }
    }
}

class ForgeDocNode(
    initialDoc: ForgeDocument,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("ForgeDocNode")),
) {
    private val node = ArticulatedNode(initialDoc = initialDoc, scope = scope)
    val signalIn: Channel<ForgeSignal> get() = node.signalIn
    val projections: SharedFlow<ForgeProjection> get() = node.projections
    fun start() = node.start()
    fun stop() = node.stop()
}

class UserContext(
    val name: String,
    scope: CoroutineScope,
    /** Provenance link — the context this one forked from, or null for a root. */
    val parentId: String? = null,
) : AsyncContextElement(parentJob = scope.coroutineContext[Job]) {
    companion object Key : CoroutineContext.Key<UserContext>
    override val key: CoroutineContext.Key<UserContext> get() = Key

    private val gate = Any()
    val executionScope: CoroutineScope = CoroutineScope(
        scope.coroutineContext + supervisor + this + CoroutineName("UserContext-$name")
    )
    private val children = mutableListOf<AsyncContextElement>()
    override val lifecycleState: ElementState get() = synchronizedLock(gate) { state }
    override val fanoutSubscribers: List<AsyncContextElement>
        get() = synchronizedLock(gate) { children.filter { !it.supervisor.isCompleted } }
    private val facts = mutableListOf<CausalAssertion>()
    private val polyglotFacts = mutableListOf<PolyglotFact>()
    private var activated = false
    private var completionCause: Throwable? = null
    val active: Boolean get() = synchronizedLock(gate) {
        activated && state < ElementState.DRAINING && supervisor.isActive
    }

    init {
        supervisor.invokeOnCompletion { cause ->
            synchronizedLock(gate) {
                completionCause = cause
                state = ElementState.CLOSED
                activated = false
                children.clear()
            }
        }
    }

    private fun admit() {
        check(state < ElementState.DRAINING && supervisor.isActive) { "UserContext is not accepting work" }
        if (state == ElementState.CREATED) state = ElementState.OPEN
    }

    override suspend fun open() {
        synchronizedLock(gate) {
            if (state == ElementState.CREATED && supervisor.isActive) state = ElementState.OPEN
        }
    }

    fun cancel() {
        val owned = synchronizedLock(gate) {
            if (state >= ElementState.DRAINING) return
            if (state == ElementState.CREATED) state = ElementState.OPEN
            state = ElementState.DRAINING
            activated = false
            children.toList()
        }
        owned.forEach { child ->
            when (child) {
                is ArticulatedNode -> child.cancel()
                is UserContext -> child.cancel()
            }
        }
        supervisor.complete()
    }

    fun stop() = cancel()

    override suspend fun drain() {
        cancel()
        supervisor.join()
        synchronizedLock(gate) { completionCause }?.let { throw it }
    }

    override suspend fun close() = drain()

    fun abort(cause: CancellationException? = null) {
        val owned = synchronizedLock(gate) {
            if (state < ElementState.DRAINING) state = ElementState.DRAINING
            activated = false
            children.toList()
        }
        owned.forEach { child ->
            when (child) {
                is ArticulatedNode -> child.abort(cause)
                is UserContext -> child.abort(cause)
            }
        }
        supervisor.cancel(cause)
    }

    /**
     * Stable identity for CAS persistence under `contexts/<id>`.
     *
     * The old `ctx-$name` collided across forks of the same role name — two
     * forks of `legal` shared one CAS path and one overwrote the other. The
     * id is minted once per instance (secure hex); `name` stays the human
     * label and is carried separately in [toDocument].
     */
    val id: String = modelmux.defaultSecureIdGenerator.generateHexId("ctx", 16)

    val reteTable: CausalReteTable
        get() = synchronizedLock(gate) { CausalReteTable(facts.toList()) }

    /** Read-only view of asserted facts — forks copy these. */
    val factCount: Int get() = synchronizedLock(gate) { facts.size }

    fun activate() = synchronizedLock(gate) {
        admit()
        state = ElementState.ACTIVE
        activated = true
    }

    fun deactivate() = synchronizedLock(gate) {
        activated = false
    }

    fun assertFact(assertion: CausalAssertion) = synchronizedLock(gate) {
        admit()
        state = ElementState.ACTIVE
        facts += assertion
    }

    // Already admitted node fanout may finish recording facts while the context drains.
    private fun recordFact(assertion: CausalAssertion) = synchronizedLock(gate) {
        check(state != ElementState.CLOSED) { "UserContext is closed" }
        facts += assertion
    }

    /**
     * W3.1: fan-out is N forks; a role change is a fork with a new role.
     * The child copies this context's facts and links back via [parentId] —
     * provenance without shared mutable state.
     */
    fun fork(newRole: String): UserContext = synchronizedLock(gate) {
        admit()
        val child = UserContext(newRole, executionScope, parentId = id)
        // Copy-on-fork: the child starts with the parent's epistemic state.
        facts.forEach { child.assertFact(it) }
        polyglotFacts.forEach { child.loadPolyglotFacts(listOf(it)) }
        children += child
        state = ElementState.ACTIVE
        child
    }

    /**
     * CAS persistence under `contexts/<id>` — the SAME document plane as
     * `lcnc/<name>` (see LcncProgramConfix.saveProgramToOroboros).
     */
    fun toDocument(): Map<String, Any?> = synchronizedLock(gate) { linkedMapOf(
        "id" to id,
        "name" to name,
        "parentId" to parentId,
        "active" to active,
        "facts" to facts.map { linkedMapOf("kind" to it.kind, "fields" to it.fields) },
    ) }

    fun loadPolyglotFacts(allFacts: List<PolyglotFact>) = synchronizedLock(gate) {
        admit()
        state = ElementState.ACTIVE
        polyglotFacts += allFacts
    }

    fun queryPolyglot(language: String, kind: String): List<PolyglotFact> =
        synchronizedLock(gate) { polyglotFacts.filter { it.language == language && it.kind == kind } }

    fun predictModel(modelName: String, inputs: Map<String, Any>): Map<String, Any> {
        val count = inputs["count"]?.toString()?.toIntOrNull() ?: 0
        return when (inputs["method"]?.toString()) {
            "appendBlock" -> mapOf("model" to modelName, "expectedBlocks" to count + 1)
            else -> mapOf("model" to modelName)
        }
    }

    fun tableTest(prediction: Map<String, Any>): TableTestResult {
            val expected = prediction["expectedBlocks"] as? Int
                ?: return TableTestResult(false, "no expectedBlocks in prediction")
            // Count all facts that represent blocks (class:, method:, block:)
            val actual = synchronizedLock(gate) {
                facts.count { it.kind.contains("block") || it.kind.contains("class:") || it.kind.contains("method:") }
            }
            val passed = actual >= expected - 1
            return TableTestResult(passed, if (passed) "OK" else "expected $expected, found $actual")
        }

    fun createGraphicalFlow(name: String): GraphicalFlow = GraphicalFlow(name)
    fun spreadsheetVeneer(): SpreadsheetVeneer = synchronizedLock(gate) { SpreadsheetVeneer(facts.toList()) }

    fun adaptParadigm(paradigm: MetaLcncParadigm): AdaptedParadigm = synchronizedLock(gate) {
        admit()
        paradigm.rules.forEach { rule ->
            facts += CausalAssertion("rule:${paradigm.name}:${rule.name}", mapOf("expr" to rule.expression))
        }
        state = ElementState.ACTIVE
        AdaptedParadigm(paradigm.name, executionScope, reteTable)
    }

    fun choreograph(doc: ForgeDocument): ArticulatedNode = synchronizedLock(gate) {
        admit()
        val node = ArticulatedNode(
            initialDoc = doc,
            scope = executionScope,
        )
        node.subscribeAgent("$name-causal-assertions", this) { signal ->
            when (signal) {
                is ForgeSignal.AppendBlock -> recordFact(CausalAssertion("block:appended", mapOf("text" to signal.text, "kind" to signal.kind.name)))
                is ForgeSignal.UpdateText -> recordFact(CausalAssertion("block:updated", mapOf("blockId" to signal.blockId)))
                is ForgeSignal.DeleteBlock -> recordFact(CausalAssertion("block:deleted", mapOf("blockId" to signal.blockId)))
                is ForgeSignal.MoveCard -> recordFact(CausalAssertion("card:moved", mapOf("cardId" to signal.cardId, "to" to signal.toColumnId)))
                is ForgeSignal.Continue -> recordFact(CausalAssertion("card:continued", mapOf("cardId" to signal.cardId)))
                is ForgeSignal.Repeat -> recordFact(CausalAssertion("card:repeated", mapOf("cardId" to signal.cardId, "edgeId" to signal.edgeId)))
                is ForgeSignal.Abort -> recordFact(CausalAssertion("card:aborted", mapOf("cardId" to signal.cardId, "reason" to signal.reason)))
                is ForgeSignal.Fork -> recordFact(CausalAssertion("card:forked", mapOf("cardId" to signal.cardId, "targetLane" to signal.targetLane)))
                is ForgeSignal.Join -> recordFact(CausalAssertion("card:joined", mapOf("cardId" to signal.cardId, "group" to signal.group)))
                is ForgeSignal.Vote -> recordFact(CausalAssertion("card:voted", mapOf("cardId" to signal.cardId, "verdict" to signal.verdict)))
            }
        }
        children += node
        state = ElementState.ACTIVE
        node
    }
}

data class CausalAssertion(val kind: String, val fields: Map<String, Any>)
data class PolyglotFact(val language: String, val opcode: String, val target: String, val kind: String)

class CausalReteTable(private val facts: List<CausalAssertion>) {
    val rowCount: Int get() = facts.size
    fun containsFact(kind: String): Boolean = facts.any { it.kind == kind || it.kind.startsWith(kind) }
    fun query(kind: String): List<CausalAssertion> = facts.filter { it.kind == kind || it.kind.startsWith(kind) }
}

data class TableTestResult(val passed: Boolean, val evidence: String? = null)

class GraphicalFlow(val name: String) {
    private val blocks = mutableListOf<GraphicalBlock>()
    private val edges = mutableListOf<GraphicalEdge>()

    fun addBlock(block: GraphicalBlock) {
        blocks += block
    }

    fun connect(from: String, to: String) {
        edges += GraphicalEdge(from, to)
    }

    fun asCursor(): GraphicalCursor = GraphicalCursor(blocks.toList())
    fun edges(): List<GraphicalEdge> = edges.toList()
}

data class GraphicalBlock(val id: String, val label: String, val properties: Map<String, String>)
data class GraphicalEdge(val from: String, val to: String)
class GraphicalCursor(val blocks: List<GraphicalBlock>) { val size: Int get() = blocks.size }

class SpreadsheetVeneer(private val facts: List<CausalAssertion>) {
    val rowCount: Int get() = facts.size
    fun facet(column: String, value: String): List<CausalAssertion> = facts.filter { fact -> fact.fields[column]?.toString() == value }
}

data class MetaLcncParadigm(val name: String, val rules: List<LcncRule>)
data class LcncRule(val name: String, val expression: String)
class AdaptedParadigm(val name: String, val scope: CoroutineScope, val reteTable: CausalReteTable) {
    val isActive: Boolean get() = scope.isActive
}
