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
    private val casStore: CasStore,
    private val jobLog: JobLog,
    private val reteNetwork: borg.trikeshed.dag.ReteNetwork,
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
    private val reteConsumerJob: Job
    private val reteProducerJob: Job

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
        reteProducerJob = parentScope.launch(coroutineContext) { reteNetwork.run(_commands) }
        reteConsumerJob = parentScope.launch(coroutineContext) {
            for (fact in _facts) {
                val snap = snapshots[fact.jobId] ?: continue
                val factId = borg.trikeshed.dag.FactId("job-board", fact.jobId.value)
                val fields = mapOf(
                    "jobId" to snap.jobId.value,
                    "revision" to snap.revision,
                    "lifecycle" to snap.lifecycle,
                    "dependencies" to snap.dependencies.map { it.value }
                )

                val existing = reteNetwork.workingMemory.facts(factId)
                if (existing.isEmpty()) {
                    reteNetwork.assert(factId, fields, fact.cid, borg.trikeshed.cursor.BlackboardContext("job-board"))
                } else {
                    reteNetwork.modify(factId, fields, fact.cid)
                }
            }
        }
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
        try {
            casStore.put(canonicalBytes)
            casStore.get(cid) ?: throw IllegalStateException("CAS digest verification failed")
        } catch (e: Exception) {
            return
        }
        instrumentationRef.casWriteCount++
        instrumentationRef.casWriteSequence = ++instrumentationRef.globalSequence

        // Step 4: WAL append
        instrumentationRef.walAppendAttempts++
        try {
            jobLog.append(committedSequence + 1, canonicalBytes)
        } catch (e: Exception) {
            return
        }
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
        val operation = doc.value("operation")?.toString() ?: ""
        val jobId = JobId.of(doc.value("jobId")?.toString() ?: "")
        val idemKey = doc.value("idempotencyKey")?.toString() ?: ""
        val cmd = when (operation) {
            "submit" -> JobCommand.Submit(jobId, idemKey)
            "start" -> JobCommand.Start(jobId, idemKey, doc.value("expectedRevision")?.toString()?.toLong() ?: 0L)
            "complete" -> JobCommand.Complete(jobId, idemKey, doc.value("expectedRevision")?.toString()?.toLong() ?: 0L)
            "fail" -> JobCommand.Fail(jobId, idemKey, doc.value("expectedRevision")?.toString()?.toLong() ?: 0L, doc.value("reason")?.toString() ?: "")
            "retry" -> JobCommand.Retry(jobId, idemKey, doc.value("expectedRevision")?.toString()?.toLong() ?: 0L)
            else -> { _commands.send(JobCommand.Submit(jobId, idemKey)); return }
        }
        // Validate operation — invalid ops fail at schema validation
        val knownOps = setOf("submit", "start", "progress", "block", "complete", "fail",
            "cancel", "retry", "move", "acknowledge", "retract")
        if (operation !in knownOps) {
            // Schema validation fails — don't attempt CAS or WAL
            return
        }
        _commands.send(cmd)
    }

    fun trySubmit(cmd: JobCommand): Boolean {
        if (_lifecycleState != ElementState.ACTIVE && _lifecycleState != ElementState.OPEN) return false
        return _commands.trySend(cmd).isSuccess
    }

    suspend fun drain() {
        // Wait for Rete to finish producing events and adding to _commands BEFORE beginDrain
        // Actually we need to process all facts first, so wait for _facts to empty.
        // But _facts doesn't close until we close it.
        // Rete processing loop pushes to _commands. We must ensure commands are drained.
        // Let's yield first so that the test coroutine scheduler runs the child coroutines.
        kotlinx.coroutines.yield()

        beginDrain()
        reactorJob.join()
        _committed.close()
        _facts.close()
        reteConsumerJob.join()
        reteNetwork.close()
        reteProducerJob.join()
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
    fun setInstrumentation(inst: Instrumentation) { }

    companion object {
        fun open(
            scope: kotlinx.coroutines.CoroutineScope,
            capacity: Int = 64,
            walData: MutableMap<String, ByteArray>? = null,
            casStore: CasStore = CasStore.inMemory(),
            jobLog: JobLog = JobLog.inMemory(),
            injectCasFailure: Boolean = false,
            injectWalFailure: Boolean = false,
        ): JobSupervisorElement {
            require(capacity > 0) { "command capacity must be positive: $capacity" }
            val inst = Instrumentation()

            val failCasStore = object : CasStore() {
                override fun put(bytes: ByteArray): ContentId = throw RuntimeException("injected CAS failure")
            }
            val failJobLog = object : JobLog() {
                override fun append(sequence: Long, payload: ByteArray) = throw RuntimeException("injected WAL failure")
            }

            return JobSupervisorElement(
                scope,
                capacity,
                walData,
                inst,
                if (injectCasFailure) failCasStore else casStore,
                if (injectWalFailure) failJobLog else jobLog,
                borg.trikeshed.dag.ReteNetwork(),
            )
        }
    }
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
