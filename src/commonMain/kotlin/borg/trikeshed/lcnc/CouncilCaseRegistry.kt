package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.parse.jsonOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One per-case nexus and mutex, borrowing the caller's storage and parent lifetime. */
class CouncilCaseRegistry(
    private val scope: CoroutineScope,
    private val plan: ConfixFacetPlan,
    private val cap: Int = 64,
    private val componentFactories: (String) -> JobNexusComponentFactories = { JobNexusComponentFactories() },
) {
    private val parentJob = requireNotNull(scope.coroutineContext[Job]) { "CouncilCaseRegistry requires a parent Job" }
    init { require(cap > 0) { "Council case capacity must be positive" } }
    private val ownerJob: CompletableJob = SupervisorJob(parentJob)

    class Capacity(val limit: Int) : IllegalStateException("Council case capacity exhausted: $limit")
    class Missing(val caseId: String) : IllegalStateException("Unknown council case: $caseId")

    /** Every access belongs to the active callback; no command/event channel or mutable owner escapes. */
    class Case internal constructor(
        val caseId: String,
        val jobId: JobId,
        private val nexus: JobSupervisorElement,
        private val cas: CasStore,
        private val guard: suspend () -> Unit,
        private val readOnly: Boolean,
        private val seedCase: suspend (String, String) -> Unit,
    ) {
        data class Commit(val cid: ContentId, val snapshot: JobSnapshot)
        @Volatile private var valid = true
        private val commands = Mutex()
        internal fun expire() { valid = false }
        private suspend fun checkOwner() { currentCoroutineContext().ensureActive(); check(valid) { "Council case view expired" }; guard() }
        private fun checkMutable() {
            check(!readOnly && nexus.snapshot(jobId)?.lifecycle !in setOf("closed", "failed")) {
                "Council case view is read-only"
            }
        }
        suspend fun seed(seedKey: String, startKey: String) = commands.withLock {
            checkOwner()
            checkMutable()
            withContext(NonCancellable) { seedCase(seedKey, startKey) }
        }
        suspend fun snapshot(): JobSnapshot? { checkOwner(); return nexus.snapshot(jobId) }
        suspend fun snapshotCid(): ContentId? { checkOwner(); return nexus.snapshotCid(jobId.value) }
        suspend fun receipt(key: String): ContentId? { checkOwner(); return nexus.receipt(key) }

        /** One admitted command owns its matching event and authenticated receipt before releasing the gate. */
        suspend fun advance(command: JobCommand): Commit = commands.withLock {
            checkOwner()
            checkMutable()
            require(command.jobId == jobId) { "Council case command names another job" }
            check(nexus.receipt(command.idempotencyKey) == null) { "Council case request already accepted: ${command.idempotencyKey}" }
            // Admission owns delivery and acknowledgement even if the caller is then cancelled.
            withContext(NonCancellable) {
                nexus.submit(command)
                while (true) {
                    val event = nexus.committed.receive()
                    if (event.idempotencyKey != command.idempotencyKey) continue
                    val eventJob = when (event) {
                        is JobEvent.Accepted -> event.jobId
                        is JobEvent.Rejected -> event.jobId
                    }
                    check(eventJob == jobId) { "Council case acknowledgement names another job" }
                    check(event is JobEvent.Accepted) { "Council case '${caseId}': command rejected: $event" }
                    val cid = checkNotNull(nexus.receipt(command.idempotencyKey)) { "Accepted council case request has no receipt" }
                    val bytes = checkNotNull(cas.get(cid)) { "Council case receipt is missing: $cid" }
                    check(ContentId.of(bytes) == cid) { "Council case receipt digest mismatch: $cid" }
                    val snapshot = CanonicalCbor.decodeJobSnapshot(bytes)
                    check(snapshot.jobId == jobId) { "Council case receipt names another job" }
                    snapshot.stateCid?.let { cas.verifyState(it) }
                    return@withContext Commit(cid, snapshot)
                }
                error("Council case acknowledgement loop ended")
            }
        }
    }

    private class Call(val registry: CouncilCaseRegistry, val caseId: String, val parent: Call?) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Call>
    }

    private fun Call?.contains(registry: CouncilCaseRegistry, caseId: String? = null): Boolean {
        var call = this
        while (call != null) {
            if (call.registry === registry && (caseId == null || call.caseId == caseId)) return true
            call = call.parent
        }
        return false
    }

    private inner class CaseEntry(val caseId: String, factories: JobNexusComponentFactories) {
        val jobId = "case/$caseId"
        val childJob: CompletableJob = SupervisorJob(ownerJob)
        val childScope = CoroutineScope(scope.coroutineContext + childJob)
        private var caseCas: CasStore? = null
        val cas: CasStore get() = checkNotNull(caseCas)
        val instance = try {
            val bound = JobNexusComponentFactories(factories.scopeFactory,
                CasStoreFactory { factories.casStoreFactory().also { caseCas = it } }, factories.walFactory,
                factories.indexFactory, factories.reteFactory, factories.projectionFactory, factories.checkpointFactory)
            TribunalInstance(JobNexusFactory.open(JobNexusSpec(),
                JobNexusBindings(parentScope = childScope, componentFactories = bound)), plan, listOf(jobId))
        } catch (failure: Throwable) {
            childJob.cancel()
            throw failure
        }
        init {
            // A dispatcher may cancel an unstarted reactor before its catch can close these channels.
            childJob.invokeOnCompletion { cause -> if (cause != null) instance.nexus.cancel(cause) }
        }
        val mutex = Mutex()
        val recordedByKey = mutableMapOf<String, String>()
        var terminal = instance.lifecycle(jobId) in setOf("closed", "failed")
        var lastRecordedCid: String? = if (terminal) instance.snapshotCid(jobId) else null
        var seeded = instance.lifecycle(jobId) == "active" || terminal
        var terminalAt = 0L
        var pins = 0
        /** Completes when this entry is claimed for closing; [closed] completes once that close has finished. */
        val closing: CompletableJob = Job()
        val closed: CompletableJob = Job()
    }

    private val entriesLock = Mutex()
    private val entries = LinkedHashMap<String, CaseEntry>()
    private val operations = MutableStateFlow(0)
    /** Completes when [drain] begins: new operations are refused and later drains await the first. */
    private val admission: CompletableJob = Job()
    private val drained = CompletableDeferred<Result<Unit>>()
    private var terminalClock = 0L

    private companion object {
        const val AUTO_START_WAIT_MS = 2_000
    }

    suspend fun recordRuling(caseId: String, verdictCid: String, transcriptCid: String): String = record(
        caseId, ContentId.of(jsonOf(listOf("council-ruling", caseId, verdictCid, transcriptCid)).encodeToByteArray()).value,
        "complete", "",
    )

    suspend fun recordMistrial(caseId: String, reason: String): String = record(
        caseId, ContentId.of(jsonOf(listOf("council-mistrial", caseId, reason)).encodeToByteArray()).value,
        "fail", reason,
    )

    fun liveCases(): Int = if (ownerJob.isCancelled) 0 else entries.values.count { !it.closed.isCompleted }
    fun recordedCid(caseId: String): String? = entries[caseId]?.lastRecordedCid
    fun lifecycle(caseId: String): String? = entries[caseId]?.let { it.instance.lifecycle(it.jobId) }
    fun revision(caseId: String): Long? = entries[caseId]?.let { it.instance.revision(it.jobId) }

    /** The callback includes its actual execution and later ordinary run receipt persistence. */
    suspend fun <T> withCase(caseId: String, create: Boolean, body: suspend (Case) -> T): T {
        var borrowed: Case? = null
        return withEntry(caseId, create, { borrowed?.expire() }) { entry ->
            val call = checkNotNull(currentCoroutineContext()[Call])
            val view = Case(caseId, JobId.of(entry.jobId), entry.instance.nexus, entry.cas,
                guard = { check(currentCoroutineContext()[Call] === call) { "Council case view belongs to another callback" } },
                readOnly = entry.closed.isCompleted || entry.terminal || entry.instance.lifecycle(entry.jobId) in setOf("closed", "failed"),
                seedCase = { seedKey, startKey ->
                    if (!entry.seeded) {
                        seed(entry, seedKey, startKey)
                        entry.seeded = true
                    }
                })
            borrowed = view
            body(view)
        }
    }

    private suspend fun <T> withEntry(caseId: String, create: Boolean, exit: () -> Unit = {}, body: suspend (CaseEntry) -> T): T {
        require(caseId.isNotBlank()) { "Empty council case id" }
        currentCoroutineContext().ensureActive()
        val caller = currentCoroutineContext()[Call]
        check(!caller.contains(this, caseId)) { "Council case '$caseId' cannot enter itself" }
        val entry = acquire(caseId, create)
        try {
            return entry.mutex.withLock {
                check(!ownerJob.isCancelled && parentJob.isActive && (entry.childJob.isActive ||
                    entry.closed.isCompleted && entry.terminal && entry.childJob.isCompleted)) { "Council case owner stopped" }
                withContext(Call(this, caseId, caller)) { body(entry) }
            }
        } finally {
            exit() // Expire the view after structured children settle, before unpinning or suspending.
            withContext(NonCancellable) {
                entriesLock.withLock {
                    check(entry.pins > 0 && operations.value > 0)
                    if (!entry.terminal && entry.instance.lifecycle(entry.jobId) in setOf("closed", "failed")) {
                        entry.terminal = true
                        entry.lastRecordedCid = entry.instance.snapshotCid(entry.jobId)
                        entry.terminalAt = ++terminalClock
                    }
                    entry.pins--
                    operations.value--
                }
            }
        }
    }

    private suspend fun acquire(caseId: String, create: Boolean): CaseEntry {
        entriesLock.withLock {
            check(admission.isActive && !ownerJob.isCancelled && parentJob.isActive) { "Council case admission stopped" }
            operations.value++ // Includes an admitted open/eviction preparation before its entry pin exists.
        }
        try {
            while (true) {
                var victim: CaseEntry? = null
                var closing: Job? = null
                var inactive: CaseEntry? = null
                val found = entriesLock.withLock {
                    check(!ownerJob.isCancelled && parentJob.isActive) { "Council case owner stopped during admission" }
                    entries[caseId]?.let { entry ->
                        if (entry.closing.isCompleted && !entry.closed.isCompleted) closing = entry.closed
                        else { entry.pins++; return@withLock entry }
                        return@withLock null
                    }
                    if (entries.values.count { !it.closed.isCompleted } >= cap) {
                        victim = entries.values.filter { !it.closed.isCompleted && !it.closing.isCompleted && it.terminal && it.pins == 0 }
                            .minByOrNull { it.terminalAt }
                        val selected = victim ?: throw Capacity(cap)
                        selected.closing.complete()
                        return@withLock null
                    }
                    val source = componentFactories(caseId)
                    val factories = if (create) source else {
                        val log = source.walFactory()
                        if (log.replay().none()) throw Missing(caseId)
                        JobNexusComponentFactories(source.scopeFactory, source.casStoreFactory, JobLogFactory { log },
                            source.indexFactory, source.reteFactory, source.projectionFactory, source.checkpointFactory)
                    }
                    // The global lock reserves capacity before any child/nexus allocation.
                    check(!ownerJob.isCancelled && parentJob.isActive) { "Council case owner stopped during preparation" }
                    val entry = CaseEntry(caseId, factories)
                    entries[caseId] = entry
                    if (ownerJob.isCancelled || !parentJob.isActive || !entry.childJob.isActive) {
                        entry.closing.complete()
                        inactive = entry
                        null
                    } else { entry.pins++; entry }
                }
                if (found != null) return found
                inactive?.let {
                    closeEntry(it)
                    error("Council case parent stopped during open")
                }
                closing?.join()
                victim?.let { closeEntry(it) }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { entriesLock.withLock { operations.value-- } }
            throw failure
        }
    }

    private suspend fun record(caseId: String, key: String, operation: String, reason: String): String {
        // Immutable terminal cache reads survive abort/drain; lookup shares the writers' lock.
        val recorded = entriesLock.withLock {
            val entry = entries[caseId] ?: return@withLock null
            entry.recordedByKey[key] ?: if (entry.terminal && entry.instance.nexus.snapshot(entry.jobId)?.stateCid == null)
                entry.instance.nexus.receipt(key)?.value else null
        }
        if (recorded != null) return recorded
        return withEntry(caseId, true) { entry ->
            withContext(NonCancellable) {
                entry.recordedByKey[key]?.let { return@withContext it }
                check(entry.instance.nexus.snapshot(entry.jobId)?.stateCid == null) {
                    "council case '$caseId' has an FSM head; terminal-only record refused"
                }
                entry.instance.nexus.receipt(key)?.let { return@withContext it.value }
                check(!entry.terminal) { "council case '$caseId' is closed for commits (already terminal)" }
                if (!entry.seeded) {
                    seed(entry, "council-case-$caseId", "council-case-$caseId-start")
                    entry.seeded = true
                }
                drainCommitted(entry)
                advanceLoud(entry, operation, key, checkNotNull(entry.instance.revision(entry.jobId)), reason)
                val cid = checkNotNull(entry.instance.snapshotCid(entry.jobId)) { "council case '$caseId': missing terminal snapshot" }
                entriesLock.withLock {
                    entry.recordedByKey[key] = cid
                    entry.lastRecordedCid = cid
                    entry.terminal = true
                    entry.terminalAt = ++terminalClock
                }
                cid
            }
        }
    }

    private suspend fun seed(entry: CaseEntry, seedKey: String, startKey: String) {
        val recovered = entry.instance.lifecycle(entry.jobId)
        check(recovered == null || recovered == "submitted") { "Cannot seed council case '${entry.caseId}' from '$recovered'" }
        if (recovered == "submitted") {
            advanceLoud(entry, "start", startKey, checkNotNull(entry.instance.revision(entry.jobId)))
            return
        }
        advanceLoud(entry, "submit", seedKey, 0L)
        var waitedMs = 0
        while (entry.instance.lifecycle(entry.jobId) != "active" && waitedMs < AUTO_START_WAIT_MS) {
            delay(1)
            waitedMs++
        }
        if (entry.instance.lifecycle(entry.jobId) != "active") {
            drainCommitted(entry)
            advanceLoud(entry, "start", startKey, checkNotNull(entry.instance.revision(entry.jobId)))
        }
    }

    private fun drainCommitted(entry: CaseEntry) {
        while (entry.instance.nexus.committed.tryReceive().isSuccess) { }
    }

    private suspend fun advanceLoud(entry: CaseEntry, operation: String, key: String, revision: Long, reason: String = "") {
        val event = try { entry.instance.advance(entry.jobId, operation, key, revision, reason) }
        catch (failure: Throwable) {
            throw IllegalStateException("council case '${entry.caseId}': '$operation' advance failed: ${failure.message}", failure)
        }
        check(event is JobEvent.Accepted) { "council case '${entry.caseId}': '$operation' advance rejected: $event" }
    }

    /** Stop new operations, finish admitted callbacks/receipts, then drain every nexus and child. */
    suspend fun drain(): Unit = withContext(NonCancellable) {
        check(!currentCoroutineContext()[Call].contains(this@CouncilCaseRegistry)) { "Council case cannot drain its own registry" }
        val leader = entriesLock.withLock { admission.complete() }
        if (!leader) { drained.await().getOrThrow(); return@withContext }
        var failure: Throwable? = null
        try {
            operations.first { it == 0 }
            val pending = entriesLock.withLock {
                entries.values.filter { !it.closed.isCompleted || !it.childJob.isCompleted }.onEach { entry ->
                    check(entry.closing.complete())
                }
            }
            for (entry in pending) try { closeEntry(entry) }
            catch (caught: Throwable) {
                if (failure == null) failure = caught else if (failure !== caught) failure!!.addSuppressed(caught)
            }
        } catch (caught: Throwable) { failure = caught }
        if (failure != null) ownerJob.cancel() else ownerJob.complete()
        ownerJob.join()
        val result = failure?.let { Result.failure<Unit>(it) } ?: Result.success(Unit)
        drained.complete(result)
        result.getOrThrow()
    }

    private suspend fun closeEntry(entry: CaseEntry): Unit = withContext(NonCancellable) {
        var failure: Throwable? = null
        try {
            entry.mutex.withLock {
                coroutineScope {
                    val output = launch {
                        while (true) {
                            val event = entry.instance.nexus.committed.receiveCatching()
                            if (event.isClosed) {
                                event.exceptionOrNull()?.let {
                                    if (failure == null && !(it is CancellationException && (ownerJob.isCancelled || !parentJob.isActive))) failure = it
                                }
                                break
                            }
                        }
                    }
                    try { entry.instance.nexus.drain() }
                    catch (caught: Throwable) { failure = caught; entry.instance.nexus.cancel(caught) }
                    finally { output.join() }
                }
            }
        } catch (caught: Throwable) { failure = caught }
        finally {
            if (failure != null) entry.childJob.cancel() else entry.childJob.complete()
            entry.childJob.join()
            entriesLock.withLock { entry.closed.complete() }
        }
        failure?.let { throw it }
    }

    /** Explicit abort compatibility. Normal owners use suspend drain(). */
    fun close() {
        ownerJob.cancel() // Includes children constructed concurrently with the abort.
    }
}
