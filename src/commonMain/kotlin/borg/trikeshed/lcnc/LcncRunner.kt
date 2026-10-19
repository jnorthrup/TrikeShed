package borg.trikeshed.lcnc

import borg.trikeshed.collections._m
import borg.trikeshed.lib.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

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
    /** Explicit implementation contract: this runner performs no effects outside the walk. */
    val isPure: Boolean get() = false

    suspend fun execute(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?>

    /** Identity outputs name the actual selected input. Metadata only: never dispatch or evaluate a guest. */
    fun outputSources(node: LcncNode, inputs: Map<String, Any?>): Map<String, String> = emptyMap()

    /** Project already produced values; branch choices use actual inputs, never dispatch again.
     * Retained lists keep [LcncProjection.Absent] slots until root export; preserve original positions. */
    fun retainedOutputs(
        node: LcncNode,
        inputs: Map<String, Any?>,
        retainedInputs: Map<String, Any?>,
        outputs: Map<String, Any?>,
    ): Map<String, Any?> {
        val origins = outputSources(node, inputs)
        return if (origins.isEmpty()) outputs else LinkedHashMap(outputs).also { projection ->
            for ((port, input) in origins) if (port in outputs) {
                if (input in retainedInputs) projection[port] = retainedInputs[input] else projection.remove(port)
            }
        }
    }

    /** Presence of already selected occurrences; computed outputs have no borrowed origin. */
    fun retainedOrigins(
        node: LcncNode,
        inputs: Map<String, Any?>,
        inputOrigins: Map<String, LcncBindingOrigins>,
        outputs: Map<String, Any?>,
        terms: LcncBindingTerms,
    ): Map<String, LcncBindingOrigins> = buildMap {
        for ((port, input) in outputSources(node, inputs)) if (port in outputs) {
            inputOrigins[input]?.let { put(port, it) }
        }
    }

    /** Observational only: no node dispatch, guest creation or source evaluation. */
    suspend fun sourceDebugging(node: LcncNode, inputs: Map<String, Any?>): Boolean = false

    suspend fun run(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> {
        currentCoroutineContext().ensureActive()
        val key = LcncNodeKey.of(node.type)
        check(LcncContracts.find(node.type) == null || key != null) {
            "${node.type}: palette contract has no invocation key"
        }
        return if (key == null) execute(node, inputs)
            else key.construct(node, inputs, this, currentCoroutineContext().job).execute()
    }
}

/** Pure builtin implementations opt in; replacing their registered name does not inherit this contract. */
fun interface LcncPureRunner : LcncNodeRunner {
    override val isPure: Boolean get() = true
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
 * SupervisorJob is already the substrate — failure
 * isolation is a fact, not a feature.
 */
class LcncRunner(registry: Map<String, LcncNodeRunner>) {
    /** Admission and late-binding dispatch classify against the same invocation vocabulary. */
    private val registry = registry.toMap()
    private val pureRunnerTypes = this.registry.filterValues { it.isPure }.keys.toSet()

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

    /** The activity ring this walk fills (node begin/end, rings, yields, loop turns); null fills nothing. */
    var trail: LcncTrail? = null
    private var trailRun = -1
    private var trailProgram = ""
    private fun mark(kind: LcncTrail.Kind, path: List<String>, node: LcncNode, note: String? = null) {
        val t = trail ?: return
        t.emit(trailRun, kind, t.node(trailProgram, path, node.id, node.type), note)
    }
    var maxNodeExecutions: Int = 10000
    private var executedNodes: Int = 0
    private val argumentBindings = ArrayList<Map<String, Any?>>()
    private var bindingsTruncated = false
    private val retainedProjections = ArrayList<() -> Unit>()
    private var retainedWork = 0
    private var retainedUnavailable = false
    private var retainedReason = "work_limit"
    private var bindingTerms: LcncBindingTerms? = null
    private var cachedOrigins: Map<String, Map<String, LcncBindingOrigins>> = emptyMap()
    private val cachedValues = LinkedHashMap<String, Map<String, Any?>>()

    private fun projectionWork(): Boolean {
        if (retainedUnavailable) return false
        if (++retainedWork <= 65536) return true
        retainedUnavailable = true
        retainedProjections.clear()
        return false
    }

    private inline fun projection(crossinline value: () -> Unit) {
        if (projectionWork()) retainedProjections.add { value() }
    }

    /** Partition the authenticated global table once; node/port prefixes are not value paths. */
    private fun cachedOrigins(terms: LcncBindingTerms, retained: LcncRetainedFrame): Map<String, Map<String, LcncBindingOrigins>> {
        val nodes = LinkedHashMap<String, MutableMap<String, MutableList<Join<LcncBindingPath, Int>>>>()
        val decoded = terms.decode(retained.origins, retained.bindings.keys)
        terms.validate(retained.outputs, decoded)
        val live = terms.evaluate(retained.bindings.keys)
        for (row in decoded.view) {
            terms.account()
            require(live[row.b]) { "binding_origin_occurrence" }
            val path = row.a
            require(path.size >= 2) { "binding_origin_path" }
            val node = requireNotNull(path[0].leftOrNull) { "binding_origin_path" }
            val port = requireNotNull(path[1].leftOrNull) { "binding_origin_path" }
            require(retained.outputs[node]?.containsKey(port) == true) { "binding_origin_path" }
            val nested: LcncBindingPath = (path.size - 2) j { index: Int -> path[index + 2] }
            nodes.getOrPut(node) { LinkedHashMap() }.getOrPut(port) { ArrayList() }.add(nested j row.b)
        }
        return nodes.mapValues { (_, ports) -> ports.mapValues { it.value.toSeries() } }
    }

    private fun returnOrigins(frame: LcncScopeFrame): Map<String, LcncBindingOrigins> {
        val terms = bindingTerms ?: return emptyMap()
        val result = LinkedHashMap<String, LcncBindingOrigins>()
        val fields = ArrayList<LcncBindingOrigins>()
        for (name in frame.retainedReturns.keys) {
            val rows = frame.retainedReturnOrigins[name] ?: emptySeriesOf()
            result[name] = rows
            fields.add(terms.prefix(rows, s_[Either.left<String, Int>(name)]))
        }
        result["returns"] = terms.join(fields.toSeries())
        return result
    }

    class LcncWorkLimitExceeded : Exception("node execution work_limit exceeded")

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
        val bindings: List<Map<String, Any?>> = emptyList(),
        val bindingsTruncated: Boolean = false,
        /** What the run READ (LcncConsumedLedger entries), and one fingerprint over their cids. */
        val consumed: List<Map<String, Any?>> = emptyList(),
        val consumedTruncated: Boolean = false,
        val inputFingerprint: String? = null,
        val retained: LcncRetainedFrame = LcncRetainedFrame(),
    )

    /**
     * The consumed-input ledger this walk records into, installed with the root
     * frame so a reading runner anywhere in the ring tree finds it in its context.
     * Null records nothing (a bare walk).
     */
    var ledger: LcncConsumedLedger? = null

    /** Optional bounded per-invocation evidence, also available when the walk throws. */
    var invocations: LcncInvocationLedger? = null

    /** Verified retained frame plus one control event; null executes the full program. */
    var pulse: LcncPulse? = null
    private var activePulse: LcncPulse? = null
    private var pulseRoots: Set<String> = emptySet()

    suspend fun runAll(program: LcncProgram): Map<String, Map<String, Any?>> =
        runProcedure(program).nodeOutputs

    /**
     * Contract line 1: run [program] as the outermost ring — [args] are the
     * root bindings, children run in authored order, and the ring's
     * `scope.out` yields come back as [ScopeResult.returns].
     */
    suspend fun runProcedure(program: LcncProgram, args: Map<String, Any?> = emptyMap()): ScopeResult {
        require(!program.controls.inspectionOnly) { "Inspection-only wiring specimen; execution is disabled" }
        activePulse = pulse?.let { it.copy(outputs = it.outputs.toMap(), definitions = it.definitions.toMap()) }
        pulseRoots = activePulse?.roots(program, registry.keys, pureRunnerTypes) ?: emptySet()
        executedNodes = 0
        invocations?.reset()
        trail?.let { trailProgram = program.name; trailRun = it.run(program.name) }
        argumentBindings.clear()
        bindingsTruncated = false
        retainedProjections.clear()
        retainedWork = 0
        retainedUnavailable = false
        retainedReason = "work_limit"
        cachedValues.clear()
        cachedOrigins = emptyMap()
        bindingTerms = if (activePulse == null || activePulse?.retained?.origins != null) LcncBindingTerms() else null
        val state = WalkState(program)
        val rootBindings = activePulse?.retained?.bindings ?: args
        val root = LcncScopeFrame(bindings = rootBindings, chain = FrameIdChain.root(ROOT_SCOPE), bindingSources = rootBindings.keys.associateWith { "invocation" })
        bindingTerms?.let { terms ->
            activePulse?.retained?.let {
                try { cachedOrigins = cachedOrigins(terms, it) }
                catch (failure: IllegalArgumentException) { throw IllegalArgumentException("pulse_state_unavailable", failure) }
            }
        }
        try {
            bindingTerms?.let { terms ->
                for (name in rootBindings.keys) root.retainedBindingOrigins[name] = terms.guard(terms.binding(name))
            }
        } catch (limit: LcncBindingTerms.Limit) {
            retainedUnavailable = true; retainedReason = limit.reason
        }
        val ledger = this.ledger
        val returns = try {
            val result = lcncContext(if (ledger == null) root else root + ledger) {
                runRing(program.nodes, state, root, emptyList())
            }
            try {
                if (!retainedUnavailable) {
                    // Only cached values need a mask before propagation; actual receipts stay untouched.
                    bindingTerms?.let { terms ->
                        val live = terms.evaluate(root.retainedBindings.keys)
                        for ((node, ports) in cachedValues.toMap()) {
                            val projected = LinkedHashMap<String, Any?>()
                            for ((port, value) in ports) {
                                val rows = cachedOrigins[node]?.get(port) ?: emptySeriesOf()
                                val masked = if (rows.size == 0) value else terms.mask(value, rows, live)
                                if (masked !== LcncProjection.Absent) projected[port] = masked
                            }
                            cachedValues[node] = projected
                        }
                    }
                    // Actual execution registered every dependency before its consumer; binding expirations are final.
                    for (value in retainedProjections) {
                        currentCoroutineContext().ensureActive()
                        value()
                    }
                }
            } catch (limit: LcncBindingTerms.Limit) {
                retainedUnavailable = true; retainedReason = limit.reason
            }
            result
        } finally { retainedProjections.clear() }
        return ScopeResult(
            root.outputs, returns, argumentBindings.toList(), bindingsTruncated,
            consumed = ledger?.entries()?.map { it.toMap() } ?: emptyList(),
            consumedTruncated = ledger?.truncated ?: false,
            inputFingerprint = ledger?.fingerprint(),
            retained = if (retainedUnavailable) LcncRetainedFrame(unavailable = retainedReason)
                else try { LcncProjection.frame(root.retainedOutputs, root.retainedBindings, bindingTerms, root.retainedOutputOrigins) }
                catch (limit: LcncBindingTerms.Limit) { LcncRetainedFrame(unavailable = limit.reason) },
        )
    }

    /** Per-document walk state: the node universe, each node's ring path,
     *  wires by target, and the authored sweep's visited set. */
    private class WalkState(program: LcncProgram) {
        val index = HashMap<String, LcncNode>()
        val ringPath = HashMap<String, List<String>>()
        val wiresTo = HashMap<String, MutableList<LcncWire>>()
        val visited = HashSet<String>()
        private val contracts = LcncContracts.all().associateBy { it.type }
        val kind = LcncTypeCheck.portKindResolver(program, contracts, LcncFacts.of(contracts.values))

        init {
            fun walk(nodes: Series<LcncNode>, path: List<String>) {
                for (n in nodes.view) {
                    require(index.put(n.id, n) == null) { "duplicate node id '${n.id}' — ids are document-wide identities" }
                    ringPath[n.id] = path
                    if (n.children.size > 0) walk(n.children, path + n.id)
                }
            }
            walk(program.nodes, emptyList())
            for (w in program.wires.view) {
                wiresTo.getOrPut(w.toNode) { mutableListOf() }.add(w)
            }
        }
    }

    private fun isPrefix(a: List<String>, b: List<String>): Boolean =
        a.size <= b.size && a.indices.all { a[it] == b[it] }

    /** Gather a node's inputs through the frame chain — the warm base: an
     *  enclosing ring's outputs reach an inner consumer with zero plumbing. */
    private fun gather(node: LcncNode, state: WalkState, frame: LcncScopeFrame,
                       retained: MutableMap<String, Any?>,
                       origins: MutableMap<String, LcncBindingOrigins>): LinkedHashMap<String, Any?> {
        val gathered = LinkedHashMap<String, MutableList<Any?>>()
        val feeders = ArrayList<Join<LcncWire, LcncScopeFrame>>()
        for (wire in state.wiresTo[node.id].orEmpty()) {
            val srcNode = state.index[wire.fromNode] ?: continue // stale wire from a deleted node: tolerated
            val srcPath = state.ringPath.getValue(srcNode.id)
            val dstPath = state.ringPath.getValue(node.id)
            // Data flows lateral or inward — never outward, never cousin-to-cousin.
            if (!isPrefix(srcPath, dstPath)) throw LcncScopeViolation(wire.fromNode, wire.toNode)
            val owner = frame.outputOwner(wire.fromNode)
            val fromOut = owner?.outputs?.get(wire.fromNode)
            if (fromOut == null) {
                // Authored order: consuming a statement that has not run yet is loud.
                if (wire.fromNode !in state.visited) throw LcncUseBeforeDef(wire.fromNode, wire.toNode)
                continue // ran but produced nothing: the port stays absent, silently
            }
            if (fromOut.containsKey(wire.fromPort)) {
                gathered.getOrPut(wire.toPort) { mutableListOf() }.add(fromOut[wire.fromPort])
                if (projectionWork()) feeders.add(wire j checkNotNull(owner)) else feeders.clear()
            }
        }
        val inputs = LinkedHashMap<String, Any?>()
        for ((port, values) in gathered) inputs[port] =
            if (isManyInput(node.type, port) && values.size > 1) values.toList() else values.lastOrNull()
        projection {
            val retainedMany = LinkedHashMap<String, MutableList<Any?>>()
            val manyOrigins = LinkedHashMap<String, MutableList<LcncBindingOrigins>>()
            val terms = bindingTerms
            for ((wire, owner) in feeders) {
                val projected = owner.retainedOutputs[wire.fromNode].orEmpty()
                val rows = terms?.let {
                    if (wire.fromPort in projected) owner.retainedOutputOrigins[wire.fromNode]?.get(wire.fromPort) ?: emptySeriesOf()
                    else it.guard(LcncBindingTerms.FALSE)
                }
                if (isManyInput(node.type, wire.toPort)) {
                    retainedMany.getOrPut(wire.toPort) { mutableListOf() }.add(
                        if (wire.fromPort in projected) projected[wire.fromPort] else LcncProjection.Absent
                    )
                    if (rows != null) manyOrigins.getOrPut(wire.toPort) { ArrayList() }.add(rows)
                } else {
                    // Only the actual last fed wire can supply this input, even if it expired.
                    if (wire.fromPort in projected) retained[wire.toPort] = projected[wire.fromPort]
                    else retained.remove(wire.toPort)
                    if (rows != null) origins[wire.toPort] = rows
                }
            }
            for ((port, values) in retainedMany) {
                if (values.any { it !== LcncProjection.Absent })
                    retained[port] = if (gathered.getValue(port).size == 1) values[0] else values.toList()
                if (terms != null) {
                    val rows = manyOrigins.getValue(port)
                    origins[port] = if (rows.size == 1) rows[0] else {
                        val parts = ArrayList<LcncBindingOrigins>()
                        for ((index, value) in rows.withIndex()) parts.add(terms.prefix(value, s_[Either.right<String, Int>(index)]))
                        parts.add(terms.guard(terms.any(rows.toSeries() α { terms.presence(it) })))
                        terms.join(parts.toSeries())
                    }
                }
            }
        }
        return inputs
    }

    private suspend fun runRing(
        nodes: Series<LcncNode>,
        state: WalkState,
        frame: LcncScopeFrame,
        pathNames: List<String>,
        parentInvocation: Int? = null,
        iteration: Int? = null,
    ): Map<String, Any?> {
        val returns = LinkedHashMap<String, Any?>()

        for (node in nodes.view) {
            // Cooperative cancellation between statements: ABORT stops the walk
            // here; in-flight runners cancel at their next suspension point.
            currentCoroutineContext().job.ensureActive()
            if (++executedNodes > maxNodeExecutions) throw LcncWorkLimitExceeded()
            state.visited.add(node.id)

            val ordinal = executedNodes
            val invocation = invocations?.begin(ordinal, node, pathNames, parentInvocation, iteration)
            var status = "completed"
            var reason: String? = null
            var observedOutputs: Map<String, Any?>? = null
            fun publish(value: Map<String, Any?>,
                        origins: () -> Map<String, LcncBindingOrigins> = { emptyMap() },
                        retained: () -> Map<String, Any?> = {
                value.filterKeys { !LcncPulse.event(node, it, state.kind(node, "out", it).kind) }
            }) {
                frame.outputs[node.id] = value
                observedOutputs = value
                projection {
                    val projected = retained()
                    frame.retainedOutputs[node.id] = projected
                    frame.retainedOutputOrigins[node.id] = origins().filterKeys { it in projected }
                }
            }
            fun skip(why: String) { status = "skipped"; reason = why }
            try {

                val event = activePulse
                if (event != null && pathNames.isEmpty() && node.id !in pulseRoots) {
                    event.retained.outputs[node.id]?.let { retained ->
                        cachedValues[node.id] = retained
                        publish(retained, origins = { cachedOrigins[node.id].orEmpty() }) { cachedValues.getValue(node.id) }
                    }
                    status = "reused"
                    reason = "retained_root_frame"
                    continue
                }

                // scope.in never runs a runner — it IS the binding: the name
                // resolves outward through the frame chain (nearest ring shadows),
                // then the declared default.
                if (node.type == LcncContracts.SCOPE_IN) {
                    invocation?.capture("inputs", emptyMap<String, Any?>())
                    currentCoroutineContext()[LcncDebug]?.checkpoint(ordinal, node, pathNames, parentInvocation, iteration, emptyMap())
                    val name = node.params["name"]?.removeSuffix("?")
                    val installed = checkNotNull(currentCoroutineContext()[LcncScopeFrame])
                    check(installed === frame) { "scope frame differs from installed CoroutineContext element" }
                    val owner = name?.let(installed::bindingOwner)
                    val eventBinding = LcncPulse.event(state.kind(node, "out", "value").kind)
                    if (eventBinding && name != null) {
                        owner?.retainedBindings?.remove(name)
                        owner?.retainedBindingOrigins?.remove(name)
                    }
                    when {
                        name != null && owner != null -> {
                            if (event != null && owner.parent == null && eventBinding) skip("expired_binding")
                            else publish(_m["value" j frame.binding(name)], origins = {
                                mapOf("value" to (owner.retainedBindingOrigins[name] ?: emptySeriesOf()))
                            }) {
                                if (name in owner.retainedBindings) _m["value" j owner.retainedBindings[name]] else emptyMap()
                            }
                        }
                        node.params.containsKey("default") ->
                            publish(_m["value" j node.params["default"]])
                        // unbound and defaultless: the port stays absent downstream
                        else -> skip("unbound")
                    }
                    if (argumentBindings.size < 1024) argumentBindings.add(linkedMapOf(
                        "nodeId" to node.id, "path" to pathNames, "name" to name,
                        "type" to (node.params["kind"] ?: "generic"),
                        "source" to when { owner != null -> owner.bindingSources[name] ?: "binding"; node.params.containsKey("default") -> "default"; else -> "unbound" },
                        "ownerDepth" to owner?.depth, "depth" to installed.depth,
                        "value" to frame.outputs[node.id]?.get("value"),
                        "status" to if (frame.outputs[node.id]?.containsKey("value") == true) "resolved" else "unbound",
                    )) else bindingsTruncated = true
                    continue
                }

                val retainedInputs = LinkedHashMap<String, Any?>()
                val inputOrigins = LinkedHashMap<String, LcncBindingOrigins>()
                val inputs = gather(node, state, frame, retainedInputs, inputOrigins)
                invocation?.capture("inputs", inputs)
                // Annotation-only nodes are not debugger stops. Rings and binding/yield nodes are:
                // stepping enters their real nested walk, including each/carry iterations.
                val debug = currentCoroutineContext()[LcncDebug]
                var sourceStep = false
                if (debug != null) {
                    val debugContract = LcncContracts.find(node.type)
                    if (debugContract == null || debugContract.inputs.isNotEmpty() || debugContract.outputs.isNotEmpty() ||
                        node.children.size > 0 || node.subprogram != null)
                        sourceStep = debug.checkpoint(ordinal, node, pathNames, parentInvocation, iteration, inputs,
                            sourceDebugging = registry[node.type]?.sourceDebugging(node, inputs) == true)
                }

                // scope.out gathers this ring's yield — the ONLY thing that crosses out.
                if (node.type == LcncContracts.SCOPE_OUT) {
                    val name = node.params["name"]?.removeSuffix("?")
                    val fed = "value" in inputs || "value?" in inputs
                    if (fed) {
                        if (name != null) {
                            returns[name] = inputs["value"] ?: inputs["value?"]
                            val port = if (inputs["value"] != null || "value?" !in inputs) "value" else "value?"
                            projection {
                                if (port in retainedInputs && !LcncPulse.event(state.kind(node, "in", "value").kind)) {
                                    frame.retainedReturns[name] = retainedInputs[port]
                                    frame.retainedReturnOrigins[name] = inputOrigins[port] ?: emptySeriesOf()
                                } else {
                                    frame.retainedReturns.remove(name)
                                    frame.retainedReturnOrigins.remove(name)
                                }
                            }
                        }
                        publish(emptyMap())
                        mark(LcncTrail.Kind.YIELD, pathNames, node, name)
                    } else skip("unfed")
                    continue
                }

                // A ring: inline children, or a NAMED ring (lazy containment — the
                // loaded body runs under the same rules and still sees this
                // environment; never a call into a vacuum).
                val loader = subprogramLoader
                val definitions = activePulse?.definitions.orEmpty()
                val subName = node.subprogram
                    ?: node.params["program"]?.takeIf { node.type == LcncContracts.SCOPE && it.isNotBlank() }
                    // LATE BINDING: a type that is neither a registered runner nor a
                    // compiled contract names a stored program — a COMPOSITE wrapper
                    // (LcncComposites). The name resolves through the loader when
                    // the walk reaches it, never at boot; the body runs as a named
                    // ring under the same rules. A name the loader cannot find
                    // still throws LcncUnknownNodeType below.
                    ?: node.type.takeIf { (loader != null || it in definitions) && it !in registry && LcncContracts.find(it) == null }
                val inline = node.children.size > 0
                if (inline || (subName != null && (loader != null || subName in definitions))) {
                    // if (cond) { ring }: a falsy guard skips — yields stay absent. An `else` ring runs
                    // only on a falsy guard; two rings on one guard, their yields joined by `merge`
                    // (typed a?: T, b?: T → T), are if/else with a checked merge.
                    val guard = if ("when" in inputs) inputs["when"] else inputs["when?"]
                    val falsy = guard == false || guard == "false"
                    if (falsy != (node.params["else"] == "true")) { skip("guard"); mark(LcncTrail.Kind.SKIP, pathNames, node, "guard"); continue }

                    val ringName = subName ?: node.id
                    mark(LcncTrail.Kind.RING, pathNames, node, ringName)
                    // A reference cycle is a data error, not a stack overflow.
                    if (pathNames.size >= maxScopeDepth) throw LcncScopeDepthExceeded(pathNames + ringName)

                    val bodyNodes: Series<LcncNode>
                    val bodyState: WalkState
                    var bodyDoc: LcncProgram? = null
                    if (inline) {
                        bodyNodes = node.children
                        bodyState = state
                    } else {
                        // A missing named body is a data error, never a silent leaf.
                        val doc = definitions[subName] ?: loader?.invoke(subName!!) ?: throw LcncUnknownNodeType(subName!!)
                        require(!doc.controls.inspectionOnly) { "Inspection-only wiring specimen: $subName" }
                        bodyNodes = doc.nodes
                        bodyState = WalkState(doc)
                        bodyDoc = doc
                    }

                    // Install the envelope: the generic args? map merges UNDER the
                    // per-name wires (per-name wins); `when` guards, never binds.
                    val bound = LinkedHashMap<String, Any?>()
                    val retainedBound = LinkedHashMap<String, Any?>()
                    val boundOrigins = LinkedHashMap<String, LcncBindingOrigins>()
                    val sources = LinkedHashMap<String, String>()
                    val envelope = if (inputs.containsKey("args")) inputs["args"] else inputs["args?"]
                    require(!inputs.containsKey("args") && !inputs.containsKey("args?") || envelope is Map<*, *>) {
                        "scope ${node.id}: args must be an object"
                    }
                    val envelopePort = if (inputs.containsKey("args")) "args" else "args?"
                    (envelope as? Map<*, *>)?.forEach { (k, v) ->
                        require(k is String) { "scope ${node.id}: argument names must be strings" }
                        bound[k] = v; sources[k] = "args"
                    }
                    for ((port, v) in inputs) {
                        val p = port.removeSuffix("?")
                        if (p != "args" && p != "when") {
                            bound[p] = v; sources[p] = "input"
                        }
                    }
                    projection {
                        val terms = bindingTerms
                        val retainedEnvelope = retainedInputs[envelopePort] as? Map<*, *>
                        (envelope as? Map<*, *>)?.keys?.forEach { key ->
                            if (retainedEnvelope?.containsKey(key) == true) {
                                retainedBound[key as String] = retainedEnvelope[key]
                                if (terms != null) boundOrigins[key] = terms.select(inputOrigins[envelopePort] ?: emptySeriesOf(), s_[Either.left<String, Int>(key)])
                            }
                        }
                        for (port in inputs.keys) {
                            val name = port.removeSuffix("?")
                            if (name != "args" && name != "when") {
                                if (port in retainedInputs) {
                                    retainedBound[name] = retainedInputs[port]
                                    boundOrigins[name] = inputOrigins[port] ?: emptySeriesOf()
                                } else {
                                    retainedBound.remove(name)
                                    boundOrigins.remove(name)
                                }
                            }
                        }
                    }
                    // for (item in each) { ring }: a fed `each?` runs the body once per element,
                    // binding the item name per iteration (source "each"); per-name yields
                    // collect into lists, `returns` becomes the list of per-iteration maps and
                    // `count` the length. The chain is the ring's, not the iteration's: the
                    // navigator dives one path per ring (LcncScopeSemanticsTest pins that).
                    val eachFed = inputs.containsKey("each") || inputs.containsKey("each?")
                    val itemName = node.params["item"]?.takeIf { it.isNotBlank() } ?: "item"
                    // Required = the body's non-optional scope.in names, satisfiable
                    // by the envelope OR the enclosing chain — rings are blocks. Under
                    // `each`, the item name is bound per iteration.
                    if (requiredScopeIns(bodyNodes).view.any { !bound.containsKey(it) && !frame.hasBinding(it) && !(eachFed && it == itemName) }) { skip("unbound"); continue }

                    val childChain = FrameIdChain.append(frame.chain, ringName)
                    if (eachFed) {
                        val eachPort = if (inputs.containsKey("each")) "each" else "each?"
                        val each = inputs[eachPort]
                        require(each is List<*>) { "scope ${node.id}: each must be a list, got ${each?.let { it::class.simpleName } ?: "null"}" }
                        val limit = node.params["limit"]?.toIntOrNull()?.coerceAtLeast(1) ?: 64
                        val iterations = ArrayList<Map<String, Any?>>()
                        val iterationFrames = ArrayList<LcncScopeFrame>()
                        val expiredNames = HashSet<String>()
                        val perName = LinkedHashMap<String, MutableList<Any?>>()
                        // Empty loops retain declared data lists, without inventing an event occurrence.
                        for (child in bodyNodes.view) if (child.type == LcncContracts.SCOPE_OUT) child.params["name"]?.removeSuffix("?")?.let {
                            perName.getOrPut(it) { ArrayList() }
                            if (each.isEmpty() && projectionWork() && LcncPulse.event(bodyState.kind(child, "in", "value").kind)) expiredNames.add(it)
                        }
                        for ((turn, element) in each.take(limit).withIndex()) {
                            mark(LcncTrail.Kind.TURN, pathNames, node, "item ${turn + 1}")
                            val iterBound = LinkedHashMap(bound).also { it[itemName] = element }
                            val iterSources = LinkedHashMap(sources).also { it[itemName] = "each" }
                            // Every execution gets its own binding owner, including identical-valued items.
                            val iterState = if (inline) state else WalkState(bodyDoc!!)
                            val iterFrame = LcncScopeFrame(bindings = iterBound, chain = childChain, parent = frame, bindingSources = iterSources)
                            projection {
                                val values = LinkedHashMap(retainedBound)
                                val origins = LinkedHashMap(boundOrigins)
                                val retainedEach = retainedInputs[eachPort] as? List<*>
                                if (retainedEach != null && turn < retainedEach.size && retainedEach[turn] !== LcncProjection.Absent) {
                                    values[itemName] = retainedEach[turn]
                                    bindingTerms?.let { terms -> origins[itemName] = terms.select(inputOrigins[eachPort] ?: emptySeriesOf(), s_[Either.right<String, Int>(turn)]) }
                                } else {
                                    values.remove(itemName)
                                    origins.remove(itemName)
                                }
                                iterFrame.retainBindings(values, origins)
                            }
                            onScopeEnter?.invoke(pathNames + ringName, childChain)
                            val yielded = lcncContext(iterFrame) {
                                runRing(bodyNodes, iterState, iterFrame, pathNames + ringName, ordinal, turn)
                            }
                            iterations.add(yielded)
                            if (retainedUnavailable) iterationFrames.clear() else iterationFrames.add(iterFrame)
                            for ((name, value) in yielded) perName.getOrPut(name) { ArrayList() }.add(value)
                        }
                        val eachOrigins = LinkedHashMap<String, LcncBindingOrigins>()
                        publish(perName.mapValues { it.value.toList() } +
                            ("returns" to iterations.toList()) + ("count" to iterations.size), origins = { eachOrigins }) {
                            val projectedIterations = iterationFrames.map { it.retainedReturns.toMap() }
                            val retainedPerName = LinkedHashMap<String, MutableList<Any?>>()
                            val terms = bindingTerms
                            val nameOrigins = LinkedHashMap<String, MutableList<LcncBindingOrigins>>()
                            val returnedOrigins = ArrayList<LcncBindingOrigins>()
                            for (name in perName.keys) retainedPerName[name] = ArrayList()
                            for ((index, yielded) in iterations.withIndex()) {
                                val projected = projectedIterations[index]
                                val origins = iterationFrames[index].retainedReturnOrigins
                                // Slots correspond to actual yielded occurrences, not every possible iteration.
                                for (name in yielded.keys) {
                                    retainedPerName.getValue(name).add(if (name in projected) projected[name] else LcncProjection.Absent)
                                    if (terms != null) nameOrigins.getOrPut(name) { ArrayList() }.add(
                                        if (name in projected) origins[name] ?: emptySeriesOf() else terms.guard(LcncBindingTerms.FALSE)
                                    )
                                }
                                if (terms != null) for (name in projected.keys) {
                                    val path: LcncBindingPath = s_[Either.right<String, Int>(index), Either.left<String, Int>(name)]
                                    returnedOrigins.add(terms.prefix(origins[name] ?: emptySeriesOf(), path))
                                }
                            }
                            if (terms != null) {
                                for ((name, rows) in nameOrigins) {
                                    val parts = ArrayList<LcncBindingOrigins>()
                                    for ((index, rowsAtIndex) in rows.withIndex())
                                        parts.add(terms.prefix(rowsAtIndex, s_[Either.right<String, Int>(index)]))
                                    parts.add(terms.guard(terms.any(rows.toSeries() α { terms.presence(it) })))
                                    eachOrigins[name] = terms.join(parts.toSeries())
                                }
                                eachOrigins["returns"] = terms.join(returnedOrigins.toSeries())
                                eachOrigins.remove("count") // The structural iteration count overwrites a same-named yield.
                            }
                            retainedPerName.filter { (name, values) ->
                                name !in expiredNames && (values.isEmpty() || values.any { it !== LcncProjection.Absent })
                            }.mapValues { it.value.toList() } +
                                ("returns" to projectedIterations) + ("count" to iterations.size)
                        }
                        continue
                    }
                    // while (…) { ring }: with `carry`, each iteration's yields rebind the same-named
                    // scope.in of the next (loop-carried values, source "carry"). The loop ends at the
                    // fixed point (carried yields unchanged), when the `until` yield is empty, or at `limit`.
                    if (node.params["carry"] == "true") {
                        val limit = node.params["limit"]?.toIntOrNull()?.coerceAtLeast(1) ?: 64
                        val until = node.params["until"]?.takeIf { it.isNotBlank() }
                        val carried = LinkedHashMap(bound); val carriedSources = LinkedHashMap(sources)
                        val retainedCarried = LinkedHashMap<String, Any?>()
                        val carriedOrigins = LinkedHashMap<String, LcncBindingOrigins>()
                        projection { retainedCarried.putAll(retainedBound); carriedOrigins.putAll(boundOrigins) }
                        var finalFrame: LcncScopeFrame? = null
                        var yielded: Map<String, Any?> = emptyMap()
                        var turns = 0
                        while (turns < limit) {
                            turns++
                            mark(LcncTrail.Kind.TURN, pathNames, node, "turn $turns")
                            val iterFrame = LcncScopeFrame(bindings = LinkedHashMap(carried), chain = childChain, parent = frame, bindingSources = LinkedHashMap(carriedSources))
                            projection { iterFrame.retainBindings(retainedCarried, carriedOrigins) }
                            onScopeEnter?.invoke(pathNames + ringName, childChain)
                            val next = lcncContext(iterFrame) {
                                runRing(bodyNodes, if (inline) state else WalkState(bodyDoc!!), iterFrame, pathNames + ringName, ordinal, turns - 1)
                            }
                            val fixed = turns > 1 && next == yielded
                            yielded = next
                            finalFrame = iterFrame
                            if (fixed || (until != null && empty(next[until]))) break
                            for ((name, value) in next) { carried[name] = value; carriedSources[name] = "carry" }
                            if (!retainedUnavailable) {
                                val names = next.keys.toSet()
                                projection {
                                    for (name in names) {
                                        if (name in iterFrame.retainedReturns) {
                                            retainedCarried[name] = iterFrame.retainedReturns[name]
                                            carriedOrigins[name] = iterFrame.retainedReturnOrigins[name] ?: emptySeriesOf()
                                        } else {
                                            retainedCarried.remove(name)
                                            carriedOrigins.remove(name)
                                        }
                                    }
                                }
                            }
                        }
                        val last = checkNotNull(finalFrame)
                        publish(yielded + ("returns" to yielded) + ("count" to turns), origins = { returnOrigins(last) - "count" }) {
                            val retained = last.retainedReturns.toMap()
                            retained + ("returns" to retained) + ("count" to turns)
                        }
                        continue
                    }
                    val childFrame = LcncScopeFrame(bindings = bound, chain = childChain, parent = frame, bindingSources = sources)
                    projection { childFrame.retainBindings(retainedBound, boundOrigins) }
                    onScopeEnter?.invoke(pathNames + ringName, childChain)
                    // Ring entry IS withContext: any suspend runner in the subtree
                    // reads currentCoroutineContext()[LcncScopeFrame] — block
                    // compatibility through the context machinery, not the grammar.
                    val yielded = lcncContext(childFrame) {
                        runRing(bodyNodes, bodyState, childFrame, pathNames + ringName, ordinal)
                    }
                    publish(yielded + ("returns" to yielded), origins = { returnOrigins(childFrame) }) {
                        val retained = childFrame.retainedReturns.toMap()
                        retained + ("returns" to retained)
                    }
                    continue
                }

                // Annotation nodes — zero-port contracts (`note`, `program.ref`) —
                // are presentation, not statements. The walk passes them by.
                val contract = LcncContracts.find(node.type)
                if (contract != null && contract.inputs.isEmpty() && contract.outputs.isEmpty()) { skip("annotation"); continue }

                // Readiness: every REQUIRED input must be fed (silent degrade).
                val required = requiredInputs(node.type)
                    ?: state.wiresTo[node.id].orEmpty().map { it.toPort }.filterNot { it.endsWith("?") }.toSet()
                if (required.any { req -> inputs[req] == null }) { skip("unfed"); mark(LcncTrail.Kind.SKIP, pathNames, node, "unfed"); continue }

                val runner = registry[node.type] ?: throw LcncUnknownNodeType(node.type)
                mark(LcncTrail.Kind.BEGIN, pathNames, node)
                val produced = try {
                    if (invocation == null && !sourceStep) runner.run(node, inputs)
                    else {
                        val sourceContext = if (sourceStep) borg.trikeshed.vm.VmDebugRequest(currentCoroutineContext()) {
                            checkNotNull(debug).sourceCheckpoint(ordinal, node.id, it)
                        } else kotlin.coroutines.EmptyCoroutineContext
                        lcncContext((invocation ?: kotlin.coroutines.EmptyCoroutineContext) + sourceContext) { runner.run(node, inputs) }
                    }
                }
                    catch (failure: Throwable) { mark(LcncTrail.Kind.FAIL, pathNames, node, failure.message); throw failure }
                val output = if (event != null && pathNames.isEmpty() && node.id == event.nodeId)
                    event.sourceOutput(node, produced) else produced
                publish(output, origins = {
                    bindingTerms?.let { runner.retainedOrigins(node, inputs, inputOrigins, output, it) } ?: emptyMap()
                }) {
                    runner.retainedOutputs(node, inputs, retainedInputs, output)
                        .filterKeys { !LcncPulse.event(node, it, state.kind(node, "out", it).kind) }
                }
                mark(LcncTrail.Kind.END, pathNames, node, if (trail != null) LcncTrail.summary(produced) else null)
            } catch (failure: Throwable) {
                status = if (failure is kotlinx.coroutines.CancellationException) "cancelled" else "failed"
                reason = failure.message ?: failure::class.simpleName
                throw failure
            } finally {
                invocation?.finish(status, reason, observedOutputs)
            }
        }
        return returns
    }

    /** A loop's `until` yield ends it when absent, false, zero, or an empty text/list/map. */
    private fun empty(v: Any?): Boolean = when (v) {
        null, false, "false", "" -> true
        is Number -> v.toDouble() == 0.0
        is Collection<*> -> v.isEmpty()
        is Map<*, *> -> v.isEmpty()
        else -> false
    }

    /** The body's non-optional `scope.in` names — a trailing `?` on the name or a declared default opts out. */
    private fun requiredScopeIns(body: Series<LcncNode>): Series<String> =
        body.filter { n ->
            n.type == LcncContracts.SCOPE_IN &&
                n.params["name"]?.endsWith("?") == false &&
                !n.params.containsKey("default")
        } α { it.params.getValue("name") }
}

/**
 * Run a program inside a CCEK-owned scope: the returned Deferred is bound to
 * [scope]'s Job, so cancelling the assembly's scope cancels the walk and any
 * in-flight suspend runners — the ABORT verb lowered to structured concurrency.
 */
fun LcncRunner.runAllIn(scope: CoroutineScope, program: LcncProgram) =
    scope.async { runAll(program) }
