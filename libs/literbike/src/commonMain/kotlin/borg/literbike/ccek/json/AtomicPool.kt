package borg.literbike.ccek.json

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe object pool using lock-free queues
 *
 * This module provides `AtomicPool<T>`, a thread-safe pool for reusing objects
 * across multiple threads without locks. It uses Java's ConcurrentLinkedQueue
 * for maximum performance under concurrent access.
 *
 * Architecture:
 * - Uses `ConcurrentLinkedQueue` for lock-free push/pop
 * - Supports any type `T: Any`
 * - Objects are returned to the pool when dropped
 * - Pool size is unbounded but self-limiting via object reuse
 *
 * Thread Safety:
 * All operations are thread-safe and can be called concurrently without
 * external synchronization. The internal queue uses atomic operations for
 * coordination.
 *
 * Example:
 * ```kotlin
 * val pool = AtomicPool<MutableList<Byte>>()
 *
 * // Get an object from the pool (or create new)
 * val obj = pool.getOrCreate { mutableListOf() }
 *
 * // Use the object
 * obj.add(1)
 *
 * // Return to pool
 * pool.put(obj)
 * ```
 */

/**
 * Thread-safe pool for object reuse
 *
 * Uses a lock-free queue for maximum performance under concurrent access.
 * Objects are recycled to avoid allocation overhead.
 *
 * Memory Safety:
 * - Uses atomic operations for proper synchronization
 * - Pool has maximum size to prevent unbounded growth
 * - Counters are updated atomically with queue operations
 */
class AtomicPool<T : Any> private constructor(
    private val queue: ConcurrentLinkedQueue<T>,
    private val totalCreated: AtomicInteger,
    private val currentSize: AtomicInteger,
    private val maxSize: Int,
) {
    companion object {
        /** Create a new empty pool with default max size (1000) */
        fun <T : Any> create(): AtomicPool<T> {
            return withMaxSize(1000)
        }

        /** Create a new empty pool with specified max size */
        fun <T : Any> withMaxSize(maxSize: Int): AtomicPool<T> {
            return AtomicPool(
                queue = ConcurrentLinkedQueue(),
                totalCreated = AtomicInteger(0),
                currentSize = AtomicInteger(0),
                maxSize = maxSize,
            )
        }
    }

    /**
     * Get an object from the pool, or create a new one if empty
     *
     * This is lock-free and will return immediately with either a reused
     * object or a newly created one.
     */
    inline fun getOrCreate(factory: () -> T): T {
        // Try to pop from pool with proper acquire semantics
        val obj = queue.poll()
        if (obj != null) {
            // Use atomic decrement to synchronize with put()
            currentSize.decrementAndGet()
            return obj
        }

        // Pool empty, create new object
        val newObj = factory()
        // Use relaxed for totalCreated since it's just statistics
        totalCreated.incrementAndGet()
        return newObj
    }

    /**
     * Return an object to the pool for reuse
     *
     * Objects should be in a clean state before returning.
     * If the pool is at max capacity, the object will be dropped
     * to prevent unbounded memory growth.
     */
    fun put(obj: T) {
        // Check current size BEFORE pushing to prevent race condition
        var current = currentSize.get()

        if (current >= maxSize) {
            // Pool at capacity, drop the object to prevent leak
            return
        }

        // Try to increment size, but check again to prevent race
        if (currentSize.compareAndSet(current, current + 1)) {
            // Successfully reserved a slot, now push to queue
            queue.offer(obj)
        } else {
            // Another thread added an object, check new size
            current = currentSize.get()
            if (current < maxSize) {
                // Still under capacity, try again
                queue.offer(obj)
                currentSize.incrementAndGet()
            }
            // Otherwise, drop the object
        }
    }

    /**
     * Get the number of objects currently in the pool
     *
     * This is an approximate value due to concurrent access.
     */
    fun size(): Int = currentSize.get()

    /** Get the total number of objects created (pool size + in-use) */
    fun totalCreated(): Int = totalCreated.get()

    /**
     * Clear all objects from the pool
     *
     * Useful for cleanup or memory reclamation.
     */
    fun clear() {
        while (queue.poll() != null) {
            // Atomic decrement to synchronize with any pending operations
            currentSize.decrementAndGet()
        }
    }
}

/**
 * A wrapper that automatically returns objects to the pool when no longer referenced
 *
 * This ensures objects are always returned, even if an exception occurs.
 *
 * Thread Safety:
 * `Pooled<T>` is safe to transfer across threads.
 *
 * Example:
 * ```kotlin
 * val pool = AtomicPool<MutableList<Byte>>()
 * val pooled = Pooled(pool) { mutableListOf() }
 * pooled.use { obj ->
 *     obj.add(1)
 * }
 * // obj is automatically returned to pool
 * ```
 */
class Pooled<T : Any> private constructor(
    private val pool: AtomicPool<T>,
    private var obj: T?,
) : AutoCloseable {
    companion object {
        /**
         * Create a new pooled object
         */
        inline fun <T : Any> create(
            pool: AtomicPool<T>,
            noinline factory: () -> T,
        ): Pooled<T> {
            val obj = pool.getOrCreate(factory)
            return Pooled(pool, obj)
        }
    }

    /** Get a reference to the inner object */
    fun get(): T = obj ?: throw IllegalStateException("Object already returned to pool")

    /** Get a mutable reference to the inner object */
    fun getMut(): T = obj ?: throw IllegalStateException("Object already returned to pool")

    /**
     * Take the object out of the wrapper, preventing automatic return
     *
     * You must manually return it to the pool with `pool.put()`.
     */
    fun take(): T {
        val taken = obj ?: throw IllegalStateException("Object already taken")
        obj = null
        return taken
    }

    /** Manually return the object to the pool before close */
    fun returnToPool() {
        obj?.let { o ->
            pool.put(o)
            obj = null
        }
    }

    override fun close() {
        obj?.let { o ->
            pool.put(o)
            obj = null
        }
    }
}

/** Extension to use Pooled with use block for auto-return */
inline fun <T : Any, R> Pooled<T>.use(block: (T) -> R): R {
    val value = get()
    try {
        return block(value)
    } finally {
        returnToPool()
    }
}
