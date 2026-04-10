package borg.literbike.ccek.store.couchdb

/**
 * CouchDB cursor for pagination
 */

/**
 * Cursor state for iterating through view results
 */
class CouchCursor(
    private val query: ViewQuery,
    private val hasMore: () -> Boolean,
    private val fetchNext: suspend () -> ViewResult
) {
    private var currentResult: ViewResult? = null
    private var currentIndex: Int = 0

    companion object {
        fun new(
            query: ViewQuery,
            hasMore: () -> Boolean,
            fetchNext: suspend () -> ViewResult
        ): CouchCursor = CouchCursor(query, hasMore, fetchNext)
    }

    /**
     * Get next row from cursor
     */
    suspend fun next(): ViewRow? {
        if (currentResult == null || currentIndex >= currentResult!!.rows.size) {
            if (hasMore()) {
                currentResult = fetchNext()
                currentIndex = 0
            } else {
                return null
            }
        }

        return currentResult?.rows?.get(currentIndex++)
    }

    /**
     * Check if there are more rows
     */
    suspend fun hasNext(): Boolean {
        if (currentResult == null || currentIndex >= currentResult!!.rows.size) {
            return hasMore()
        }
        return true
    }

    /**
     * Get encoded cursor string for resuming
     */
    fun encode(): String? {
        val currentRow = currentResult?.rows?.getOrNull(currentIndex) ?: return null
        return Cursor.new(currentRow.key, currentRow.id).encode()
    }
}
