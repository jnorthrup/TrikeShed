package borg.trikeshed.lcnc

import borg.trikeshed.parse.ValueBudget
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reify
import borg.trikeshed.vm.VmDebugAction
import borg.trikeshed.vm.VmSourceCoordinates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Node-boundary control of the existing walk. Commands use the caller's owned CCEK execution. */
class LcncDebug(stepping: Boolean = true, private val allowInterventions: Boolean = true) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LcncDebug>
    enum class Command { STEP, CONTINUE, SOURCE_INTO, SOURCE_OVER, SOURCE_OUT, SOURCE_LINE }
    enum class Automatic { NONE, FAILURE, SKIP }
    data class Patch(val inputs: Map<String, Any?>, val bindings: Map<String, Any?>)
    class Cancelled : CancellationException("cancelled by user at execution boundary")
    private data class Resume(val command: Command, val line: Int?, val sequence: Int? = null)

    private val gate = Mutex()
    private val checkpoints = Mutex()
    private val commands = Channel<Resume>(1)
    private var stepping = stepping
    private var detached = false
    private var closed = false
    private var cancellation = false
    private var pauseRequested = false
    private var state = "running"
    private var ordinal = 0
    private var site: Map<String, Any?>? = null
    private var actions = emptyList<Command>()
    private var nodeIds = emptySet<String>()
    private var scope: List<String>? = null
    private var automatic = Automatic.NONE
    private var writableInputs = emptySet<String>()
    private var writable = false
    private var pending: Patch? = null
    private var pendingSequence: Int? = null
    private var interventionSequence = 0
    private var interventionDrops = 0
    private val interventions = ArrayList<Map<String, Any?>>()
    private var controlSequence = 0
    private var controlDrops = 0
    private var pauseSequence: Int? = null
    private var cancelSequence: Int? = null
    private val controls = ArrayList<Map<String, Any?>>()
    private val observations = ArrayList<Map<String, Any?>>()
    private var observationDrops = 0
    private val mutationMonitor = OroborosMonitor()
    var intervened: Boolean = false
        private set

    private fun active(): Boolean = !closed && !detached && state !in setOf("completed", "failed", "cancelled")

    private fun selected(nodeId: String, path: List<String>): Boolean =
        (nodeIds.isEmpty() || nodeId in nodeIds) &&
            (scope?.let { path.size >= it.size && path.take(it.size) == it } ?: true)

    private fun observe(fields: Map<String, Any?>) {
        if (observations.size == 128) { observations.removeAt(0); observationDrops++ }
        observations.add(fields.filterKeys { it in setOf("nodeId", "type", "path", "invocationOrdinal",
            "parent", "iteration", "phase", "status", "reason") })
    }

    private fun patchStatus(status: String) {
        val index = interventions.indexOfLast { it["sequence"] == pendingSequence }
        if (index >= 0) interventions[index] = interventions[index] + ("status" to status)
    }

    private fun control(action: String, status: String, fields: Map<String, Any?> = emptyMap()): Int {
        if (controls.size == 64) { controls.removeAt(0); controlDrops++ }
        val sequence = ++controlSequence
        controls.add(fields + mapOf("sequence" to sequence, "action" to action, "status" to status,
            "ordinal" to ordinal, "nodeId" to site?.get("nodeId"), "path" to site?.get("path")))
        return sequence
    }

    private fun controlStatus(sequence: Int?, status: String) {
        val index = controls.indexOfLast { it["sequence"] == sequence }
        if (index >= 0) controls[index] = controls[index] + mapOf("status" to status,
            "effectOrdinal" to ordinal, "effectNodeId" to site?.get("nodeId"), "effectPath" to site?.get("path"))
    }

    private fun cancelled(): Nothing {
        controlStatus(cancelSequence, "applied")
        controlStatus(pauseSequence, "not_applied")
        pauseSequence = null
        state = "cancelled"
        discardPatch("cancelled")
        throw Cancelled()
    }

    private fun discardPatch(status: String) {
        if (pending != null) patchStatus(status)
        pending = null
        pendingSequence = null
    }

    private fun location(at: Int, node: LcncNode, path: List<String>, parent: Int?, iteration: Int?,
                         inputs: Map<String, Any?>, frame: LcncScopeFrame?): MutableMap<String, Any?> {
        val address = mapOf("invocationOrdinal" to at, "nodeId" to node.id, "type" to node.type,
            "path" to path.toList(), "parent" to parent, "iteration" to iteration)
        val fields = if (ValueBudget(maxNodes = 128, maxChars = 4096).violation(address) == null)
            address.toMutableMap() else mutableMapOf<String, Any?>("locationOmitted" to "payload_limit")
        val observation = LcncInvocationLedger.Invocation(fields)
        observation.capture("params", node.params)
        observation.capture("inputs", inputs)
        frame?.let {
            observation.capture("bindings", it.bindings)
            observation.capture("outputs", it.outputs)
            fields["depth"] = it.depth
            val frames = ArrayList<Map<String, Any?>>()
            var owner: LcncScopeFrame? = it
            while (owner != null && frames.size < 8) {
                val values = mutableMapOf<String, Any?>("depth" to owner.depth)
                val copied = LcncInvocationLedger.Invocation(values)
                copied.capture("bindings", owner.bindings)
                copied.capture("outputs", owner.outputs)
                frames.add(values.toMap())
                owner = owner.parent
            }
            fields["scopeFrames"] = frames
            fields["scopeFramesTruncated"] = owner != null
        }
        return fields
    }

    /** Values are bounded copies. Pending patches are applied by the held execution on release. */
    suspend fun checkpoint(at: Int, node: LcncNode, path: List<String>, parent: Int?, iteration: Int?,
                           inputs: Map<String, Any?>, sourceDebugging: Boolean = false,
                           frame: LcncScopeFrame? = null, apply: ((Patch) -> Unit)? = null) = checkpoints.withLock {
        val paused = gate.withLock {
            check(!closed) { "Debug controller is closed" }
            if (cancellation) cancelled()
            val fields = location(at, node, path, parent, iteration, inputs, frame)
            fields["phase"] = "before"
            fields["status"] = "running"
            site = fields.toMap()
            observe(fields)
            mutationMonitor.observe(path.joinToString("/"), node.id, "begin", after = fields)
            writable = apply != null && allowInterventions
            writableInputs = inputs.keys + LcncContracts.find(node.type)?.inputs.orEmpty().map { it.removeSuffix("?") }
            if (detached || !(stepping || pauseRequested || (automatic == Automatic.NONE &&
                    (nodeIds.isNotEmpty() || scope != null) && selected(node.id, path)))) false
            else {
                // Nested callers may restart their walk ordinal. The command identity never does.
                ordinal++
                actions = listOf(Command.STEP, Command.CONTINUE) +
                    if (sourceDebugging) listOf(Command.SOURCE_INTO) else emptyList()
                state = "paused"
                pauseRequested = false
                controlStatus(pauseSequence, "applied")
                pauseSequence = null
                true
            }
        }
        // The bounded channel carries the command back into the existing owned execution.
        val resume = if (paused) commands.receive() else null
        gate.withLock {
            if (cancellation) {
                controlStatus(resume?.sequence, "not_applied")
                cancelled()
            }
            controlStatus(resume?.sequence, "applied")
            pending?.let { patch ->
                checkNotNull(apply)(patch)
                intervened = true
                val before = site.orEmpty()
                site = location(at, node, path, parent, iteration, inputs + patch.inputs, frame) +
                    mapOf("phase" to "executing", "status" to "running")
                patchStatus("applied")
                mutationMonitor.observe(path.joinToString("/"), node.id, "intervention", before, site.orEmpty())
                pending = null
                pendingSequence = null
            }
            site = site?.plus("phase" to "executing")
            writable = false
        }
        resume?.command == Command.SOURCE_INTO
    }

    /** A completed node remains inspectable. Automatic stops precede failure propagation. */
    suspend fun completed(at: Int, node: LcncNode, path: List<String>, parent: Int?, iteration: Int?,
                          status: String, reason: String?, outputs: Map<String, Any?>?, frame: LcncScopeFrame) = checkpoints.withLock {
        val paused = gate.withLock {
            if (closed) return@withLock false
            val fields = location(at, node, path, parent, iteration, emptyMap(), frame)
            fields["phase"] = "after"
            fields["status"] = status
            reason?.let { fields["reason"] = it.take(512) }
            LcncInvocationLedger.Invocation(fields).capture("result", outputs)
            site = fields.toMap()
            observe(fields)
            mutationMonitor.observe(path.joinToString("/"), node.id, status, after = fields)
            writable = false
            if (status == "cancelled" || cancellation) {
                state = "cancelled"
                controlStatus(cancelSequence, "applied")
                controlStatus(pauseSequence, "not_applied")
                pauseSequence = null
                discardPatch("cancelled")
                false
            } else if (active() && selected(node.id, path) &&
                ((automatic == Automatic.FAILURE && status == "failed") || (automatic == Automatic.SKIP && status == "skipped"))) {
                ordinal++
                actions = listOf(Command.STEP, Command.CONTINUE)
                state = "paused"
                pauseRequested = false
                controlStatus(pauseSequence, "applied")
                pauseSequence = null
                true
            } else false
        }
        val resume = if (paused) commands.receive() else null
        gate.withLock {
            if (cancellation && status != "cancelled") {
                controlStatus(resume?.sequence, "not_applied")
                cancelled()
            }
            controlStatus(resume?.sequence, "applied")
        }
    }

    /** The same channel controls a source callback; only copied, bounded data cross the VM boundary. */
    suspend fun sourceCheckpoint(at: Int, nodeId: String, observation: Map<String, Any?>): VmDebugAction = checkpoints.withLock {
        val paused = gate.withLock {
            check(!closed) { "Debug controller is closed" }
            if (cancellation) cancelled()
            writable = false
            if ((!stepping && !pauseRequested) || detached) false else {
                val data = if (ValueBudget(maxNodes = 8192, maxChars = 65536).violation(observation) == null) {
                    @Suppress("UNCHECKED_CAST")
                    (reify(jsonOf(observation)) as Map<String, Any?>)
                } else mapOf("observationOmitted" to "payload_limit")
                val before = site.orEmpty()
                site = data + mapOf("kind" to "source", "nodeId" to nodeId, "invocationOrdinal" to at,
                    "path" to site?.get("path"), "phase" to "source")
                val path = (site?.get("path") as? List<*>)?.filterIsInstance<String>().orEmpty()
                mutationMonitor.observe(path.joinToString("/"), nodeId, "source", before, site.orEmpty())
                observe(site.orEmpty())
                actions = Command.entries.filter { it != Command.SOURCE_LINE ||
                    (data["lineCount"] as? Number)?.toInt()?.let { count -> count > 0 } == true }
                ordinal++
                state = "paused"
                pauseRequested = false
                controlStatus(pauseSequence, "applied")
                pauseSequence = null
                true
            }
        }
        if (!paused) VmDebugAction.CONTINUE else commands.receive().let { resume ->
            gate.withLock {
                if (cancellation) {
                    controlStatus(resume.sequence, "not_applied")
                    cancelled()
                }
                controlStatus(resume.sequence, "applied")
            }
            when (resume.command) {
            Command.SOURCE_INTO -> VmDebugAction.INTO
            Command.SOURCE_OVER -> VmDebugAction.OVER
            Command.SOURCE_OUT -> VmDebugAction.OUT
            Command.STEP, Command.CONTINUE -> VmDebugAction.CONTINUE
            Command.SOURCE_LINE -> VmDebugAction.Line(checkNotNull(resume.line))
        } }
    }

    suspend fun snapshot(): Map<String, Any?> = gate.withLock {
        val available = if (!active()) emptyList() else buildList {
            add("monitor-attach"); add("monitor-detach")
            if (!cancellation) {
                add("select")
                add("cancel")
                if (state == "paused") {
                    addAll(actions.map { it.name.lowercase().replace('_', '-') })
                    if (writable && pending == null) add("intervene")
                } else if (!pauseRequested) add("pause")
            }
        }
        mapOf<String, Any?>("state" to state, "ordinal" to ordinal, "site" to site, "detached" to detached,
            "actions" to available, "boundary" to "cooperative node boundary; source controls only when advertised",
            "pointcuts" to mapOf("nodeIds" to nodeIds.toList(), "scope" to scope, "automatic" to automatic.name.lowercase()),
            "interventions" to interventions.toList(), "interventionsDropped" to interventionDrops,
            "controls" to controls.toList(), "controlsDropped" to controlDrops,
            "observations" to observations.toList(), "observationsDropped" to observationDrops,
            "monitor" to mutationMonitor.snapshot())
    }

    suspend fun pause(): Boolean = gate.withLock {
        if (!active() || cancellation || state == "paused" || pauseRequested) false else {
            pauseRequested = true
            pauseSequence = control("pause", "requested")
            state = "pause-requested"
            true
        }
    }

    /** Cancellation is observed at a node/source boundary; admitted node effects finish first. */
    suspend fun cancel(): Boolean = gate.withLock {
        if (!active() || cancellation) false else {
            cancellation = true
            cancelSequence = control("cancel", "requested")
            if (state == "paused") check(commands.trySend(Resume(Command.CONTINUE, null)).isSuccess)
            state = "cancel-requested"
            true
        }
    }

    suspend fun select(nodeIds: Set<String>, scope: List<String>? = null,
                       automatic: Automatic = Automatic.NONE): Boolean = gate.withLock {
        require(nodeIds.size <= 128 && nodeIds.all { it.isNotBlank() && it.length <= 256 }) { "invalid_pointcuts" }
        require(scope == null || (scope.size <= 16 && scope.all { it.isNotBlank() && it.length <= 256 })) { "invalid_scope" }
        if (!active() || cancellation) false else {
            this.nodeIds = nodeIds.toSet()
            this.scope = scope?.toList()
            this.automatic = automatic
            control("select", "applied", mapOf("nodeIds" to this.nodeIds.toList(),
                "scope" to this.scope, "automatic" to automatic.name.lowercase()))
            true
        }
    }

    /** Accept a bounded JSON patch at the held invocation, never a reference into a guest frame. */
    suspend fun intervene(expected: Int, inputs: Map<String, Any?> = emptyMap(),
                          bindings: Map<String, Any?> = emptyMap()): Boolean = gate.withLock {
        require(inputs.isNotEmpty() || bindings.isNotEmpty()) { "empty_intervention" }
        val fields = mapOf("inputs" to inputs, "bindings" to bindings)
        require(ValueBudget(maxNodes = 256, maxChars = 4096, maxDepth = 12).violation(fields) == null) { "intervention_payload_limit" }
        require((inputs.keys + bindings.keys).all { it.isNotBlank() && it.length <= 128 && !it.startsWith(LcncCaseProgram.BINDING_PREFIX) }) { "invalid_binding" }
        if (!active() || cancellation || state != "paused" || ordinal != expected || !writable || pending != null) false
        else {
            require(inputs.keys.all { it in writableInputs }) { "unknown_input" }
            // Capture rejects host objects and detaches every nested collection from the request.
            val copied = LinkedHashMap<String, Any?>()
            LcncInvocationLedger.Invocation(copied).capture("patch", fields)
            require(copied.containsKey("patch")) { "unsupported_intervention" }
            @Suppress("UNCHECKED_CAST") val copy = copied["patch"] as Map<String, Map<String, Any?>>
            pending = Patch(copy.getValue("inputs"), copy.getValue("bindings"))
            // Capability was determined from the pre-edit inputs. Re-enter at a later node to
            // inspect source; a pending trust/language edit must not reuse that capability.
            if (inputs.isNotEmpty()) actions = actions.filter { it != Command.SOURCE_INTO }
            val sequence = ++interventionSequence
            pendingSequence = sequence
            if (interventions.size == 32) { interventions.removeAt(0); interventionDrops++ }
            interventions.add(mapOf("sequence" to sequence, "action" to "intervene", "ordinal" to ordinal, "nodeId" to site?.get("nodeId"),
                "path" to site?.get("path"), "status" to "accepted", "inputs" to pending!!.inputs, "bindings" to pending!!.bindings))
            true
        }
    }

    suspend fun monitor(attached: Boolean, scope: String? = null, pointcut: String? = null,
                        spiking: Boolean = false): Boolean = gate.withLock {
        if (!active()) false else {
            if (attached) mutationMonitor.attach(scope, pointcut, spiking) else mutationMonitor.detach()
            control(if (attached) "monitor-attach" else "monitor-detach", "applied",
                mapOf("scope" to scope, "pointcut" to pointcut, "spiking" to spiking))
            true
        }
    }

    suspend fun persistent(scope: String, pointcut: String, before: Map<String, Any?>,
                           after: Map<String, Any?>, durable: Boolean = true) = gate.withLock {
        mutationMonitor.observe(scope, pointcut, if (durable) "persistent" else "commit", before, after, persistent = durable)
    }

    /** The service records this terminal snapshot before releasing the controller's channel. */
    suspend fun finish(status: String) = gate.withLock {
        require(status in setOf("completed", "failed", "cancelled"))
        if (status == "completed" && cancellation) cancelled()
        if (state == "paused") check(commands.trySend(Resume(Command.CONTINUE, null)).isSuccess)
        state = status
        if (status == "cancelled") controlStatus(cancelSequence, "applied")
        controlStatus(pauseSequence, "not_applied")
        pauseSequence = null
        controls.indices.filter { controls[it]["status"] == "requested" }.forEach { index ->
            controls[index] = controls[index] + ("status" to "not_applied")
        }
        discardPatch("not_applied")
        writable = false
    }

    /** An ordinal admits exactly one command; delayed/double clicks cannot advance another pause. */
    suspend fun command(expected: Int, command: Command, line: Int? = null,
                        coordinates: VmSourceCoordinates = VmSourceCoordinates.GENERATED): Boolean = gate.withLock {
        if (state != "paused" || ordinal != expected || detached || closed || cancellation || command !in actions) false
        else {
            val generated = if (command != Command.SOURCE_LINE) line else when (coordinates) {
                VmSourceCoordinates.GENERATED -> {
                    require(line != null && line in 1..((site?.get("lineCount") as? Number)?.toInt() ?: 0)) {
                        "Line is outside the current guest source"
                    }
                    line
                }
                VmSourceCoordinates.AUTHORED -> {
                    fun positiveInt(value: Any?): Int? = (value as? Number)?.toDouble()?.takeIf {
                        it.isFinite() && it >= 1 && it <= Int.MAX_VALUE && it % 1 == 0.0
                    }?.toInt()
                    val authored = site?.get("authored") as? Map<*, *>
                    val start = positiveInt(authored?.get("generatedStartLine"))
                    val count = positiveInt(authored?.get("lineCount"))
                    val total = positiveInt(site?.get("lineCount"))
                    // Validate the whole mapping without overflowing an Int endpoint.
                    require(start != null && count != null && total != null && start <= total &&
                        count <= total - start + 1 && line != null && line in 1..count) {
                        "Line is outside the current authored source"
                    }
                    start + (line - 1)
                }
            }
            val sequence = control(command.name.lowercase().replace('_', '-'), "requested",
                if (generated == null) emptyMap() else mapOf("line" to generated, "coordinates" to "generated"))
            check(commands.trySend(Resume(command, generated, sequence)).isSuccess)
            stepping = command != Command.CONTINUE
            state = "running"
            site = site?.plus("phase" to "executing")
            true
        }
    }

    /** Shutdown removes inspection holds. Already accepted patches finish in the owned execution. */
    suspend fun drain() = gate.withLock {
        detached = true
        stepping = false
        pauseRequested = false
        controlStatus(pauseSequence, "not_applied")
        pauseSequence = null
        if (state == "paused") check(commands.trySend(Resume(Command.CONTINUE, null)).isSuccess)
        if (!closed && state !in setOf("completed", "failed", "cancelled")) state = "running"
    }

    suspend fun close() = gate.withLock {
        closed = true
        if (state !in setOf("completed", "failed", "cancelled")) {
            state = "closed"
            site = null
        }
        discardPatch("not_applied")
        commands.close()
    }
}
