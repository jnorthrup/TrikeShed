package borg.trikeshed.cursor

import borg.trikeshed.lib.*

/**
 * Zooms into a nested cursor along a path of column indices.
 *
 * Each path element selects a column in the current cursor.
 * The operation flattens the resulting cursors across all parent rows.
 *
 * @param path Column indices to zoom into.
 * @return A new Cursor unnesting the values found at the target column.
 */
fun Cursor.zoom(vararg path: Int): Cursor {
    if (path.isEmpty()) return this
    
    var current: Cursor = this
    
    for (colIndex in path) {
        val n = current.size
        if (n == 0) {
            current = emptySeries()
            continue
        }
        
        // compute prefix sum of sizes
        var totalSize = 0
        val prefixSums = IntArray(n + 1)
        
        val capturedCurrent = current
        
        for (i in 0 until n) {
            val cellValue = capturedCurrent.b(i).b(colIndex).a
            val childCursor = cellValue as? Cursor
            totalSize += childCursor?.size ?: 0
            prefixSums[i + 1] = totalSize
        }
        
        if (totalSize == 0) {
            current = emptySeries()
            continue
        }
        
        val nextCursor = join(totalSize, fun(globalIndex: Int): RowVec {
            // binary search for parent row index
            var left = 0
            var right = n - 1
            var parentIndex = 0
            
            while (left <= right) {
                val mid = (left + right) / 2
                if (globalIndex < prefixSums[mid]) {
                    right = mid - 1
                } else if (globalIndex >= prefixSums[mid + 1]) {
                    left = mid + 1
                } else {
                    parentIndex = mid
                    break
                }
            }
            
            val cellValue = capturedCurrent.b(parentIndex).b(colIndex).a
            val childCursor = cellValue as Cursor
            val childIndex = globalIndex - prefixSums[parentIndex]
            
            return childCursor.b(childIndex)
        })
        
        current = nextCursor
    }
    
    return current
}

/**
 * Zooms into a nested cursor along a path of column names.
 *
 * @param path Column names to zoom into.
 * @return A new Cursor unnesting the values found at the target column.
 */
fun Cursor.zoom(vararg path: CharSequence): Cursor {
    if (path.isEmpty()) return this
    
    var current: Cursor = this
    
    for (colName in path) {
        if (current.size == 0) {
            current = emptySeries()
            continue
        }
        
        val firstRow = current.b(0)
        var colIndex = -1
        for (c in 0 until firstRow.size) {
            val meta = firstRow.b(c).b()
            if (meta.name == colName) {
                colIndex = c
                break
            }
        }
        
        if (colIndex == -1) {
            error("Column '$colName' not found")
        }
        
        current = current.zoom(colIndex)
    }
    
    return current
}
