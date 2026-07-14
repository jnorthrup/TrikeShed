package borg.trikeshed.job

import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.confixDoc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * JobSupervisorElement — composition root for the Job Nexus.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobSupervisorElement(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val capacity: Int = 64,
) : kotlinx.coroutines.CoroutineScope by scope {

    override val coroutineContext: CoroutineContext
        get() = scope.coroutineContext

    private val _commands = Channel<JobCommand>(capacity = capacity, onBufferOverflow = BufferOverflow.SUSPEND)
    private val _committed = Channel<JobEvent>(capacity = capacity, onBufferOverflow = BufferOverflow.SUSPEND)
    private val _facts = Channel<JobFact>(capacity = capacity * 2)
    private val _activations = Channel<Activation>(capacity = capacity)

    val commands: SendChannel<JobCommand> get() = _commands
    val committed: ReceiveChannel<JobEvent> get() = _committed
    val facts: ReceiveChannel<JobFact> get() = _facts
    val activations: ReceiveChannel<Activation> get() = _activations

    private var _lifecycleState: ElementState = ElementState.CREATED
    private var _committedSequence: Long = 0

    val lifecycleState: ElementState get() = _lifecycleState
    var committedSequence: Long
        get() = _committedSequence
        private set(value) { _committedSequence = value }

    private val reducer = JobReducer()
    private var instrumentation: Instrumentation? = null

    val isActive: Boolean get() = _lifecycleState == ElementState.ACTIVE

    init { open() }

    private fun open() {
        check(_lifecycleState == ElementState.CREATED) { "Already opened" }
        _lifecycleState = ElementState.OPEN
        val rootJob = SupervisorJob(scope.coroutineContext[Job])
        scope.launch(scope.coroutineContext + rootJob) { reactor() }
        _lifecycleState = ElementState.ACTIVE
    }

    private suspend fun reactor() {
        for (cmd in _commands) {
            val canonicalBytes = CanonicalCbor.encode(cmd)
            val cid = ContentId.of(canonicalBytes)

            val frame = JobFrame(confixDoc(canonicalBytes, Syntax.JSON))
            val result = reducer.reduce(frame)

            if (result.accepted) {
                committedSequence++
                result.event?.let { _committed.send(it) }
                result.fact?.let { _facts.send(it) }
                result.snapshot?.let { snapshots[it.jobId] = it }
            } else {
                result.event?.let { _committed.send(it) }
            }
        }
    }

    suspend fun submit(cmd: JobCommand) { _commands.send(cmd) }

    fun trySubmit(cmd: JobCommand): Boolean = _commands.trySend(cmd).isSuccess

    suspend fun drain() {
        check(_lifecycleState == ElementState.ACTIVE) { "Not active" }
        _lifecycleState = ElementState.DRAINING
        _commands.close()
        _lifecycleState = ElementState.CLOSED
    }

    fun cancel() {
        _lifecycleState = ElementState.CLOSED
        _commands.close()
    }

    fun snapshot(jobId: JobId): JobSnapshot? = snapshots[jobId]
    fun snapshots(): List<JobSnapshot> = snapshots.values.toList()

    private val snapshots = mutableMapOf<JobId, JobSnapshot>()

    fun setWipLimit(limit: Int) { }
    fun injectCasFailure() { }
    fun injectWalFailure() { }
    fun setInstrumentation(inst: Instrumentation) { instrumentation = inst }
}

data class ValidationResult(val valid: Boolean, val reason: String?)

class Instrumentation {
    var casWriteCount = 0
    var walAppendCount = 0
    var reducerApplyCount = 0
    var durabilityBarrierCount = 0
    var schemaValidationCount = 0
    var casWriteSequence = 0L
    var walAppendSequence = 0L
    var durabilityBarrierSequence = 0L
    var reducerApplySequence = 0L
    var casWriteAttempts = 0
    var walAppendAttempts = 0
}

enum class ElementState { CREATED, OPEN, ACTIVE, DRAINING, CLOSED }

data class Activation(val ruleId: String, val factId: String, val payload: String)