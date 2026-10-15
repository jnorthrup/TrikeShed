package borg.trikeshed.lcnc

import borg.trikeshed.parse.ValueBudget
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reify
import borg.trikeshed.vm.VmDebugAction
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Node-boundary control of the existing walk. No worker, guest thread, or second executor. */
class LcncDebug : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LcncDebug>
    enum class Command { STEP, CONTINUE, SOURCE_INTO, SOURCE_OVER, SOURCE_OUT, SOURCE_LINE }
    private data class Resume(val command: Command, val line: Int?)

    private val gate = Mutex()
    private val checkpoints = Mutex()
    private val commands = Channel<Resume>(1)
    private var stepping = true
    private var detached = false
    private var state = "running"
    private var ordinal = 0
    private var site: Map<String, Any?>? = null
    private var actions = emptyList<Command>()

    /** Values are bounded copies, never aliases to the live runner's inputs or bindings. */
    suspend fun checkpoint(at: Int, node: LcncNode, path: List<String>, parent: Int?, iteration: Int?,
                           inputs: Map<String, Any?>) = checkpoints.withLock {
        val paused = gate.withLock {
            check(state != "closed") { "Debug controller is closed" }
            if (!stepping || detached) false
            else {
                val location = mapOf("invocationOrdinal" to at, "nodeId" to node.id, "type" to node.type, "path" to path.toList(),
                    "parent" to parent, "iteration" to iteration)
                val fields = if (ValueBudget(maxNodes = 128, maxChars = 4096).violation(location) == null)
                    location.toMutableMap() else mutableMapOf<String, Any?>("locationOmitted" to "payload_limit")
                val observation = LcncInvocationLedger.Invocation(fields)
                observation.capture("params", node.params)
                observation.capture("inputs", inputs)
                // Nested callers may restart their walk ordinal. The command identity never does.
                ordinal++
                site = fields.toMap()
                actions = listOf(Command.STEP, Command.CONTINUE) +
                    if (node.type == "vm.polyglot") listOf(Command.SOURCE_INTO) else emptyList()
                state = "paused"
                true
            }
        }
        // This suspension belongs to the caller's existing CCEK Job and timeout.
        paused && commands.receive().command == Command.SOURCE_INTO
    }

    /** The same channel controls a source callback; only copied, bounded data cross the VM boundary. */
    suspend fun sourceCheckpoint(at: Int, nodeId: String, observation: Map<String, Any?>): VmDebugAction = checkpoints.withLock {
        val paused = gate.withLock {
            check(state != "closed") { "Debug controller is closed" }
            if (!stepping || detached) false else {
                val data = if (ValueBudget(maxNodes = 8192, maxChars = 65536).violation(observation) == null) {
                    @Suppress("UNCHECKED_CAST")
                    (reify(jsonOf(observation)) as Map<String, Any?>)
                } else mapOf("observationOmitted" to "payload_limit")
                site = data + mapOf("kind" to "source", "nodeId" to nodeId, "invocationOrdinal" to at)
                actions = Command.entries.filter { it != Command.SOURCE_LINE ||
                    (data["lineCount"] as? Number)?.toInt()?.let { count -> count > 0 } == true }
                ordinal++
                state = "paused"
                true
            }
        }
        if (!paused) VmDebugAction.CONTINUE else commands.receive().let { resume -> when (resume.command) {
            Command.SOURCE_INTO -> VmDebugAction.INTO
            Command.SOURCE_OVER -> VmDebugAction.OVER
            Command.SOURCE_OUT -> VmDebugAction.OUT
            Command.STEP, Command.CONTINUE -> VmDebugAction.CONTINUE
            Command.SOURCE_LINE -> VmDebugAction.Line(checkNotNull(resume.line))
        } }
    }

    suspend fun snapshot(): Map<String, Any?> = gate.withLock {
        mapOf<String, Any?>("state" to state, "ordinal" to ordinal, "site" to site, "detached" to detached,
            "actions" to if (state == "paused") actions.map { it.name.lowercase().replace('_', '-') } else emptyList())
    }

    /** An ordinal admits exactly one command; delayed/double clicks cannot advance another pause. */
    suspend fun command(expected: Int, command: Command, line: Int? = null): Boolean = gate.withLock {
        if (state != "paused" || ordinal != expected || detached || command !in actions) false
        else {
            if (command == Command.SOURCE_LINE) require(line != null &&
                line in 1..((site?.get("lineCount") as? Number)?.toInt() ?: 0)) { "Line is outside the current guest source" }
            check(commands.trySend(Resume(command, line)).isSuccess)
            stepping = command != Command.CONTINUE
            state = "running"
            site = null
            true
        }
    }

    /** Shutdown drains admitted work: remove the inspection hold, never substitute hard cancellation. */
    suspend fun drain() = gate.withLock {
        detached = true
        stepping = false
        if (state == "paused") check(commands.trySend(Resume(Command.CONTINUE, null)).isSuccess)
        if (state != "closed") state = "running"
        site = null
    }

    suspend fun close() = gate.withLock {
        state = "closed"
        site = null
        commands.close()
    }
}
