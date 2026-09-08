package borg.trikeshed.ccek

import borg.trikeshed.context.ElementState
import borg.trikeshed.forge.ForgeDocument
import borg.trikeshed.kanban.BoardCol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class CausalAssertion(
    val kind: String,
    val fields: Map<String, Any>,
)

class CausalReteTable(initial: List<CausalAssertion> = emptyList()) {
    private val rows = ArrayList<CausalAssertion>(initial)
    val rowCount: Int get() = rows.size

    fun assert(assertion: CausalAssertion) {
        rows += assertion
    }

    fun query(kind: String): List<CausalAssertion> =
        rows.filter { it.kind == kind || it.kind.startsWith(kind) }

    fun all(): List<CausalAssertion> = rows.toList()
}

data class PolyglotFact(
    val language: String,
    val opcode: String,
    val target: String,
    val kind: String,
)

data class PredictionTestResult(
    val passed: Boolean,
    val evidence: String?,
)

data class GraphicalBlock(
    val id: String,
    val label: String,
    val properties: Map<String, String> = emptyMap(),
)

data class GraphicalEdge(val from: String, val to: String)

data class GraphicalCursor(
    val blocks: List<GraphicalBlock>,
    val size: Int,
)

class GraphicalFlow(val name: String) {
    private val blockRows = ArrayList<GraphicalBlock>()
    private val edgeRows = ArrayList<GraphicalEdge>()

    fun addBlock(block: GraphicalBlock) {
        blockRows += block
    }

    fun connect(from: String, to: String) {
        edgeRows += GraphicalEdge(from, to)
    }

    fun edges(): List<GraphicalEdge> = edgeRows.toList()

    fun asCursor(): GraphicalCursor = GraphicalCursor(blockRows.toList(), blockRows.size)
}

class SpreadsheetVeneer(private val rows: () -> List<CausalAssertion>) {
    fun facet(column: String, value: String): List<CausalAssertion> =
        rows().filter { it.fields[column]?.toString() == value }
}

data class LcncRule(val name: String, val expression: String)

data class MetaLcncParadigm(val name: String, val rules: List<LcncRule>)

class AdaptedParadigm(
    val name: String,
    val scope: CoroutineScope,
    val reteTable: CausalReteTable,
    private val supervisor: CompletableJob,
) {
    val isActive: Boolean get() = supervisor.isActive
}

class UserContext(
    val name: String,
    scope: CoroutineScope,
    val parentId: String? = null,
    inheritedFacts: List<CausalAssertion> = emptyList(),
    inheritedPolyglot: List<PolyglotFact> = emptyList(),
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<UserContext>

    override val key: CoroutineContext.Key<*> get() = Key

    val id: String = "ctx-${nextUserContextId()}"
    val supervisor: CompletableJob = SupervisorJob(scope.coroutineContext[Job])
    val executionScope: CoroutineScope = CoroutineScope(scope.coroutineContext + supervisor + this)
    val reteTable = CausalReteTable(inheritedFacts)
    private val polyglotFacts = ArrayList<PolyglotFact>(inheritedPolyglot)
    private val children = ArrayList<UserContext>()
    private val nodes = ArrayList<ArticulatedNode>()
    private val adapted = ArrayList<AdaptedParadigm>()

    var lifecycleState: ElementState = ElementState.CREATED
        private set

    var active: Boolean = false
        private set

    val factCount: Int get() = reteTable.rowCount

    val fanoutSubscribers: List<CoroutineContext.Element>
        get() = children.toList() + nodes

    init {
        supervisor.invokeOnCompletion {
            active = false
            lifecycleState = ElementState.CLOSED
            children.clear()
            nodes.clear()
        }
    }

    suspend fun open() {
        if (lifecycleState == ElementState.CLOSED) return
        if (lifecycleState == ElementState.CREATED) lifecycleState = ElementState.OPEN
    }

    fun activate() {
        requireAccepting()
        if (lifecycleState == ElementState.CREATED) lifecycleState = ElementState.OPEN
        lifecycleState = ElementState.ACTIVE
        active = true
    }

    fun deactivate() {
        check(lifecycleState != ElementState.CLOSED) { "UserContext is closed" }
        active = false
        if (lifecycleState == ElementState.CREATED) lifecycleState = ElementState.OPEN
        if (lifecycleState == ElementState.OPEN) lifecycleState = ElementState.ACTIVE
    }

    fun fork(role: String): UserContext {
        requireAccepting()
        val child = UserContext(
            name = role,
            scope = executionScope,
            parentId = id,
            inheritedFacts = reteTable.all(),
            inheritedPolyglot = polyglotFacts.toList(),
        )
        children += child
        return child
    }

    fun choreograph(doc: ForgeDocument): ArticulatedNode {
        requireAccepting()
        val node = ArticulatedNode(
            initialDoc = doc,
            scope = executionScope,
            record = false,
            fanoutSubscribers = listOf(this),
        )
        node.subscribeAgent("context:$id") { signal -> assertFromSignal(signal) }
        nodes += node
        return node
    }

    fun assertFact(assertion: CausalAssertion) {
        requireAccepting()
        reteTable.assert(assertion)
    }

    fun loadPolyglotFacts(facts: List<PolyglotFact>) {
        requireAccepting()
        polyglotFacts += facts
    }

    fun queryPolyglot(language: String, kind: String): List<PolyglotFact> =
        polyglotFacts.filter { it.language == language && it.kind == kind }

    fun predictModel(model: String, inputs: Map<String, Any>): Map<String, Any?> {
        requireAccepting()
        val count = inputs["count"]?.toString()?.toIntOrNull() ?: 0
        return when (inputs["method"]?.toString()) {
            "appendBlock" -> linkedMapOf("model" to model, "expectedBlocks" to count + 1)
            else -> linkedMapOf("model" to model)
        }
    }

    fun tableTest(prediction: Map<String, Any>): PredictionTestResult {
        val expected = prediction["expectedBlocks"] as? Int
            ?: return PredictionTestResult(false, "no expectedBlocks in prediction")
        val actual = reteTable.all().count {
            it.kind.contains("block") || it.kind.contains("class:") || it.kind.contains("method:")
        }
        val passed = actual >= expected
        return PredictionTestResult(
            passed = passed,
            evidence = if (passed) "OK" else "expected $expected, found $actual",
        )
    }

    fun createGraphicalFlow(name: String): GraphicalFlow {
        requireAccepting()
        return GraphicalFlow(name)
    }

    fun spreadsheetVeneer(): SpreadsheetVeneer = SpreadsheetVeneer { reteTable.all() }

    fun adaptParadigm(paradigm: MetaLcncParadigm): AdaptedParadigm {
        requireAccepting()
        val table = CausalReteTable()
        paradigm.rules.forEach { rule ->
            val assertion = CausalAssertion(
                kind = "rule:${paradigm.name}:${rule.name}",
                fields = mapOf("expr" to rule.expression),
            )
            table.assert(assertion)
            reteTable.assert(assertion)
        }
        val job = SupervisorJob(supervisor)
        val adaptedScope = CoroutineScope(executionScope.coroutineContext + job + CoroutineName("ccek-paradigm:${paradigm.name}"))
        val result = AdaptedParadigm(paradigm.name, adaptedScope, table, job)
        adapted += result
        return result
    }

    fun toDocument(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to name,
        "parentId" to parentId,
        "active" to active,
        "facts" to reteTable.all().map { linkedMapOf("kind" to it.kind, "fields" to it.fields) },
        "factCount" to factCount,
    )

    suspend fun drain() {
        if (lifecycleState == ElementState.CLOSED) return
        lifecycleState = ElementState.DRAINING
        active = false
        children.toList().forEach { it.drain() }
        nodes.toList().forEach { it.drain() }
        adapted.forEach { it.scope.coroutineContext[Job]?.completeIfCompletable() }
        adapted.forEach { it.scope.coroutineContext[Job]?.join() }
        supervisor.complete()
        supervisor.join()
    }

    suspend fun close() {
        drain()
    }

    fun cancel() {
        children.toList().forEach { it.cancel() }
        nodes.toList().forEach { it.abort(CancellationException("UserContext cancelled")) }
        adapted.forEach { it.scope.cancel(CancellationException("UserContext cancelled")) }
        supervisor.cancel(CancellationException("UserContext cancelled"))
    }

    private fun requireAccepting() {
        check(lifecycleState < ElementState.DRAINING && supervisor.isActive) { "UserContext is closed" }
    }

    private fun assertFromSignal(signal: ForgeSignal) {
        val assertion = when (signal) {
            is ForgeSignal.AppendBlock -> CausalAssertion(
                "block:appended",
                mapOf("text" to signal.text, "kind" to signal.kind.name) + signal.properties,
            )
            is ForgeSignal.UpdateText -> CausalAssertion("block:updated", mapOf("blockId" to signal.blockId, "text" to signal.text))
            is ForgeSignal.DeleteBlock -> CausalAssertion("block:deleted", mapOf("blockId" to signal.blockId))
            is ForgeSignal.MoveCard -> CausalAssertion(
                "card:moved",
                mapOf(
                    "cardId" to signal.cardId,
                    "toColumnId" to signal.toColumnId,
                    "status" to BoardCol.statusFor(signal.toColumnId),
                ),
            )
            is ForgeSignal.Continue -> CausalAssertion("card:continue", mapOf("cardId" to signal.cardId))
            is ForgeSignal.Repeat -> CausalAssertion("card:repeat", mapOf("cardId" to signal.cardId, "edgeId" to signal.edgeId))
            is ForgeSignal.Abort -> CausalAssertion("card:abort", mapOf("cardId" to signal.cardId, "reason" to signal.reason))
            is ForgeSignal.Fork -> CausalAssertion("card:fork", mapOf("cardId" to signal.cardId, "targetLane" to signal.targetLane))
            is ForgeSignal.Join -> CausalAssertion(
                "card:join",
                mapOf("cardId" to signal.cardId, "group" to signal.group, "requiredBranches" to signal.requiredBranches),
            )
            is ForgeSignal.Vote -> CausalAssertion("card:vote", mapOf("cardId" to signal.cardId, "verdict" to signal.verdict))
        }
        reteTable.assert(assertion)
    }
}

private var contextOrdinal: Long = 0L

private fun nextUserContextId(): String {
    contextOrdinal += 1
    return contextOrdinal.toString(36)
}

private fun Job.completeIfCompletable() {
    (this as? CompletableJob)?.complete()
}
