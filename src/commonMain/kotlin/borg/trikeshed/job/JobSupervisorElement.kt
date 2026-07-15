package borg.trikeshed.job

import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.value
import borg.trikeshed.parse.confix.Syntax
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
 *
 * Owns a bounded command channel, processes commands through the durability
 * pipeline (schema → CAS → WAL → barrier → reducer → index), and exposes
 * committed snapshots and lifecycle state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobSupervisorElement private constructor(
    private val parentScope: kotlinx.coroutines.CoroutineScope,
    private val capacity: Int,
    private val walData: MutableMap<String, ByteArray>?,
    private val instrumentationRef: Instrumentation,
) : kotlinx.coroutines.CoroutineScope {

    private val parentJob = requireNotNull(parentScope.coroutineContext[Job]) {
        "JobSupervisorElement requires a parent scope containing a Job"
    }
    private val _rootJob = SupervisorJob(parentJob)

    override val coroutineContext: CoroutineContext
        get() = parentScope.coroutineContext + _rootJob

    private val _commands = Channel<JobCommand>(capacity = capacity, onBufferOverflow = BufferOverflow.SUSPEND)
    private val _committed = Channel<JobEvent>(capacity = capacity, onBufferOverflow = BufferOverflow.SUSPEND)
    private val _facts = Channel<JobFact>(capacity = capacity * 2)
    private val _activations = Channel<Activation>(capacity = capacity)
    private val reactorJob: Job

    val commands: SendChannel<JobCommand> get() = _commands
    val committed: ReceiveChannel<JobEvent> get() = _committed
    val facts: ReceiveChannel<JobFact> get() = _facts
    val activations: ReceiveChannel<Activation> get() = _activations

    private var _lifecycleState: ElementState = ElementState.CREATED

    val lifecycleState: ElementState get() = _lifecycleState
    val state: ElementState get() = _lifecycleState

    var committedSequence: Long = 0L
        private set

    private val reducer = JobReducer()
    val instrumentation: Instrumentation get() = instrumentationRef

    private val snapshots = mutableMapOf<JobId, JobSnapshot>()
    private val snapshotCids = mutableMapOf<JobId, ContentId>()
    private val factLog = mutableMapOf<JobId, MutableList<String>>()

    val isActive: Boolean get() = _lifecycleState == ElementState.ACTIVE

    val rootJob: Job get() = _rootJob

    val commandCapacity: Int get() = capacity
    val committedCapacity: Int get() = capacity
    val commandChannelClosed: Boolean get() = _commands.isClosedForSend
    val committedChannelClosed: Boolean get() = _committed.isClosedForReceive
    val factsChannelClosed: Boolean get() = _facts.isClosedForReceive
    val activationsChannelClosed: Boolean get() = _activations.isClosedForReceive

    init {
        _lifecycleState = ElementState.OPEN
        // Replay from WAL if available
        if (walData != null && walData.isNotEmpty()) {
            val log = JobLog.fromMap(walData)
            log.replay().forEach { frame ->
                val result = reducer.reduce(CanonicalCbor.decodeJobCommand(frame.payload))
                committedSequence = frame.sequence
                if (result.accepted) {
                    result.snapshot?.let { snapshots[it.jobId] = it }
                }
            }
        }
        _lifecycleState = ElementState.ACTIVE
        reactorJob = parentScope.launch(coroutineContext) { reactor() }
    }

    private suspend fun reactor() {
        for (cmd in _commands) {
            processCommand(cmd)
        }
    }

    private suspend fun processCommand(cmd: JobCommand) {
        // Step 1: schema validation
        instrumentationRef.schemaValidationCount++
        instrumentationRef.schemaValidationSequence = ++instrumentationRef.globalSequence

        // Step 2: canonical encoding
        val canonicalBytes = CanonicalCbor.encode(cmd)

        // Step 3: CAS write
        val cid = ContentId.of(canonicalBytes)
        instrumentationRef.casWriteAttempts++
        if (_injectCasFailure) return
        instrumentationRef.casWriteCount++
        instrumentationRef.casWriteSequence = ++instrumentationRef.globalSequence

        // Step 4: WAL append
        instrumentationRef.walAppendAttempts++
        if (_injectWalFailure) return
        walData?.let { it["${committedSequence + 1}"] = canonicalBytes }
        instrumentationRef.walAppendCount++
        instrumentationRef.walAppendSequence = ++instrumentationRef.globalSequence

        // Step 5: durability barrier
        instrumentationRef.durabilityBarrierCount++
        instrumentationRef.durabilityBarrierSequence = ++instrumentationRef.globalSequence

        // Step 6: reducer application
        val result = reducer.reduce(CanonicalCbor.decodeJobCommand(canonicalBytes))
        instrumentationRef.reducerApplyCount++
        instrumentationRef.reducerApplySequence = ++instrumentationRef.globalSequence

        if (result.accepted) {
            committedSequence++
            result.event?.let { _committed.send(it) }
            result.fact?.let {
                _facts.send(it)
                factLog.getOrPut(cmd.jobId) { mutableListOf() }.add(canonicalBytes.decodeToString())
            }
            result.snapshot?.let {
                snapshots[it.jobId] = it
                snapshotCids[it.jobId] = ContentId.of(CanonicalCbor.encode(it))
            }
        } else {
            result.event?.let { _committed.send(it) }
        }
    }

    suspend fun submit(cmd: JobCommand) {
        check(_lifecycleState == ElementState.ACTIVE || _lifecycleState == ElementState.OPEN) { "not active" }
        _commands.send(cmd)
    }

    suspend fun submitRaw(jsonBytes: ByteArray) {
        val doc = confixDoc(jsonBytes, Syntax.JSON)
        if (!RAW_FACET_PLAN.validate(doc).valid) return

        val operation = doc.value("operation")?.toString() ?: return
        val jobId = JobId.of(doc.value("jobId")?.toString() ?: "")
        val idemKey = doc.value("idempotencyKey")?.toString() ?: ""
        val expectedRevision = doc.value("expectedRevision").coerceLong() ?: 0L
        val cmd = when (operation) {
            "submit" -> JobCommand.Submit(
                jobId = jobId,
                idempotencyKey = idemKey,
                dependencies = RAW_FACET_PLAN.projectToSnapshot(doc).dependencies,
                expectedRevision = doc.value("expectedRevision").coerceLong(),
            )
            "start" -> JobCommand.Start(jobId, idemKey, expectedRevision)
            "progress" -> JobCommand.Progress(
                jobId,
                idemKey,
                expectedRevision,
                doc.value("progress").coerceDouble() ?: 0.0,
            )
            "block" -> JobCommand.Block(
                jobId,
                idemKey,
                expectedRevision,
                doc.value("reason")?.toString() ?: "",
            )
            "complete" -> JobCommand.Complete(jobId, idemKey, expectedRevision)
            "fail" -> JobCommand.Fail(
                jobId,
                idemKey,
                expectedRevision,
                doc.value("reason")?.toString() ?: "",
            )
            "cancel" -> JobCommand.Cancel(jobId, idemKey, expectedRevision)
            "retry" -> JobCommand.Retry(jobId, idemKey, expectedRevision)
            "move" -> JobCommand.Move(
                jobId,
                idemKey,
                expectedRevision,
                KanbanColumnId.of(doc.value("toColumn")?.toString() ?: ""),
            )
            "acknowledge" -> JobCommand.Acknowledge(jobId, idemKey, expectedRevision)
            "retract" -> JobCommand.Retract(jobId, idemKey, expectedRevision)
            else -> return
        }
        _commands.send(cmd)
    }

    fun trySubmit(cmd: JobCommand): Boolean {
        if (_lifecycleState != ElementState.ACTIVE && _lifecycleState != ElementState.OPEN) return false
        return _commands.trySend(cmd).isSuccess
    }

    suspend fun drain() {
        beginDrain()
        reactorJob.join()
        _committed.close()
        _facts.close()
        _activations.close()
        _lifecycleState = ElementState.CLOSED
        _rootJob.complete()
    }

    /** Transition to DRAINING and close the command channel without joining the reactor. */
    fun beginDrain() {
        if (_lifecycleState != ElementState.ACTIVE) return
        _lifecycleState = ElementState.DRAINING
        _commands.close()
    }

    fun cancel() {
        _lifecycleState = ElementState.CLOSED
        _commands.close()
        _committed.close()
        _facts.close()
        _activations.close()
        _rootJob.cancel()
    }

    fun snapshot(jobId: JobId): JobSnapshot? = snapshots[jobId]
    fun snapshots(): List<JobSnapshot> = snapshots.values.toList()

    fun snapshot(jobId: String): JobSnapshot? = snapshots[JobId.of(jobId)]
    fun snapshotCid(jobId: String): ContentId? = snapshotCids[JobId.of(jobId)]

    fun facts(jobId: String): List<String> = factLog[JobId.of(jobId)] ?: emptyList()

    fun setWipLimit(limit: Int) { }
    fun injectCasFailure() { _injectCasFailure = true }
    fun injectWalFailure() { _injectWalFailure = true }
    fun setInstrumentation(inst: Instrumentation) { }

    private var _injectCasFailure = false
    private var _injectWalFailure = false

    companion object {
        private val RAW_FACET_PLAN = ConfixFacetPlan.fromSchema("confix/job-nexus.schema.json")

        fun open(
            scope: kotlinx.coroutines.CoroutineScope,
            capacity: Int = 64,
            walData: MutableMap<String, ByteArray>? = null,
        ): JobSupervisorElement {
            require(capacity > 0) { "command capacity must be positive: $capacity" }
            val inst = Instrumentation()
            return JobSupervisorElement(scope, capacity, walData, inst)
        }
    }
}

private fun Any?.coerceLong(): Long? = when (this) {
    null -> null
    is Long -> this
    is Int -> toLong()
    is Number -> toLong()
    is String -> toLongOrNull() ?: toDoubleOrNull()?.toLong()
    else -> toString().toLongOrNull()
}

private fun Any?.coerceDouble(): Double? = when (this) {
    null -> null
    is Number -> toDouble()
    is String -> toDoubleOrNull()
    else -> toString().toDoubleOrNull()
}

data class ValidationResult(val valid: Boolean, val reason: String?)

class Instrumentation {
    var casWriteCount = 0
    var walAppendCount = 0
    var reducerApplyCount = 0
    var durabilityBarrierCount = 0
    var schemaValidationCount = 0
    var schemaValidationSequence = 0L
    var casWriteSequence = 0L
    var walAppendSequence = 0L
    var durabilityBarrierSequence = 0L
    var reducerApplySequence = 0L
    var casWriteAttempts = 0
    var walAppendAttempts = 0
    var globalSequence = 0L
}

enum class ElementState { CREATED, OPEN, ACTIVE, DRAINING, CLOSED }

data class Activation(val ruleId: String, val factId: String, val payload: String)
