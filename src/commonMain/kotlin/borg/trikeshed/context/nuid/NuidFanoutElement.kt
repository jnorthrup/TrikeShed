package borg.trikeshed.context.nuid

import borg.trikeshed.context.Drain
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.concurrency.Channel
import kotlin.concurrent.Volatile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

typealias NuidLineage = Series<Join<Workgroup, NuidFanoutElement.Claim>>

private class NuidAuthority(val invocation: NuidInvocation) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NuidAuthority>
}

/** The selected workgroup and claim belong to the executing slot, independently of caller bindings. */
class NuidInvocation private constructor(val value: Join<Workgroup, NuidFanoutElement.Claim>, owned: Boolean) : AbstractCoroutineContextElement(Key) {
    constructor(value: Join<Workgroup, NuidFanoutElement.Claim>) : this(value, false)

    /** Append last when borrowing caller services. Publicly constructed metadata carries no authority. */
    val context: CoroutineContext = if (owned) this + NuidAuthority(this) else this

    /** Actual selected parents, followed by this invocation; caller payloads do not supply ancestry. */
    val lineage: NuidLineage get() {
        var size = 1
        var parent = value.b.parent
        while (parent != null) { size++; parent = parent.b.parent }
        return size j { index ->
            var selected = value
            repeat(size - index - 1) { selected = checkNotNull(selected.b.parent) }
            selected
        }
    }

    companion object Key : CoroutineContext.Key<NuidInvocation> {
        internal fun owned(value: Join<Workgroup, NuidFanoutElement.Claim>): NuidInvocation = NuidInvocation(value, true)
        suspend fun current(): Join<Workgroup, NuidFanoutElement.Claim>? = currentCoroutineContext()[this]?.value
        suspend fun require(): Join<Workgroup, NuidFanoutElement.Claim> =
            checkNotNull(current()) { "No NUID workgroup invocation" }
    }
}

/** A sequential slot cannot wait on itself or a current dependency back to itself. */
class NuidDispatchCycle(val lineage: NuidLineage, val target: Workgroup, val path: Series<String>) :
    IllegalStateException("NUID workgroup dependency cycle: ${path.view.joinToString(" -> ")}")

/** Capability × prefix-subnet admission, followed by optional owned workgroup execution. */
class NuidFanoutElement(
    parentJob: Job? = null,
    val escalationBudget: Int = 3,
    private val capacity: Int = 64,
) : CoroutineContext.Element, Drain {
    companion object Key : CoroutineContext.Key<NuidFanoutElement>
    override val key: CoroutineContext.Key<NuidFanoutElement> = Key
    val supervisor: CompletableJob = SupervisorJob(parentJob)
    /** Completes at [stopAdmission]; afterwards only live invocations register or claim. */
    val stopped: CompletableJob = Job()

    class WorkgroupSlot(val workgroup: Workgroup, capacity: Int = 64) {
        private data class Admission(val claim: Claim, val completion: CompletableDeferred<Any?>?)
        private val inbox = Channel.buffered<Claim>(capacity)
        private val accepted = Channel.buffered<Admission>(capacity)
        private var worker: Job? = null
        internal val executes: Boolean get() = worker != null

        /** Explicit offers are separate from the dispatcher's accepted work. */
        suspend fun consume(): Claim = inbox.kChannel.receive()
        fun tryTake(): Claim? = inbox.kChannel.tryReceive().getOrNull()
        suspend fun offer(claim: Claim) { inbox.kChannel.send(claim) }

        /** Admission notifications only; a registered handler owns its own accepted channel. */
        suspend fun consumeAccepted(): Claim {
            check(!executes) { "${workgroup.name}: a registered handler owns accepted claims" }
            return accepted.kChannel.receive().claim
        }

        internal fun execute(
            scope: CoroutineScope,
            handler: suspend (Claim) -> Any?,
            cleanup: suspend (Claim) -> Unit,
            started: suspend (NuidInvocation) -> Unit,
            finished: suspend (Claim) -> Unit,
        ) {
            check(worker == null)
            suspend fun settle(admission: Admission, outcome: Result<Any?>, invocation: NuidInvocation? = null) {
                var failure = outcome.exceptionOrNull()
                withContext(NonCancellable + (invocation?.context ?: EmptyCoroutineContext)) {
                    try {
                        cleanup(admission.claim)
                    } catch (caught: Throwable) {
                        val original = failure
                        if (original == null) failure = caught
                        else if (original !== caught) original.addSuppressed(caught)
                    } finally {
                        finished(admission.claim)
                    }
                }
                val finalFailure = failure
                if (finalFailure == null) admission.completion?.complete(outcome.getOrThrow())
                else admission.completion?.completeExceptionally(finalFailure)
            }
            worker = scope.launch(CoroutineName("nuid-workgroup:${workgroup.name}"), start = CoroutineStart.UNDISPATCHED) {
                var failure: Throwable = CancellationException("NUID workgroup stopped")
                try {
                    for (admission in accepted.kChannel) {
                        val invocation = NuidInvocation.owned(workgroup j admission.claim)
                        val outcome = try {
                            started(invocation)
                            // Carry handler failure as a value across our context boundary. Stack
                            // recovery may otherwise copy it and later discard cleanup suppression.
                            withContext(invocation.context) {
                                try { Result.success(handler(admission.claim)) }
                                catch (error: Throwable) { Result.failure(error) }
                            }
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                        settle(admission, outcome, invocation)
                        currentCoroutineContext().ensureActive()
                    }
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    accepted.close()
                    withContext(NonCancellable) {
                        while (true) {
                            val pending = accepted.kChannel.tryReceive().getOrNull() ?: break
                            settle(pending, Result.failure(CancellationException(
                                "${workgroup.name}: claim ${pending.claim.claimId} did not execute", failure)))
                        }
                    }
                }
            }
        }

        /** Select commits a send or timeout; a timed-out offer cannot execute at two scopes. */
        @OptIn(ExperimentalCoroutinesApi::class)
        internal suspend fun admit(claim: Claim, timeoutMillis: Long): DispatchResult? {
            val completion = if (executes) CompletableDeferred<Any?>() else null
            val admission = Admission(claim, completion)
            val admitted = try {
                select {
                    accepted.kChannel.onSend(admission) { true }
                    onTimeout(timeoutMillis) { false }
                }
            } catch (_: ClosedSendChannelException) { false }
            if (!admitted) return null
            return DispatchResult(claim.claimId, workgroup.name, workgroup.scope,
                claim.nuid.subnet.level - workgroup.scope.level, completion)
        }

        /** Buffered accepted work remains drainable after admission closes. */
        fun close() { inbox.close(); accepted.close() }
        internal suspend fun join() { worker?.join() }
    }

    data class Claim(
        val claimId: Long,
        val nuid: Nuid,
        val payload: Any?,
        val parent: Join<Workgroup, Claim>? = null,
    )

    /** A winner denotes admission. Only [completion] reports a registered handler's result or failure. */
    data class DispatchResult(
        val claimId: Long,
        val winner: String?,
        val claimedAtSubnet: Subnet?,
        val escalatedLevels: Int,
        val completion: Deferred<Any?>? = null,
    )

    private data class Dispatch(
        val claim: Claim,
        val candidates: List<WorkgroupSlot>,
        val timeoutMillis: Long,
        val result: CompletableDeferred<DispatchResult>,
    )

    private val registryMutex = Mutex()
    private val workgroups = mutableListOf<WorkgroupSlot>()
    private val retiring = mutableMapOf<String, WorkgroupSlot>()
    private val ownedClaims = mutableMapOf<Long, Claim>()
    private val invocations = mutableMapOf<Long, NuidInvocation>()
    private val dependencies = mutableMapOf<Long, Join<Claim, Twin<String>>>()
    @Volatile private var registry: Series<WorkgroupSlot> = emptySeriesOf()
    private val mutableSubscribers = mutableListOf<CoroutineContext.Element>()
    private val dispatches = Channel.buffered<Dispatch>(capacity)
    private var executionScope: CoroutineScope? = null
    private var dispatcher: Job? = null
    private var claimCounter = 0L

    init { require(escalationBudget >= 0); require(capacity > 0) }

    val fanoutSubscribers: List<CoroutineContext.Element> get() = mutableSubscribers.toList()
    fun subscribe(observer: CoroutineContext.Element) { mutableSubscribers.add(observer) }
    fun unsubscribe(observer: CoroutineContext.Element) { mutableSubscribers.remove(observer) }

    /** Admission registrations are idempotent by name. Handler registration never replaces a binding. */
    suspend fun register(workgroup: Workgroup, execute: (suspend (Claim) -> Any?)? = null) =
        registerSlot(workgroup, execute) {}

    /** Cleanup belongs to each accepted claim, including claims stopped before handler execution. */
    suspend fun register(workgroup: Workgroup, cleanup: suspend (Claim) -> Unit, execute: suspend (Claim) -> Any?) =
        registerSlot(workgroup, execute, cleanup)

    private suspend fun registerSlot(workgroup: Workgroup, execute: (suspend (Claim) -> Any?)?, cleanup: suspend (Claim) -> Unit) {
        val invocation = currentCoroutineContext()[NuidAuthority]?.invocation
        registryMutex.withLock {
            if (stopped.isCompleted && invocation != null && invocations[invocation.value.b.claimId] === invocation)
                check(supervisor.isActive && !dispatches.isClosed()) { "NUID workgroup owner is closed" }
            else checkAccepting()
            check(workgroup.name !in retiring) { "${workgroup.name}: workgroup is draining" }
            val existing = workgroups.firstOrNull { it.workgroup.name == workgroup.name }
            if (existing != null) {
                check(execute == null) { "${workgroup.name}: already registered" }
                return
            }
            val slot = WorkgroupSlot(workgroup, capacity)
            if (execute != null) slot.execute(checkNotNull(executionScope), execute,
                cleanup = cleanup,
                started = { invocation ->
                    registryMutex.withLock { invocations[invocation.value.b.claimId] = invocation }
                },
                finished = ::finishClaim)
            workgroups.add(slot)
            registry = workgroups.toTypedArray().toSeries()
        }
    }

    /** Reserve the name until the removed handler finishes every accepted invocation. */
    suspend fun unregister(name: String) {
        val invocation = currentCoroutineContext()[NuidAuthority]?.invocation
        val slot = registryMutex.withLock {
            checkLifecycleCaller(invocation, name)
            retiring[name] ?: workgroups.firstOrNull { it.workgroup.name == name }?.also {
                workgroups.remove(it)
                registry = workgroups.toTypedArray().toSeries()
                retiring[name] = it
                it.close()
            }
        }
        if (slot != null) withContext(NonCancellable) {
            slot.join()
            registryMutex.withLock { if (retiring[name] === slot) retiring.remove(name) }
        }
    }

    fun slotOf(name: String): WorkgroupSlot? = registry.view.firstOrNull { it.workgroup.name == name }
    fun workgroups(): Series<Workgroup> = registry α { it.workgroup }

    /** The executing owner's live identity, independent of public caller metadata. */
    suspend fun currentInvocation(): Join<Workgroup, Claim>? {
        val invocation = currentCoroutineContext()[NuidAuthority]?.invocation ?: return null
        return registryMutex.withLock {
            invocation.takeIf { invocations[it.value.b.claimId] === it }?.value
        }
    }

    suspend fun open() {
        registryMutex.withLock {
            if (executionScope != null || stopped.isCompleted) return
            supervisor.ensureActive()
            val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + supervisor + this)
            executionScope = scope
            dispatcher = scope.launch(CoroutineName("nuid-dispatch"), start = CoroutineStart.UNDISPATCHED) {
                var failure: Throwable = CancellationException("NUID dispatcher stopped")
                try {
                    for (dispatch in dispatches.kChannel) {
                        var executing = false
                        try {
                            var admitted: DispatchResult? = null
                            var escalated = 0
                            for (slot in dispatch.candidates) {
                                escalated = dispatch.claim.nuid.subnet.level - slot.workgroup.scope.level
                                reserveDependency(dispatch.claim, slot.workgroup)
                                admitted = slot.admit(dispatch.claim, dispatch.timeoutMillis)
                                if (admitted != null) {
                                    executing = admitted.completion != null
                                    break
                                }
                                registryMutex.withLock { dependencies.remove(dispatch.claim.claimId) }
                            }
                            dispatch.result.complete(admitted ?: DispatchResult(dispatch.claim.claimId, null, null, escalated))
                        } catch (error: Throwable) {
                            dispatch.result.completeExceptionally(error)
                            currentCoroutineContext().ensureActive()
                        } finally {
                            if (!executing) withContext(NonCancellable) { finishClaim(dispatch.claim) }
                        }
                    }
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    dispatches.close()
                    withContext(NonCancellable) {
                        while (true) {
                            val pending = dispatches.kChannel.tryReceive().getOrNull() ?: break
                            pending.result.completeExceptionally(failure)
                            finishClaim(pending.claim)
                        }
                        registryMutex.withLock {
                            workgroups.forEach { it.close() }
                            retiring.values.forEach { it.close() }
                        }
                    }
                }
            }.also { it.invokeOnCompletion { supervisor.complete() } }
        }
    }

    /** Stop external ingress; accepted handlers retain dependency admission until they finish. */
    suspend fun stopAdmission(): Unit = withContext(NonCancellable) {
        registryMutex.withLock {
            stopped.complete()
            if (ownedClaims.isEmpty()) dispatches.close()
            if (dispatcher == null) supervisor.complete()
        }
    }

    /** Stop admission, finish queued dispatch and accepted handlers, then release the registry. */
    override suspend fun drain(): Unit = withContext(NonCancellable) {
        val invocation = currentCoroutineContext()[NuidAuthority]?.invocation
        registryMutex.withLock { checkLifecycleCaller(invocation) }
        stopAdmission()
        dispatcher?.join()
        supervisor.join()
        registryMutex.withLock {
            workgroups.forEach { it.close() }
            retiring.values.forEach { it.close() }
            workgroups.clear()
            retiring.clear()
            registry = emptySeriesOf()
            ownedClaims.clear()
            invocations.clear()
            dependencies.clear()
            mutableSubscribers.clear()
        }
    }

    suspend fun close() = drain()

    /**
     * Prefix containment determines eligibility. The deepest eligible prefix is nearest;
     * [escalationBudget] bounds the actual number of ancestor edges crossed.
     * The bounded dispatcher owns admitted requests even if a caller stops awaiting them.
     */
    suspend fun dispatch(nuid: Nuid, payload: Any? = null, timeoutMillis: Long = 50L): DispatchResult {
        require(timeoutMillis >= 0)
        val result = CompletableDeferred<DispatchResult>()
        val invocation = currentCoroutineContext()[NuidAuthority]?.invocation
        val dispatch = registryMutex.withLock {
            val parent = invocation?.takeIf { invocations[it.value.b.claimId] === it }
            if (parent == null) checkAccepting()
            else check(supervisor.isActive) { "NUID workgroup owner is closed" }
            do { claimCounter = if (claimCounter == Long.MAX_VALUE) 0L else claimCounter + 1L }
            while (claimCounter in ownedClaims)
            val candidates = workgroups.filter {
                it.workgroup.canHandle(nuid) && nuid.subnet.level - it.workgroup.scope.level <= escalationBudget
            }.sortedByDescending { it.workgroup.scope.level }
            val claim = Claim(claimCounter, nuid, payload, parent?.value)
            ownedClaims[claim.claimId] = claim
            Dispatch(claim, candidates, timeoutMillis, result)
        }
        try {
            // Reserving ownership precedes the bounded send. Cancellation relinquishes the caller's
            // wait, not work already admitted by the owner; no mutex is held while the queue is full.
            withContext(NonCancellable) { dispatches.kChannel.send(dispatch) }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { finishClaim(dispatch.claim) }
            throw failure
        }
        return result.await()
    }

    private suspend fun finishClaim(claim: Claim) = registryMutex.withLock {
        if (ownedClaims[claim.claimId] === claim) {
            ownedClaims.remove(claim.claimId)
            invocations.remove(claim.claimId)
            dependencies.remove(claim.claimId)
            dependencies.entries.removeAll { it.value.a === claim }
            if (stopped.isCompleted && ownedClaims.isEmpty()) dispatches.close()
        }
    }

    /** An active parent may wait on its child; historical parent identity alone cannot block reuse. */
    private suspend fun reserveDependency(claim: Claim, target: Workgroup) = registryMutex.withLock {
        val parent = claim.parent ?: return@withLock
        if (invocations[parent.b.claimId]?.value?.b !== parent.b) return@withLock
        val lineage = NuidInvocation(parent).lineage
        val back = dependencyPath(target.name, parent.a.name)
        if (back != null) throw NuidDispatchCycle(lineage, target,
            (listOf(parent.a.name) + back).toTypedArray().toSeries())
        dependencies[claim.claimId] = parent.b j (parent.a.name j target.name)
    }

    /** Read under registryMutex, including reservations waiting at a bounded slot channel. */
    private fun dependencyPath(from: String, to: String): List<String>? {
        val visited = mutableSetOf<String>()
        val path = mutableListOf<String>()
        fun visit(name: String): Boolean {
            if (!visited.add(name)) return false
            path.add(name)
            if (name == to) return true
            for (dependency in dependencies.values) {
                val edge = dependency.b
                if (edge.a == name && visit(edge.b)) return true
            }
            path.removeAt(path.lastIndex)
            return false
        }
        return if (visit(from)) path else null
    }

    suspend fun claimWinnerCapability(nuid: Nuid, payload: Any? = null, timeoutMillis: Long = 50L): Capability? {
        val result = dispatch(nuid, payload, timeoutMillis)
        val winner = result.winner ?: return null
        val slot = slotOf(winner) ?: return nuid.capability
        val caps = slot.workgroup.traits.capabilities()
        for (i in 0 until caps.a) if (caps.b(i) matches nuid.capability) return caps.b(i)
        return nuid.capability
    }

    /** Explicit offer only; this bypass does not report dispatch admission or handler completion. */
    suspend fun offer(name: String, claim: Claim) {
        registryMutex.withLock { checkAccepting(); slotOf(name) }?.offer(claim)
    }

    private fun checkAccepting() {
        check(executionScope != null && !stopped.isCompleted && supervisor.isActive) {
            "NuidFanoutElement is not accepting"
        }
    }

    /** Lifecycle waits cannot join the currently executing slot or its owning supervisor. */
    private fun checkLifecycleCaller(invocation: NuidInvocation?, name: String? = null) {
        val active = invocation?.takeIf { invocations[it.value.b.claimId] === it } ?: return
        check(name != null && name != active.value.a.name) {
            "${active.value.a.name}: an active workgroup cannot join its own lifecycle"
        }
    }
}
