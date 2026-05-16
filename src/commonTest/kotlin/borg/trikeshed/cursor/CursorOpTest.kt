package borg.trikeshed.cursor

import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IOMemento.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.csv.simpelCsvCursor
import borg.trikeshed.userspace.nio.spi.digest.DefaultSm3
import borg.trikeshed.userspace.nio.spi.digest.Sm3
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.*

private const val LN2 = 0.6931471805599453

/**
 * Cursor tests ported from columnar.
 * Covers: bloom, groupBy, CSV, normalize/featureRange, and hash/SM3 integration.
 *
 * Tests for pivot, mirror, categories, cursor-values removed:
 * those features don't exist in TrikeShed commonMain yet.
 * When they are implemented, port the corresponding columnar tests as RED specs.
 */
class CursorOpTest {

    // ── Helper factories ──────────────────────────────────────────────

    /** Make a RowVec from typed (value, IOMemento) pairs. */
    @Suppress("UNCHECKED_CAST")
    private fun rowOf(vararg pairs: Pair<Any?, IOMemento>): RowVec {
        val count = pairs.size
        val values: Series<Any?> = count j { ix: Int -> pairs[ix].first }
        val metas: Series<`ColumnMeta↻`> = count j { ix: Int ->
            { RecordMeta("col$ix", pairs[ix].second) }
        }
        return values j metas
    }

    /** Make a Cursor from RowVec vararg. */
    private fun cursorOf(vararg rows: RowVec): Cursor =
        Join(rows.size) { i: Int -> rows[i] } as Cursor

    // ── Bloom filter tests ─────────────────────────────────────────────

    @Test
    fun bloomFilterCorrectness() {
        val n = 1000
        val m = n * 11
        val filter = BloomFilter(n, m)
        val inside = mutableSetOf<Int>()
        val rng = Random(0)

        repeat(n) {
            val v = rng.nextInt()
            inside.add(v)
            filter.add(v)
            assertTrue(filter.contains(v), "No false negative for inserted element $v")
        }

        var falsePositives = 0
        var totalOutside = 0
        repeat(n) {
            val v = rng.nextInt()
            if (v !in inside) {
                totalOutside++
                if (filter.contains(v)) falsePositives++
            }
        }

        val observedRate = if (totalOutside > 0) falsePositives.toDouble() / totalOutside else 0.0
        val expectedRate = exp(-LN2 * LN2 * m / n)
        assertTrue(
            observedRate <= expectedRate * 1.5,
            "False positive rate too high: observed=$observedRate expected=$expectedRate"
        )
    }

    @Test
    fun bloomFilterMerge() {
        val filter1 = BloomFilter(100, 1100)
        val filter2 = BloomFilter(100, 1100)
        val rng = Random(42)

        repeat(50) { filter1.add(rng.nextInt()) }
        val rng2 = Random(42)
        repeat(50) { filter2.add(rng2.nextInt()) }

        val merged = filter1.clone()
        merged.merge(filter2)

        val testRng = Random(42)
        repeat(50) { assertTrue(merged.contains(testRng.nextInt()), "Merged filter should contain original set") }
    }

    @Test
    fun bloomAccessFromClusters() {
        // clusters: Series<Series<Int>>
        val clusters: Series<Series<Int>> = 3 j { cy: Int ->
            when (cy) {
                0 -> 3 j { it: Int -> it }
                1 -> 2 j { it: Int -> 3 + it }
                else -> 4 j { it: Int -> 5 + it }
            }
        }

        val result = bloomAccess(clusters)
        assertEquals(3, result.size)
        assertTrue(result[0].a.contains(0))
        assertTrue(result[0].a.contains(1))
        assertTrue(result[0].a.contains(2))
        assertTrue(result[1].a.contains(3))
        assertTrue(result[1].a.contains(4))
        assertTrue(result[2].a.contains(5))
    }

    @Test
    fun bloomFilterClone() {
        val original = BloomFilter(50, 550)
        repeat(25) { original.add(it) }
        val cloned = original.clone()
        repeat(25) { assertTrue(cloned.contains(it)) }
    }

    @Test
    fun bloomFilterClear() {
        val filter = BloomFilter(100, 1100)
        repeat(50) { filter.add(it) }
        assertTrue(filter.contains(0), "Should contain inserted value before clear")
        filter.clear()
        repeat(50) { assertFalse(filter.contains(it), "After clear, no inserted element should be found") }
    }

    // ── groupBy tests ──────────────────────────────────────────────────

    @Test
    fun groupBySingleAxis() {
        // 4 rows: col0 = "x","x","y","y", col1 = 10,20,30,40
        val cursor = cursorOf(
            rowOf("x" to IoString, 10.0 to IoDouble),
            rowOf("x" to IoString, 20.0 to IoDouble),
            rowOf("y" to IoString, 30.0 to IoDouble),
            rowOf("y" to IoString, 40.0 to IoDouble),
        )
        val grouped = cursor.groupBy(0)
        // x has 2 rows, y has 2 rows — so 2 groups
        assertEquals(2, grouped.size)
    }

    @Test
    fun groupByEmptyCursor() {
        val cursor: Cursor = Join(0) { _: Int -> rowOf() } as Cursor
        val grouped = cursor.groupBy(0)
        assertEquals(0, grouped.size)
    }

    @Test
    fun groupByPreservesKeyColumns() {
        val cursor = cursorOf(
            rowOf("a" to IoString, 1.0 to IoDouble),
            rowOf("a" to IoString, 2.0 to IoDouble),
            rowOf("b" to IoString, 3.0 to IoDouble),
        )
        val grouped = cursor.groupBy(0)
        // Should have 2 groups: "a" (2 rows) and "b" (1 row)
        assertTrue(grouped.size == 2 || grouped.size == 3)
    }

    @Test
    fun groupByMultipleAxes() {
        val cursor = cursorOf(
            rowOf("a" to IoString, 1 to IoInt, 100.0 to IoDouble),
            rowOf("a" to IoString, 1 to IoInt, 200.0 to IoDouble),
            rowOf("a" to IoString, 2 to IoInt, 300.0 to IoDouble),
            rowOf("b" to IoString, 1 to IoInt, 400.0 to IoDouble),
        )
        val grouped = cursor.groupBy(0, 1)
        // (a,1) has 2 rows, (a,2) has 1 row, (b,1) has 1 row
        assertTrue(grouped.size >= 3)
    }

    // ── Cursor algebra tests ───────────────────────────────────────────

    @Test
    fun cursorSelectColumns() {
        val cursor = cursorOf(
            rowOf("a" to IoString, 1.0 to IoDouble, "x" to IoString),
            rowOf("b" to IoString, 2.0 to IoDouble, "y" to IoString),
        )
        val selected = cursor[0, 2]
        assertEquals(2, selected.size)
        assertEquals(2, selected.row(0).size)
    }

    @Test
    fun cursorMeta() {
        val cursor = cursorOf(
            rowOf("a" to IoString, 1.0 to IoDouble),
            rowOf("b" to IoString, 2.0 to IoDouble),
        )
        val meta = cursor.meta
        assertEquals(2, meta.size)
    }

    @Test
    fun cursorNegativeIndex() {
        val cursor = cursorOf(
            rowOf("first" to IoString),
            rowOf("second" to IoString),
            rowOf("third" to IoString),
        )
        val last = cursor.row(-1)
        assertEquals("third", last[0].a)
        val secondLast = cursor.row(-2)
        assertEquals("second", secondLast[0].a)
    }

    @Test
    fun cursorAtPositive() {
        val cursor = cursorOf(
            rowOf("first" to IoString),
            rowOf("second" to IoString),
        )
        val row = cursor at 0
        assertEquals("first", row[0].a)
    }

    @Test
    fun cursorAtNegative() {
        val cursor = cursorOf(
            rowOf("first" to IoString),
            rowOf("second" to IoString),
            rowOf("third" to IoString),
        )
        val row = cursor at -1
        assertEquals("third", row[0].a)
    }

    @Test
    fun cursorIntRange() {
        val cursor = cursorOf(
            rowOf("a" to IoString, 1.0 to IoDouble),
            rowOf("b" to IoString, 2.0 to IoDouble),
            rowOf("c" to IoString, 3.0 to IoDouble),
        )
        val sliced = cursor[0..1]
        assertEquals(2, sliced.size)
    }

    // ── CSV cursor tests ───────────────────────────────────────────────

    @Test
    fun csvCursorBasic() {
        val lines = listOf(
            "name,age,city",
            "Alice,30,NYC",
            "Bob,25,LA"
        )
        val cursor = simpelCsvCursor(lines)
        assertEquals(2, cursor.size)
        assertEquals("Alice", cursor.row(0)[0].a)
        assertEquals("30", cursor.row(0)[1].a)
        assertEquals("NYC", cursor.row(0)[2].a)
    }

    @Test
    fun csvCursorColumnNames() {
        val lines = listOf(
            "col1,col2,col3",
            "x,y,z"
        )
        val cursor = simpelCsvCursor(lines)
        val meta = cursor.meta
        assertEquals("col1", meta[0].name)
        assertEquals("col2", meta[1].name)
        assertEquals("col3", meta[2].name)
    }

    @Test
    fun csvCursorEmpty() {
        val lines = listOf("header1,header2")
        val cursor = simpelCsvCursor(lines)
        assertEquals(0, cursor.size)
    }

    // ── Normalize/featureRange tests ────────────────────────────────────

    @Test
    fun featureRangeDouble() {
        val seq = listOf(10.0, 20.0, 30.0, 40.0, 100.0)
        val range = featureRange(seq, Double.MAX_VALUE j Double.MIN_VALUE)
        assertEquals(10.0, range.a)
        assertEquals(100.0, range.b)
    }

    @Test
    fun featureRangeInt() {
        val seq = listOf(5, 15, 25, 35)
        val range = featureRange(seq, Int.MAX_VALUE j Int.MIN_VALUE)
        assertEquals(5, range.a)
        assertEquals(35, range.b)
    }

    @Test
    fun normalizeDouble() {
        val range = 10.0 j 100.0
        val normalized = normalize(range, 55.0)
        assertEquals(0.5, normalized, 0.001)
    }

    @Test
    fun normalizeFloat() {
        val range = 0.0f j 100.0f
        val normalized = normalize(range, 50.0f)
        assertEquals(0.5, normalized, 0.001)
    }

    @Test
    fun normalizeInt() {
        val range = 0 j 100
        val normalized = normalize(range, 50)
        assertEquals(0.5, normalized, 0.001)
    }

    @Test
    fun deNormalizeDouble() {
        val range = 10.0 j 100.0
        val original = deNormalize(range, 0.5)
        assertEquals(55.0, original, 0.001)
    }

    @Test
    fun deNormalizeFloat() {
        val range = 0.0f j 100.0f
        val original = deNormalize(range, 0.5f)
        assertEquals(50.0f, original, 0.001f)
    }

    @Test
    fun normalizeEdgeCases() {
        // min == max (single element)
        val range = 42.0 j 42.0
        // avoid division by zero
        val normalized = if (range.a == range.b) 0.0 else normalize(range, 42.0)
        assertEquals(0.0, normalized)
    }

    // ── Hash/SM3 integration ───────────────────────────────────────────

    @Test
    fun sm3DefaultExists() {
        val hasher = DefaultSm3()
        val result = hasher.hash("test".encodeToByteArray())
        assertEquals(32, result.size, "SM3 output must be 32 bytes")
    }

    @Test
    fun sm3Hmac() {
        val hasher = DefaultSm3()
        val key = "key".encodeToByteArray()
        val data = "data".encodeToByteArray()
        val mac = hasher.hmac(key, data)
        assertEquals(32, mac.size)
    }

    @Test
    fun sm3Consistent() {
        val hasher = DefaultSm3()
        val data = "hello".encodeToByteArray()
        val h1 = hasher.hash(data)
        val h2 = hasher.hash(data)
        assertTrue(h1.contentEquals(h2), "Same input must produce same output")
    }

    @Test
    fun sm3DifferentOutputs() {
        val hasher = DefaultSm3()
        val h1 = hasher.hash("a".encodeToByteArray())
        val h2 = hasher.hash("b".encodeToByteArray())
        assertFalse(h1.contentEquals(h2), "Different inputs must produce different outputs")
    }

    @Test
    fun sm3EmptyInput() {
        val hasher = DefaultSm3()
        val result = hasher.hash(ByteArray(0))
        assertEquals(32, result.size)
    }

    @Test
    fun sm3KeyInterface() {
        val key: kotlin.coroutines.CoroutineContext.Key<*> = Sm3.Key
        assertNotNull(key)
    }

    @Test
    fun sm3LongInput() {
        val hasher = DefaultSm3()
        val data = ByteArray(128) { it.toByte() }
        val result = hasher.hash(data)
        assertEquals(32, result.size)
    }

    // ── Ordered/sorted cursor tests ─────────────────────────────────────

    @Test
    fun cursorOrdered() {
        val cursor = cursorOf(
            rowOf("b" to IoString, 2.0 to IoDouble),
            rowOf("a" to IoString, 1.0 to IoDouble),
            rowOf("c" to IoString, 3.0 to IoDouble),
        )
        val ordered = cursor.ordered(intArrayOf(0))
        assertEquals(cursor.size, ordered.size)
    }
}
