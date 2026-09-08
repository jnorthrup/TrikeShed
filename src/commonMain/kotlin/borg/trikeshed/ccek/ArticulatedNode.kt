package borg.trikeshed.ccek

import borg.trikeshed.context.ElementState
import borg.trikeshed.forge.ForgeBlockId
import borg.trikeshed.forge.ForgeBlockKind
import borg.trikeshed.forge.ForgeDocument
import borg.trikeshed.forge.ForgeDoc
import borg.trikeshed.forge.toKanbanBoard
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.KanbanColumnId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

typealias ForgeDocNode = ArticulatedNode

class ForgeSignalChannel internal constructor(
    private val owner: ArticulatedNode,
    private val delegate: Channel<ForgeSignal>,
) {
    suspend fun send(signal: ForgeSignal) {
        owner.requireAccepting()
        delegate.send(signal)
    }

    fun trySend(signal: ForgeSignal): ChannelResult<Unit> =
        if (owner.acceptsSignals) delegate.trySend(signal) else {
            delegate.close()
            delegate.trySend(signal)
        }

    fun close(cause: Throwable? = null): Boolean {
        owner.closeAdmission(cause)
        return delegate.close(cause)
    }
}

class ArticulatedNode(
    initialDoc: ForgeDocument,
    scope: CoroutineScope,
    private val record: Boolean = false,
    enabledProjections: Set<ProjectionKind> = ProjectionKind.ALL,
    private val maxConcurrency: Int = 8,
    autoStart: Boolean = true,
    fanoutSubscribers: List<CoroutineContext.Element> = emptyList(),
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ArticulatedNode>

    override val key: CoroutineContext.Key<*> get() = Key

    private val parentContext = scope.coroutineContext
    val supervisor: CompletableJob = SupervisorJob(parentContext[Job])
    private val executionScope = CoroutineScope(parentContext + supervisor + this)
    private val input = Channel<ForgeSignal>(capacity = 256)
    val signalIn: ForgeSignalChannel = ForgeSignalChannel(this, input)
    private val projectionSet = enabledProjections.toSet()
    private val recorded = ArrayList<ForgeSignal>()
    private val agents = LinkedHashMap<String, suspend (ForgeSignal) -> Unit>()
    private val downstream = ArrayList<CoroutineContext.Element>(fanoutSubscribers)
    private var document: ForgeDocument = initialDoc
    private var consumer: Job? = null
    private var terminalFailure: Throwable? = null

    private val _projections = MutableSharedFlow<ForgeProjection>(replay = 1, extraBufferCapacity = 256)
    val projections = _projections.asSharedFlow()
    val documentProjections = MutableSharedFlow<ForgeDocument>(replay = 1, extraBufferCapacity = 16)
    val boardProjections = MutableSharedFlow<borg.trikeshed.kanban.KanbanBoard>(replay = 1, extraBufferCapacity = 16)
    val markdownProjections = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 16)
    val agentStatus = MutableSharedFlow<AgentStatusEvent>(replay = 1, extraBufferCapacity = 256)

    var lifecycleState: ElementState = ElementState.CREATED
        private set

    val isActive: Boolean
        get() = lifecycleState == ElementState.ACTIVE && supervisor.isActive

    val childScopeCount: Int
        get() = supervisor.children.count()

    val agentCount: Int
        get() = if (lifecycleState == ElementState.CLOSED) 0 else agents.size

    val markdownProjectionCount: Int
        get() = markdownProjections.replayCache.size

    val fanoutSubscribers: List<CoroutineContext.Element>
        get() = downstream.toList()

    internal val acceptsSignals: Boolean
        get() = lifecycleState < ElementState.DRAINING && supervisor.isActive

    init {
        require(maxConcurrency in 1..256) { "maxConcurrency must be in 1..256" }
        supervisor.invokeOnCompletion { cause ->
            if (cause != null) {
                terminalFailure = cause
                input.close(cause)
            }
            lifecycleState = ElementState.CLOSED
            agents.clear()
            downstream.clear()
        }
        if (autoStart) start()
    }

    fun open() {
        if (lifecycleState == ElementState.CLOSED) return
        if (lifecycleState == ElementState.CREATED) lifecycleState = ElementState.OPEN
    }

    fun start() {
        if (lifecycleState >= ElementState.DRAINING) return
        if (consumer != null) return
        open()
        lifecycleState = ElementState.ACTIVE
        consumer = executionScope.launch(CoroutineName("ccek-node")) {
            try {
                for (signal in input) process(signal)
            } catch (t: Throwable) {
                terminalFailure = t
                throw t
            } finally {
                lifecycleState = ElementState.CLOSED
                supervisor.complete()
            }
        }
    }

    fun stop() {
        cancel()
    }

    fun cancel() {
        closeAdmission(null)
        if (consumer == null) supervisor.complete()
    }

    fun abort(cause: CancellationException = CancellationException("ArticulatedNode aborted")) {
        closeAdmission(cause)
        supervisor.cancel(cause)
    }

    suspend fun drain() {
        closeAdmission(null)
        if (consumer == null) supervisor.complete()
        supervisor.join()
        terminalFailure?.let { if (it is CancellationException) throw it }
    }

    suspend fun close() {
        drain()
    }

    fun sendSignal(signal: ForgeSignal) {
        requireAccepting()
        check(signalIn.trySend(signal).isSuccess) { "ArticulatedNode signal buffer is full" }
    }

    fun subscribeAgent(name: String, callback: suspend (ForgeSignal) -> Unit) {
        requireAccepting()
        if (lifecycleState == ElementState.CREATED) start()
        agents[name] = callback
    }

    fun recording(): List<ForgeSignal> = recorded.toList()

    internal fun requireAccepting() {
        check(acceptsSignals) { "ArticulatedNode is not accepting signals: $lifecycleState" }
    }

    internal fun closeAdmission(cause: Throwable?) {
        if (lifecycleState < ElementState.DRAINING) lifecycleState = ElementState.DRAINING
        input.close(cause)
    }

    private suspend fun process(signal: ForgeSignal) {
        if (record) recorded += signal
        document = applySignal(document, signal)
        emitProjections(document)
        fanOut(signal)
    }

    private suspend fun fanOut(signal: ForgeSignal) {
        val snapshot = agents.entries.map { AgentSubscription(it.key, it.value) }
        if (snapshot.isEmpty()) return
        var cancellation: CancellationException? = null
        val fanout = executionScope.launch(CoroutineName("ccek-fanout")) {
            val work = Channel<AgentSubscription>(capacity = snapshot.size)
            val workers = List(minOf(maxConcurrency, snapshot.size)) {
                launch(CoroutineName("ccek-agent")) {
                    try {
                        for (subscription in work) deliver(subscription, signal)
                    } catch (t: CancellationException) {
                        cancellation = t
                        work.close(t)
                        throw t
                    }
                }
            }
            try {
                snapshot.forEach { work.send(it) }
                work.close()
                workers.joinAll()
            } finally {
                work.close()
            }
        }
        fanout.join()
        cancellation?.let { throw it }
    }

    private suspend fun deliver(subscription: AgentSubscription, signal: ForgeSignal) {
        agentStatus.emit(AgentStatusEvent.Started(subscription.name, signal))
        try {
            subscription.callback(signal)
            agentStatus.emit(AgentStatusEvent.Completed(subscription.name))
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            agentStatus.emit(AgentStatusEvent.Failed(subscription.name, signal, t))
            _projections.emit(ForgeProjection.Error(subscription.name, t))
        }
    }

    private suspend fun emitProjections(doc: ForgeDocument) {
        if (ProjectionKind.DOCUMENT in projectionSet) {
            documentProjections.emit(doc)
            _projections.emit(ForgeProjection.DocumentChanged(doc))
        }
        if (ProjectionKind.BOARD in projectionSet) {
            val board = doc.toKanbanBoard()
            boardProjections.emit(board)
            _projections.emit(ForgeProjection.BoardChanged(board))
        }
        if (ProjectionKind.MARKDOWN in projectionSet) {
            val markdown = ForgeDoc.renderMarkdown(doc)
            markdownProjections.emit(markdown)
            _projections.emit(ForgeProjection.MarkdownChanged(markdown))
        }
    }

    private fun applySignal(doc: ForgeDocument, signal: ForgeSignal): ForgeDocument = when (signal) {
        is ForgeSignal.AppendBlock -> doc.appendBlock(signal.kind, signal.text, signal.properties)
        is ForgeSignal.UpdateText -> doc.block(ForgeBlockId(signal.blockId))
            ?.let { doc.updateText(it.id, signal.text) } ?: doc
        is ForgeSignal.DeleteBlock -> doc.block(ForgeBlockId(signal.blockId))
            ?.let { doc.deleteBlock(it.id) } ?: doc
        is ForgeSignal.MoveCard -> doc.moveCard(KanbanCardId(signal.cardId), KanbanColumnId(signal.toColumnId))
        is ForgeSignal.Continue -> doc.appendBlock(
            ForgeBlockKind.TEXT,
            "continue ${signal.cardId}",
            mapOf("ccek.verb" to "continue", "cardId" to signal.cardId),
        )
        is ForgeSignal.Repeat -> doc.appendBlock(
            ForgeBlockKind.TEXT,
            "repeat ${signal.cardId}",
            mapOf("ccek.verb" to "repeat", "cardId" to signal.cardId, "edgeId" to signal.edgeId),
        )
        is ForgeSignal.Abort -> doc.appendBlock(
            ForgeBlockKind.TEXT,
            "abort ${signal.cardId}: ${signal.reason}",
            mapOf("ccek.verb" to "abort", "cardId" to signal.cardId, "reason" to signal.reason),
        )
        is ForgeSignal.Fork -> doc.appendBlock(
            ForgeBlockKind.TEXT,
            "fork ${signal.cardId} -> ${signal.targetLane}",
            mapOf("ccek.verb" to "fork", "cardId" to signal.cardId, "targetLane" to signal.targetLane),
        )
        is ForgeSignal.Join -> doc.appendBlock(
            ForgeBlockKind.TEXT,
            "join ${signal.cardId}",
            mapOf(
                "ccek.verb" to "join",
                "cardId" to signal.cardId,
                "group" to signal.group,
                "requiredBranches" to signal.requiredBranches.toString(),
            ),
        )
        is ForgeSignal.Vote -> doc.appendBlock(
            ForgeBlockKind.TEXT,
            "vote ${signal.cardId}: ${signal.verdict}",
            mapOf("ccek.verb" to "vote", "cardId" to signal.cardId, "verdict" to signal.verdict),
        )
    }

    private data class AgentSubscription(
        val name: String,
        val callback: suspend (ForgeSignal) -> Unit,
    )
}
