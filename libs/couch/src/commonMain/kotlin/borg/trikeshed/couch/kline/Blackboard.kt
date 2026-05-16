package borg.trikeshed.couch.kline

import borg.trikeshed.userspace.concurrency.Channel
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Blackboard — a shared, pub/sub observation field for KlineCharacteristics.
 *
 * All characteristics live here simultaneously. Subscribers observe
 * specific characteristic names and react when thresholds trigger.
 * The board is append-only — once published, a characteristic cannot be retracted,
 * only superseded by a newer one for the same name+origin.
 *
 * Blackboards can be distributed across nodes (same API, different transport).
 *
 * @param T the timestamp type (usually Long for openTime)
 */
class Blackboard<T : Comparable<T>> {
    /** Published characteristics, sorted by (timestamp, origin, name). */
    private var field = listOf<KlineCharacteristic>()

    /** Current head timestamp — the board's cursor into time. */
    private var headTimestamp: T? = null

    /** Subscriptions by characteristic name → list of callbacks. */
    private var subscriptions = mapOf<CharSequence, List<(KlineCharacteristic) -> Unit>>()

    /**
     * Publish a characteristic to the board.
     * The board may discard characteristics older than `headTimestamp`.
     */
    fun publish(char: KlineCharacteristic) {
        if (headTimestamp != null) {
            @Suppress("UNCHECKED_CAST")
            val t = headTimestamp as T
            @Suppress("UNCHECKED_CAST")
            val ct = char.timestamp as T
            if (ct < t) return  // stale, discard
        }
        field = field + listOf(char)
        // Notify subscribers for this characteristic's name
        subscriptions[char.name]?.forEach { it(char) }
    }

    /**
     * Publish a batch of characteristics atomically.
     * All are visible to subscribers only after the batch is complete.
     */
    fun publishAll(chars: List<KlineCharacteristic>) {
        // Collect subs before publishing so notifications happen in batch order
        for (char in chars) {
            field = field + listOf(char)
            subscriptions[char.name]?.forEach { it(char) }
        }
    }

    /** Advance the head timestamp — discards everything older. */
    fun advanceHead(t: T) {
        headTimestamp = t
        field = field.filter { c ->
            @Suppress("UNCHECKED_CAST")
            (c.timestamp as T) >= t
        }
    }

    /** Subscribe to a characteristic by name. Returns an unsubscribe handle. */
    fun subscribe(name: CharSequence, callback: (KlineCharacteristic) -> Unit): () -> Unit {
        val existing = subscriptions[name] ?: emptyList()
        subscriptions = subscriptions + (name to existing + listOf(callback))
        return { subscriptions[name]?.let { subscriptions = subscriptions + (name to it.filter { it != callback }) } }
    }

    /** Observe all characteristics at or after a given timestamp. */
    fun observe(since: T): List<KlineCharacteristic> = field.filter { c ->
        @Suppress("UNCHECKED_CAST")
        (c.timestamp as T) >= since
    }

    /** Observe all characteristics of a given name, newest first. */
    fun observe(name: CharSequence): List<KlineCharacteristic> =
        field.filter { it.name == name }.sortedByDescending { it.timestamp }

    /** Observe all characteristics with any of the given names. */
    fun observe(vararg names: CharSequence): List<KlineCharacteristic> =
        field.filter { it.name in names }.sortedByDescending { it.timestamp }

    /** Snapshot of the entire board. */
    fun snapshot(): List<KlineCharacteristic> = field.toList()

    /** Number of characteristics on the board. */
    fun size(): Int = field.size

    /** Advance head to a new timestamp, returning what's been removed. */
    fun advanceHeadAndReturnEvicted(t: T): List<KlineCharacteristic> {
        val evicted = field.filter { c ->
            @Suppress("UNCHECKED_CAST")
            (c.timestamp as T) < t
        }
        advanceHead(t)
        return evicted
    }
}

/**
 * Funnel — a pipe of transformation stages that compute characteristics from klines.
 *
 * Each stage:
 *   - receives a Kline (or KlineBlock)
 *   - computes zero or more KlineCharacteristics
 *   - optionally branches, maps, or buffers
 *
 * Funnel is NOT a cursor — it transforms klines into characteristic streams.
 * The blackboard is the output sink. The source is whatever drives the funnel.
 */
class Funnel(
    private val inputChannel: Channel<Kline>,
    private val blackboard: Blackboard<Long>,
) {
    /** Registered stages, in order. */
    private var stages = listOf<Stage>()

    /** Whether the funnel is running. */
    private var running = false

    /**
     * A single stage in the funnel.
     * Each stage receives a kline and publishes computed characteristics to the blackboard.
     */
    interface Stage {
        val name: CharSequence
        val consumes: Set<CharSequence>  // characteristic names this stage reads from the blackboard
        val produces: Set<CharSequence>  // characteristic names this stage publishes

        /**
         * Process a kline and return characteristics.
         * May inspect the blackboard for prior characteristics (consumed names).
         * May return empty list (pure pass-through) or many characteristics.
         */
        suspend fun process(kline: Kline): List<KlineCharacteristic>
    }

    /** Register a stage. Stages execute in registration order. */
    fun register(stage: Stage): Funnel {
        stages = stages + listOf(stage)
        return this
    }

    /**
     * Run the funnel — drain the input channel and publish characteristics.
     * Suspends until the input channel closes.
     */
    suspend fun run() {
        running = true
        var block = KlineBlock.mutable()
        val capacity = 100

        while (true) {
            val result = inputChannel.recv()
            val kline = result.getOrNull()
            if (kline == null) {
                // Channel closed — seal and deliver any partial block
                if (block.rowCount > 0) {
                    block.seal()
                    processBlock(block)
                }
                break
            }

            block.append(kline)
            if (block.rowCount >= capacity) {
                block.seal()
                processBlock(block)
                block = KlineBlock.mutable()
            }
        }
        running = false
    }

    private suspend fun processBlock(block: KlineBlock) {
        val snapshot = block.rows.toList()
        for (kline in snapshot) {
            val chars = stages.flatMap { stage ->
                stage.process(kline)
            }
            if (chars.isNotEmpty()) {
                blackboard.publishAll(chars)
            }
        }
    }

    /** Number of registered stages. */
    fun stageCount(): Int = stages.size
}

/**
 * A funnel stage that computes multiple characteristics at once.
 * Use with `Funnel().register(computeStage("sma", listOf(CharName.SMA20, CharName.SMA50)) { kline, sma20, sma50 -> ... })`
 */
class ComputeStage(
    override val name: CharSequence,
    override val consumes: Set<CharSequence>,
    override val produces: Set<CharSequence>,
    private val fn: suspend (Kline) -> List<KlineCharacteristic>,
) : Funnel.Stage {
    override suspend fun process(kline: Kline): List<KlineCharacteristic> = fn(kline)
}

/**
 * A funnel stage that filters klines based on blackboard characteristics.
 * Returns an empty list (kline dropped) or the same kline (passthrough, no new chars).
 */
class FilterStage(
    override val name: CharSequence,
    override val consumes: Set<CharSequence>,
    private val predicate: (Kline) -> Boolean,
) : Funnel.Stage {
    override val produces: Set<CharSequence> = emptySet()
    override suspend fun process(kline: Kline): List<KlineCharacteristic> {
        return if (predicate(kline)) emptyList() else emptyList()  // empty = no new chars; filter is implied
    }
}

/**
 * A funnel stage that branches klines to a side channel when a condition is met.
 */
class BranchStage(
    override val name: CharSequence,
    override val consumes: Set<CharSequence>,
    override val produces: Set<CharSequence> = emptySet(),
    private val condition: (Kline) -> Boolean,
    private val branchChannel: Channel<Kline>,
) : Funnel.Stage {
    override suspend fun process(kline: Kline): List<KlineCharacteristic> {
        if (condition(kline)) {
            branchChannel.send(kline)
        }
        return emptyList()
    }
}

/**
 * A funnel stage that buffers klines for batch processing.
 * Flushes when buffer reaches capacity OR when age since first entry exceeds maxAgeMs.
 */
class BufferStage(
    override val name: CharSequence,
    override val consumes: Set<CharSequence> = emptySet(),
    override val produces: Set<CharSequence> = emptySet(),
    private val capacity: Int,
    private val maxAgeMs: Long,
    private val onFlush: (List<Kline>) -> Unit,
) : Funnel.Stage {
    private var buffer = listOf<Kline>()
    private var firstTimestamp: Long? = null

    override suspend fun process(kline: Kline): List<KlineCharacteristic> {
        if (firstTimestamp == null) firstTimestamp = kline.openTime
        buffer = buffer + listOf(kline)

        val age = kline.openTime - (firstTimestamp ?: kline.openTime)
        if (buffer.size >= capacity || age > maxAgeMs) {
            val flush = buffer
            buffer = emptyList()
            firstTimestamp = null
            onFlush(flush)
        }
        return emptyList()
    }
}

// ─── Convenience factory functions ────────────────────────────────────────────

/**
 * Create a compute stage that produces named characteristics from a lambda.
 * Example: computeStage("vol", setOf(CharName.VolumeRatio)) { kline -> ... }
 */
fun computeStage(
    name: CharSequence,
    consumes: Set<CharSequence> = emptySet(),
    produces: Set<CharSequence>,
    fn: suspend (Kline) -> List<KlineCharacteristic>,
) = ComputeStage(name, consumes, produces, fn)

/**
 * Create a filter stage that drops klines where predicate returns false.
 */
fun filterStage(
    name: CharSequence,
    consumes: Set<CharSequence> = emptySet(),
    predicate: (Kline) -> Boolean,
) = FilterStage(name, consumes, predicate)

/**
 * Create a branch stage that sends matching klines to a side channel.
 */
fun branchStage(
    name: CharSequence,
    consumes: Set<CharSequence> = emptySet(),
    condition: (Kline) -> Boolean,
    branch: Channel<Kline>,
) = BranchStage(name, consumes, emptySet(), condition, branch)

/**
 * Create a buffer stage that batches klines and flushes on capacity or age.
 */
fun bufferStage(
    name: CharSequence,
    capacity: Int,
    maxAgeMs: Long,
    onFlush: (List<Kline>) -> Unit,
) = BufferStage(name, emptySet(), emptySet(), capacity, maxAgeMs, onFlush)
