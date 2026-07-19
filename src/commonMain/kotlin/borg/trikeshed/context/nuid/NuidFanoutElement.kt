@file:Suppress("UNCHECKED_CAST", "ObjectPropertyName")

package borg.trikeshed.context.nuid

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.time.TimeSource

/**
 * NuidFanoutElement — the CCEK owner of the concentric-narrowing dispatcher.
 *
 * Attraction model
 * ----------------
 * Workers advertise themselves as [Workgroup]s with a [Subnet] [Workgroup.scope]
 * and a [TraitSpace]. This element holds the *registry* — the list of
 * workgroups willing to receive work — and the *dispatcher* — the algorithm
 * that walks the registry for each incoming Nuid and decides who claims it.
 *
 * The concentric narrowing rule:
 *   1. Sort eligible workgroups by `scope.level` ascending (innermost first).
 *      A worker on `local` outranks a worker on `lan.localhost` outranks
 *      a worker on `mesh.worker.<id>` — the request should land as locally
 *      as possible.
 *   2. For each eligible workgroup (scope ⊇ request.subnet AND traits include
 *      request.capability), offer the action via the workgroup's claim inbox.
 *   3. The first workgroup whose `claim(claimId)` returns true wins the action.
 *      Losers observe false and stand down — the next eligible workgroup is
 *      then offered. This is the CCEK analogue of Hermes's `BEGIN IMMEDIATE`
 *      + `claim_lock` CAS.
 *   4. If no workgroup at the request's scope claims, [dispatch] walks one
 *      level outward (escalation). Concentric authority for *visibility*
 *      flows inward (outer workers see inner requests), but escalation for
 *      *unclaimed* work flows outward.
 *
 * Lifecycle
 * ---------
 *   CREATED   ⇒ registry exists; [register] allowed; [dispatch] rejected.
 *   OPEN      ⇒ accepting [register] and [dispatch].
 *   ACTIVE    ⇒ same as OPEN plus escalation.
 *   DRAINING  ⇒ registry frozen; no new dispatches; in-flight claims finish.
 *   CLOSED    ⇒ registry empty; supervisor cancelled.
 */
class NuidFanoutElement(
    parentJob: Job? = null,
    val escalationBudget: Int = 3,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    companion object Key : AsyncContextKey<NuidFanoutElement>()
    override val key: AsyncContextKey<NuidFanoutElement> = Key

    /** Per-workgroup claim slot. Claim intake belongs to the dispatcher;
     *  production workers receive accepted claims through [consume]. */
    class WorkgroupSlot(val workgroup: Workgroup) {
        // Buffered so a brief latency from one worker doesn't block concurrent
        // offers to its peers. Capacity scales with claim pressure.
        private val inbox: Channel<Claim> = Channel(Channel.BUFFERED)
        private val accepted: Channel<Claim> = Channel(Channel.BUFFERED)

        /** Consume one pending claim, suspending if empty. */
        suspend fun consume(): Claim = inbox.receive()

        /** Try to consume one pending claim (non-blocking). Returns null if empty. */
        fun tryTake(): Claim? = inbox.tryReceive().getOrNull()

        /** Consume one claim accepted for this workgroup. */
        suspend fun consume(): Claim = accepted.receive()

        /** Publish a claim after dispatcher admission succeeds. */
        internal suspend fun publishAccepted(claim: Claim) { accepted.send(claim) }

        /** Enqueue a claim offer to this workgroup. */
        suspend fun offer(claim: Claim) { inbox.send(claim) }

        /** Close the slot — no more offers. Existing claims remain drainable. */
        fun close() {
            inbox.close()
            accepted.close()
        }
    }

    /** A claim is the unit of dispatch: identity + payload + the originating Nuid. */
    data class Claim(
        val claimId: Long,
        val nuid: Nuid,
        val payload: Any?,
    )

    /** Result of a dispatch attempt — winner is decided by the workgroup, not the
     *  dispatcher. [winner] is null on timeout. */
    data class DispatchResult(
        val claimId: Long,
        val winner: String?,
        val claimedAtSubnet: Subnet?,
        val escalatedLevels: Int,
    )

    // ── registry ────────────────────────────────────────────────

    private val registryMutex: Mutex = Mutex()

    /** Backing list — append-only on register, mutated on unregister/close. */
    private val workgroups: MutableList<WorkgroupSlot> = mutableListOf()

    /** Snapshot for read-side iteration; cached and invalidated on mutate. */
    @Suppress("private val can be var") private var cachedSnapshot: List<WorkgroupSlot>? = null

    /** Mutable list of CCEK subscribers; mirrored through [fanoutSubscribers]. */
    private val mutableSubscribers: MutableList<AsyncContextElement> = mutableListOf()

    private fun snapshot(): List<WorkgroupSlot> {
        cachedSnapshot?.let { return it }
        val s = workgroups.toList()
        cachedSnapshot = s
        return s
    }

    private fun invalidate() { cachedSnapshot = null }

    // ── CCEK surface ─────────────────────────────────────────────

    override val fanoutSubscribers: List<AsyncContextElement>
        get() = mutableSubscribers.toList()

    fun subscribe(observer: AsyncContextElement) {
        mutableSubscribers.add(observer)
    }

    fun unsubscribe(observer: AsyncContextElement) {
        mutableSubscribers.remove(observer)
    }

    // ── registry operations ─────────────────────────────────────

    /** Register a workgroup. Idempotent on `name`. Allowed in OPEN/ACTIVE. */
    suspend fun register(workgroup: Workgroup) {
        check(state == ElementState.OPEN || state == ElementState.ACTIVE) {
            "NuidFanoutElement must be OPEN before register() (was $state)"
        }
        registryMutex.withLock {
            if (workgroups.none { it.workgroup.name == workgroup.name }) {
                workgroups.add(WorkgroupSlot(workgroup))
                invalidate()
            }
        }
    }

    /** Remove a workgroup by name. Drains its claim slot. */
    suspend fun unregister(name: String) {
        val slot: WorkgroupSlot? = registryMutex.withLock {
            val idx = workgroups.indexOfFirst { it.workgroup.name == name }
            if (idx < 0) null
            else workgroups.removeAt(idx).also { invalidate() }
        }
        slot?.close()
    }

    /** Look up a workgroup's slot, or null. */
    fun slotOf(name: String): WorkgroupSlot? =
        snapshot().firstOrNull { it.workgroup.name == name }

    // ── lifecycle overrides ───────────────────────────────────────

    override suspend fun open() {
        if (state == ElementState.CREATED) state = ElementState.OPEN
    }

    /** Promote to ACTIVE — equivalent for dispatch purposes; here as the open() alias. */
    suspend fun activate() {
        if (state == ElementState.OPEN) state = ElementState.ACTIVE
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            if (state < ElementState.DRAINING) state = ElementState.DRAINING
            supervisor.cancel()
            registryMutex.withLock {
                workgroups.forEach { it.close() }
                workgroups.clear()
                mutableSubscribers.clear()
                invalidate()
            }
            state = ElementState.CLOSED
        }
    }

    // ── dispatcher ────────────────────────────────────────────────

    /** Monotonic claim id; rolls over at [Long.MAX_VALUE]. */
    private val claimMutex: Mutex = Mutex()
    private var claimCounter: Long = 0L

    private suspend fun nextClaimId(): Long = claimMutex.withLock {
        claimCounter = if (claimCounter == Long.MAX_VALUE) 0L else claimCounter + 1L
        claimCounter
    }

    /**
     * Offer a [Claim] to the most-local eligible workgroup. Returns the
     * [DispatchResult] with the winner name (or null if no workgroup claimed
     * within [timeoutMillis]).
     *
     * Concentric narrowing: filter registry to those whose scope contains the
     * request's subnet AND whose traits include the capability; sort ascending
     * by scope.level; offer the claim at each level; first acceptor wins.
     * No acceptor ⇒ escalate one outward level, up to [escalationBudget]+1.
     */
    suspend fun dispatch(
        nuid: Nuid,
        payload: Any? = null,
        timeoutMillis: Long = 50L,
    ): DispatchResult {
        check(state == ElementState.OPEN || state == ElementState.ACTIVE) {
            "NuidFanoutElement must be OPEN/ACTIVE before dispatch() (was $state)"
        }
        val claimId = nextClaimId()
        val claim = Claim(claimId, nuid, payload)
        val eligible: List<WorkgroupSlot> = snapshot()
            .filter { it.workgroup.canHandle(nuid) }
            .sortedBy { it.workgroup.scope.level }

        if (eligible.isEmpty()) {
            return DispatchResult(claimId, winner = null, claimedAtSubnet = null, escalatedLevels = 0)
        }

        val levels: Map<Int, List<WorkgroupSlot>> = eligible.groupBy { it.workgroup.scope.level }
        val startLevel = nuid.subnet.level
        val levelsToTry: List<Int> = levels.keys
            .filter { key -> key >= startLevel }
            .sorted()
            .take(escalationBudget + 1)
        var escalatedLevels = 0

        for ((idx, level) in levelsToTry.withIndex()) {
            if (idx > 0) escalatedLevels = idx
            val candidates = levels[level] ?: continue
            for (slot in candidates) slot.offer(claim)
            val winner = pollForWinner(candidates, claimId, timeoutMillis)
            if (winner != null) {
                return DispatchResult(
                    claimId = claimId,
                    winner = winner.workgroup.name,
                    claimedAtSubnet = winner.workgroup.scope,
                    escalatedLevels = escalatedLevels,
                )
            }
            if (idx >= levelsToTry.lastIndex) break
        }

        return DispatchResult(
            claimId = claimId,
            winner = null,
            claimedAtSubnet = null,
            escalatedLevels = escalatedLevels,
        )
    }

    /** Watch candidate slots for any one of them to take [claimId]. */
    private suspend fun pollForWinner(
        candidates: List<WorkgroupSlot>,
        claimId: Long,
        timeoutMillis: Long,
    ): WorkgroupSlot? {
        val mark = TimeSource.Monotonic.markNow()
        val deadlineNanos = timeoutMillis * 1_000_000L
        while (mark.elapsedNow().inWholeNanoseconds < deadlineNanos) {
            for (slot in candidates) {
                val taken = slot.tryTake() ?: continue
                if (taken.claimId == claimId && acceptClaim(slot.workgroup, taken)) {
                    slot.publishAccepted(taken)
                    return slot
                }
            }
            yield()
        }
        return null
    }

    /** Default admission policy — accepts any claim whose workgroup can handle it. */
    private fun acceptClaim(workgroup: Workgroup, claim: Claim): Boolean =
        workgroup.canHandle(claim.nuid)

    /** Lift an offer directly to a named workgroup (bypass narrowing). */
    suspend fun offer(name: String, claim: Claim) {
        slotOf(name)?.offer(claim)
    }
}
