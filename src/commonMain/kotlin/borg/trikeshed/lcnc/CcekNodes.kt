package borg.trikeshed.lcnc

import borg.trikeshed.ccek.AgentStatusEvent
import borg.trikeshed.ccek.ArticulatedNode
import borg.trikeshed.ccek.CausalAssertion
import borg.trikeshed.ccek.CcekKeyService
import borg.trikeshed.ccek.ForgeSignal
import borg.trikeshed.ccek.GraphicalBlock
import borg.trikeshed.ccek.GraphicalEdge
import borg.trikeshed.ccek.LcncRule
import borg.trikeshed.ccek.MetaLcncParadigm
import borg.trikeshed.ccek.PolyglotFact
import borg.trikeshed.ccek.ProjectionKind
import borg.trikeshed.ccek.UserContext
import borg.trikeshed.ccek.requireCcekScope
import borg.trikeshed.forge.ForgeBlockKind
import borg.trikeshed.forge.ForgeDoc
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.concurrency.ParseScopeKey
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The CCEK plane as plain functions, so the runners stay commonMain-pure:
 * [live] wires REAL [ArticulatedNode]s (CCEK is commonMain — the showcase runs
 * unmocked), while tests wire [inMemory] and spy the backing store.
 *
 * Handles are keyed by title and incarnation is IDEMPOTENT: a program's sweep
 * re-runs `ccek.incarnate` on every tick, and a fresh node per tick would throw
 * away every agent, projection and recording. Same title ⇒ same node.
 */
data class CcekSeams(
    /** Incarnate (or re-attach to) an articulated node; returns its handle. */
    val incarnate: suspend (title: String, record: Boolean, maxConcurrency: Int, projections: Set<ProjectionKind>) -> String,
    /** Send one signal into the node's bounded fan-out. */
    val send: suspend (handle: String, signal: ForgeSignal) -> Boolean,
    /** The recorded signal log — replay, when the node was incarnated with record=true. */
    val recording: (handle: String) -> List<ForgeSignal>,
    /** Latest projection of [kind]: "document" | "board" | "markdown". */
    val projection: (handle: String, kind: String) -> Any?,
    /** Drain the buffer an LCNC-subscribed agent has accumulated (subscribes on first call). */
    val agentDrain: (handle: String, agent: String) -> List<ForgeSignal>,
    /** Agent status events seen so far (Started / Completed / Failed). */
    val status: (handle: String) -> List<Map<String, Any?>>,
    /** Graceful cancel + drain; the node stays addressable, inert. */
    val drain: (handle: String) -> Boolean,
    /** Fork a user context (parent null ⇒ a root context); returns its document. */
    val forkContext: (parent: String?, role: String) -> Map<String, Any?>,
    /** Assert one causal fact into a context; returns the new fact count. */
    val assertFact: (contextId: String, kind: String, fields: Map<String, Any>) -> Int,
    // ── the rest of the engine (lcnc-depth's CCEK decomposition named each) ──
    /** Liveness: `active`, `childScopes`, `markdownProjections`; null for an unknown handle. */
    val vitals: (handle: String) -> Map<String, Any?>?,
    /** [UserContext.choreograph]: a node whose causal-assertion agent asserts into the
     *  context. Idempotent by title like incarnate; null when the context is unknown. */
    val choreograph: suspend (contextId: String, title: String) -> String?,
    /** activate / deactivate; the resulting `active`, or null when the context is unknown. */
    val activate: (contextId: String, active: Boolean) -> Boolean?,
    /** The context document as it stands NOW ([UserContext.toDocument] + factCount), or null. */
    val lineage: (contextId: String) -> Map<String, Any?>?,
    /** [borg.trikeshed.ccek.CausalReteTable.query]: facts whose kind equals or starts with [kind]; null when unknown. */
    val query: (contextId: String, kind: String) -> List<Map<String, Any?>>?,
    /** Load polyglot facts; how many, or -1 when the context is unknown. */
    val loadPolyglot: (contextId: String, facts: List<PolyglotFact>) -> Int,
    val queryPolyglot: (contextId: String, language: String, kind: String) -> List<Map<String, Any?>>?,
    /** [UserContext.predictModel], or null when unknown. */
    val predict: (contextId: String, model: String, inputs: Map<String, Any>) -> Map<String, Any?>?,
    /** [UserContext.tableTest]: `passed` + `evidence`, or null when unknown. */
    val tableTest: (contextId: String, prediction: Map<String, Any>) -> Map<String, Any?>?,
    /** [UserContext.createGraphicalFlow] fed its blocks and edges, read back as a cursor. */
    val flow: (contextId: String, name: String, blocks: List<GraphicalBlock>, edges: List<GraphicalEdge>) -> Map<String, Any?>?,
    /** [borg.trikeshed.ccek.SpreadsheetVeneer.facet] over the context's facts. */
    val facet: (contextId: String, column: String, value: String) -> List<Map<String, Any?>>?,
    /** [UserContext.adaptParadigm]: rules land as facts; `name`, `active`, `rows`, `factCount`. */
    val adapt: (contextId: String, paradigm: MetaLcncParadigm) -> Map<String, Any?>?,
) {
    companion object {

        /**
         * The real plane. Every handle is a live [ArticulatedNode] under [scope]
         * (its `init` starts the fan-out; `sendSignal` restarts an idle one).
         * Agent buffers are [Channel]s, not lists: CCEK fans out on real threads,
         * so the drain seam must be the thread-safe primitive CCEK itself uses.
         */
        fun live(scope: CoroutineScope): CcekSeams = liveOf(
            scope = scope,
            newNode = { title, record, maxConcurrency, projections ->
                ArticulatedNode(
                    initialDoc = ForgeDoc.empty(title),
                    scope = scope,
                    record = record,
                    enabledProjections = projections,
                    maxConcurrency = maxConcurrency,
                )
            },
            newContext = { role -> UserContext(role, scope) },
        )

        /**
         * The daemon's plane: nodes are choreographed by the reactor-bound
         * [CCEK.CcekReactorBinding] (OroborosDaemon's `CCEK.initialize(muxReactor)`),
         * so a program drives the same CCEK the rest of the process rides —
         * not a private instance beside it.
         */
        fun live(binding: borg.trikeshed.ccek.CCEK.CcekReactorBinding): CcekSeams = liveOf(
            scope = binding.reactorScope,
            newNode = { title, record, maxConcurrency, projections ->
                binding.choreograph(ForgeDoc.empty(title), record, projections, maxConcurrency)
            },
            newContext = { role -> binding.createUserContext(role) },
        )

        private fun liveOf(
            scope: CoroutineScope,
            newNode: (String, Boolean, Int, Set<ProjectionKind>) -> ArticulatedNode,
            newContext: (String) -> UserContext,
        ): CcekSeams {
            val nodes = LinkedHashMap<String, ArticulatedNode>()
            val constructions = LinkedHashMap<String, CcekConstruction>()
            val incarnationMutex = Mutex()
            val agentBuffers = LinkedHashMap<String, Channel<ForgeSignal>>()
            val contexts = LinkedHashMap<String, UserContext>()
            // agentStatus is a replay-1 SharedFlow — a WINDOW, not a log: reading its
            // replayCache loses every event but the last. A program watching fan-out
            // needs the log, so each node gets a bounded channel subscribed at
            // incarnation and drained into an accumulator on read (the same
            // single-reader discipline agentDrain uses — CCEK fans out on real threads).
            val statusChannels = LinkedHashMap<String, Channel<Map<String, Any?>>>()
            val statusLog = LinkedHashMap<String, ArrayList<Map<String, Any?>>>()
            // One adoption path for every node, however it was made: the status
            // subscription and the construction record are what make a handle.
            fun adopt(title: String, requested: CcekConstruction, make: () -> ArticulatedNode): ArticulatedNode {
                require(constructions[title] == null || constructions[title] == requested) {
                    "incarnation_conflict: $title already exists with different construction settings"
                }
                return nodes.getOrPut(title) {
                    val node = make()
                    val channel = Channel<Map<String, Any?>>(256, BufferOverflow.DROP_OLDEST)
                    statusChannels[title] = channel
                    statusLog[title] = ArrayList()
                    scope.launch { node.agentStatus.collect { channel.trySend(statusMap(it)) } }
                    constructions[title] = requested
                    node
                }
            }
            fun assertion(a: CausalAssertion): Map<String, Any?> = linkedMapOf("kind" to a.kind, "fields" to a.fields)
            return CcekSeams(
                incarnate = { title, record, maxConcurrency, projections ->
                    incarnationMutex.withLock {
                        adopt(title, CcekConstruction(title, record, maxConcurrency, projections.toSet())) {
                            newNode(title, record, maxConcurrency, projections)
                        }
                        title
                    }
                },
                send = { handle, signal ->
                    val node = nodes[handle]
                    if (node == null) false else { node.sendSignal(signal); true }
                },
                recording = { handle -> nodes[handle]?.recording() ?: emptyList() },
                projection = { handle, kind ->
                    val node = nodes[handle]
                    when {
                        node == null -> null
                        kind == "board" -> node.boardProjections.replayCache.lastOrNull()?.let { board ->
                            linkedMapOf<String, Any?>(
                                "id" to board.id.value,
                                "name" to board.name,
                                "columns" to board.columns.map { linkedMapOf("id" to it.id.value, "name" to it.name) },
                                "cards" to board.cards.map {
                                    linkedMapOf("id" to it.id.value, "title" to it.title, "column" to it.columnId.value)
                                },
                            )
                        }
                        kind == "markdown" -> node.markdownProjections.replayCache.lastOrNull()
                        else -> node.documentProjections.replayCache.lastOrNull()?.let { doc ->
                            linkedMapOf<String, Any?>(
                                "root" to doc.rootPageId.value,
                                "blocks" to doc.blocks.size,
                                "markdown" to ForgeDoc.renderMarkdown(doc),
                            )
                        }
                    }
                },
                agentDrain = { handle, agent ->
                    val node = nodes[handle]
                    if (node == null) emptyList() else {
                        val key = "$handle $agent"
                        val fresh = !agentBuffers.containsKey(key)
                        val buffer = agentBuffers.getOrPut(key) { Channel(Channel.UNLIMITED) }
                        if (fresh) node.subscribeAgent(agent) { signal -> buffer.trySend(signal) }
                        val out = ArrayList<ForgeSignal>()
                        while (true) {
                            val r = buffer.tryReceive()
                            out.add(r.getOrNull() ?: break)
                        }
                        out
                    }
                },
                status = { handle ->
                    val log = statusLog[handle]
                    val channel = statusChannels[handle]
                    if (log == null || channel == null) emptyList() else {
                        while (true) {
                            val r = channel.tryReceive()
                            log.add(r.getOrNull() ?: break)
                        }
                        log.toList()
                    }
                },
                drain = { handle -> nodes[handle]?.let { it.cancel(); true } ?: false },
                forkContext = { parent, role ->
                    val child = parent?.let { contexts[it] }?.fork(role) ?: newContext(role)
                    contexts[child.id] = child
                    child.toDocument()
                },
                assertFact = { contextId, kind, fields ->
                    val ctx = contexts[contextId]
                    if (ctx == null) -1 else {
                        ctx.assertFact(CausalAssertion(kind, fields))
                        ctx.factCount
                    }
                },
                vitals = { handle ->
                    nodes[handle]?.let { node ->
                        linkedMapOf<String, Any?>(
                            "active" to node.isActive,
                            "childScopes" to node.childScopeCount,
                            "markdownProjections" to node.markdownProjectionCount,
                        )
                    }
                },
                choreograph = { contextId, title ->
                    val ctx = contexts[contextId]
                    if (ctx == null) null else incarnationMutex.withLock {
                        // UserContext.choreograph builds the node with ArticulatedNode's
                        // defaults; recording that construction is what lets a later
                        // ccek.incarnate of the same title attach rather than conflict.
                        adopt(title, CcekConstruction(title, false, 8, ProjectionKind.ALL)) {
                            ctx.choreograph(ForgeDoc.empty(title))
                        }
                        title
                    }
                },
                activate = { contextId, active ->
                    contexts[contextId]?.let { ctx ->
                        if (active) ctx.activate() else ctx.deactivate()
                        ctx.active
                    }
                },
                lineage = { contextId ->
                    contexts[contextId]?.let { ctx -> ctx.toDocument() + ("factCount" to ctx.factCount) }
                },
                query = { contextId, kind ->
                    contexts[contextId]?.reteTable?.query(kind)?.map(::assertion)
                },
                loadPolyglot = { contextId, facts ->
                    val ctx = contexts[contextId]
                    if (ctx == null) -1 else { ctx.loadPolyglotFacts(facts); facts.size }
                },
                queryPolyglot = { contextId, language, kind ->
                    contexts[contextId]?.queryPolyglot(language, kind)?.map { f ->
                        linkedMapOf<String, Any?>("language" to f.language, "opcode" to f.opcode, "target" to f.target, "kind" to f.kind)
                    }
                },
                predict = { contextId, model, inputs -> contexts[contextId]?.predictModel(model, inputs) },
                tableTest = { contextId, prediction ->
                    contexts[contextId]?.tableTest(prediction)?.let { r ->
                        linkedMapOf<String, Any?>("passed" to r.passed, "evidence" to (r.evidence ?: ""))
                    }
                },
                flow = { contextId, name, blocks, edges ->
                    contexts[contextId]?.let { ctx ->
                        val flow = ctx.createGraphicalFlow(name)
                        blocks.forEach { flow.addBlock(it) }
                        edges.forEach { flow.connect(it.from, it.to) }
                        val cursor = flow.asCursor()
                        linkedMapOf<String, Any?>(
                            "name" to name,
                            "blocks" to cursor.blocks.map { linkedMapOf("id" to it.id, "label" to it.label, "properties" to it.properties) },
                            "edges" to flow.edges().map { linkedMapOf("from" to it.from, "to" to it.to) },
                            "size" to cursor.size,
                        )
                    }
                },
                facet = { contextId, column, value ->
                    contexts[contextId]?.spreadsheetVeneer()?.facet(column, value)?.map(::assertion)
                },
                adapt = { contextId, paradigm ->
                    contexts[contextId]?.let { ctx ->
                        val adapted = ctx.adaptParadigm(paradigm)
                        linkedMapOf<String, Any?>(
                            "name" to adapted.name, "active" to adapted.isActive,
                            "rows" to adapted.reteTable.rowCount, "factCount" to ctx.factCount,
                        )
                    }
                },
            )
        }

        /** Map-backed fakes — the zero-thread test seams. */
        fun inMemory(store: InMemoryCcekStore = InMemoryCcekStore()): CcekSeams = CcekSeams(
            incarnate = { title, record, maxConcurrency, projections ->
                store.nodes[title]?.let { existing ->
                    require(existing.record == record && existing.maxConcurrency == maxConcurrency && existing.projections == projections.map { it.name }.toSet()) {
                        "incarnation_conflict: $title already exists with different construction settings"
                    }
                }
                store.nodes.getOrPut(title) {
                    InMemoryCcekStore.Node(record, maxConcurrency, projections.map { it.name }.toSet())
                }
                title
            },
            send = { handle, signal ->
                val n = store.nodes[handle]
                if (n == null) false else {
                    if (n.record) n.recorded.add(signal)
                    n.pending.add(signal)
                    n.markdown = (n.markdown.orEmpty() + describe(signal) + "\n")
                    true
                }
            },
            recording = { handle -> store.nodes[handle]?.recorded?.toList() ?: emptyList() },
            projection = { handle, kind ->
                val n = store.nodes[handle]
                when {
                    n == null -> null
                    kind == "markdown" -> n.markdown
                    kind == "board" -> linkedMapOf<String, Any?>("id" to handle, "cards" to emptyList<Any?>())
                    else -> linkedMapOf<String, Any?>("root" to handle, "blocks" to n.recorded.size)
                }
            },
            agentDrain = { handle, agent ->
                val n = store.nodes[handle]
                if (n == null) emptyList() else {
                    n.agents.add(agent)
                    val out = n.pending.toList()
                    n.pending.clear()
                    out
                }
            },
            status = { handle ->
                store.nodes[handle]?.agents?.map { linkedMapOf<String, Any?>("event" to "Started", "agent" to it) }
                    ?: emptyList()
            },
            drain = { handle -> store.nodes[handle]?.let { it.drained = true; true } ?: false },
            forkContext = { parent, role ->
                val id = "ctx-${store.contexts.size + 1}"
                val ctx = InMemoryCcekStore.Context(id, role, parent)
                // Copy-on-fork, as UserContext.fork does.
                store.contexts[parent]?.let { ctx.facts.addAll(it.facts); ctx.polyglot.addAll(it.polyglot) }
                store.contexts[id] = ctx
                ctx.document()
            },
            assertFact = { contextId, kind, fields ->
                store.contexts[contextId]?.let { it.facts.add(linkedMapOf("kind" to kind, "fields" to fields)); it.facts.size } ?: -1
            },
            vitals = { handle ->
                store.nodes[handle]?.let { n ->
                    linkedMapOf<String, Any?>(
                        "active" to !n.drained, "childScopes" to 0,
                        "markdownProjections" to n.markdown.orEmpty().lines().count { it.isNotBlank() },
                    )
                }
            },
            choreograph = { contextId, title ->
                if (!store.contexts.containsKey(contextId)) null else {
                    store.nodes[title]?.let { existing ->
                        require(!existing.record && existing.maxConcurrency == 8 && existing.projections == ProjectionKind.ALL.map { it.name }.toSet()) {
                            "incarnation_conflict: $title already exists with different construction settings"
                        }
                    }
                    store.nodes.getOrPut(title) {
                        InMemoryCcekStore.Node(false, 8, ProjectionKind.ALL.map { it.name }.toSet()).also { it.choreographedBy = contextId }
                    }
                    title
                }
            },
            activate = { contextId, active -> store.contexts[contextId]?.let { it.active = active; it.active } },
            lineage = { contextId -> store.contexts[contextId]?.document() },
            query = { contextId, kind ->
                store.contexts[contextId]?.facts?.filter { f -> f["kind"].toString().let { it == kind || it.startsWith(kind) } }
            },
            loadPolyglot = { contextId, facts ->
                store.contexts[contextId]?.let { ctx ->
                    facts.forEach { f -> ctx.polyglot.add(linkedMapOf("language" to f.language, "opcode" to f.opcode, "target" to f.target, "kind" to f.kind)) }
                    facts.size
                } ?: -1
            },
            queryPolyglot = { contextId, language, kind ->
                store.contexts[contextId]?.polyglot?.filter { it["language"] == language && it["kind"] == kind }
            },
            // The fakes mirror UserContext.predictModel / tableTest line for line.
            predict = { contextId, model, inputs ->
                if (!store.contexts.containsKey(contextId)) null else {
                    val count = inputs["count"]?.toString()?.toIntOrNull() ?: 0
                    when (inputs["method"]?.toString()) {
                        "appendBlock" -> linkedMapOf<String, Any?>("model" to model, "expectedBlocks" to count + 1)
                        else -> linkedMapOf<String, Any?>("model" to model)
                    }
                }
            },
            tableTest = { contextId, prediction ->
                store.contexts[contextId]?.let { ctx ->
                    val expected = prediction["expectedBlocks"] as? Int
                    if (expected == null) linkedMapOf<String, Any?>("passed" to false, "evidence" to "no expectedBlocks in prediction")
                    else {
                        val actual = ctx.facts.count { f -> f["kind"].toString().let { it.contains("block") || it.contains("class:") || it.contains("method:") } }
                        val passed = actual >= expected - 1
                        linkedMapOf<String, Any?>("passed" to passed, "evidence" to if (passed) "OK" else "expected $expected, found $actual")
                    }
                }
            },
            flow = { contextId, name, blocks, edges ->
                if (!store.contexts.containsKey(contextId)) null else linkedMapOf<String, Any?>(
                    "name" to name,
                    "blocks" to blocks.map { linkedMapOf("id" to it.id, "label" to it.label, "properties" to it.properties) },
                    "edges" to edges.map { linkedMapOf("from" to it.from, "to" to it.to) },
                    "size" to blocks.size,
                )
            },
            facet = { contextId, column, value ->
                store.contexts[contextId]?.facts?.filter { f -> (f["fields"] as? Map<*, *>)?.get(column)?.toString() == value }
            },
            adapt = { contextId, paradigm ->
                store.contexts[contextId]?.let { ctx ->
                    paradigm.rules.forEach { rule ->
                        ctx.facts.add(linkedMapOf("kind" to "rule:${paradigm.name}:${rule.name}", "fields" to mapOf("expr" to rule.expression)))
                    }
                    linkedMapOf<String, Any?>("name" to paradigm.name, "active" to true, "rows" to ctx.facts.size, "factCount" to ctx.facts.size)
                }
            },
        )

        private fun statusMap(e: AgentStatusEvent): Map<String, Any?> = when (e) {
            is AgentStatusEvent.Started -> linkedMapOf("event" to "Started", "agent" to e.agentName, "signal" to describe(e.signal))
            is AgentStatusEvent.Completed -> linkedMapOf("event" to "Completed", "agent" to e.agentName)
            is AgentStatusEvent.Failed -> linkedMapOf("event" to "Failed", "agent" to e.agentName, "error" to (e.cause.message ?: "?"))
        }

        internal fun describe(s: ForgeSignal): String = when (s) {
            is ForgeSignal.AppendBlock -> "append ${s.kind}: ${s.text}"
            is ForgeSignal.UpdateText -> "update ${s.blockId}: ${s.text}"
            is ForgeSignal.DeleteBlock -> "delete ${s.blockId}"
            is ForgeSignal.MoveCard -> "move ${s.cardId} -> ${s.toColumnId}"
            is ForgeSignal.Continue -> "continue ${s.cardId}"
            is ForgeSignal.Repeat -> "repeat ${s.cardId} via ${s.edgeId}"
            is ForgeSignal.Abort -> "abort ${s.cardId}: ${s.reason}"
            is ForgeSignal.Fork -> "fork ${s.cardId} -> ${s.targetLane}"
            is ForgeSignal.Join -> "join ${s.cardId} @${s.group} x${s.requiredBranches}"
            is ForgeSignal.Vote -> "vote ${s.cardId}: ${s.verdict}"
        }
    }
}

/** The containers behind [CcekSeams.inMemory] — public so tests spy them. */
class InMemoryCcekStore {
    class Node(val record: Boolean, val maxConcurrency: Int, val projections: Set<String>) {
        val recorded = ArrayList<ForgeSignal>()
        val pending = ArrayList<ForgeSignal>()
        val agents = LinkedHashSet<String>()
        var markdown: String? = null
        var drained = false
        /** The context whose causal-assertion agent this node was choreographed for, if any. */
        var choreographedBy: String? = null
    }
    /** A UserContext's observable state: role, lineage, activation, facts (kind + fields), polyglot facts. */
    class Context(val id: String, val role: String, val parentId: String?) {
        var active = false
        val facts = ArrayList<Map<String, Any?>>()
        val polyglot = ArrayList<Map<String, Any?>>()
        fun document(): Map<String, Any?> = linkedMapOf(
            "id" to id, "name" to role, "parentId" to parentId, "active" to active,
            "facts" to facts.toList(), "factCount" to facts.size,
        )
    }
    val nodes = LinkedHashMap<String, Node>()
    val contexts = LinkedHashMap<String, Context>()
}

/**
 * The CCEK node family: the showcase, made programmable.
 *
 * CCEK is the substrate every other plane rides — bounded fan-out agents over a
 * ForgeDocument, ten control verbs, live projections, a recorded signal log, and
 * context lineage. Until now none of it was reachable from a program: LCNC could
 * talk to the kanban, belief, council and legal planes but not to the engine
 * underneath them. The first nine node types close that: a program can incarnate a
 * node, drive every [ForgeSignal] verb, read any projection, replay the
 * recording, host its own agent, watch the fan-out, drain the node, and fork the
 * context lineage the whole thing is asserted against.
 *
 * The thirteen after them are the rest of the engine, member by member — the
 * public capabilities lcnc-depth's CCEK decomposition (utils/lcnc-depth,
 * `scan_repo --fail-on-ccek-gap`) found no runner reaching: node vitals, a node
 * choreographed BY a context, context activation and its standing document, the
 * causal rete query, polyglot facts, prediction and its table test, the
 * graphical flow, the spreadsheet veneer, paradigm adaptation, and scope
 * validation. What is deliberately NOT here is ruled there: `start()` cannot
 * revive a drained node (its channel is closed for good), `stop()` is `cancel()`,
 * `ForgeDocNode` is a wrapper, the channel factories are substrate.
 */
object CcekNodes {

    /**
     * The context elements `ccek.validate` can require by name. Every key here is a
     * `throws`-severity demand somewhere in the tree (lcnc-depth's context scan):
     * a program that needs one can now ask the runner's own context whether it is
     * there BEFORE the walk reaches the throw site.
     */
    val CONTEXT_KEYS: Map<String, CoroutineContext.Key<*>> = linkedMapOf(
        "MuxReactorElement" to MuxReactorElement.Key,
        "HtxElement" to HtxKey,
        "FileOperations" to FileOperations.Key,
        "ParseScope" to ParseScopeKey,
        "NioSupervisor" to NioSupervisor.Key,
        "LcncScopeFrame" to LcncScopeFrame.Key,
        "CcekKeyService" to CcekKeyService.Key,
    )

    private fun keyName(key: CoroutineContext.Key<*>): String =
        CONTEXT_KEYS.entries.firstOrNull { it.value === key }?.key ?: key.toString()

    private fun Map<String, Any?>.port(name: String): Any? = this[name] ?: this["$name?"]
    private fun csv(text: String?): List<String> = text.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private fun obj(value: Any?): Map<String, Any?> = when (value) {
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to v }
        is String -> if (value.isBlank()) emptyMap() else JsonSupport.parseMap(value)
        else -> emptyMap()
    }
    private fun rows(value: Any?): List<Map<String, Any?>> = when (value) {
        is List<*> -> value.filterIsInstance<Map<*, *>>().map { m -> m.entries.associate { (k, v) -> k.toString() to v } }
        is String -> if (value.isBlank()) emptyList() else rows(JsonSupport.parse(value))
        else -> emptyList()
    }
    private fun str(row: Map<String, Any?>, key: String): String = row[key]?.toString().orEmpty()
    private fun contextIdOf(node: LcncNode, inputs: Map<String, Any?>): String =
        inputs.port("contextId")?.toString() ?: node.params["contextId"].orEmpty()

    /** The verb vocabulary `ccek.signal` speaks — ForgeSignal's cases, verbatim. */
    val VERBS: List<String> = listOf(
        "append", "update", "delete", "move",
        "continue", "repeat", "abort", "fork", "join", "vote",
    )

    fun registry(seams: CcekSeams): Map<String, LcncNodeRunner> = mapOf(

        // Idempotent by title: the sweep re-runs this node every tick and a
        // fresh ArticulatedNode per tick would discard agents and recordings.
        "ccek.incarnate" to LcncNodeRunner { node, inputs ->
            val resolved = CcekConstruction.resolve(node.params, inputs)
            val c = resolved.configuration
            val handle = seams.incarnate(c.title, c.record, c.maxConcurrency, c.projections)
            mapOf("handle" to handle, "node" to c.toMap(), "arguments" to resolved.arguments)
        },

        // Every ForgeSignal case, constructible from params or wired `fields`.
        // A wired map wins over params, field by field.
        "ccek.signal" to LcncNodeRunner { node, inputs ->
            val handle = (inputs["handle"] ?: inputs["handle?"])?.toString()
                ?: node.params["handle"].orEmpty()
            require(handle.isNotBlank()) { "ccek.signal ${node.id}: no handle wired" }
            val over: Map<String, Any?> = when (val f = inputs["fields"] ?: inputs["fields?"]) {
                is Map<*, *> -> f.entries.associate { (k, v) -> k.toString() to v }
                is String -> if (f.isBlank()) emptyMap() else JsonSupport.parseMap(f)
                else -> emptyMap()
            }
            fun field(name: String): String =
                over[name]?.toString()
                    ?: (if (name == "text") (inputs["text"] ?: inputs["text?"])?.toString() else null)
                    ?: node.params[name].orEmpty()
            val verb = (over["verb"]?.toString() ?: node.params["verb"] ?: "append").lowercase()
            val signal = when (verb) {
                "append" -> ForgeSignal.AppendBlock(
                    kind = ForgeBlockKind.entries.firstOrNull { it.name.equals(field("blockKind"), true) }
                        ?: ForgeBlockKind.TEXT,
                    text = field("text"),
                )
                "update" -> ForgeSignal.UpdateText(field("blockId"), field("text"))
                "delete" -> ForgeSignal.DeleteBlock(field("blockId"))
                "move" -> ForgeSignal.MoveCard(field("cardId"), field("toColumnId"))
                "continue" -> ForgeSignal.Continue(field("cardId"))
                "repeat" -> ForgeSignal.Repeat(field("cardId"), field("edgeId"))
                "abort" -> ForgeSignal.Abort(field("cardId"), field("reason"))
                "fork" -> ForgeSignal.Fork(field("cardId"), field("targetLane"))
                "join" -> ForgeSignal.Join(
                    field("cardId"), field("group"),
                    field("requiredBranches").toIntOrNull() ?: 2,
                )
                "vote" -> ForgeSignal.Vote(field("cardId"), field("verdict"))
                else -> throw IllegalArgumentException("ccek.signal ${node.id}: unknown verb '$verb' (${VERBS.joinToString("|")})")
            }
            val sent = seams.send(handle, signal)
            mapOf(
                "sent" to sent,
                "signal" to linkedMapOf<String, Any?>(
                    "verb" to verb,
                    "describe" to CcekSeams.describe(signal),
                    "handle" to handle,
                ),
            )
        },

        "ccek.projection" to LcncNodeRunner { node, inputs ->
            val handle = (inputs["handle"] ?: inputs["handle?"])?.toString()
                ?: node.params["handle"].orEmpty()
            val kind = node.params["kind"] ?: "markdown"
            mapOf("projection" to seams.projection(handle, kind), "kind" to kind)
        },

        // Replay: the signal log CCEK recorded, as the program's own data.
        "ccek.recording" to LcncNodeRunner { node, inputs ->
            val handle = (inputs["handle"] ?: inputs["handle?"])?.toString()
                ?: node.params["handle"].orEmpty()
            val signals = seams.recording(handle)
            mapOf(
                "signals" to signals.map { linkedMapOf<String, Any?>("describe" to CcekSeams.describe(it)) },
                "count" to signals.size,
            )
        },

        // An LCNC program hosting a CCEK agent: this node IS a subscriber on the
        // node's bounded fan-out, and each run drains what the fan-out delivered.
        "ccek.agent" to LcncNodeRunner { node, inputs ->
            val handle = (inputs["handle"] ?: inputs["handle?"])?.toString()
                ?: node.params["handle"].orEmpty()
            val name = node.params["name"]?.takeIf { it.isNotBlank() } ?: node.id
            val drained = seams.agentDrain(handle, name)
            mapOf(
                "agent" to name,
                "signals" to drained.map { linkedMapOf<String, Any?>("describe" to CcekSeams.describe(it)) },
                "count" to drained.size,
            )
        },

        "ccek.status" to LcncNodeRunner { node, inputs ->
            val handle = (inputs["handle"] ?: inputs["handle?"])?.toString()
                ?: node.params["handle"].orEmpty()
            val events = seams.status(handle)
            mapOf(
                "events" to events,
                "started" to events.count { it["event"] == "Started" },
                "completed" to events.count { it["event"] == "Completed" },
                "failed" to events.count { it["event"] == "Failed" },
            )
        },

        "ccek.drain" to LcncNodeRunner { node, inputs ->
            val handle = (inputs["handle"] ?: inputs["handle?"])?.toString()
                ?: node.params["handle"].orEmpty()
            mapOf("drained" to seams.drain(handle))
        },

        // Context lineage: fork a UserContext (parent wins over param), the
        // document out — id, name, parentId, factCount.
        "ccek.context" to LcncNodeRunner { node, inputs ->
            val parent = ((inputs["parent"] ?: inputs["parent?"])?.toString()
                ?: node.params["parent"]).orEmpty().takeIf { it.isNotBlank() }
            val role = node.params["role"]?.takeIf { it.isNotBlank() } ?: "root"
            val doc = seams.forkContext(parent, role)
            mapOf("context" to doc, "contextId" to (doc["id"]?.toString() ?: ""))
        },

        "ccek.fact" to LcncNodeRunner { node, inputs ->
            val contextId = (inputs["contextId"] ?: inputs["contextId?"])?.toString()
                ?: node.params["contextId"].orEmpty()
            val kind = node.params["kind"]?.takeIf { it.isNotBlank() } ?: "observation"
            val fields: Map<String, Any> = when (val f = inputs["fields"] ?: inputs["fields?"]) {
                is Map<*, *> -> f.entries.mapNotNull { (k, v) -> v?.let { k.toString() to it } }.toMap()
                is String -> if (f.isBlank()) emptyMap() else JsonSupport.parseMap(f)
                    .mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
                else -> emptyMap()
            }
            val count = seams.assertFact(contextId, kind, fields)
            mapOf("factCount" to count, "asserted" to (count >= 0))
        },

        // ── the rest of the engine ─────────────────────────────────────

        "ccek.vitals" to LcncNodeRunner { node, inputs ->
            val handle = inputs.port("handle")?.toString() ?: node.params["handle"].orEmpty()
            seams.vitals(handle) ?: mapOf("active" to false, "childScopes" to 0, "markdownProjections" to 0)
        },

        // Idempotent by title, like incarnate; the handle is the same handle space,
        // so every other ccek.* node can drive the choreographed node.
        "ccek.choreograph" to LcncNodeRunner { node, inputs ->
            val title = inputs.port("title")?.toString()?.takeIf { it.isNotBlank() }
                ?: node.params["title"]?.takeIf { it.isNotBlank() } ?: node.id
            val handle = seams.choreograph(contextIdOf(node, inputs), title)
            mapOf("handle" to (handle ?: ""), "bound" to (handle != null))
        },

        "ccek.activate" to LcncNodeRunner { node, inputs ->
            val mode = node.params["mode"]?.takeIf { it.isNotBlank() } ?: "activate"
            require(mode == "activate" || mode == "deactivate") { "ccek.activate ${node.id}: mode must be activate|deactivate, not '$mode'" }
            val active = seams.activate(contextIdOf(node, inputs), mode == "activate")
            mapOf("active" to (active ?: false), "known" to (active != null))
        },

        "ccek.lineage" to LcncNodeRunner { node, inputs ->
            val doc = seams.lineage(contextIdOf(node, inputs))
            mapOf(
                "context" to doc,
                "parentId" to (doc?.get("parentId")?.toString() ?: ""),
                "factCount" to ((doc?.get("factCount") as? Int) ?: -1),
                "active" to (doc?.get("active") == true),
            )
        },

        "ccek.query" to LcncNodeRunner { node, inputs ->
            val kind = node.params["kind"]?.takeIf { it.isNotBlank() } ?: "observation"
            val facts = seams.query(contextIdOf(node, inputs), kind) ?: emptyList()
            mapOf("facts" to facts, "count" to facts.size, "contains" to facts.isNotEmpty())
        },

        "ccek.polyglot.load" to LcncNodeRunner { node, inputs ->
            val facts = rows(inputs.port("facts")).map { r -> PolyglotFact(str(r, "language"), str(r, "opcode"), str(r, "target"), str(r, "kind")) }
            mapOf("loaded" to seams.loadPolyglot(contextIdOf(node, inputs), facts))
        },

        "ccek.polyglot.query" to LcncNodeRunner { node, inputs ->
            val facts = seams.queryPolyglot(contextIdOf(node, inputs), node.params["language"].orEmpty(), node.params["kind"].orEmpty()) ?: emptyList()
            mapOf("facts" to facts, "count" to facts.size)
        },

        "ccek.predict" to LcncNodeRunner { node, inputs ->
            val model = node.params["model"]?.takeIf { it.isNotBlank() } ?: "default"
            val args: Map<String, Any> = obj(inputs.port("inputs")).mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
            mapOf("prediction" to (seams.predict(contextIdOf(node, inputs), model, args) ?: emptyMap<String, Any?>()))
        },

        "ccek.table.test" to LcncNodeRunner { node, inputs ->
            val prediction: Map<String, Any> = obj(inputs.port("prediction")).mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
            val result = seams.tableTest(contextIdOf(node, inputs), prediction)
            mapOf("passed" to (result?.get("passed") == true), "evidence" to (result?.get("evidence")?.toString() ?: "unknown context"))
        },

        "ccek.flow" to LcncNodeRunner { node, inputs ->
            val name = node.params["name"]?.takeIf { it.isNotBlank() } ?: "flow"
            val blocks = rows(inputs.port("blocks")).map { r ->
                GraphicalBlock(str(r, "id"), str(r, "label"), obj(r["properties"]).mapValues { it.value.toString() })
            }
            val edges = rows(inputs.port("edges")).map { r -> GraphicalEdge(str(r, "from"), str(r, "to")) }
            val flow = seams.flow(contextIdOf(node, inputs), name, blocks, edges)
            mapOf("flow" to flow, "size" to ((flow?.get("size") as? Int) ?: 0))
        },

        "ccek.veneer" to LcncNodeRunner { node, inputs ->
            val rows = seams.facet(contextIdOf(node, inputs), node.params["column"].orEmpty(), node.params["value"].orEmpty()) ?: emptyList()
            mapOf("rows" to rows, "count" to rows.size)
        },

        "ccek.paradigm" to LcncNodeRunner { node, inputs ->
            val name = node.params["name"]?.takeIf { it.isNotBlank() } ?: "paradigm"
            val rules = rows(inputs.port("rules")).map { r -> LcncRule(str(r, "name"), str(r, "expression")) }
            val adapted = seams.adapt(contextIdOf(node, inputs), MetaLcncParadigm(name, rules))
            mapOf(
                "paradigm" to adapted?.filterKeys { it != "factCount" },
                "factCount" to ((adapted?.get("factCount") as? Int) ?: -1),
            )
        },

        // requireCcekScope, read for its facts rather than its throw: the runner asks
        // for NO keys (so the call reports what is there) and does the same identity
        // set-difference the validator does for the keys the program named.
        "ccek.validate" to LcncNodeRunner { node, _ ->
            val names = csv(node.params["requiredKeys"])
            val required = names.map { name ->
                CONTEXT_KEYS[name] ?: throw IllegalArgumentException(
                    "ccek.validate ${node.id}: unknown context key '$name' (${CONTEXT_KEYS.keys.joinToString("|")})",
                )
            }
            val spis = csv(node.params["minimumSpis"])
            val validation = try {
                requireCcekScope()
            } catch (e: IllegalStateException) {
                return@LcncNodeRunner mapOf(
                    "valid" to false, "providedKeys" to emptyList<String>(), "missingKeys" to names,
                    "providedSpis" to emptyList<String>(), "missingSpis" to spis,
                    "error" to (e.message ?: "no CCEK scope"),
                )
            }
            val missing = required.filter { k -> validation.providedKeys.none { it === k } }
            val missingSpis = spis.filter { it !in validation.providedSpis }
            mapOf(
                "valid" to (missing.isEmpty() && missingSpis.isEmpty()),
                "providedKeys" to validation.providedKeys.map(::keyName),
                "missingKeys" to missing.map(::keyName),
                "providedSpis" to validation.providedSpis.toList(),
                "missingSpis" to missingSpis,
                "error" to "",
            )
        },
    )

    /** The full contract: which node types the CCEK registry serves. */
    fun servedTypes(): Set<String> = setOf(
        "ccek.incarnate", "ccek.signal", "ccek.projection", "ccek.recording",
        "ccek.agent", "ccek.status", "ccek.drain", "ccek.context", "ccek.fact",
        "ccek.vitals", "ccek.choreograph", "ccek.activate", "ccek.lineage", "ccek.query",
        "ccek.polyglot.load", "ccek.polyglot.query", "ccek.predict", "ccek.table.test",
        "ccek.flow", "ccek.veneer", "ccek.paradigm", "ccek.validate",
    )
}
