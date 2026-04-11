package borg.literbike.json

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe object pool using lock-free queues.
 * Ported from literbike/src/json/pool.rs.
 *
 * Uses ConcurrentLinkedDeque for lock-free push/pop.
 * Objects are recycled to avoid allocation overhead.
 */
class AtomicPool<T : Any>(maxSize: Int = 1000) {

    private val queue = ConcurrentLinkedDeque<T>()
    private val totalCreated = AtomicInteger(0)
    private val currentSize = AtomicInteger(0)
    private val maxSize: Int = maxSize

    constructor() : this(1000)

    /**
     * Get an object from the pool, or create a new one if empty.
     * Lock-free: returns immediately with either a reused object or a newly created one.
     */
    fun getOrCreate(factory: () -> T): T {
        val obj = queue.pollFirst()
        if (obj != null) {
            currentSize.decrementAndGet()
            return obj
        }

        totalCreated.incrementAndGet()
        return factory()
    }

    /**
     * Return an object to the pool for reuse.
     * Objects should be in a clean state before returning.
     * If the pool is at max capacity, the object will be dropped.
     */
    fun put(obj: T) {
        val current = currentSize.get()
        if (current >= maxSize) {
            return // Pool at capacity, drop the object
        }

        if (currentSize.compareAndSet(current, current + 1)) {
            queue.addLast(obj)
        } else {
            // Another thread added an object, check new size
            val newCurrent = currentSize.get()
            if (newCurrent < maxSize) {
                queue.addLast(obj)
                currentSize.incrementAndGet()
            }
            // Otherwise, drop the object
        }
    }

    /**
     * Get the number of objects currently in the pool.
     * This is an approximate value due to concurrent access.
     */
    fun size(): Int = currentSize.get()

    /** Get the total number of objects created (pool size + in-use). */
    fun totalCreated(): Int = totalCreated.get()

    /** Clear all objects from the pool. Useful for cleanup or memory reclamation. */
    fun clear() {
        while (queue.pollFirst() != null) {
            currentSize.decrementAndGet()
        }
    }
}

/**
 * A wrapper that automatically returns objects to the pool when closed.
 *
 * This ensures objects are always returned, even if an exception occurs.
 * Implements AutoCloseable for use with try-with-resources (use statements).
 */
class Pooled<T : Any>(
    private val pool: AtomicPool<T>,
    factory: () -> T
) : AutoCloseable {

    private var obj: T? = pool.getOrCreate(factory)

    fun get(): T = obj ?: throw IllegalStateException("Object already taken")

    fun getOrNull(): T? = obj

    fun take(): T {
        val taken = obj ?: throw IllegalStateException("Object already taken")
        obj = null
        return taken
    }

    fun returnToPool() {
        obj?.let { pool.put(it) }
        obj = null
    }

    override fun close() {
        returnToPool()
    }

    fun getOrNull(): T? = obj
}

inline fun <T : Any, R> AtomicPool<T>.use(factory: () -> T, block: (T) -> R): R {
    val pooled = Pooled(this, factory)
    return try {
        block(pooled.get())
    } finally {
        pooled.close()
    }
}
