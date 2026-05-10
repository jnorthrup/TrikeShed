package borg.trikeshed.miniduck

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.*
import kotlin.test.*

class CursorOpsTest {

   fun cursor(vararg rows: Join<String, Any?>): Cursor =
        rows.size j { DocRowVec(listOf("key", "val"), listOf(rows[it].a, rows[it].b)) }

    // ── where ────────────────────────────────────────────────────────────────

    @Test fun whereFiltersMatchingRows() {
        val c = cursor("a" j 1, "b" j 2, "c" j 3)
        val r = c.where(col("val") gt 1)
        assertEquals(2, r.size)
        assertEquals("b", r[0].getValue("key"))
        assertEquals("c", r[1].getValue("key"))
    }

    @Test fun whereEmptyResultWhenNothingMatches() {
        val c = cursor("a" j 1, "b" j 2)
        assertEquals(0, c.where(col("val") gt 99).size)
    }

    @Test fun whereAndCombinesTwoPredicates() {
        val c = cursor("a" j 1, "b" j 5, "c" j 10)
        val r = c.where(col("val") gt 1 and (col("val") lt 10))
        assertEquals(1, r.size)
        assertEquals("b", r[0].getValue("key"))
    }

    @Test fun whereNotNegates() {
        val c = cursor("x" j 1, "y" j 2)
        val r = c.where(!col("key").eq("x"))
        assertEquals(1, r.size)
        assertEquals("y", r[0].getValue("key"))
    }

    @Test fun whereInListMatchesSet() {
        val c = cursor("a" j 1, "b" j 2, "c" j 3)
        val r = c.where(col("key") inList listOf("a", "c"))
        assertEquals(2, r.size)
    }

    @Test fun whereBetweenIsInclusive() {
        val c = cursor("a" j 1, "b" j 5, "c" j 10)
        val r = c.where(col("val") between (1 j 5))
        assertEquals(2, r.size)
    }

    // ── take / drop ──────────────────────────────────────────────────────────

    @Test fun takeReturnsFirstN() {
        val c = cursor("a" j 1, "b" j 2, "c" j 3)
        val r = c.take(2)
        assertEquals(2, r.size)
        assertEquals("a", r[0].getValue("key"))
    }

    @Test fun takeBeyondSizeReturnsAll() {
        val c = cursor("a" j 1)
        assertEquals(1, c.take(100).size)
    }

    @Test fun takeZeroIsEmpty() {
        assertEquals(0, cursor("a" j 1).take(0).size)
    }

    @Test fun takeNegativeThrows() {
        assertFailsWith<IllegalArgumentException> { cursor("a" j 1).take(-1) }
    }

    @Test fun dropSkipsFirstN() {
        val c = cursor("a" j 1, "b" j 2, "c" j 3)
        val r = c.drop(1)
        assertEquals(2, r.size)
        assertEquals("b", r[0].getValue("key"))
    }

    @Test fun dropAllProducesEmpty() {
        assertEquals(0, cursor("a" j 1).drop(5).size)
    }

    @Test fun dropNegativeThrows() {
        assertFailsWith<IllegalArgumentException> { cursor("a" j 1).drop(-1) }
    }

    // ── orderBy ──────────────────────────────────────────────────────────────

    @Test fun orderByAscending() {
        val c = cursor("b" j 2, "a" j 1, "c" j 3)
        val r = c.orderBy("val")
        assertEquals(1, r[0].getValue("val"))
        assertEquals(2, r[1].getValue("val"))
        assertEquals(3, r[2].getValue("val"))
    }

    @Test fun orderByDescending() {
        val c = cursor("b" j 2, "a" j 1, "c" j 3)
        val r = c.orderBy("val", desc = true)
        assertEquals(3, r[0].getValue("val"))
        assertEquals(1, r[2].getValue("val"))
    }

    @Test fun orderByNullSortsFirst() {
        val rows: Cursor = 3 j {
            when (it) {
                0 -> DocRowVec(listOf("v"), listOf(null))
                1 -> DocRowVec(listOf("v"), listOf(1))
                else -> DocRowVec(listOf("v"), listOf(2))
            }
        }
        val r = rows.orderBy("v")
        assertNull(r[0].getValue("v"))
    }

    @Test fun orderByMultiSpec() {
        val rows: Cursor = 3 j {
            when (it) {
                0 -> DocRowVec(listOf("grp", "val"), listOf("b", 1))
                1 -> DocRowVec(listOf("grp", "val"), listOf("a", 2))
                else -> DocRowVec(listOf("grp", "val"), listOf("a", 1))
            }
        }
        val r = rows.orderBy(OrderSpec("grp"), OrderSpec("val"))
        assertEquals("a", r[0].getValue("grp")); assertEquals(1, r[0].getValue("val"))
        assertEquals("a", r[1].getValue("grp")); assertEquals(2, r[1].getValue("val"))
        assertEquals("b", r[2].getValue("grp"))
    }

    // ── project ──────────────────────────────────────────────────────────────

    @Test fun projectSelectsNamedColumns() {
        val c: Cursor = 1 j { DocRowVec(listOf("name", "age", "city"), listOf("Alice", 30, "NY")) }
        val r = c.project("name", "age")
        val row = r[0] as DocRowVec
        assertEquals(2, row.size)
        assertEquals("Alice", row["name"])
        assertEquals(30, row["age"])
        assertNull(row["city"])
    }

    @Test fun projectMissingKeyProducesNull() {
        val c: Cursor = 1 j { DocRowVec(listOf("x"), listOf(1)) }
        val row = c.project("x", "missing")[0] as DocRowVec
        assertNull(row["missing"])
    }

    @Test fun projectRetainsDeferredChildrenOnViewRows() {
        val nested = DocRowVec(listOf("body"), listOf("hello"))
        val base: Cursor = 1 j { ViewRowVec("doc1", "k", "v") { nested } }

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
        val base: Cursor = 1 j { BlobRowVec(byteArrayOf(0x7b, 0x7d)).toRowVec() { 1 j { nested } } }

        val projected = base.columns(0)
        val row = projected[0] as DocRowVec

        assertNotNull(row.child)
        assertEquals(1, row.child!!.size)
        assertSame(nested, row.child!![0])
    }

    // ── QueryPlan execute ─────────────────────────────────────────────────────

    @Test fun executeFilterPlanComposesCorrectly() {
        val base = cursor("a" j 1, "b" j 5, "c" j 3)
        val ref = RelationRef("db", "things", RelationKind.DOCS)
        val plan = ScanPlan(ref) filter (col("val") gt 2) limit 1
        val result = execute(plan, base)
        assertEquals(1, result.size)
        assertEquals(5, result[0].getValue("val"))
    }

    @Test fun executeProjectPlan() {
        val base: Cursor = 1 j { DocRowVec(listOf("a", "b"), listOf(1, 2)) }
        val ref = RelationRef("db", "t", RelationKind.DOCS)
        val plan = ScanPlan(ref) project listOf("a")
        val result = execute(plan, base)
        val row = result[0] as DocRowVec
        assertEquals(1, row.size)
        assertEquals(1, row["a"])
    }

    @Test fun executeOrderOffsetLimit() {
        val base = cursor("c" j 3, "a" j 1, "b" j 2)
        val ref = RelationRef("db", "t", RelationKind.DOCS)
        val plan = ScanPlan(ref) orderBy listOf(OrderSpec("val")) offset 1 limit 1
        val result = execute(plan, base)
        assertEquals(1, result.size)
        assertEquals(2, result[0].getValue("val"))
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
        assertNull(BlobRowVec(byteArrayOf()).toRowVec().getValue("anything"))
    }
}
