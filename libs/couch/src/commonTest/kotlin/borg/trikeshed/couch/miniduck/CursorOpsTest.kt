package borg.trikeshed.couch.miniduck

import borg.trikeshed.lib.*
import kotlin.test.*

class CursorOpsTest {

    private fun cursor(vararg rows: Pair<String, Any?>): MiniCursor =
        rows.size j { DocRowVec(listOf("key", "val"), listOf(rows[it].first, rows[it].second)) }

    // ── where ────────────────────────────────────────────────────────────────

    @Test fun whereFiltersMatchingRows() {
        val c = cursor("a" to 1, "b" to 2, "c" to 3)
        val r = c.where(col("val") gt 1)
        assertEquals(2, r.size)
        assertEquals("b", (r[0] as DocRowVec)["key"])
        assertEquals("c", (r[1] as DocRowVec)["key"])
    }

    @Test fun whereEmptyResultWhenNothingMatches() {
        val c = cursor("a" to 1, "b" to 2)
        assertEquals(0, c.where(col("val") gt 99).size)
    }

    @Test fun whereAndCombinesTwoPredicates() {
        val c = cursor("a" to 1, "b" to 5, "c" to 10)
        val r = c.where(col("val") gt 1 and (col("val") lt 10))
        assertEquals(1, r.size)
        assertEquals("b", (r[0] as DocRowVec)["key"])
    }

    @Test fun whereNotNegates() {
        val c = cursor("x" to 1, "y" to 2)
        val r = c.where(!col("key").eq("x"))
        assertEquals(1, r.size)
        assertEquals("y", (r[0] as DocRowVec)["key"])
    }

    @Test fun whereInListMatchesSet() {
        val c = cursor("a" to 1, "b" to 2, "c" to 3)
        val r = c.where(col("key") inList listOf("a", "c"))
        assertEquals(2, r.size)
    }

    @Test fun whereBetweenIsInclusive() {
        val c = cursor("a" to 1, "b" to 5, "c" to 10)
        val r = c.where(col("val") between (1 to 5))
        assertEquals(2, r.size)
    }

    // ── take / drop ──────────────────────────────────────────────────────────

    @Test fun takeReturnsFirstN() {
        val c = cursor("a" to 1, "b" to 2, "c" to 3)
        val r = c.take(2)
        assertEquals(2, r.size)
        assertEquals("a", (r[0] as DocRowVec)["key"])
    }

    @Test fun takeBeyondSizeReturnsAll() {
        val c = cursor("a" to 1)
        assertEquals(1, c.take(100).size)
    }

    @Test fun takeZeroIsEmpty() {
        assertEquals(0, cursor("a" to 1).take(0).size)
    }

    @Test fun takeNegativeThrows() {
        assertFailsWith<IllegalArgumentException> { cursor("a" to 1).take(-1) }
    }

    @Test fun dropSkipsFirstN() {
        val c = cursor("a" to 1, "b" to 2, "c" to 3)
        val r = c.drop(1)
        assertEquals(2, r.size)
        assertEquals("b", (r[0] as DocRowVec)["key"])
    }

    @Test fun dropAllProducesEmpty() {
        assertEquals(0, cursor("a" to 1).drop(5).size)
    }

    @Test fun dropNegativeThrows() {
        assertFailsWith<IllegalArgumentException> { cursor("a" to 1).drop(-1) }
    }

    // ── orderBy ──────────────────────────────────────────────────────────────

    @Test fun orderByAscending() {
        val c = cursor("b" to 2, "a" to 1, "c" to 3)
        val r = c.orderBy("val")
        assertEquals(1, (r[0] as DocRowVec)["val"])
        assertEquals(2, (r[1] as DocRowVec)["val"])
        assertEquals(3, (r[2] as DocRowVec)["val"])
    }

    @Test fun orderByDescending() {
        val c = cursor("b" to 2, "a" to 1, "c" to 3)
        val r = c.orderBy("val", desc = true)
        assertEquals(3, (r[0] as DocRowVec)["val"])
        assertEquals(1, (r[2] as DocRowVec)["val"])
    }

    @Test fun orderByNullSortsFirst() {
        val rows: MiniCursor = 3 j {
            when (it) {
                0 -> DocRowVec(listOf("v"), listOf(null))
                1 -> DocRowVec(listOf("v"), listOf(1))
                else -> DocRowVec(listOf("v"), listOf(2))
            }
        }
        val r = rows.orderBy("v")
        assertNull((r[0] as DocRowVec)["v"])
    }

    @Test fun orderByMultiSpec() {
        val rows: MiniCursor = 3 j {
            when (it) {
                0 -> DocRowVec(listOf("grp", "val"), listOf("b", 1))
                1 -> DocRowVec(listOf("grp", "val"), listOf("a", 2))
                else -> DocRowVec(listOf("grp", "val"), listOf("a", 1))
            }
        }
        val r = rows.orderBy(OrderSpec("grp"), OrderSpec("val"))
        assertEquals("a", (r[0] as DocRowVec)["grp"]); assertEquals(1, (r[0] as DocRowVec)["val"])
        assertEquals("a", (r[1] as DocRowVec)["grp"]); assertEquals(2, (r[1] as DocRowVec)["val"])
        assertEquals("b", (r[2] as DocRowVec)["grp"])
    }

    // ── project ──────────────────────────────────────────────────────────────

    @Test fun projectSelectsNamedColumns() {
        val c: MiniCursor = 1 j { DocRowVec(listOf("name", "age", "city"), listOf("Alice", 30, "NY")) }
        val r = c.project("name", "age")
        val row = r[0] as DocRowVec
        assertEquals(2, row.size)
        assertEquals("Alice", row["name"])
        assertEquals(30, row["age"])
        assertNull(row["city"])
    }

    @Test fun projectMissingKeyProducesNull() {
        val c: MiniCursor = 1 j { DocRowVec(listOf("x"), listOf(1)) }
        val row = c.project("x", "missing")[0] as DocRowVec
        assertNull(row["missing"])
    }

    @Test fun projectRetainsDeferredChildrenOnViewRows() {
        val nested = DocRowVec(listOf("body"), listOf("hello"))
        val base: MiniCursor = 1 j { ViewRowVec("doc1", "k", "v") { nested } }

        val projected = base.project("id", "key", "value")
        val row = projected[0] as DocRowVec

        assertEquals("doc1", row["id"])
        assertEquals("k", row["key"])
        assertEquals("v", row["value"])
        assertNotNull(row.child)
        assertEquals(1, row.child!!.size)
        assertSame(nested, row.child!![0])
    }

    @Test fun columnsRetainsDeferredChildrenOnBlobRows() {
        val nested = JsonRowVec("object", "{}")
        val base: MiniCursor = 1 j { BlobRowVec(byteArrayOf(0x7b, 0x7d)) { 1 j { nested } } }

        val projected = base.columns(0)
        val row = projected[0] as DocRowVec

        assertNotNull(row.child)
        assertEquals(1, row.child!!.size)
        assertSame(nested, row.child!![0])
    }

    // ── QueryPlan execute ─────────────────────────────────────────────────────

    @Test fun executeFilterPlanComposesCorrectly() {
        val base = cursor("a" to 1, "b" to 5, "c" to 3)
        val ref = RelationRef("db", "things", RelationKind.DOCS)
        val plan = ScanPlan(ref) filter (col("val") gt 2) limit 1
        val result = execute(plan, base)
        assertEquals(1, result.size)
        assertEquals(5, (result[0] as DocRowVec)["val"])
    }

    @Test fun executeProjectPlan() {
        val base: MiniCursor = 1 j { DocRowVec(listOf("a", "b"), listOf(1, 2)) }
        val ref = RelationRef("db", "t", RelationKind.DOCS)
        val plan = ScanPlan(ref) project listOf("a")
        val result = execute(plan, base)
        val row = result[0] as DocRowVec
        assertEquals(1, row.size)
        assertEquals(1, row["a"])
    }

    @Test fun executeOrderOffsetLimit() {
        val base = cursor("c" to 3, "a" to 1, "b" to 2)
        val ref = RelationRef("db", "t", RelationKind.DOCS)
        val plan = ScanPlan(ref) orderBy listOf(OrderSpec("val")) offset 1 limit 1
        val result = execute(plan, base)
        assertEquals(1, result.size)
        assertEquals(2, (result[0] as DocRowVec)["val"])
    }

    // ── compareKeys ──────────────────────────────────────────────────────────

    @Test fun compareKeysNullBeforeValue() {
        assertTrue(compareKeys(null, 1) < 0)
        assertTrue(compareKeys(1, null) > 0)
        assertEquals(0, compareKeys(null, null))
    }

    @Test fun compareKeysMixedNumbers() {
        assertEquals(0, compareKeys(1, 1L))
        assertTrue(compareKeys(1, 2.0) < 0)
    }

    // ── getValue dispatch ────────────────────────────────────────────────────

    @Test fun getValueOnViewRowVec() {
        val v = ViewRowVec("doc42", "keyA", 99)
        assertEquals("doc42", v.getValue("id"))
        assertEquals("doc42", v.getValue("_id"))
        assertEquals("keyA", v.getValue("key"))
        assertEquals(99, v.getValue("value"))
        assertNull(v.getValue("other"))
    }

    @Test fun getValueOnJsonRowVec() {
        val j = JsonRowVec("string", "\"hello\"")
        assertEquals("string", j.getValue("nodeType"))
        assertEquals("\"hello\"", j.getValue("rawValue"))
        assertNull(j.getValue("other"))
    }

    @Test fun getValueOnShellsReturnsNull() {
        assertNull(BlockRowVec.mutable().getValue("anything"))
        assertNull(BlobRowVec(byteArrayOf()).getValue("anything"))
    }
}
