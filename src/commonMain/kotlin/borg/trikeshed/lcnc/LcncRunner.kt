package borg.trikeshed.lcnc

import borg.trikeshed.collections._m
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/**
 * One node type's behavior — a plain Kotlin function. Registered against real
 * project logic (`CausalityRete`, `Nal`, whatever), it runs THAT LOGIC
 * IN-PROCESS: no serialization, no round trip, no shim. (The retired browser
 * editor could only ever shim node types over HTTP; the daemon is the one
 * executor — spec §3.)
 *
 * W1.4: this signature IS a CCEK agent signature — `suspend`, hostable by
 * ArticulatedNode's bounded fan-out (Phase 1).
 */
fun interface LcncNodeRunner {
    suspend fun run(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?>
}

class LcncUnknownNodeType(val type: String) : Exception("no runner registered for node type '$type'")

/** A wire crossing a ring boundary outward or between cousin rings — inner
 *  locals never cross; the only export is `scope.out`. */
class LcncScopeViolation(val fromNode: String, val toNode: String) :
    Exception("scope violation: wire $fromNode → $toNode crosses a ring boundary — only scope.out crosses outward")

/** Authored order IS program order: consuming a statement that has not run
 *  yet is use-before-def, a data error — loud, like Kotlin. */
class LcncUseBeforeDef(val fromNode: String, val toNode: String) :
    Exception("use before def: $toNode consumes $fromNode before it runs — statements execute in authored order")

/**
 * The concentric machine — the ONE executor (the-concentric-machine contract,
 * lines 1–5; execution lives in the daemon, the browser never runs the graph):
 *
 *  - **Rings are blocks.** A node holding [LcncNode.children] IS a ring; a
 *    node naming a `subprogram` is a NAMED ring (lazy containment — the
 *    loaded body runs under the same rules and still sees the enclosing
 *    environment). Ring entry is `withContext(`[LcncScopeFrame]`)`: inner
 *    sees outer, nearest shadows, only `scope.out` yields cross back. No
 *    calls — the parent installs bindings, the ring resolves names outward.
 *  - **Authored order.** Children run top to bottom like Kotlin statements;
 *    a wire from a not-yet-run statement throws [LcncUseBeforeDef]; a wire
 *    crossing a ring boundary outward (or between cousins) throws
 *    [LcncScopeViolation]. Wires are the drawing convention over that.
 *  - **Conditionals** are `if (cond) { ring }`: a falsy `when?` input skips
 *    the ring and its yields stay absent downstream.
 *  - A node's inputs are the named outputs of whatever wires target it —
 *    resolved through the frame chain, so an inner node consumes an
 *    enclosing ring's output with zero re-plumbing (the warm base).
 *  - MANY-cardinality ports collect every branch into a `List<Any?>`;
 *    scalar ports keep last-write-wins (W1.4).
 *  - An unwired or unfed REQUIRED input skips the node silently; an upstream
 *    that ran but produced nothing leaves ports absent (silent degrade).
 *  - `scope.in name=k` resolves k outward through the frame chain (nearest
 *    ring shadows), then its `default`. `scope.out name=k` gathers the
 *    ring's yield. Neither runs a runner.
 *
 * Cancellation is cooperative: each node checks the caller's Job before it
 * runs, so an aborted assembly stops at the next node boundary and in-flight
 * suspend runners cancel at their next suspension point ([runAllIn]).
 * SupervisorJob is already the substrate (CCEK.childScope) — failure
 * isolation is a fact, not a feature.
 */
class LcncRunner(private val registry: Map<String, LcncNodeRunner>) {

    /**
     * Loader for [LcncNode.subprogram] internals. Null (default) = no loader
     * wired: a node carrying a subprogram runs as before, as a leaf — the
     * historic flat-sweep behaviour. Wire one (production:
     * `ModuleContext.programLoader`; tests: an in-memory map) and the walk
     * recurses: the child's `scope.in` nodes bind the caller's gathered
     * inputs, the child runs to completion, and the node's output is the
     * child's gathered `scope.out` returns (spec §3.5) — inner node outputs
     * are locals and never cross the boundary. Scope entry pushes a frame:
     * concentric scopes ARE the frame chain (spec §2).
     */
    var subprogramLoader: (suspend (String) -> LcncProgram?)? = null

    /**
     * Fires on every scope entry with the walk's scope path (subprogram
     * names, outermost first) and the frame chain at that depth — §2's
     * invariant made observable: the executor's chain must equal
     * [ProgramNavigator]'s for the same dive path (test-pinned).
     */
    var onScopeEnter: ((path: List<String>, chain: FrameIdChain) -> Unit)? = null

    /** Deepest scope nesting a single walk may enter — a cycle of subprogram
     *  references is a data error and must surface, not stack-overflow. */
    var maxScopeDepth: Int = 16

    class LcncScopeDepthExceeded(val path: List<String>) :
        Exception("scope nesting exceeded ${path.size}: ${path.joinToString(" ▸ ")}")

    /** Ports that accept many wires simultaneously, per contract. */
    private fun isManyInput(type: String, port: String): Boolean =
        LcncContracts.find(type)?.cardinality?.get(port.removeSuffix("?")) == LcncCardinality.MANY

    /** Required (non-optional) inputs per contract; null when the type has no contract. */
    private fun requiredInputs(type: String): Set<String>? =
        LcncContracts.find(type)?.inputs
            ?.filter { !it.endsWith("?") }
            ?.map { it.removeSuffix("?") }
            ?.toSet()

    /** A completed scope walk: every node's outputs plus the gathered `scope.out` returns. */
    data class ScopeResult(
        val nodeOutputs: Map<String, Map<String, Any?>>,
        val returns: Map<String, Any?>,
    )

    suspend fun runAll(program: LcncProgram): Map<String, Map<String, Any?>> =
        runProcedure(program).nodeOutputs

    /**
     * Contract line 1: run [program] as the outermost ring — [args] are the
     * root bindings, children run in authored order, and the ring's
     * `scope.out` yields come back as [ScopeResult.returns].
     */
    suspend fun runProcedure(program: LcncProgram, args: Map<String, Any?> = emptyMap()): ScopeResult {
        val state = WalkState(program)
        val root = LcncScopeFrame(bindings = args, chain = FrameIdChain.root(ROOT_SCOPE))
        val returns = withContext(root) {
            runRing(program.nodes, state, root, emptyList())
        }
        return ScopeResult(root.outputs, returns)
    }

    /** Per-document walk state: the node universe, each node's ring path,
     *  wires by target, and the authored sweep's visited set. */
    private class WalkState(program: LcncProgram) {
        val index = HashMap<String, LcncNode>()
        val ringPath = HashMap<String, List<String>>()
        val wiresTo = HashMap<String, MutableList<LcncWire>>()
        val visited = HashSet<String>()

        init {
            fun walk(nodes: Series<LcncNode>, path: List<String>) {
                for (i in 0 until nodes.size) {
                    val n = nodes[i]
                    require(index.put(n.id, n) == null) { "duplicate node id '${n.id}' — ids are document-wide identities" }
                    ringPath[n.id] = path
                    if (n.children.size > 0) walk(n.children, path + n.id)
                }
            }
            walk(program.nodes, emptyList())
            for (i in 0 until program.wires.size) {
                val w = program.wires[i]
                wiresTo.getOrPut(w.toNode) { mutableListOf() }.add(w)
            }
        }
    }

    private fun isPrefix(a: List<String>, b: List<String>): Boolean =
        a.size <= b.size && a.indices.all { a[it] == b[it] }

    /** Gather a node's inputs through the frame chain — the warm base: an
     *  enclosing ring's outputs reach an inner consumer with zero plumbing. */
    private fun gather(node: LcncNode, state: WalkState, frame: LcncScopeFrame): LinkedHashMap<String, Any?> {
        val gathered = LinkedHashMap<String, MutableList<Any?>>()
        for (wire in state.wiresTo[node.id].orEmpty()) {
            val srcNode = state.index[wire.fromNode] ?: continue // stale wire from a deleted node: tolerated
            val srcPath = state.ringPath.getValue(srcNode.id)
            val dstPath = state.ringPath.getValue(node.id)
            // Data flows lateral or inward — never outward, never cousin-to-cousin.
            if (!isPrefix(srcPath, dstPath)) throw LcncScopeViolation(wire.fromNode, wire.toNode)
            val fromOut = frame.outputsOf(wire.fromNode)
            if (fromOut == null) {
                // Authored order: consuming a statement that has not run yet is loud.
                if (wire.fromNode !in state.visited) throw LcncUseBeforeDef(wire.fromNode, wire.toNode)
                continue // ran but produced nothing: the port stays absent, silently
            }
            gathered.getOrPut(wire.toPort) { mutableListOf() }.add(fromOut[wire.fromPort])
        }
        val inputs = LinkedHashMap<String, Any?>()
        for ((port, values) in gathered) {
            if (isManyInput(node.type, port)) {
                inputs[port] = if (values.size == 1) values[0] else values.toList()
            } else {
                inputs[port] = values.lastOrNull()
            }
        }
        return inputs
    }

    private suspend fun runRing(
        nodes: Series<LcncNode>,
        state: WalkState,
        frame: LcncScopeFrame,
        pathNames: List<String>,
    ): Map<String, Any?> {
        val returns = LinkedHashMap<String, Any?>()

        for (i in 0 until nodes.size) {
            // Cooperative cancellation between statements: ABORT stops the walk
            // here; in-flight runners cancel at their next suspension point.
            currentCoroutineContext().job.ensureActive()
            val node = nodes[i]
            state.visited.add(node.id)

            // scope.in never runs a runner — it IS the binding: the name
            // resolves outward through the frame chain (nearest ring shadows),
            // then the declared default.
            if (node.type == LcncContracts.SCOPE_IN) {
                val name = node.params["name"]?.removeSuffix("?")
                when {
                    name != null && frame.hasBinding(name) ->
                        frame.outputs[node.id] = _m["value" j frame.binding(name)]
                    node.params.containsKey("default") ->
                        frame.outputs[node.id] = _m["value" j node.params["default"]]
                    // unbound and defaultless: the port stays absent downstream
                }
                continue
            }

            val inputs = gather(node, state, frame)

            // scope.out gathers this ring's yield — the ONLY thing that crosses out.
            if (node.type == LcncContracts.SCOPE_OUT) {
                val name = node.params["name"]?.removeSuffix("?")
                val fed = "value" in inputs || "value?" in inputs
                if (fed) {
                    if (name != null) returns[name] = inputs["value"] ?: inputs["value?"]
                    frame.outputs[node.id] = emptyMap()
                }
                continue
            }

            // A ring: inline children, or a NAMED ring (lazy containment — the
            // loaded body runs under the same rules and still sees this
            // environment; never a call into a vacuum).
            val loader = subprogramLoader
            val subName = node.subprogram
                ?: node.params["program"]?.takeIf { node.type == LcncContracts.SCOPE && it.isNotBlank() }
            val inline = node.children.size > 0
            if (inline || (subName != null && loader != null)) {
                // if (cond) { ring }: a falsy guard skips — yields stay absent.
                val guard = if ("when" in inputs) inputs["when"] else inputs["when?"]
                if (guard == false || guard == "false") continue

                val ringName = subName ?: node.id
                // A reference cycle is a data error, not a stack overflow.
                if (pathNames.size >= maxScopeDepth) throw LcncScopeDepthExceeded(pathNames + ringName)

                val bodyNodes: Series<LcncNode>
                val bodyState: WalkState
                if (inline) {
                    bodyNodes = node.children
                    bodyState = state
                } else {
                    // A missing named body is a data error, never a silent leaf.
                    val doc = loader!!(subName!!) ?: throw LcncUnknownNodeType(subName)
                    bodyNodes = doc.nodes
                    bodyState = WalkState(doc)
                }

                // Install the envelope: the generic args? map merges UNDER the
                // per-name wires (per-name wins); `when` guards, never binds.
                val bound = LinkedHashMap<String, Any?>()
                ((inputs["args"] ?: inputs["args?"]) as? Map<*, *>)?.forEach { (k, v) -> bound[k.toString()] = v }
                for ((port, v) in inputs) {
                    val p = port.removeSuffix("?")
                    if (p != "args" && p != "when") bound[p] = v
                }
                // Required = the body's non-optional scope.in names, satisfiable
                // by the envelope OR the enclosing chain — rings are blocks.
                if (requiredScopeIns(bodyNodes).any { !bound.containsKey(it) && !frame.hasBinding(it) }) continue

                val childChain = FrameIdChain.append(frame.chain, ringName)
                val childFrame = LcncScopeFrame(bindings = bound, chain = childChain, parent = frame)
                onScopeEnter?.invoke(pathNames + ringName, childChain)
                // Ring entry IS withContext: any suspend runner in the subtree
                // reads currentCoroutineContext()[LcncScopeFrame] — block
                // compatibility through the context machinery, not the grammar.
                val yielded = withContext(childFrame) {
                    runRing(bodyNodes, bodyState, childFrame, pathNames + ringName)
                }
                frame.outputs[node.id] = yielded + ("returns" to yielded)
                continue
            }

            // Annotation nodes — zero-port contracts (`note`, `program.ref`) —
            // are presentation, not statements. The walk passes them by.
            val contract = LcncContracts.find(node.type)
            if (contract != null && contract.inputs.isEmpty() && contract.outputs.isEmpty()) continue

            // Readiness: every REQUIRED input must be fed (silent degrade).
            val required = requiredInputs(node.type)
                ?: state.wiresTo[node.id].orEmpty().map { it.toPort }.filterNot { it.endsWith("?") }.toSet()
            if (required.any { req -> inputs[req] == null }) continue

            val runner = registry[node.type] ?: throw LcncUnknownNodeType(node.type)
            frame.outputs[node.id] = runner.run(node, inputs)
        }
        return returns
    }

    /** The body's non-optional `scope.in` names — a trailing `?` on the name or a declared default opts out. */
    private fun requiredScopeIns(body: Series<LcncNode>): List<String> {
        val req = ArrayList<String>()
        for (i in 0 until body.size) {
            val n = body[i]
            if (n.type != LcncContracts.SCOPE_IN) continue
            val name = n.params["name"] ?: continue
            if (name.endsWith("?")) continue
            if (n.params.containsKey("default")) continue
            req.add(name)
        }
        return req
    }
}

/**
 * Run a program inside a CCEK-owned scope: the returned Deferred is bound to
 * [scope]'s Job, so cancelling the assembly's scope cancels the walk and any
 * in-flight suspend runners — the ABORT verb lowered to structured concurrency.
 */
fun LcncRunner.runAllIn(scope: CoroutineScope, program: LcncProgram) =
    scope.async { runAll(program) }
