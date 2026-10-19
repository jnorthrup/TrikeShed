package borg.trikeshed.lcnc.ccek

import borg.trikeshed.context.*
import borg.trikeshed.context.nuid.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.BoardApply
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.concurrency.Channel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement

typealias LcncRunQueueEntry = Join<BoardApply.Committed, CoroutineContext>

/** The live projection of declared board programs; execution and handler lifetime belong to [fanout]. */
class LcncWorkgroupElement(
    private val blackboard: ConfixBlackboard,
    private val fanout: NuidFanoutElement,
    private val contracts: () -> Map<String, LcncPortContract>,
    private val bind: suspend (Workgroup, LcncProgram, ContentId) -> Unit,
    parentJob: Job? = null,
    private val dispatchQueued: (suspend (LcncRunQueueEntry) -> Deferred<Any?>)? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<LcncWorkgroupElement>()
    override val key: CoroutineContext.Key<*> get() = Key

    private val admission = Mutex()
    private class Preparation(val owner: LcncWorkgroupElement) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Preparation>
    }
    private val preparations = mutableSetOf<Preparation>()
    private val preparing = MutableStateFlow(0)
    private val updating = MutableStateFlow(0)
    private val queue = Channel.buffered<Join<LcncRunQueueEntry, CompletableDeferred<Deferred<Any?>>>>(8)
    private val pinning = Mutex()
    private val pins = linkedMapOf<String, Join<Workgroup, Join<String, ContentId>>>()
    private val input = Channel.buffered<CompletableDeferred<Unit>?>(8)
    private val stopping = MutableStateFlow(false)
    private val completion = CompletableDeferred<Result<Unit>>()
    private var collector: Job? = null
    private var consumer: Job? = null
    private var rootWorker: Job? = null
    private var recovery: Job? = null
    private var recoveryFailure: Throwable? = null
    private var scope: CoroutineScope? = null
    private val entries = mutableMapOf<String, Join<Long, Any?>>()
    private val bound = linkedMapOf<String, Join<Workgroup, Join<String, ContentId>>>()
    private val refused = linkedMapOf<String, String>()
    private var vocabularyRevision = -1L

    @Volatile private var registered: Series<Join<Workgroup, Join<String, ContentId>>> = emptySeriesOf()
    @Volatile private var rejected: Map<String, String> = emptyMap()
    @Volatile private var pinned: Series<Join<Workgroup, Join<String, ContentId>>> = emptySeriesOf()
    val registrations: Series<Join<Workgroup, Join<String, ContentId>>> get() = registered
    val refusals: Map<String, String> get() = rejected
    val pinnedRegistrations: Series<Join<Workgroup, Join<String, ContentId>>> get() = pinned

    override suspend fun open() {
        admission.withLock {
            if (state != ElementState.CREATED) return
            supervisor.ensureActive()
            state = ElementState.OPEN
            val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + supervisor + this)
            this.scope = scope
            rootWorker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var failure: Throwable = CancellationException("LCNC root queue closed")
                try {
                    for (entry in queue.kChannel) {
                        try {
                            val result = withContext(entry.a.b.minusKey(Job)) { checkNotNull(dispatchQueued)(entry.a) }
                            entry.b.complete(result)
                        } catch (caught: Throwable) {
                            entry.b.completeExceptionally(caught)
                            currentCoroutineContext().ensureActive()
                        }
                    }
                } catch (caught: Throwable) { failure = caught }
                finally {
                    queue.kChannel.close(failure)
                    while (true) {
                        val pending = queue.kChannel.tryReceive().getOrNull() ?: break
                        pending.b.completeExceptionally(failure)
                    }
                }
            }
            consumer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var failure: Throwable? = null
                try {
                    for (barrier in input.kChannel) {
                        try {
                            if (reconcile(retry = barrier != null)) barrier?.complete(Unit)
                            else barrier?.completeExceptionally(IllegalStateException("LCNC manifestation stopped"))
                        } catch (caught: Throwable) {
                            barrier?.completeExceptionally(caught)
                            throw caught
                        }
                    }
                } catch (caught: Throwable) { failure = caught }
                finally {
                    stopping.value = true
                    input.kChannel.close(failure)
                    while (true) {
                        val pending = input.kChannel.tryReceive()
                        if (pending.isFailure) break
                        pending.getOrThrow()?.completeExceptionally(failure ?: IllegalStateException("LCNC manifestation closed"))
                    }
                    completion.complete(failure?.let { Result.failure(it) } ?: Result.success(Unit))
                }
            }
            collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    blackboard.revisions.combine(stopping) { _, stop -> stop }
                        .takeWhile { !it }.collect { input.kChannel.send(null) }
                } catch (caught: Throwable) {
                    if (!input.isClosed()) input.kChannel.close(caught)
                }
            }
            state = ElementState.ACTIVE
        }
        refresh()
    }

    /** Return after current board state has passed through the owned update stage. */
    suspend fun refresh() {
        val barrier = CompletableDeferred<Unit>()
        admission.withLock {
            check((state == ElementState.OPEN || state == ElementState.ACTIVE) && supervisor.isActive) {
                "LCNC manifestation is not accepting updates"
            }
            updating.value++
        }
        try { withContext(NonCancellable) { input.kChannel.send(barrier) } }
        finally { withContext(NonCancellable) { admission.withLock { updating.value-- } } }
        barrier.await()
    }

    /** Retain a preparation admitted before stop, including its transfer through a bounded stage. */
    suspend fun currentPreparation(): Boolean {
        val permit = currentCoroutineContext()[Preparation]
        return admission.withLock { permit != null && permit.owner === this && permit in preparations }
    }

    suspend fun <T> prepare(action: suspend () -> T): T {
        val inherited = currentCoroutineContext()[Preparation]
        if (admission.withLock { inherited != null && inherited.owner === this && inherited in preparations })
            return withContext(NonCancellable + this) { action() }
        val dependency = fanout.currentInvocation() != null
        val permit = Preparation(this)
        admission.withLock {
            check(supervisor.isActive && (state == ElementState.OPEN || state == ElementState.ACTIVE ||
                state == ElementState.DRAINING && dependency)) { "LCNC workgroup admission stopped" }
            preparations.add(permit)
            preparing.value = preparations.size
        }
        try { return withContext(NonCancellable + this + permit) { action() } }
        finally { withContext(NonCancellable) { admission.withLock {
            preparations.remove(permit)
            preparing.value = preparations.size
        } } }
    }

    /** A saved version has an independent binding; latest-program retirement never removes it. */
    suspend fun pin(program: LcncProgram, cid: ContentId, scope: Subnet): Workgroup = prepare {
        val bytes = LcncProgramConfix.toJson(program).encodeToByteArray()
        require(ContentId.of(bytes) == cid) { "LCNC pinned program CID does not match its document" }
        val frozen = LcncProgramConfix.fromJson(program.name, bytes.decodeToString())
        require(!frozen.controls.inspectionOnly) { "Inspection-only construction cannot process workgroup claims" }
        val violations = LcncTypeCheck.check(frozen, contracts(), strict = false)
        require(violations.isEmpty()) { violations.joinToString("; ") { it.render() } }
        require(scope.level > 0 && scope.toString().length <= 256 && scope.segments.view.all {
            it.isNotEmpty() && it.none { char -> char.isWhitespace() || char.isISOControl() }
        }) { "Invalid LCNC pinned subnet" }
        val key = "${LcncBlackboard.programKey(frozen.name)}@${cid.value}"
        pinning.withLock {
            pins[key]?.let {
                check(it.a.scope.toString() == scope.toString()) { "LCNC pinned subnet cannot change" }
                return@withLock it.a
            }
            val group = Workgroup(key, scope, traitSpaceOf(Capability.Custom("lcnc", "program/${frozen.name}/${cid.value}")))
            check(fanout.slotOf(key) == null) { "Workgroup name is already registered" }
            try {
                bind(group, frozen, cid)
                val slot = fanout.slotOf(key)
                check(slot != null && slot.workgroup === group && slot.executes) { "Workgroup binding did not register its handler" }
                pins[key] = group j (frozen.name j cid)
                pinned = pins.values.toTypedArray().toSeries()
                group
            } catch (caught: Throwable) {
                if (fanout.slotOf(key)?.workgroup === group) fanout.unregister(key)
                throw caught
            }
        }
    }

    private suspend fun enqueue(entry: LcncRunQueueEntry): Deferred<Any?> {
        val result = CompletableDeferred<Deferred<Any?>>()
        val permit = currentCoroutineContext()[Preparation]
        val admitted = if (permit?.owner === this) entry.a j (entry.b + this + permit) else entry
        queue.kChannel.send(admitted j result)
        return result.await()
    }

    /** Independent roots use the owned queue; live fanout dependencies cannot wait behind their parent. */
    suspend fun queue(entry: LcncRunQueueEntry): Deferred<Any?> = prepare {
        val dispatch = checkNotNull(dispatchQueued) { "LCNC queued dispatch is unavailable" }
        if (fanout.currentInvocation() != null) dispatch(entry) else enqueue(entry)
    }

    /** The admitted recovery stream is lazy and starts after attachment yields to its caller. */
    suspend fun recover(entries: Sequence<LcncRunQueueEntry>) {
        admission.withLock {
            check(state == ElementState.OPEN || state == ElementState.ACTIVE) { "LCNC recovery admission stopped" }
            check(dispatchQueued != null && recovery == null) { "LCNC recovery is unavailable or already installed" }
            val permit = Preparation(this)
            preparations.add(permit)
            preparing.value = preparations.size
            recovery = checkNotNull(scope).launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    withContext(this@LcncWorkgroupElement + permit) {
                        yield()
                        for (entry in entries) try { enqueue(entry) }
                        catch (caught: Throwable) {
                            currentCoroutineContext().ensureActive()
                            if (recoveryFailure == null) recoveryFailure = caught
                        }
                    }
                } catch (caught: Throwable) {
                    if (caught is CancellationException) throw caught
                    if (recoveryFailure == null) recoveryFailure = caught
                } finally {
                    withContext(NonCancellable) { admission.withLock {
                        preparations.remove(permit)
                        preparing.value = preparations.size
                    } }
                }
            }
        }
    }

    /** Stop external preparations and manifestation without joining work that can still need descendants. */
    suspend fun stopAdmission(): Unit = withContext(NonCancellable) {
        admission.withLock {
            if (state < ElementState.DRAINING) state = ElementState.DRAINING
            stopping.value = true
        }
    }

    /** Finish recovery and all admitted root preparations while the borrowed fanout is still open. */
    suspend fun drainQueued(): Unit = withContext(NonCancellable) {
        stopAdmission()
        recovery?.join()
        preparing.first { it == 0 }
        queue.close()
        rootWorker?.join()
        recoveryFailure?.let { throw it }
    }

    override suspend fun drain(): Unit = withContext(NonCancellable) {
        val queuedFailure = runCatching { drainQueued() }.exceptionOrNull()
        updating.first { it == 0 }
        collector?.join()
        input.close()
        consumer?.join()
        if (consumer == null) completion.complete(Result.success(Unit))
        supervisor.complete()
        supervisor.join()
        state = ElementState.CLOSED
        queuedFailure?.let { throw it }
        completion.await().getOrThrow()
    }

    override suspend fun close() = drain()

    private fun publish() {
        registered = bound.values.toTypedArray().toSeries()
        rejected = refused.toMap()
    }

    private suspend fun reconcile(retry: Boolean): Boolean {
        while (true) {
            if (stopping.value) return false
            val snapshot = blackboard.snapshot()
            val revision = snapshot.provenance[LcncBlackboard.VOCABULARY]?.revision ?: 0L
            val vocabularyChanged = revision != vocabularyRevision
            val vocabulary by lazy(contracts)
            val keys = (entries.keys + snapshot.values.keys.filter { it.startsWith(LcncBlackboard.PROGRAM_PREFIX) }).toSet()
            var stale = false
            for (key in keys) {
                if (stopping.value) return false
                val raw = snapshot.values[key]
                val stamp = snapshot.provenance[key]?.revision ?: -1L
                val prior = entries[key]
                if (!vocabularyChanged && prior != null && prior.a == stamp && prior.b === raw && !(retry && key in refused)) continue
                val name = key.removePrefix(LcncBlackboard.PROGRAM_PREFIX)
                val entry = raw as? Map<*, *>
                val document = entry?.get("document") as? Map<*, *>
                val declared = (document?.get("controls") as? Map<*, *>)?.containsKey("subnet") == true
                var reason: String? = null
                val program = if (!declared) null else try {
                    require(name.isNotBlank() && entry?.get("name") == name) { "Program name does not match its board key" }
                    LcncProgramConfix.fromJson(name, jsonOf(document)).also {
                        require(!it.controls.inspectionOnly) { "Inspection-only construction cannot process workgroup claims" }
                        val violations = LcncTypeCheck.check(it, vocabulary, strict = false)
                        require(violations.isEmpty()) { violations.joinToString("; ") { violation -> violation.render() } }
                    }
                } catch (caught: Exception) {
                    if (caught is CancellationException) throw caught
                    reason = caught.message ?: "Invalid LCNC workgroup declaration"
                    null
                }
                val cid = program?.let { ContentId.of(LcncProgramConfix.toJson(it).encodeToByteArray()) }
                val existing = bound[key]
                if (existing != null && (cid == null || existing.b.b != cid)) {
                    bound.remove(key)
                    publish()
                    if (fanout.slotOf(key)?.workgroup === existing.a) fanout.unregister(key)
                    val current = blackboard.snapshot()
                    if (current.provenance[key]?.revision != snapshot.provenance[key]?.revision || current.values[key] !== raw) {
                        entries.remove(key)
                        stale = true
                        break
                    }
                }
                if (program != null && cid != null && key !in bound) {
                    if (stopping.value) return false
                    val group = Workgroup(key, Subnet.parse(requireNotNull(program.controls.subnet)),
                        traitSpaceOf(Capability.Custom("lcnc", "program/$name")))
                    try {
                        check(fanout.slotOf(key) == null) { "Workgroup name is already registered" }
                        bind(group, program, cid)
                        val slot = fanout.slotOf(key)
                        check(slot != null && slot.workgroup === group && slot.executes) { "Workgroup binding did not register its handler" }
                        bound[key] = group j (name j cid)
                    } catch (caught: Exception) {
                        if (caught is CancellationException) throw caught
                        if (fanout.slotOf(key)?.workgroup === group) fanout.unregister(key)
                        reason = caught.message ?: "LCNC workgroup binding failed"
                    }
                    val current = blackboard.snapshot()
                    if (current.provenance[key]?.revision != snapshot.provenance[key]?.revision || current.values[key] !== raw) {
                        entries.remove(key)
                        stale = true
                        break
                    }
                }
                if (reason == null) refused.remove(key) else refused[key] = reason
                if (key in snapshot.values) entries[key] = stamp j raw else entries.remove(key)
                publish()
            }
            if (!stale) {
                vocabularyRevision = revision
                return true
            }
        }
    }
}
