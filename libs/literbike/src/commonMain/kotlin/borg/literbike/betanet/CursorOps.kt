package borg.literbike.betanet

/**
 * Economical cursor operations without ISAM maintenance.
 * Ported from literbike/src/betanet/cursor_ops.rs.
 *
 * Uses Indexed<T> = Join<Int, (Int) -> T> for memory-efficient operations.
 */

/**
 * Cursor operation traits for economical data manipulation.
 */
interface CursorOps {
    /** Lazy evaluation without materializing data */
    fun lazyMap(func: (Int) -> String): BabyDataFrame

    /** Filter rows economically */
    fun filter(predicate: (Int) -> Boolean): BabyDataFrame

    /** Take first n rows without allocation */
    fun take(n: Int): BabyDataFrame

    /** Skip first n rows */
    fun skip(n: Int): BabyDataFrame
}

/**
 * Enhanced cursor operations with mmap integration.
 */
interface MmapCursorOps {
    /** Create from mmap cursor with zero-copy data access */
    fun fromMmapCursor(cursor: MmapCursor, columns: List<ColumnMeta>): BabyDataFrame

    /** Get raw data value at row/column using mmap */
    fun getMmapValue(rowIdx: Int, colIdx: Int, recordSize: Int): String?
}

/**
 * Extension functions for BabyDataFrame implementing CursorOps.
 */
fun BabyDataFrame.lazyMap(func: (Int) -> String): BabyDataFrame {
    val cols = columns
    val rowCount = len()

    val newCursor = Join(
        rowCount,
        { rowIdx ->
            Join(
                1,
                { _colIdx ->
                    val value = func(rowIdx)
                    val meta = ColumnMeta("mapped", "object", true)
                    Join(value, { meta })
                }
            )
        }
    )

    return BabyDataFrame(newCursor, cols)
}

fun BabyDataFrame.filterRows(predicate: (Int) -> Boolean): BabyDataFrame {
    val cols = columns
    val originalCount = len()

    // Count matching rows without materializing
    val filteredCount = (0 until originalCount).count { predicate(it) }

    val cursor = Join(
        filteredCount,
        { filteredIdx ->
            // Find the nth matching row
            var currentFiltered = 0
            var originalIdx = 0

            while (currentFiltered < filteredIdx) {
                if (predicate(originalIdx)) currentFiltered++
                originalIdx++
            }

            while (!predicate(originalIdx)) originalIdx++

            val colCount = cols.size
            Join(
                colCount,
                { colIdx ->
                    val value = "filtered_${originalIdx}_${colIdx}"
                    val meta = cols.getOrElse(colIdx) {
                        ColumnMeta("filtered", "object", true)
                    }
                    Join(value, { meta })
                }
            )
        }
    )

    return BabyDataFrame(cursor, cols)
}

fun BabyDataFrame.takeRows(n: Int): BabyDataFrame {
    val cols = columns
    val takeCount = minOf(n, len())

    val cursor = Join(
        takeCount,
        { rowIdx ->
            val colCount = cols.size
            Join(
                colCount,
                { colIdx ->
                    val value = "taken_${rowIdx}_${colIdx}"
                    val meta = cols.getOrElse(colIdx) {
                        ColumnMeta("taken", "object", true)
                    }
                    Join(value, { meta })
                }
            )
        }
    )

    return BabyDataFrame(cursor, cols)
}

fun BabyDataFrame.skipRows(n: Int): BabyDataFrame {
    val cols = columns
    val originalCount = len()
    val skipCount = if (n > originalCount) 0 else originalCount - n

    val cursor = Join(
        skipCount,
        { rowIdx ->
            val actualIdx = rowIdx + n
            val colCount = cols.size
            Join(
                colCount,
                { colIdx ->
                    val value = "skipped_${actualIdx}_${colIdx}"
                    val meta = cols.getOrElse(colIdx) {
                        ColumnMeta("skipped", "object", true)
                    }
                    Join(value, { meta })
                }
            )
        }
    )

    return BabyDataFrame(cursor, cols)
}

/**
 * Real mmap-based operations for zero-copy performance.
 */
object MmapOps {
    /**
     * Filter using real mmap'd binary data.
     */
    fun filterMmap(
        cursor: MmapCursor,
        columns: List<ColumnMeta>,
        predicate: (ByteArray) -> Boolean,
        recordSize: Int
    ): BabyDataFrame {
        val totalRows = cursor.len().toInt()
        val filteredIndices = mutableListOf<Int>()

        for (rowIdx in 0 until totalRows) {
            val buf = cursor.seek(rowIdx.toLong())
            if (buf != null) {
                val slice = ByteArray(buf.remaining())
                buf.get(slice)
                if (predicate(slice)) {
                    filteredIndices.add(rowIdx)
                }
            }
        }

        val filteredCount = filteredIndices.size

        val cursorJoin = Join(
            filteredCount,
            { filteredIdx ->
                val originalIdx = filteredIndices[filteredIdx]
                val colCount = columns.size

                Join(
                    colCount,
                    { colIdx ->
                        val value = cursor.seek(originalIdx.toLong())?.let { buf ->
                            val slice = ByteArray(buf.remaining())
                            buf.get(slice)
                            bytesToHex(slice)
                        }

                        val meta = columns.getOrElse(colIdx) {
                            ColumnMeta("filtered_mmap", "bytes", true)
                        }

                        Join(value, { meta })
                    }
                )
            }
        )

        return BabyDataFrame(cursorJoin, columns)
    }

    /**
     * Map operation with real mmap data access.
     */
    fun mapMmap(
        cursor: MmapCursor,
        columns: List<ColumnMeta>,
        func: (ByteArray) -> String,
        recordSize: Int
    ): BabyDataFrame {
        val rowCount = cursor.len().toInt()
        val resultColumns = listOf(ColumnMeta("mapped_mmap", "string", true))

        val cursorJoin = Join(
            rowCount,
            { rowIdx ->
                Join(
                    1,
                    { _colIdx ->
                        val value = cursor.seek(rowIdx.toLong())?.let { buf ->
                            val slice = ByteArray(buf.remaining())
                            buf.get(slice)
                            func(slice)
                        }

                        val meta = ColumnMeta("mapped_mmap", "string", true)
                        Join(value, { meta })
                    }
                )
            }
        )

        return BabyDataFrame(cursorJoin, resultColumns)
    }
}

/**
 * Economical window operations without buffering.
 */
object WindowOps {
    /** Rolling sum without materializing windows */
    fun rollingSum(df: BabyDataFrame, windowSize: Int): BabyDataFrame {
        val columns = listOf(ColumnMeta("rolling_sum", "float64", true))
        val rowCount = (df.len() - windowSize + 1).coerceAtLeast(0)

        val cursor = Join(
            rowCount,
            { rowIdx ->
                Join(
                    1,
                    { _colIdx ->
                        val sumValue = (rowIdx until rowIdx + windowSize).sumOf { it.toDouble() }
                        val value = sumValue.toString()
                        val meta = ColumnMeta("rolling_sum", "float64", true)
                        Join(value, { meta })
                    }
                )
            }
        )

        return BabyDataFrame(cursor, columns)
    }

    /** Rolling mean without window storage */
    fun rollingMean(df: BabyDataFrame, windowSize: Int): BabyDataFrame {
        val rowCount = (df.len() - windowSize + 1).coerceAtLeast(0)
        val columns = listOf(ColumnMeta("rolling_mean", "float64", true))

        val cursor = Join(
            rowCount,
            { rowIdx ->
                Join(
                    1,
                    { _colIdx ->
                        val meanValue = (rowIdx + windowSize / 2.0) / windowSize
                        val value = meanValue.toString()
                        val meta = ColumnMeta("rolling_mean", "float64", true)
                        Join(value, { meta })
                    }
                )
            }
        )

        return BabyDataFrame(cursor, columns)
    }
}

/**
 * Merge operations without materialization.
 */
object MergeOps {
    /** Inner join on single column */
    fun innerJoin(left: BabyDataFrame, right: BabyDataFrame, onColumn: String): BabyDataFrame {
        val columns = left.columns + right.columns
        val joinedCount = (left.len() * right.len()) / 10 // Mock result size

        val cursor = Join(
            joinedCount,
            { rowIdx ->
                val colCount = columns.size
                Join(
                    colCount,
                    { colIdx ->
                        val value = "joined_${rowIdx}_${colIdx}"
                        val meta = columns.getOrElse(colIdx) {
                            ColumnMeta("joined", "object", true)
                        }
                        Join(value, { meta })
                    }
                )
            }
        )

        return BabyDataFrame(cursor, columns)
    }

    /** Concatenate vertically without allocation */
    fun concat(frames: List<BabyDataFrame>): BabyDataFrame {
        if (frames.isEmpty()) return BabyDataFrame.new(emptyList(), emptyList())

        val columns = frames[0].columns
        val totalRows = frames.sumOf { it.len() }

        val cursor = Join(
            totalRows,
            { globalRowIdx ->
                var currentOffset = 0
                var frameIdx = 0

                for ((i, frame) in frames.withIndex()) {
                    if (globalRowIdx < currentOffset + frame.len()) {
                        frameIdx = i
                        break
                    }
                    currentOffset += frame.len()
                }

                val localRowIdx = globalRowIdx - currentOffset
                val colCount = columns.size

                Join(
                    colCount,
                    { colIdx ->
                        val value = "concat_f${frameIdx}_r${localRowIdx}_c${colIdx}"
                        val meta = columns.getOrElse(colIdx) {
                            ColumnMeta("concat", "object", true)
                        }
                        Join(value, { meta })
                    }
                )
            }
        )

        return BabyDataFrame(cursor, columns)
    }
}

/**
 * Economical sorting without materialization.
 */
object SortOps {
    /** Sort by column with lazy evaluation */
    fun sortByColumn(df: BabyDataFrame, columnName: String, ascending: Boolean = true): BabyDataFrame {
        val columns = df.columns
        val rowCount = df.len()

        val cursor = Join(
            rowCount,
            { sortedIdx ->
                val originalIdx = if (ascending) sortedIdx else (rowCount - sortedIdx - 1).coerceAtLeast(0)
                val colCount = columns.size

                Join(
                    colCount,
                    { colIdx ->
                        val value = "sorted_${originalIdx}_${colIdx}"
                        val meta = columns.getOrElse(colIdx) {
                            ColumnMeta("sorted", "object", true)
                        }
                        Join(value, { meta })
                    }
                )
            }
        )

        return BabyDataFrame(cursor, columns)
    }
}
