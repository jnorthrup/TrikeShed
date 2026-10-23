package borg.trikeshed.cas

import borg.trikeshed.context.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.*

/**
 * The messages of [PostingLifecycleElement]'s intake. Every message carries its own reply, so admission
 * (the send) is not completion (the await), as the belief bag's and the HTX reactor's intakes do.
 */
sealed class PostingIntake {
    abstract val reply: CompletableDeferred<*>

    class Upsert(val index: String, val key: Any?, val tokens: List<String>, override val reply: CompletableDeferred<Long> = CompletableDeferred()) : PostingIntake()
    class Retire(val index: String, val key: Any?, override val reply: CompletableDeferred<Boolean> = CompletableDeferred()) : PostingIntake()
    /** `evoke_index_maintain` / `evoke_index_try_maintain`: one bounded action on one index. */
    class Maintain(val index: String, override val reply: CompletableDeferred<PostingLifecycle.MaintenanceResult> = CompletableDeferred()) : PostingIntake()
    /** `evoke_index_maintain_due(max_indexes)`: one action each on the due indexes, strict before low-debt, named by index. */
    class MaintainDue(val maxIndexes: Int, override val reply: CompletableDeferred<List<Join<String, PostingLifecycle.MaintenanceResult>>> = CompletableDeferred()) : PostingIntake()
    /** The best [k] hits of [query] (every hit when k < 0): score descending, then slot ascending, Evoke's order. */
    class Query(val index: String, val query: CharSequence, val k: Int, override val reply: CompletableDeferred<List<PostingHit>> = CompletableDeferred()) : PostingIntake()
    class Status(val index: String, override val reply: CompletableDeferred<Map<String, Any?>> = CompletableDeferred()) : PostingIntake()
    /** The ticker's launch of the maintenance worker; answers the actions taken. Callers never send it. */
    class Tick(override val reply: CompletableDeferred<Int> = CompletableDeferred()) : PostingIntake()

    fun fail(cause: Throwable): Boolean = reply.completeExceptionally(cause)
}

/**
 * Evoke's maintenance worker over [PostingLifecycle]s, a CCEK element: one worker owns every index and
 * is the only coroutine that touches one, so reads are exact and need no lock; a bounded [intake]
 * carries writes, reads and maintenance requests to it; a ticker sends one [PostingIntake.Tick] at a
 * time, every [intervalMs] after the last one answered (Evoke's preload timer and the documented
 * one-second floor; Postgres admits one maintenance worker), and the worker answers it with one launch
 * of `evoke_maintenance_worker_main`: one bounded action at a time, over the due indexes strict-first,
 * until [actionsPerTick] actions were taken or [tickBudgetMs] passed on [clock] since the first, or
 * nothing is due; writers queued during a launch are served between its actions. [drain] stops the
 * ticker, closes the intake, lets the worker finish what was admitted, then completes the owning
 * supervisor. An index exists from its first [PostingIntake.Upsert]; a refused write (the hard L0
 * frontier) fails its own reply and nothing else.
 */
class PostingLifecycleElement(
    val limits: PostingLifecycle.Limits = PostingLifecycle.Limits(),
    val intervalMs: Long = 1_000,
    /** `EVOKE_AM_MAINTENANCE_ACTIONS_PER_WORKER` and `EVOKE_AM_MAINTENANCE_ACTION_BUDGET_MS`: one launch's budget. */
    val actionsPerTick: Int = 256,
    val tickBudgetMs: Long = 1_000,
    /** Milliseconds for the lifecycles' low-debt clock and the launch budget: monotonic by default, a test steps it. */
    val clock: () -> Long = monotonicMillis(),
    val analyzer: Analyzer = Analyzer(),
    capacity: Int = 256,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {
    companion object Key : AsyncContextKey<PostingLifecycleElement>() {
        const val STOPPED = "posting lifecycle element stopped"
    }
    override val key: CoroutineContext.Key<*> get() = Key

    val intake: Channel<PostingIntake> = Channel(capacity, onUndeliveredElement = { it.fail(IllegalStateException(STOPPED)) })
    /** The worker's indexes by name, in order of first sight; only the worker touches them, readers send a [PostingIntake]. */
    val indexes = LinkedHashMap<String, PostingLifecycle>()
    val stop = CompletableDeferred<Unit>()
    var worker: Job? = null
    var ticker: Job? = null

    /** Actions the last launch took, and launches so far: the worker's, read by anyone. */
    @Volatile var lastBurstActions: Int = 0
    @Volatile var bursts: Long = 0

    override suspend fun open() {
        if (state != ElementState.CREATED) return
        super.open()
        state = ElementState.ACTIVE
        // The caller's dispatcher is inherited: a test's virtual clock then drives the ticker and the worker.
        val scope = CoroutineScope(currentCoroutineContext() + supervisor + this)
        worker = scope.launch {
            try {
                for (message in intake) handle(message)
            } finally {
                // The worker ends only by drain or by cancellation of its supervisor: either way admission stops
                // here, so a later send fails with the cause instead of waiting on a reply nobody will give.
                val stopped = IllegalStateException(STOPPED)
                intake.close(stopped)
                while (true) (intake.tryReceive().getOrNull() ?: break).fail(stopped)
            }
        }
        // A launch that throws fails the ticker under the supervisor, where it is seen; maintenance is not retried blind.
        ticker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (!stop.isCompleted) {
                val tick = PostingIntake.Tick()
                intake.send(tick)
                tick.reply.await()
                if (withTimeoutOrNull(intervalMs) { stop.await() } != null) break
            }
        }
    }

    /** The worker's index by name; a missing name reads as Postgres's undefined relation. */
    fun index(name: String): PostingLifecycle = indexes[name] ?: throw NoSuchElementException("evoke index \"$name\" does not exist")

    /** One message on the spine; a failing body fails its own reply and nothing else. */
    fun handle(message: PostingIntake) {
        when (message) {
            is PostingIntake.Upsert -> message.reply.answer {
                indexes.getOrPut(message.index) { PostingLifecycle(analyzer, limits = limits, clock = clock) }.upsertTokens(message.key, message.tokens)
            }
            is PostingIntake.Retire -> message.reply.answer { index(message.index).retire(message.key) }
            is PostingIntake.Maintain -> message.reply.answer { index(message.index).tryMaintain() }
            is PostingIntake.MaintainDue -> message.reply.answer { due(message.maxIndexes) }
            is PostingIntake.Query -> message.reply.answer { index(message.index).topK(message.query, message.k) }
            is PostingIntake.Status -> message.reply.answer { index(message.index).status() }
            is PostingIntake.Tick -> message.reply.answer { burst() }
        }
    }

    inline fun <T> CompletableDeferred<T>.answer(body: () -> T) {
        try { complete(body()) } catch (failure: Throwable) { if (failure is CancellationException) throw failure; completeExceptionally(failure) }
    }

    /** `evoke_index_maintain_due(max_indexes)`'s rows, named by index. */
    fun due(maxIndexes: Int): List<Join<String, PostingLifecycle.MaintenanceResult>> {
        val names = indexes.keys.toList()
        return PostingLifecycle.maintainDue(indexes.values.toList(), maxIndexes).map { (i, result) -> names[i] j result }
    }

    /**
     * One launch of the maintenance worker: one bounded action at a time over the due indexes (strict
     * before low-debt, as [PostingLifecycle.maintainDue] ranks them) until the count or the time budget
     * is spent or nothing is due. Writers that arrived during the launch are served between its actions,
     * never after it; a tick queued meanwhile is folded into this launch. A due index whose action is
     * blocked is tried and passed over on every iteration, where Evoke's worker ends its launch.
     */
    fun burst(): Int {
        val started = clock()
        var actions = 0
        while (actions < actionsPerTick && clock() - started < tickBudgetMs) {
            val rows = PostingLifecycle.maintainDue(indexes.values.toList(), 1)
            if (rows.isEmpty()) break
            actions += rows.size
            while (true) {
                val queued = intake.tryReceive().getOrNull() ?: break
                if (queued is PostingIntake.Tick) queued.reply.complete(0) else handle(queued)
            }
        }
        lastBurstActions = actions
        bursts++
        return actions
    }

    // ── callers' suspend forms: admission then completion ───────────────────────────────────────

    suspend fun <T> send(message: PostingIntake, reply: CompletableDeferred<T>): T {
        check(state == ElementState.ACTIVE) { "posting lifecycle element is not accepting work: $state" }
        intake.send(message)
        return reply.await()
    }

    suspend fun upsert(index: String, key: Any?, text: CharSequence): Long = upsertTokens(index, key, analyzer.tokens(text))
    suspend fun upsertTokens(index: String, key: Any?, tokens: List<String>): Long = PostingIntake.Upsert(index, key, tokens).let { send(it, it.reply) }
    suspend fun retire(index: String, key: Any?): Boolean = PostingIntake.Retire(index, key).let { send(it, it.reply) }
    suspend fun maintain(index: String): PostingLifecycle.MaintenanceResult = PostingIntake.Maintain(index).let { send(it, it.reply) }
    suspend fun maintainDue(maxIndexes: Int = 1): List<Join<String, PostingLifecycle.MaintenanceResult>> = PostingIntake.MaintainDue(maxIndexes).let { send(it, it.reply) }
    suspend fun query(index: String, query: CharSequence, k: Int = -1): List<PostingHit> = PostingIntake.Query(index, query, k).let { send(it, it.reply) }
    suspend fun status(index: String): Map<String, Any?> = PostingIntake.Status(index).let { send(it, it.reply) }

    override suspend fun drain(): Unit = withContext(NonCancellable) {
        if (state == ElementState.CLOSED) return@withContext
        state = ElementState.DRAINING
        stop.complete(Unit)
        ticker?.join()
        intake.close()
        worker?.join()
        supervisor.complete()
        supervisor.join()
        state = ElementState.CLOSED
    }

    override suspend fun close() = drain()
}

/** Milliseconds since the first reading, on the monotonic source: the element's production clock. */
fun monotonicMillis(): () -> Long = TimeSource.Monotonic.markNow().let { origin -> { origin.elapsedNow().inWholeMilliseconds } }
