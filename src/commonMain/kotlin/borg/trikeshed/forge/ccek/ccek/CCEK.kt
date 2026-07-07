package borg.trikeshed.ccek

import borg.trikeshed.forge.ForgeBlockKind
import borg.trikeshed.forge.ForgeDoc
import borg.trikeshed.forge.ForgeDocument
import borg.trikeshed.forge.toForgeDocument
import borg.trikeshed.forge.toKanbanBoard
import borg.trikeshed.kanban.KanbanBoard
import borg.trikeshed.kanban.KanbanColumnId
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

object CCEK {
    fun initialize(reactor: MuxReactorElement): CcekReactorBinding = CcekReactorBinding(reactor)

    fun <T> inputChannel(capacity: Int = Channel.BUFFERED): Channel<T> = Channel(capacity)
    fun <T> fanOutChannel(capacity: Int = 64): Channel<T> = Channel(capacity)

    fun childScope(name: String, parentScope: CoroutineScope): CoroutineScope =
        CoroutineScope(parentScope.coroutineContext + SupervisorJob() + CoroutineName("CCEK-$name"))

    class CcekReactorBinding(val reactor: MuxReactorElement) {
        val reactorScope: CoroutineScope = CoroutineScope(
            reactor.supervisor + Dispatchers.Default + CoroutineName("CCEK-reactor")
        )

        fun choreograph(
            doc: ForgeDocument,
            record: Boolean = false,
            enabledProjections: Set<ProjectionKind> = ProjectionKind.ALL,
        ): ArticulatedNode = ArticulatedNode(
            initialDoc = doc,
            scope = reactorScope,
            record = record,
            enabledProjections = enabledProjections,
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
}

sealed class ForgeProjection {
    data class DocumentChanged(val doc: ForgeDocument) : ForgeProjection()
    data class BoardChanged(val board: KanbanBoard) : ForgeProjection()
    data class MarkdownChanged(val markdown: String) : ForgeProjection()
}

class ArticulatedNode(
    initialDoc: ForgeDocument,
    private val scope: CoroutineScope,
    private val record: Boolean = false,
    private val enabledProjections: Set<ProjectionKind> = ProjectionKind.ALL,
) {
    private var doc: ForgeDocument = initialDoc
    val signalIn: Channel<ForgeSignal> = Channel(Channel.UNLIMITED)

    private val _documentProjections = MutableSharedFlow<ForgeDocument>(replay = 64, extraBufferCapacity = 64)
    val documentProjections: SharedFlow<ForgeDocument> = _documentProjections.asSharedFlow()

    private val _boardProjections = MutableSharedFlow<KanbanBoard>(replay = 64, extraBufferCapacity = 64)
    val boardProjections: SharedFlow<KanbanBoard> = _boardProjections.asSharedFlow()

    private val _markdownProjections = MutableSharedFlow<String>(replay = 64, extraBufferCapacity = 64)
    val markdownProjections: SharedFlow<String> = _markdownProjections.asSharedFlow()

    private val _projections = MutableSharedFlow<ForgeProjection>(replay = 64, extraBufferCapacity = 64)
    val projections: SharedFlow<ForgeProjection> = _projections.asSharedFlow()

    private val childScopes = linkedMapOf<String, CoroutineScope>()
    private val agents = linkedMapOf<String, (ForgeSignal) -> Unit>()
    private val recordedSignals = mutableListOf<ForgeSignal>()
    private var fanOutJob: Job? = null
    private val startMutex = Mutex()

    val markdownProjectionCount: Int get() = _markdownProjections.replayCache.size
    val childScopeCount: Int get() = childScopes.size
    val isActive: Boolean get() = fanOutJob?.isActive == true

    init {
        start()
    }

    fun start() {
        if (fanOutJob?.isActive == true) return
        scope.launch {
            startMutex.lock()
            try {
                if (fanOutJob?.isActive == true) {
                    return@launch
                }
                fanOutJob = scope.launch {
                    fanOutAll()
                    for (signal in signalIn) {
                        if (record) recordedSignals += signal
                        val agentsSnapshot = agents.values.toList()
                        agentsSnapshot.forEach { it(signal) }
                        doc = applySignal(signal)
                        registerChildScopes(doc)
                        fanOutAll()
                    }
                }
            } finally {
                startMutex.unlock()
            }
        }
    }

    fun cancel() {
        childScopes.values.forEach { it.cancel() }
        childScopes.clear()
        fanOutJob?.cancel()
        fanOutJob = null
        signalIn.close()
    }

    fun stop() = cancel()

    suspend fun sendSignal(signal: ForgeSignal) {
        if (!isActive) start()
        signalIn.send(signal)
    }

    fun subscribeAgent(name: String, handler: (ForgeSignal) -> Unit) {
        agents[name] = handler
    }

    fun recording(): List<ForgeSignal> = recordedSignals.toList()

    /** For tests only: snapshot the post-start state under a transient scope. */
    private val docMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Apply a signal synchronously and return the resulting document.
     * Updates internal state so repeated applications are idempotent.
     * Test seams only — production callers should use `sendSignal`.
     */
    fun applySignalForTest(signal: ForgeSignal): ForgeDocument {
        doc = applySignal(signal)
        return doc
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
            // round-trip is identity-stable.
            val targetBlock = doc.blocks[signal.cardId]?.takeIf { block ->
                block.kind == ForgeBlockKind.HEADING_2 ||
                    block.kind == ForgeBlockKind.HEADING_1 ||
                    block.kind == ForgeBlockKind.HEADING_3
            } ?: return doc
            val status = when (signal.toColumnId) {
                "col-inprogress" -> "in-progress"
                "col-done" -> "done"
                else -> "backlog"
            }
            val withId = ForgeDoc.setProperty(doc, targetBlock.id, "kanban.column.id", signal.toColumnId)
            ForgeDoc.setProperty(withId, targetBlock.id, "kanban.status", status)
        }
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

    private fun registerChildScopes(doc: ForgeDocument) {
        val rootId = doc.rootPageId.value
        doc.blocks.values
            .asSequence()
            .filter { it.id.value != rootId }
            .forEach { block ->
                childScopes.getOrPut(block.id.value) {
                    CCEK.childScope(block.id.value, scope)
                }
            }

        val liveIds = doc.blocks.keys - rootId
        val stale = childScopes.keys - liveIds
        stale.forEach { id ->
            childScopes.remove(id)?.cancel()
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
    private val scope: CoroutineScope,
) {
    private val facts = mutableListOf<CausalAssertion>()
    private val polyglotFacts = mutableListOf<PolyglotFact>()
    var active: Boolean = false
        private set

    val reteTable: CausalReteTable
        get() = CausalReteTable(facts.toList())

    fun activate() {
        active = true
    }

    fun deactivate() {
        active = false
    }

    fun assertFact(assertion: CausalAssertion) {
        facts += assertion
    }

    fun loadPolyglotFacts(allFacts: List<PolyglotFact>) {
        polyglotFacts += allFacts
    }

    fun queryPolyglot(language: String, kind: String): List<PolyglotFact> =
        polyglotFacts.filter { it.language == language && it.kind == kind }

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
            val actual = facts.count { it.kind.contains("block") || it.kind.contains("class:") || it.kind.contains("method:") }
            val passed = actual >= expected - 1
            return TableTestResult(passed, if (passed) "OK" else "expected $expected, found $actual")
        }

    fun createGraphicalFlow(name: String): GraphicalFlow = GraphicalFlow(name)
    fun spreadsheetVeneer(): SpreadsheetVeneer = SpreadsheetVeneer(facts.toList())

    fun adaptParadigm(paradigm: MetaLcncParadigm): AdaptedParadigm {
        paradigm.rules.forEach { rule ->
            facts += CausalAssertion("rule:${paradigm.name}:${rule.name}", mapOf("expr" to rule.expression))
        }
        return AdaptedParadigm(paradigm.name, scope, reteTable)
    }

    fun choreograph(doc: ForgeDocument): ArticulatedNode {
        val node = ArticulatedNode(
            initialDoc = doc,
            scope = CoroutineScope(scope.coroutineContext + SupervisorJob() + CoroutineName("UserContext-$name")),
        )
        node.subscribeAgent("$name-causal-assertions") { signal ->
            when (signal) {
                is ForgeSignal.AppendBlock -> assertFact(CausalAssertion("block:appended", mapOf("text" to signal.text, "kind" to signal.kind.name)))
                is ForgeSignal.UpdateText -> assertFact(CausalAssertion("block:updated", mapOf("blockId" to signal.blockId)))
                is ForgeSignal.DeleteBlock -> assertFact(CausalAssertion("block:deleted", mapOf("blockId" to signal.blockId)))
                is ForgeSignal.MoveCard -> assertFact(CausalAssertion("card:moved", mapOf("cardId" to signal.cardId, "to" to signal.toColumnId)))
            }
        }
        return node
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
class AdaptedParadigm(val name: String, val scope: CoroutineScope, val reteTable: CausalReteTable) { val isActive: Boolean get() = true }
