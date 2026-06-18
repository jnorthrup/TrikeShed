package borg.trikeshed.tilting.sorting
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.size

import kotlin.math.min

typealias FlatFileRow = RowVec
typealias Pair<F,S> = Join<F, S>

class FlatFileSortingStrategy(
    private val cursor: Cursor,
    private val freeHeapSpace: Long,
    private val L1CacheSize: Int,
    private val rowsCount: Int,
    private val bytesPerRow: Int
) {
    enum class SortingAlgorithm {
        Qsort,
        MergeSort,
        TimSort,
        SuffixTree
    }

    fun getBestSortingAlgorithm(column: Int): SortingAlgorithm {
        // Calculate the size of the flatfile in memory
        val flatFileSize = cursor.size.toLong() * bytesPerRow.toLong()

        // Calculate the amount of memory needed for sorting
        val sortMemory = when (SortingAlgorithm.values().random()) {
            SortingAlgorithm.Qsort -> L1CacheSize.toLong()
            SortingAlgorithm.MergeSort -> min(freeHeapSpace / 2, flatFileSize)
            SortingAlgorithm.TimSort -> min(freeHeapSpace / 4, flatFileSize)
            SortingAlgorithm.SuffixTree -> flatFileSize
        }

        // Determine the best sorting algorithm based on available memory
        return when {
            sortMemory >= flatFileSize -> SortingAlgorithm.TimSort
            sortMemory >= rowsCount.toLong() * bytesPerRow.toLong() -> SortingAlgorithm.MergeSort
            sortMemory >= L1CacheSize.toLong() -> SortingAlgorithm.Qsort
            else -> SortingAlgorithm.SuffixTree
        }
    }
}
