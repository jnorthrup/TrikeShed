package borg.trikeshed.couch.miniduck.query

import borg.trikeshed.couch.miniduck.*
import borg.trikeshed.lib.*
import kotlin.test.*

/**
 * RED test: Hash join across block cursors.
 *
 * SQL equivalent: SELECT * FROM orders JOIN users ON orders.userId = users.id
 *
 * In MiniDuck, this is a cursor transform:
 *   cursor.hashJoin(other, leftKey, rightKey)
 *
 * Output rows have columns from both sides. Left-key columns come first,
 * right-key columns are appended. The join key column from the right side
 * is omitted to avoid duplication.
 *
 * Donor: DuckDB hash join, Spark broadcastHashJoin, CouchDB view collation.
 */
class BlockJoinTest {

    private fun usersCursor(): MiniCursor {
        val rows = listOf(
            DocRowVec(listOf("id", "name"), listOf(1, "alice")),
            DocRowVec(listOf("id", "name"), listOf(2, "bob")),
            DocRowVec(listOf("id", "name"), listOf(3, "carol")),
        )
        return rows.size j { rows[it] }
    }

    private fun ordersCursor(): MiniCursor {
        val rows = listOf(
            DocRowVec(listOf("orderId", "userId", "amount"), listOf(101, 1, 50.0)),
            DocRowVec(listOf("orderId", "userId", "amount"), listOf(102, 2, 75.0)),
            DocRowVec(listOf("orderId", "userId", "amount"), listOf(103, 1, 25.0)),
            DocRowVec(listOf("orderId", "userId", "amount"), listOf(104, 4, 100.0)), // no user 4
        )
        return rows.size j { rows[it] }
    }

    // ── Basic join ───────────────────────────────────────────────────────

    @Test
    fun hashJoinReturnsMatchingRows() {
        val users = usersCursor()
        val orders = ordersCursor()

        val joined = orders.hashJoin(users, "userId", "id")

        // 3 matching orders: 101 (user 1), 102 (user 2), 103 (user 1)
        // Order 104 (user 4) has no match → excluded
        assertEquals(3, joined.size)
    }

    @Test
    fun hashJoinIncludesColumnsFromBothSides() {
        val users = usersCursor()
        val orders = ordersCursor()

        val joined = orders.hashJoin(users, "userId", "id")
        val row = joined.at(0) as DocRowVec

        // Should have: orderId, userId, amount, name (from users)
        assertTrue(row.keys.contains("orderId"))
        assertTrue(row.keys.contains("userId"))
        assertTrue(row.keys.contains("amount"))
        assertTrue(row.keys.contains("name"))
    }

    @Test
    fun hashJoinDuplicatesForOneToMany() {
        val users = usersCursor()
        val orders = ordersCursor()

        val joined = orders.hashJoin(users, "userId", "id")

        // User 1 has 2 orders → 2 rows with name="alice"
        val aliceRows = (0 until joined.size).filter { i ->
            val row = joined.at(i) as DocRowVec
            row["name"] == "alice"
        }
        assertEquals(2, aliceRows.size)
    }

    // ── No matches ───────────────────────────────────────────────────────

    @Test
    fun hashJoinNoMatchesReturnsEmpty() {
        val left = listOf(
            DocRowVec(listOf("k", "v"), listOf(99, "x")),
        ).let { list -> list.size j { i: Int -> list[i] as MiniRowVec } }

        val right = listOf(
            DocRowVec(listOf("k", "v"), listOf(1, "a")),
        ).let { list -> list.size j { i: Int -> list[i] as MiniRowVec } }

        val joined = left.hashJoin(right, "k", "k")
        assertEquals(0, joined.size)
    }

    // ── Empty cursors ────────────────────────────────────────────────────

    @Test
    fun hashJoinEmptyLeftReturnsEmpty() {
        val left = emptyMiniCursor()
        val right = usersCursor()
        val joined = left.hashJoin(right, "id", "id")
        assertEquals(0, joined.size)
    }

    @Test
    fun hashJoinEmptyRightReturnsEmpty() {
        val left = usersCursor()
        val right = emptyMiniCursor()
        val joined = left.hashJoin(right, "id", "id")
        assertEquals(0, joined.size)
    }

    // ── Many-to-many ─────────────────────────────────────────────────────

    @Test
    fun hashJoinManyToMany() {
        val left = listOf(
            DocRowVec(listOf("k", "a"), listOf(1, "x")),
            DocRowVec(listOf("k", "a"), listOf(1, "y")),
        ).let { list -> list.size j { i: Int -> list[i] as MiniRowVec } }

        val right = listOf(
            DocRowVec(listOf("k", "b"), listOf(1, "p")),
            DocRowVec(listOf("k", "b"), listOf(1, "q")),
        ).let { list -> list.size j { i: Int -> list[i] as MiniRowVec } }

        val joined = left.hashJoin(right, "k", "k")
        // 2×2 = 4 rows
        assertEquals(4, joined.size)
    }

    // ── Right key column omitted from output ─────────────────────────────

    @Test
    fun hashJoinOmitsRightJoinColumn() {
        val left = listOf(
            DocRowVec(listOf("uid", "val"), listOf(1, "x")),
        ).let { list -> list.size j { i: Int -> list[i] as MiniRowVec } }

        val right = listOf(
            DocRowVec(listOf("id", "extra"), listOf(1, "y")),
        ).let { list -> list.size j { i: Int -> list[i] as MiniRowVec } }

        val joined = left.hashJoin(right, "uid", "id")
        val row = joined.at(0) as DocRowVec

        // "id" from right should NOT appear (it's the join key, already present as "uid")
        // "extra" from right SHOULD appear
        assertTrue(row.keys.contains("uid"))
        assertTrue(row.keys.contains("val"))
        assertTrue(row.keys.contains("extra"))
        assertFalse(row.keys.contains("id"))
    }

    // ── Null join keys don't match ────────────────────────────────────────

    @Test
    fun hashJoinNullKeysDontMatch() {
        val left = listOf(
            DocRowVec(listOf("k", "a"), listOf(null, "x")),
        ).let { list -> list.size j { i: Int -> list[i] as MiniRowVec } }

        val right = listOf(
            DocRowVec(listOf("k", "b"), listOf(null, "y")),
        ).let { list -> list.size j { i: Int -> list[i] as MiniRowVec } }

        val joined = left.hashJoin(right, "k", "k")
        // null != null in join semantics
        assertEquals(0, joined.size)
    }

    // ── Cursor infix join syntax ──────────────────────────────────────────

    @Test
    fun infixJoinSyntax() {
        val users = usersCursor()
        val orders = ordersCursor()

        // Use infix syntax: orders ⋈ users on "userId" = "id"
        val joined = orders.join(users, "userId", "id")
        assertEquals(3, joined.size)
    }
}
