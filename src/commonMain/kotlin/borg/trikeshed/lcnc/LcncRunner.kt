package borg.trikeshed.lcnc

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

/**
 * One node type's behavior. Unlike `panels.html`'s node types — which can
 * only ever be an HTTP call out to the JVM daemon, because browser JS has no
 * other way to reach it — an [LcncNodeRunner] is a plain Kotlin function.
 * Registered against real project logic (`CausalityRete`, `Nal`, whatever),
 * it runs THAT LOGIC IN-PROCESS: no serialization, no round trip, no shim.
 *
 * W1.4: this signature IS a CCEK agent signature — `suspend`, hostable by
 * ArticulatedNode's bounded fan-out (Phase 1).
 */
fun interface LcncNodeRunner {
    suspend fun run(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?>
}

class LcncUnknownNodeType(val type: String) : Exception("no runner registered for node type '$type'")

/**
 * Topological executor over an [LcncProgram]. Semantics (verified against
 * `panels.html`'s `runNode()`):
 *
 *  - A node's inputs are the named outputs of whatever wires target it.
 *  - MANY-cardinality input ports collect EVERY upstream branch into a
 *    `List<Any?>`; scalar ports keep last-write-wins single values. The old
 *    code was uniformly last-write-wins — silently discarding all but one
 *    branch of a fan-in, fatal for vote-style assemblies (W1.4 fix).
 *  - An unwired or unfed REQUIRED input skips the node silently: with a
 *    contract, required = inputs not ending `?`; without one (legacy/unknown
 *    type), the same wire-name heuristic panels.html uses applies.
 *  - A node whose upstream produced nothing keeps its downstream ports
 *    absent; consumers with required inputs degrade silently, matching JS.
 *
 * Cancellation is cooperative: each node checks the caller's Job before it
 * runs, so an aborted assembly stops at the next node boundary and in-flight
 * suspend runners cancel at their next suspension point ([runAllIn]).
 */
class LcncRunner(private val registry: Map<String, LcncNodeRunner>) {

    /**
     * Loader for [LcncNode.subprogram] internals. Null (default) = no loader
     * wired: a node carrying a subprogram runs as before, as a leaf — the
     * historic flat-sweep behaviour. Wire one (e.g. fetch
     * `panels/<name>` via CouchAttachmentGateway, or an in-memory map in
     * tests) and `runAll` recurses: the inner program runs to completion and
     * the node's output is the inner outputs map. Scope entry pushes a frame:
     * concentric scopes ARE the frame chain (plan step 3).
     */
    var subprogramLoader: (suspend (String) -> LcncProgram?)? = null

    /** Ports that accept many wires simultaneously, per contract. */
    private fun isManyInput(type: String, port: String): Boolean =
        LcncContracts.find(type)?.cardinality?.get(port.removeSuffix("?")) == LcncCardinality.MANY

    /** Required (non-optional) inputs per contract; null when the type has no contract. */
    private fun requiredInputs(type: String): Set<String>? =
        LcncContracts.find(type)?.inputs
            ?.filter { !it.endsWith("?") }
            ?.map { it.removeSuffix("?") }
            ?.toSet()

    suspend fun runAll(program: LcncProgram): Map<String, Map<String, Any?>> {
        val order = program.topo()
        val outputs = LinkedHashMap<String, Map<String, Any?>>()
        val ranNodes = HashSet<String>() // nodes whose runner completed successfully

        for (i in 0 until order.size) {
            // Cooperative cancellation between nodes: ABORT stops the walk here,
            // and any in-flight runner below cancels at its next suspension point.
            currentCoroutineContext().job.ensureActive()

            val node = order[i]
            val wires = program.inputsOf(node.id)

            // Gather incoming wires by target port.
            val gathered = LinkedHashMap<String, MutableList<Any?>>()
            for (w in 0 until wires.size) {
                val wire = wires[w]
                if (wire.fromNode !in ranNodes) continue // upstream skipped/failed ⇒ port absent
                val fromOut = outputs[wire.fromNode] ?: continue
                gathered.getOrPut(wire.toPort) { mutableListOf() }.add(fromOut[wire.fromPort])
            }

            // MANY ports fan in as a list; scalar ports take the last value.
            val inputs = LinkedHashMap<String, Any?>()
            for ((port, values) in gathered) {
                if (isManyInput(node.type, port)) {
                    inputs[port] = if (values.size == 1) values[0] else values.toList()
                } else {
                    inputs[port] = values.lastOrNull()
                }
            }

            // Readiness: every REQUIRED input must be fed. Unknown types use the
            // wire-name heuristic (required = wired port names not ending `?`),
            // which matches panels.html for graphs it authored pre-contracts.
            val required = requiredInputs(node.type)
                ?: (0 until wires.size).map { wires[it].toPort }.filterNot { port -> port.endsWith("?") }.toSet()
            if (required.any { req -> inputs[req] == null }) continue

            // Concentric closure: a node carrying a subprogram IS a scope — run
            // the inner program first (recursively, so nesting goes all the way
            // down) and expose its outputs as this node's output. A load failure
            // is a data error, not a silent leaf: it surfaces as
            // LcncUnknownNodeType carrying the subprogram name.
            val loader = subprogramLoader
            if (node.subprogram != null && loader != null) {
                val inner = loader(node.subprogram!!)
                    ?: throw LcncUnknownNodeType(node.subprogram!!)
                outputs[node.id] = runAll(inner)
                ranNodes.add(node.id)
                continue
            }

            val runner = registry[node.type] ?: throw LcncUnknownNodeType(node.type)
            outputs[node.id] = runner.run(node, inputs)
            ranNodes.add(node.id)
        }
        return outputs
    }
}

/**
 * Run a program inside a CCEK-owned scope: the returned Deferred is bound to
 * [scope]'s Job, so cancelling the assembly's scope cancels the walk and any
 * in-flight suspend runners — the ABORT verb lowered to structured concurrency.
 */
fun LcncRunner.runAllIn(scope: CoroutineScope, program: LcncProgram) =
    scope.async { runAll(program) }
