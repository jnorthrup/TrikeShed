package borg.trikeshed.dag

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.first
import borg.trikeshed.lib.isNotEmpty
import borg.trikeshed.lib.iterator
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.view
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The three things a writer can do to working memory; what a [ReteObserver] is told after each succeeds. */
enum class ReteOp { ASSERT, MODIFY, RETRACT }

/**
 * Called after every SUCCESSFUL [ReteNetwork.assert] (a new fact only — an
 * identical re-assert is a no-op the observer never sees), [ReteNetwork.modify],
 * and [ReteNetwork.retract] (the fact as it was before removal). Invoked while
 * the network's write lock is still held, so observers see ops in exactly the
 * order working memory applied them; an observer therefore must NOT write back
 * into the same network synchronously (the lock is not reentrant) — hand the
 * op to a channel or a scope if it needs to.
 */
typealias ReteObserver = (op: ReteOp, fact: ReteStoredFact) -> Unit

/** An observed refraction admission, not proof that a downstream action completed. */
data class ReteAdmission(
    val ordinal: Long,
    val productionId: String,
    val partitionId: String,
    val activation: Activation,
    val delivery: String,
)

data class ReteAdmissionSnapshot(
    val capacity: Int,
    val admitted: Long,
    val dropped: Long,
    val receipts: List<ReteAdmission>,
)

/**
 * Production ReteNetwork. Owns the memories and coordinates rule matching.
 *
 * Rules live in a [ReteProductionRegistry] (the seam the network never had —
 * the old evaluateRules was ONE hardcoded job production behind a
 * `lifecycle=="submitted"` count gate, silently starving any rule keyed on
 * other facts). The gate generalized: per-interest incremental counters —
 * a production declaring interests only evaluates in partitions where at
 * least one interest count is non-zero, preserving the 10⁴-fact tendon
 * guarantee for every production, not just the job one.
 *
 * Writers are serialized HERE. The working memory is actor-confined by design
 * ([ReteWorkingMemory]), but in the daemon three writers already reach it from
 * three coroutines — the couch tendon's drain on Dispatchers.Default
 * ([borg.trikeshed.couch.CouchChangesFactElement]), the kanban module's
 * committed collector and ticker ([borg.trikeshed.kanban.BoardFactElement]),
 * and the job supervisor's fact consumer
 * ([borg.trikeshed.job.JobSupervisorElement]) — and every plane bridge adds
 * another. So [assert], [modify] and [retract] take one [Mutex] around the
 * memory update, the interest counters, rule evaluation and the observer
 * fan-out; no writer needs a gate object threaded to it, and a bridge that
 * holds only a `ReteNetwork` is already safe. [snapshot] reads under the same
 * lock so a projection never sees a half-applied op.
 */
class ReteNetwork(
    val productions: ReteProductionRegistry = ReteProductionRegistry(),
    private val traceCapacity: Int = 256,
) {
    /** One lock for every write and for [snapshot]; see the class doc. Not reentrant. */
    private val writeLock = Mutex()
    private val traceGate = Any()
    private val admissionTrace = ArrayDeque<ReteAdmission>()
    private var admittedCount = 0L

    fun admissionSnapshot(): ReteAdmissionSnapshot = borg.trikeshed.isam.synchronizedLock(traceGate) {
        ReteAdmissionSnapshot(traceCapacity, admittedCount, admittedCount - admissionTrace.size, admissionTrace.toList())
    }

    private fun recordAdmission(productionId: String, partitionId: String, activation: Activation, delivery: String) {
        borg.trikeshed.isam.synchronizedLock(traceGate) {
            admittedCount++
            if (traceCapacity > 0) {
                if (admissionTrace.size == traceCapacity) admissionTrace.removeFirst()
                admissionTrace.addLast(ReteAdmission(admittedCount, productionId, partitionId,
                    activation.copy(supportCids = activation.supportCids.toList(), bindings = activation.bindings.toMap()), delivery))
            }
        }
    }

    @kotlin.concurrent.Volatile
    private var observers: List<ReteObserver> = emptyList()

    /** Observer callbacks that threw; the op itself had already been applied, so the failure is counted, not propagated. */
    var observerFailures: Long = 0L
        private set

    /**
     * Register an observer told of every successful op (see [ReteObserver] for
     * the exactly-once and ordering guarantees). Returns a disposer, the same
     * module-handle discipline as [register]. Registration is a COW swap behind a
     * volatile reference, so it may happen while writers are active.
     */
    fun observe(observer: ReteObserver): AutoCloseable {
        observers = observers + observer
        return AutoCloseable { observers = observers.filter { it !== observer } }
    }

    private fun notifyObservers(op: ReteOp, fact: ReteStoredFact) {
        val current = observers
        for (o in current) {
            try {
                o(op, fact)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                observerFailures++
            }
        }
    }

    /** Every current fact in every partition, ordered by (partition, localId), read under the write lock. */
    suspend fun snapshot(): Series<ReteStoredFact> = writeLock.withLock { workingMemory.all() }

    /** A coherent selection of partitions, under the same lock as the complete snapshot. */
    suspend fun snapshot(partitions: Series<String>): Series<ReteStoredFact> =
        writeLock.withLock { workingMemory.all(partitions) }

    suspend fun query(board: BlackboardContext, facet: Join<String, Any?>): Series<ReteStoredFact> =
        writeLock.withLock { workingMemory.query(board, facet) }

    /** Replace one projection under the write lock. The supplier must not re-enter this network. */
    suspend fun replace(
        board: BlackboardContext,
        facet: Join<String, Any?>,
        facts: () -> Series<ReteStoredFact>,
    ): Int = writeLock.withLock {
        val next = facts()
        val ids = mutableSetOf<Pair<String, String>>()
        for (fact in next.view) {
            require(fact.board == board && fact.factId.a == board.id && fact.fields[facet.a] == facet.b)
            require(ids.add(fact.factId.pair)) { "Duplicate fact in projection" }
        }
        val previous = workingMemory.query(board, facet).view.associateByTo(mutableMapOf()) { it.factId.pair }
        var applied = 0
        for (fact in next) {
            val old = previous.remove(fact.factId.pair)
            when {
                old == null -> { assertFact(fact.factId, fact.fields, fact.versionCid, fact.board); applied++ }
                old.versionCid != fact.versionCid || old.fields != fact.fields -> {
                    modifyFact(fact.factId, fact.fields, fact.versionCid)
                    applied++
                }
            }
        }
        for (gone in previous.values) { retractFact(gone.factId); applied++ }
        applied
    }

    /** Read-only projection under the write lock; the consumer must not re-enter this network. */
    suspend fun snapshot(consume: (Series<ReteStoredFact>) -> Unit) = writeLock.withLock {
        consume(workingMemory.all())
    }
    val workingMemory = ReteWorkingMemory()
    val alphaMemory = ReteAlphaMemory()
    val betaMemory = ReteBetaMemory(BetaJoin("dependsOn", "jobId"))
    val agenda = ReteAgenda()

    /**
     * Attach a causal landmark index for differential-heuristic ordering.
     * The landmark index is built from a [borg.trikeshed.graph.CausalGraphNodeIndex]
     * that tracks the causal graph of facts asserted into this network.
     * Once attached, the agenda uses A* f-value ordering to prefer
     * activations causally closer to a reconciled landmark.
     */
    fun attachLandmarkIndex(index: CausalLandmarkIndex) {
        agenda.landmarkIndex = index
    }
    val refraction = ReteRefraction()

    /**
     * Where non-job activations land after refraction admits them (board rules,
     * audit rules — anything the [run] command loop does not lower). The module
     * that registers such productions wires this to its receipt/command path.
     */
    var productionSink: ((Activation) -> Unit)? = null

    /** Incremental per-partition counters, one per DISTINCT declared interest (field⋈value). */
    private val tracked = mutableMapOf<Pair<String, Any?>, MutableMap<String, Int>>()
    private val knownPartitions = mutableSetOf<String>()

    init {
        require(traceCapacity >= 0) { "traceCapacity must be non-negative" }
        // The extracted job production: today's behavior, now just the first registrant.
        register(JobDependencyProduction())
    }

    /**
     * Register a production and start (or adopt) its interest counters. Keys
     * already tracked are shared; new keys recount once over known partitions,
     * so a production attached AFTER facts exist still sees them (the
     * late-module correctness case). Returns a disposer (module detach).
     */
    fun register(p: ReteProduction): AutoCloseable {
        val disposer = productions.register(p)
        for (i in 0 until p.interests.size) {
            val interest = p.interests[i]
            tracked.getOrPut(interest.pair) {
                val counts = mutableMapOf<String, Int>()
                for (part in knownPartitions) {
                    val n = workingMemory.query(BlackboardContext(part), interest).size
                    if (n != 0) counts[part] = n
                }
                counts
            }
        }
        return disposer
    }

    private fun countInterests(partitionId: String, old: Map<String, Any?>?, new: Map<String, Any?>?) {
        knownPartitions.add(partitionId)
        for ((predicate, counts) in tracked) {
            val was = old != null && old[predicate.first] == predicate.second
            val now = new != null && new[predicate.first] == predicate.second
            if (was == now) continue
            counts[partitionId] = (counts[partitionId] ?: 0) + (if (now) 1 else -1)
        }
    }

    private fun interested(p: ReteProduction, partitionId: String): Boolean {
        val n = p.interests.size
        if (n == 0) return true
        for (i in 0 until n) {
            val interest = p.interests[i]
            val counts = tracked[interest.pair] ?: continue
            if ((counts[partitionId] ?: 0) > 0) return true
        }
        return false
    }

    suspend fun assert(
        factId: FactId,
        fields: Map<String, Any?>,
        versionCid: borg.trikeshed.job.ContentId,
        board: BlackboardContext,
    ) = writeLock.withLock { assertFact(factId, fields, versionCid, board) }

    private suspend fun assertFact(
        factId: FactId,
        fields: Map<String, Any?>,
        versionCid: borg.trikeshed.job.ContentId,
        board: BlackboardContext,
    ) {
        val result = workingMemory.assert(factId, fields, versionCid, board)
        if (result.isNew) {
            countInterests(board.id, null, fields)
            alphaMemory.accept(result.fact)
            betaMemory.acceptLeft(result.fact)
            betaMemory.acceptRight(result.fact)
            evaluateRules(board.id)
            signalAgenda()
            notifyObservers(ReteOp.ASSERT, result.fact)
        }
    }

    suspend fun modify(
        factId: FactId,
        fields: Map<String, Any?>,
        versionCid: borg.trikeshed.job.ContentId,
    ) = writeLock.withLock { modifyFact(factId, fields, versionCid) }

    private suspend fun modifyFact(
        factId: FactId,
        fields: Map<String, Any?>,
        versionCid: borg.trikeshed.job.ContentId,
    ) {
        // Find old fact for invalidation
        val oldFacts = workingMemory.facts(factId)
        if (oldFacts.isNotEmpty()) {
            val oldFact = oldFacts.first()
            agenda.removeBySupport(oldFact.versionCid)
            refraction.invalidateBySupport(oldFact.versionCid)
            countInterests(factId.a, oldFact.fields, fields)
        } else {
            countInterests(factId.a, null, fields)
        }

        val fact = workingMemory.modify(factId, fields, versionCid)
        alphaMemory.accept(fact)
        betaMemory.acceptLeft(fact)
        betaMemory.acceptRight(fact)
        evaluateRules(fact.factId.a)
        signalAgenda()
        notifyObservers(ReteOp.MODIFY, fact)
    }

    suspend fun retract(factId: FactId) = writeLock.withLock { retractFact(factId) }

    private suspend fun retractFact(factId: FactId) {
        val facts = workingMemory.facts(factId)
        if (facts.isNotEmpty()) {
            val fact = facts.first()
            if (workingMemory.retract(factId)) {
                countInterests(factId.a, fact.fields, null)
                alphaMemory.retract(factId)
                betaMemory.retractLeft(factId)
                betaMemory.retractRight(factId)
                agenda.removeBySupport(fact.versionCid)
                refraction.invalidateBySupport(fact.versionCid)
                evaluateRules(factId.a)
                signalAgenda()
                notifyObservers(ReteOp.RETRACT, fact)
            }
        }
    }

    private val signal = kotlinx.coroutines.channels.Channel<Unit>(capacity = kotlinx.coroutines.channels.Channel.CONFLATED)

    private fun signalAgenda() {
        signal.trySend(Unit)
    }

    suspend fun evaluateRules(partitionId: String) {
        for (p in productions.all()) {
            if (!interested(p, partitionId)) continue
            p.evaluate(this, partitionId) { a ->
                if (refraction.record(a)) {
                    var delivery = "delivery-failed"
                    try {
                        if (a.ruleId == JobDependencyProduction.START_RULE || a.ruleId == JobDependencyProduction.BLOCK_RULE) {
                            agenda.add(a)
                            delivery = "agenda-enqueued"
                        } else {
                            val sink = productionSink
                            if (sink == null) delivery = "no-sink"
                            else {
                                sink(a)
                                delivery = "sink-returned"
                            }
                        }
                    } finally {
                        recordAdmission(p.ruleId, partitionId, a, delivery)
                    }
                }
            }
        }
    }

    suspend fun run(output: kotlinx.coroutines.channels.SendChannel<borg.trikeshed.job.JobCommand>) {
        try {
            for (sig in signal) {
                while (true) {
                    val activation = agenda.popNext() ?: break
                    val jobId = borg.trikeshed.job.JobId.of(activation.bindings["jobId"] ?: continue)
                    val expectedRevision = activation.sequence
                    val ik = "rete-${activation.activationId}-$expectedRevision"
                    val cmd = when (activation.ruleId) {
                        JobDependencyProduction.START_RULE ->
                            borg.trikeshed.job.JobCommand.Start(jobId, ik, expectedRevision = expectedRevision)
                        JobDependencyProduction.BLOCK_RULE ->
                            borg.trikeshed.job.JobCommand.Block(
                                jobId, ik, expectedRevision = expectedRevision,
                                reason = activation.bindings["reason"] ?: "dependency failed",
                            )
                        else -> null // non-job activation: never lowered to a command here
                    }
                    if (cmd != null) output.send(cmd)
                }
            }
        } catch (e: kotlinx.coroutines.channels.ClosedSendChannelException) {
            // Channel is closed, meaning supervisor is draining/cancelled.
            // It's safe to exit the run loop gracefully.
        }
    }

    fun close() {
        signal.close()
    }
}
