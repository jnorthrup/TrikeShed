package borg.trikeshed.collections.bits

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.binarySearch
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j

/**
 * A Roaring-shaped set of non-negative Ints in the MetaSeries idiom.
 *
 * Roaring's methodology: the key space is cut into 65,536-key chunks by the
 * high 16 bits, and each chunk holds its low 16 bits in whichever of three
 * containers is smallest — a sorted array, a list of runs, or a 1,024-word
 * bitmap. Here every one of those is a Series, and the set is a Series of
 * them (the curiously recursive shape: a container IS a `Series<Int>` of its
 * members, and [chunks] is a `Series2<high, container>`). No LongArray leaves
 * the type and there is no library behind it, so it is commonMain-pure.
 *
 * Immutable: [and], [or], [andNot] build new sets. Built for the SUMO
 * classifier ([borg.trikeshed.ontology.SumoClassifier]) where descendant sets
 * of DFS-numbered classes are a few runs and ancestor sets are short arrays;
 * the chooser is Roaring's own (smallest serialized form wins), so those
 * shapes fall out of the numbering rather than being special-cased.
 */
class RoaringSeries private constructor(
    private val keys: IntArray,
    private val containers: Array<BitContainer>,
) {
    /** The chunks: high-16-bit key paired with the container holding that chunk's low bits. */
    val chunks: Series2<Int, BitContainer> get() = keys.size j { i: Int -> keys[i] j containers[i] }

    val cardinality: Int = containers.sumOf { it.cardinality }
    fun isEmpty(): Boolean = cardinality == 0

    fun contains(x: Int): Boolean {
        if (x < 0) return false
        val at = keys.binarySearch(x ushr 16)
        return at >= 0 && containers[at].contains(x and 0xFFFF)
    }

    /** Ascending members as a Series: the k-th smallest member is `members[k]`. */
    val members: Series<Int>
        get() {
            val offsets = IntArray(keys.size + 1)
            for (i in keys.indices) offsets[i + 1] = offsets[i] + containers[i].cardinality
            return cardinality j { k: Int ->
                var c = offsets.binarySearch(k)
                if (c < 0) c = -c - 2 else while (c + 1 < keys.size && offsets[c + 1] == k) c++
                (keys[c] shl 16) or containers[c].members[k - offsets[c]]
            }
        }

    inline fun forEach(crossinline f: (Int) -> Unit) {
        for ((high, container) in chunkList()) {
            val base = high shl 16
            container.forEach { low -> f(base or low) }
        }
    }

    @PublishedApi
    internal fun chunkList(): List<Pair<Int, BitContainer>> = keys.indices.map { keys[it] to containers[it] }

    fun toIntArray(): IntArray {
        val out = IntArray(cardinality)
        var n = 0
        forEach { out[n++] = it }
        return out
    }

    fun first(): Int {
        require(cardinality > 0) { "empty RoaringSeries has no first member" }
        return (keys[0] shl 16) or containers[0].members[0]
    }

    infix fun or(other: RoaringSeries): RoaringSeries {
        if (other.isEmpty()) return this
        if (isEmpty()) return other
        val a = toIntArray(); val b = other.toIntArray()
        val out = IntArray(a.size + b.size)
        var i = 0; var j = 0; var n = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] < b[j] -> out[n++] = a[i++]
                a[i] > b[j] -> out[n++] = b[j++]
                else -> { out[n++] = a[i]; i++; j++ }
            }
        }
        while (i < a.size) out[n++] = a[i++]
        while (j < b.size) out[n++] = b[j++]
        return fromSortedDistinct(out, n)
    }

    infix fun and(other: RoaringSeries): RoaringSeries {
        if (isEmpty() || other.isEmpty()) return EMPTY
        val (small, big) = if (cardinality <= other.cardinality) this to other else other to this
        val out = IntArray(small.cardinality)
        var n = 0
        small.forEach { if (big.contains(it)) out[n++] = it }
        return fromSortedDistinct(out, n)
    }

    infix fun andNot(other: RoaringSeries): RoaringSeries {
        if (isEmpty() || other.isEmpty()) return this
        val out = IntArray(cardinality)
        var n = 0
        forEach { if (!other.contains(it)) out[n++] = it }
        return fromSortedDistinct(out, n)
    }

    fun intersects(other: RoaringSeries): Boolean {
        if (isEmpty() || other.isEmpty()) return false
        val (small, big) = if (cardinality <= other.cardinality) this to other else other to this
        var hit = false
        small.forEach { if (!hit && big.contains(it)) hit = true }
        return hit
    }

    /** Container shapes in use: array / run / bitmap counts. */
    fun shapeHistogram(): Map<String, Int> {
        var arrays = 0; var runs = 0; var bitmaps = 0
        for (c in containers) when (c) {
            is ArrayContainer -> arrays++
            is RunContainer -> runs++
            is BitmapContainer -> bitmaps++
        }
        return mapOf("array" to arrays, "run" to runs, "bitmap" to bitmaps)
    }

    /** Bytes the containers would serialize to (Roaring's own accounting; keys excluded). */
    val byteSize: Int get() = containers.sumOf { it.byteSize }

    override fun equals(other: Any?): Boolean =
        other is RoaringSeries && cardinality == other.cardinality && toIntArray().contentEquals(other.toIntArray())

    override fun hashCode(): Int = toIntArray().contentHashCode()

    override fun toString(): String = buildString {
        append("RoaringSeries(").append(cardinality).append(")[")
        var n = 0
        forEach { if (n < 16) { if (n > 0) append(','); append(it) }; n++ }
        if (n > 16) append(",…")
        append(']')
    }

    companion object {
        val EMPTY = RoaringSeries(IntArray(0), emptyArray())

        /** Build from any Ints (order and duplicates do not matter; negatives are refused). */
        fun of(values: IntArray): RoaringSeries {
            if (values.isEmpty()) return EMPTY
            val sorted = values.copyOf()
            sorted.sort()
            require(sorted[0] >= 0) { "RoaringSeries holds non-negative Ints; got ${sorted[0]}" }
            var n = 0
            for (i in sorted.indices) if (i == 0 || sorted[i] != sorted[i - 1]) sorted[n++] = sorted[i]
            return fromSortedDistinct(sorted, n)
        }

        fun of(values: Iterable<Int>): RoaringSeries = of(values.toList().toIntArray())

        fun singleton(x: Int): RoaringSeries = of(intArrayOf(x))

        fun ofAll(vararg values: Int): RoaringSeries = of(values)

        /** Every Int in `[from, until)`. */
        fun range(from: Int, until: Int): RoaringSeries {
            if (until <= from) return EMPTY
            return of(IntArray(until - from) { from + it })
        }

        private fun fromSortedDistinct(sorted: IntArray, n: Int): RoaringSeries {
            if (n == 0) return EMPTY
            val keys = ArrayList<Int>()
            val containers = ArrayList<BitContainer>()
            var start = 0
            while (start < n) {
                val high = sorted[start] ushr 16
                var end = start
                while (end < n && (sorted[end] ushr 16) == high) end++
                val lows = IntArray(end - start) { sorted[start + it] and 0xFFFF }
                keys.add(high)
                containers.add(BitContainer.choose(lows))
                start = end
            }
            return RoaringSeries(keys.toIntArray(), containers.toTypedArray())
        }
    }
}

/** One 65,536-key chunk. Members are the low 16 bits, ascending. */
sealed interface BitContainer {
    val cardinality: Int
    fun contains(low: Int): Boolean
    /** The container as a Series of its members — `members[k]` is the k-th smallest. */
    val members: Series<Int>
    fun forEach(f: (Int) -> Unit)
    /** Serialized size in bytes by Roaring's accounting — the chooser's yardstick. */
    val byteSize: Int

    companion object {
        const val BITMAP_WORDS = 1024

        /** Roaring's rule: the smallest of the three forms wins; ties go to the array. */
        fun choose(sortedLows: IntArray): BitContainer {
            val card = sortedLows.size
            var runs = 0
            for (i in sortedLows.indices) if (i == 0 || sortedLows[i] != sortedLows[i - 1] + 1) runs++
            val arrayBytes = 2 * card
            val runBytes = 2 + 4 * runs
            val bitmapBytes = 8 * BITMAP_WORDS
            return when {
                runBytes < arrayBytes && runBytes < bitmapBytes -> RunContainer.fromSorted(sortedLows, runs)
                arrayBytes <= bitmapBytes -> ArrayContainer(sortedLows)
                else -> BitmapContainer.fromSorted(sortedLows)
            }
        }
    }
}

/** Sorted low values — the sparse shape. */
class ArrayContainer(private val values: IntArray) : BitContainer {
    override val cardinality: Int get() = values.size
    override fun contains(low: Int): Boolean = values.binarySearch(low) >= 0
    override val members: Series<Int> get() = values.size j { i: Int -> values[i] }
    override fun forEach(f: (Int) -> Unit) { for (v in values) f(v) }
    override val byteSize: Int get() = 2 * values.size
}

/** `(start, length)` runs, ascending and non-overlapping — the shape of a DFS-numbered subtree. */
class RunContainer(private val starts: IntArray, private val lengths: IntArray) : BitContainer {
    private val before = IntArray(starts.size + 1).also { for (i in starts.indices) it[i + 1] = it[i] + lengths[i] }
    override val cardinality: Int get() = before[starts.size]

    /** The runs themselves as a Series2 of (start, length). */
    val runs: Series2<Int, Int> get() = starts.size j { i: Int -> starts[i] j lengths[i] }

    override fun contains(low: Int): Boolean {
        var at = starts.binarySearch(low)
        if (at >= 0) return true
        at = -at - 2
        return at >= 0 && low < starts[at] + lengths[at]
    }

    override val members: Series<Int>
        get() = cardinality j { k: Int ->
            var r = before.binarySearch(k)
            if (r < 0) r = -r - 2 else while (r + 1 < starts.size && before[r + 1] == k) r++
            starts[r] + (k - before[r])
        }

    override fun forEach(f: (Int) -> Unit) {
        for (i in starts.indices) for (v in starts[i] until starts[i] + lengths[i]) f(v)
    }

    override val byteSize: Int get() = 2 + 4 * starts.size

    companion object {
        fun fromSorted(sortedLows: IntArray, runCount: Int): RunContainer {
            val starts = IntArray(runCount); val lengths = IntArray(runCount)
            var r = -1
            for (i in sortedLows.indices) {
                if (i == 0 || sortedLows[i] != sortedLows[i - 1] + 1) { r++; starts[r] = sortedLows[i]; lengths[r] = 1 }
                else lengths[r]++
            }
            return RunContainer(starts, lengths)
        }
    }
}

/** 1,024 words of 64 bits — the dense shape. */
class BitmapContainer(private val words: LongArray) : BitContainer {
    init { require(words.size == BitContainer.BITMAP_WORDS) }
    override val cardinality: Int = words.sumOf { it.countOneBits() }
    override fun contains(low: Int): Boolean = (words[low ushr 6] ushr (low and 63)) and 1L == 1L

    override val members: Series<Int>
        get() = cardinality j { k: Int ->
            var remaining = k
            var w = 0
            while (true) {
                val pop = words[w].countOneBits()
                if (remaining < pop) break
                remaining -= pop; w++
            }
            var bits = words[w]
            repeat(remaining) { bits = bits and (bits - 1) }
            (w shl 6) + bits.countTrailingZeroBits()
        }

    override fun forEach(f: (Int) -> Unit) {
        for (w in words.indices) {
            var bits = words[w]
            while (bits != 0L) {
                f((w shl 6) + bits.countTrailingZeroBits())
                bits = bits and (bits - 1)
            }
        }
    }

    override val byteSize: Int get() = 8 * BitContainer.BITMAP_WORDS

    companion object {
        fun fromSorted(sortedLows: IntArray): BitmapContainer {
            val words = LongArray(BitContainer.BITMAP_WORDS)
            for (v in sortedLows) words[v ushr 6] = words[v ushr 6] or (1L shl (v and 63))
            return BitmapContainer(words)
        }
    }
}

/** Growable Int accumulator for building sets from many small unions in one sort. */
class IntAccumulator(initial: Int = 16) {
    private var buf = IntArray(initial)
    var size = 0
        private set
    fun add(v: Int) {
        if (size == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[size++] = v
    }
    fun addAll(set: RoaringSeries) { set.forEach { add(it) } }
    fun toRoaring(): RoaringSeries = RoaringSeries.of(buf.copyOf(size))
}
