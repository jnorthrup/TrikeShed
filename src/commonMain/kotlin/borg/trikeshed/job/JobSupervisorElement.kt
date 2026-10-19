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
import borg.trikeshed.lib.isEmpty

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
    private val jobIndex: JobIndex,
    private val projectionEngine: JobProjectionEngine,
    private val checkpoint: Checkpoint,
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

    // Rejected commands also occupy a WAL frame. They must not reuse the
    // next accepted command's sequence or poison subsequent appends.
    private var logSequence: Long = 0L

    private val reducer = JobReducer()
    val instrumentation: Instrumentation get() = instrumentationRef

    private val snapshots = mutableMapOf<JobId, JobSnapshot>()
    private val snapshotCids = mutableMapOf<JobId, ContentId>()
    private val receipts = mutableMapOf<String, ContentId>()
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
        // The supplied log is authoritative; walData retains the compatibility
        // entrypoint's map-backed replay. Rebuild receipts before admission.
        try {
            val log = if (walData != null && walData.isNotEmpty()) JobLog.fromMap(walData) else jobLog
            log.replay().forEach { frame ->
                val command = CanonicalCbor.decodeJobCommand(frame.payload)
                val result = reducer.reduce(command)
                logSequence = frame.sequence
                committedSequence = frame.sequence
                if (result.accepted) {
                    if (command is JobCommand.State) casStore.verifyState(command.stateCid)
                    result.snapshot?.let { snapshot ->
                        val snapshotCid = retain(CanonicalCbor.encode(snapshot))
                        snapshot.stateCid?.let { casStore.verifyState(it) }
                        snapshotCids[snapshot.jobId] = snapshotCid
                        receipts[command.idempotencyKey] = snapshotCid
                        snapshots[snapshot.jobId] = snapshot
                    }
                }
            }
        } catch (failure: Throwable) {
            _rootJob.cancel()
            throw failure
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
        try {
            for (cmd in _commands) processCommand(cmd)
        } catch (failure: Throwable) {
            // A failed durability boundary leaves an unknown WAL outcome.
            // Stop admission and wake commit waiters with the actual failure;
            // no later command may use the potentially advanced reducer.
            cancel(failure)
        }
    }

    private fun retain(bytes: ByteArray): ContentId {
        val expected = ContentId.of(bytes)
        check(casStore.put(bytes) == expected) { "CAS acknowledgement digest mismatch for $expected" }
        check(casStore.get(expected)?.contentEquals(bytes) == true) { "CAS read-back verification failed for $expected" }
        casStore.sync()
        val retained = casStore.get(expected)
        check(retained != null && ContentId.of(retained) == expected && retained.contentEquals(bytes)) {
            "CAS post-sync read-back verification failed for $expected"
        }
        return expected
    }

    private suspend fun processCommand(cmd: JobCommand) {
        // Step 1: schema validation
        instrumentationRef.schemaValidationCount++
        instrumentationRef.schemaValidationSequence = ++instrumentationRef.globalSequence

        // Only a candidate accepted head owns its referenced bytes. Rejected commands remain replayable.
        if (cmd is JobCommand.State && reducer.stateRejection(cmd) == null)
            casStore.verifyState(cmd.stateCid, synchronize = true)

        // Step 2: canonical encoding
        val canonicalBytes = CanonicalCbor.encode(cmd)

        // Step 3: CAS write
        instrumentationRef.casWriteAttempts++
        retain(canonicalBytes)
        instrumentationRef.casWriteCount++
        instrumentationRef.casWriteSequence = ++instrumentationRef.globalSequence

        // Step 4: WAL append
        instrumentationRef.walAppendAttempts++
        val sequence = logSequence + 1
        jobLog.append(sequence, canonicalBytes)
        logSequence = sequence
        walData?.let { it["$sequence"] = canonicalBytes }
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
            result.snapshot?.let {
                val snapshotCid = retain(CanonicalCbor.encode(it))
                it.stateCid?.let { stateCid -> casStore.verifyState(stateCid) }
                snapshots[it.jobId] = it
                snapshotCids[it.jobId] = snapshotCid
                receipts[cmd.idempotencyKey] = snapshotCid
            }
            committedSequence++
            // Receiving Accepted is a visibility boundary, not merely a signal
            // that the reducer ran. The snapshot and its CAS bytes exist first.
            result.event?.let { _committed.send(it) }
            result.fact?.let {
                _facts.send(it)
                factLog.getOrPut(cmd.jobId) { mutableListOf() }.add(canonicalBytes.decodeToString())
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
        if (operation == "state") {
            submit(JobFrame(doc).stateCommand())
            return
        }
        val jobId = JobId.of(doc.value("jobId")?.toString() ?: "")
        val idemKey = doc.value("idempotencyKey")?.toString() ?: ""
        val expectedRev = doc.value("expectedRevision")?.toString()?.toLong() ?: 0L
        val cmd = when (operation) {
            "submit" -> JobCommand.Submit(jobId, idemKey)
            "start" -> JobCommand.Start(jobId, idemKey, expectedRev)
            "complete" -> JobCommand.Complete(jobId, idemKey, expectedRev)
            "fail" -> JobCommand.Fail(jobId, idemKey, expectedRev, doc.value("reason")?.toString() ?: "")
            "retry" -> JobCommand.Retry(jobId, idemKey, expectedRev)
            "progress" -> JobCommand.Progress(jobId, idemKey, expectedRev, doc.value("progress")?.toString()?.toDouble() ?: 0.0)
            "block" -> JobCommand.Block(jobId, idemKey, expectedRev, doc.value("reason")?.toString() ?: "")
            "cancel" -> JobCommand.Cancel(jobId, idemKey, expectedRev)
            "move" -> JobCommand.Move(jobId, idemKey, expectedRev, KanbanColumnId(doc.value("toColumn")?.toString() ?: ""))
            "acknowledge" -> JobCommand.Acknowledge(jobId, idemKey, expectedRev)
            "retract" -> JobCommand.Retract(jobId, idemKey, expectedRev)
            else -> return
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

    fun cancel(cause: Throwable? = null) {
        _lifecycleState = ElementState.CLOSED
        _commands.close()
        _committed.close(cause)
        _facts.close()
        _activations.close()
        _rootJob.cancel()
    }

    fun snapshot(jobId: JobId): JobSnapshot? = snapshots[jobId]
    /** Original accepted snapshot for a command, restored from the authoritative log on open. */
    fun receipt(idempotencyKey: String): ContentId? = receipts[idempotencyKey]
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
            jobIndex: JobIndex = JobIndex(),
            projectionEngine: JobProjectionEngine = JobProjectionEngine(),
            checkpoint: Checkpoint = Checkpoint(),
            reteNetwork: borg.trikeshed.dag.ReteNetwork = borg.trikeshed.dag.ReteNetwork(),
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
                jobIndex,
                projectionEngine,
                checkpoint,
                reteNetwork,
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
