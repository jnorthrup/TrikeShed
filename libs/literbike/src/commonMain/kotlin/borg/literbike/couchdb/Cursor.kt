package borg.literbike.couchdb

import kotlinx.datetime.*
import kotlinx.serialization.json.*
import kotlin.math.min

/**
 * Manages cursor-based pagination for efficient data retrieval
 */
class CursorManager(
    private val cursorTtl: Duration = Duration.minutes(30) // 30 minute TTL
) {
    private val cursors: MutableMap<String, Cursor> = mutableMapOf()

    companion object {
        fun new() = CursorManager()
        fun newWithTtl(ttlMinutes: Long) = CursorManager(cursorTtl = Duration.minutes(ttlMinutes))
    }

    /**
     * Create a new cursor
     */
    fun createCursor(key: JsonElement, docId: String? = null, skip: UInt): String {
        val cursorId = generateUuid()
        val cursor = Cursor(key = key, docId = docId, skip = skip)

        cursors[cursorId] = cursor
        cursorId
    }

    /**
     * Get cursor by ID
     */
    fun getCursor(cursorId: String): CouchResult<Cursor> {
        val cursor = cursors[cursorId]
            ?: return Result.failure(CouchException(CouchError.notFound("Cursor not found")))

        // Check if cursor has expired
        val now = Clock.System.now()
        if (now - cursor.timestamp > cursorTtl) {
            return Result.failure(CouchException(CouchError.notFound("Cursor has expired")))
        }
        return Result.success(cursor)
    }

    /**
     * Encode cursor to base64 string for client
     */
    fun encodeCursor(cursorId: String): CouchResult<String> {
        val cursor = getCursor(cursorId).getOrThrow()
        return runCatching { cursor.encode() }
            .recoverCatching { throw CouchException(CouchError.internalServerError("Failed to encode cursor: ${it.message}")) }
    }

    /**
     * Decode cursor from base64 string
     */
    fun decodeCursor(encoded: String): CouchResult<Cursor> {
        return runCatching { Cursor.decode(encoded) }
            .recoverCatching { throw CouchException(CouchError.badRequest("Invalid cursor format: ${it.message}")) }
    }

    /**
     * Store decoded cursor and return ID
     */
    fun storeDecodedCursor(encoded: String): CouchResult<String> {
        val cursor = decodeCursor(encoded).getOrThrow()
        val cursorId = generateUuid()
        cursors[cursorId] = cursor
        return Result.success(cursorId)
    }

    /**
     * Update cursor position
     */
    fun updateCursor(cursorId: String, newKey: JsonElement, newDocId: String?, newSkip: UInt): CouchResult<Unit> {
        val cursor = cursors[cursorId]
            ?: return Result.failure(CouchException(CouchError.notFound("Cursor not found")))

        // Cursors are immutable in our Kotlin version, so we replace
        cursors[cursorId] = cursor.copy(
            key = newKey,
            docId = newDocId,
            skip = newSkip,
            timestamp = Clock.System.now()
        )
        return Result.success(Unit)
    }

    /**
     * Delete cursor
     */
    fun deleteCursor(cursorId: String): Boolean {
        return cursors.remove(cursorId) != null
    }

    /**
     * Clean up expired cursors
     */
    fun cleanupExpired() {
        val now = Clock.System.now()
        val expiredKeys = cursors.filterValues { now - it.timestamp > cursorTtl }.keys.toList()
        expiredKeys.forEach { cursors.remove(it) }
    }

    /**
     * Get cursor statistics
     */
    fun getStats(): CursorStats {
        val now = Clock.System.now()
        var expiredCount = 0
        var activeCount = 0

        cursors.values.forEach { cursor ->
            if (now - cursor.timestamp > cursorTtl) {
                expiredCount++
            } else {
                activeCount++
            }
        }

        return CursorStats(
            totalCursors = cursors.size,
            activeCursors = activeCount,
            expiredCursors = expiredCount,
            ttlMinutes = cursorTtl.inWholeMinutes
        )
    }

    private fun generateUuid(): String {
        return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace("[xy]".toRegex()) { match ->
            val r = (Math.random() * 16).toInt()
            val v = if (match.value == "x") r else (r and 0x3 or 0x8)
            v.toString(16)
        }
    }
}

/**
 * Cursor statistics
 */
data class CursorStats(
    val totalCursors: Int,
    val activeCursors: Int,
    val expiredCursors: Int,
    val ttlMinutes: Long
)

/**
 * Cursor-based pagination helper
 */
object PaginationHelper {
    /**
     * Apply cursor-based pagination to view results
     */
    fun applyCursorPagination(
        results: MutableList<JsonElement>,
        cursor: Cursor?,
        limit: Int,
        descending: Boolean
    ): JsonElement? {
        cursor?.let { c ->
            // Find the starting position based on cursor
            val startPos = findCursorPosition(results, c, descending)

            // Apply skip from cursor
            val skipPos = startPos + c.skip.toInt()

            if (skipPos < results.size) {
                results.subList(0, skipPos).clear()
            } else {
                results.clear()
                return null
            }
        }

        // Apply limit
        if (results.size > limit) {
            results.subList(limit, results.size).clear()

            // Create next cursor if there are more results
            return results.lastOrNull()
        }

        return null
    }

    /**
     * Find position of cursor in results
     */
    private fun findCursorPosition(
        results: List<JsonElement>,
        cursor: Cursor,
        descending: Boolean
    ): Int {
        results.forEachIndexed { i, result ->
            result.jsonObject["key"]?.let { key ->
                val comparison = compareKeys(cursor.key, key)

                val found = if (descending) {
                    comparison >= kotlin.comparisons.Ordering.EQUAL
                } else {
                    comparison <= kotlin.comparisons.Ordering.EQUAL
                }

                if (found) {
                    // If we have a doc_id, check for exact match
                    cursor.docId?.let { cursorDocId ->
                        result.jsonObject["id"]?.let { resultId ->
                            if (resultId.jsonPrimitive.contentOrNull == cursorDocId) {
                                return i
                            }
                        }
                    } ?: return i
                }
            }
        }
        return 0
    }

    /**
     * Compare two JSON values for ordering
     */
    fun compareKeys(a: JsonElement, b: JsonElement): Int {
        return when {
            a is JsonNull && b is JsonNull -> 0
            a is JsonNull -> -1
            b is JsonNull -> 1
            a is JsonPrimitive && a.isString && b is JsonPrimitive && b.isString ->
                a.jsonPrimitive.content.compareTo(b.jsonPrimitive.content)
            a is JsonPrimitive && a.longOrNull != null && b is JsonPrimitive && b.longOrNull != null ->
                a.jsonPrimitive.long.compareTo(b.jsonPrimitive.long)
            a is JsonPrimitive && a.doubleOrNull != null && b is JsonPrimitive && b.doubleOrNull != null ->
                a.jsonPrimitive.double.compareTo(b.jsonPrimitive.double)
            a is JsonArray && b is JsonArray -> {
                val size = min(a.size, b.size)
                for (i in 0 until size) {
                    val cmp = compareKeys(a[i], b[i])
                    if (cmp != 0) return cmp
                }
                a.size.compareTo(b.size)
            }
            else -> a.toString().compareTo(b.toString())
        }
    }
}
