package org.xvm.cursor

import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton string intern pool with live int hash IDs.
 *
 * All strings are deduplicated via [intern]. The returned int is a stable
 * identifier that can be used in event records and Redux state instead of
 * holding live String objects.
 *
 * - O(1) intern (ConcurrentHashMap lookup)
 * - No GC pressure from repeated string values
 * - Events store int hash IDs, not String references
 * - [resolve] to get the canonical String back from a hash ID
 *
 * String pool is never cleared — it grows for the lifetime of the process.
 * This is intentional: the same typedef names and class names appear
 * repeatedly, so the pool converges to a stable working set.
 */
object StringPool {

    /** Map: canonical String → live int hash ID (assigned sequentially from 1) */
    private val byString = ConcurrentHashMap<String, Int>()

    /** Map: live int hash ID → canonical String */
    private val byId = ConcurrentHashMap<Int, String>()

    /** Next ID to assign — start at 1 so 0 can mean "null/unset" */
    private val nextId = java.util.concurrent.atomic.AtomicInteger(1)

    /**
     * Intern a string, returning its stable live hash ID.
     *
     * If the string has already been interned, returns the existing ID.
     * Otherwise assigns a new ID and stores the canonical reference.
     *
     * @param s the string to intern (may be empty or blank)
     * @return a positive int ID uniquely identifying this string in this process
     */
    @JvmStatic
    fun intern(s: String): Int {
        if (s.isEmpty()) return 0  // 0 is reserved for "empty/absent"

        return byString.computeIfAbsent(s) { str ->
            val id = nextId.getAndIncrement()
            byId.put(id, str)
            id
        }
    }

    /**
     * Resolve a live hash ID back to its canonical string.
     *
     * @param id a hash ID returned by [intern]
     * @return the canonical String, or null if the ID is unknown (0 or stale)
     */
    @JvmStatic
    fun resolve(id: Int): String? {
        if (id == 0) return ""
        return byId[id]
    }

    /**
     * Current number of unique strings in the pool.
     */
    @JvmStatic
    fun size(): Int = byString.size

    /**
     * All known (id, string) pairs — for debugging and serialization.
     */
    @JvmStatic
    fun all(): Map<Int, String> = java.util.Collections.unmodifiableMap(byId)

    /**
     * Clear the string pool and reset the next ID back to 1.
     */
    @JvmStatic
    fun clear() {
        byString.clear()
        byId.clear()
        nextId.set(1)
    }
}
