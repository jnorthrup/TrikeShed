package borg.trikeshed.couch

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD tests for functional MiniSqlDsl using Series/Join composition.
 *
 * Tests cover:
 * - Filter with composable predicates (eq, gt, lt, like, and, or, not, in)
 * - Sort with ascending/descending
 * - Take/drop for pagination
 * - Select columns
 * - Aggregations (sum, avg, min, max, count)
 * - Joins (inner, left, right, full)
 * - GroupBy and aggregations
 */
class MiniSqlDslTest {

    @Test
    fun testFilterEq() = runTest {
        val dsl = createTestDsl()

        val results = dsl.query("users")
            .filter(eq("name", "Alice"))

        assertEquals(1, results.size)
        val accessor = results[0].toRowAccessor()
        assertEquals("Alice", accessor["name"])
    }

    @Test
    fun testFilterGt() = runTest {
        val dsl = createTestDsl()

        val results = dsl.query("users")
            .filter(gt("age", 30))

        assertEquals(1, results.size)
        val accessor = results[0].toRowAccessor()
        assertEquals("Charlie", accessor["name"])
    }

    @Test
    fun testFilterLt() = runTest {
        val dsl = createTestDsl()

        val results = dsl.query("users")
            .filter(lt("age", 30))

        assertEquals(1, results.size)
        val accessor = results[0].toRowAccessor()
        assertEquals("Bob", accessor["name"])
    }

    @Test
    fun testFilterLike() = runTest {
        val dsl = createTestDsl()

        val results = dsl.query("users")
            .filter(like("name", "A%"))

        assertEquals(1, results.size)
        val accessor = results[0].toRowAccessor()
        assertEquals("Alice", accessor["name"])
    }

    @Test
    fun testFilterIn() = runTest {
        val dsl = createTestDsl()

        val results = dsl.query("users")
            .filter(`in`("name", "Alice", "Charlie"))

        assertEquals(2, results.size)
    }

    @Test
    fun testFilterAnd() = runTest {
        val dsl = createTestDsl()

        val results = dsl.query("users")
            .filter(gt("age", 20) and lt("age", 35))

        assertEquals(1, results.size)
        val accessor = results[0].toRowAccessor()
        assertEquals("Bob", accessor["name"])
    }

    @Test
    fun testFilterOr() = runTest {
        val dsl = createTestDsl()

        val results = dsl.query("users")
            .filter(eq("name", "Alice") or eq("name", "Bob"))

        assertEquals(2, results.size)
    }

    @Test
    fun testFilterNot() = runTest {
        val dsl = createTestDsl()

        val results = dsl.query("users")
            .filter(!eq("name", "Alice"))

        assertEquals(2, results.size)
        val names = results.map { it.toRowAccessor()["name"] }.toSet()
        assertTrue(names.contains("Bob"))
        assertTrue(names.contains("Charlie"))
    }

    @Test
    fun testSortAscending() = runTest {
        val dsl = createTestDsl()

        val results = dsl.query("users")
            .sortByAsc { it["age"] as Int }

        assertEquals(3, results.size)
        assertEquals("Bob", results[0].toRowAccessor()["name"])
        assertEquals("Alice", results[1].toRowAccessor()["name"])
        assertEquals("Charlie", results[2].toRowAccessor()["name"])
    }

    @Test
    fun testSortDescending() = runTest {
        val dsl = createTestDsl()

        val results = dsl.query("users")
            .sortByDesc { it["age"] as Int }

        assertEquals(3, results.size)
        assertEquals("Charlie", results[0].toRowAccessor()["name"])
        assertEquals("Alice", results[1].toRowAccessor()["name"])
        assertEquals("Bob", results[2].toRowAccessor()["name"])
    }

    @Test
    fun testSortByMultiColumn() = runTest {
        val dsl = createTestDsl()

        val results = dsl.query("users")
            .sortBy(
                SortSpec({ it["age"] as Int }, SortDirection.ASC),
                SortSpec({ it["name"] as String }, SortDirection.DESC)
            )

        assertEquals(3, results.size)
        assertEquals("Bob", results[0].toRowAccessor()["name"])
        assertEquals("Alice", results[1].toRowAccessor()["name"])
        assertEquals("Charlie", results[2].toRowAccessor()["name"])
    }

    @Test
    fun testTake() = runTest {
        val dsl = createTestDsl()

        val results = dsl.query("users")
            .sortByAsc { it["age"] as Int }
            .take(2)

        assertEquals(2, results.size)
        assertEquals("Bob", results[0].toRowAccessor()["name"])
        assertEquals("Alice", results[1].toRowAccessor()["name"])
    }

    @Test
    fun testDrop() = runTest {
        val dsl = createTestDsl()

        val results = dsl.query("users")
            .sortByAsc { it["age"] as Int }
            .drop(1)
            .take(1)

        assertEquals(1, results.size)
        assertEquals("Alice", results[0].toRowAccessor()["name"])
    }

    @Test
    fun testSelectColumnsByName() = runTest {
        val dsl = createTestDsl()

        val results = dsl.query("users")
            .select("id", "name")

        assertEquals(3, results.size)
        assertEquals(2, results[0].size)  // Only 2 columns
        
        val accessor = results[0].toRowAccessor()
        assertTrue(accessor.containsKey("id"))
        assertTrue(accessor.containsKey("name"))
        assertTrue(!accessor.containsKey("age"))
    }

    @Test
    fun testSelectColumnsByIndex() = runTest {
        val dsl = createTestDsl()

        val results = dsl.query("users")
            .select(0, 1)  // id and name only

        assertEquals(3, results.size)
        assertEquals(2, results[0].size)  // Only 2 columns
    }

    @Test
    fun testComposedQuery() = runTest {
        val dsl = createTestDsl()

        // Complex query: users over 25, sorted by age, take first 2, select id and name
        val results = dsl.query("users")
            .filter(ge("age", 25))
            .sortByAsc { it["age"] as Int }
            .take(2)
            .select("id", "name")

        assertEquals(2, results.size)
        val accessor0 = results[0].toRowAccessor()
        val accessor1 = results[1].toRowAccessor()
        assertEquals("Bob", accessor0["name"])   // age 25
        assertEquals("Alice", accessor1["name"])  // age 30
    }

    @Test
    fun testAggregateSum() = runTest {
        val dsl = createTestDsl()

        val sum = dsl.query("users")
            .aggregate(Aggregation.Sum { it["age"] as Double })

        assertNotNull(sum)
        assertEquals(90.0, sum, 0.01)  // 30 + 25 + 35 = 90
    }

    @Test
    fun testAggregateAvg() = runTest {
        val dsl = createTestDsl()

        val avg = dsl.query("users")
            .aggregate(Aggregation.Avg { it["age"] as Double })

        assertNotNull(avg)
        assertEquals(30.0, avg, 0.01)  // 90 / 3 = 30
    }

    @Test
    fun testAggregateMin() = runTest {
        val dsl = createTestDsl()

        val min = dsl.query("users")
            .aggregate(Aggregation.Min { it["age"] as Double })

        assertNotNull(min)
        assertEquals(25.0, min, 0.01)
    }

    @Test
    fun testAggregateMax() = runTest {
        val dsl = createTestDsl()

        val max = dsl.query("users")
            .aggregate(Aggregation.Max { it["age"] as Double })

        assertNotNull(max)
        assertEquals(35.0, max, 0.01)
    }

    @Test
    fun testAggregateCount() = runTest {
        val dsl = createTestDsl()

        val count = dsl.query("users")
            .aggregate(Aggregation.Count())

        assertNotNull(count)
        assertEquals(3.0, count, 0.01)
    }

    @Test
    fun testCountAll() = runTest {
        val dsl = createTestDsl()

        val count = dsl.query("users")
            .count()

        assertEquals(3, count)
    }

    @Test
    fun testCountWithPredicate() = runTest {
        val dsl = createTestDsl()

        val count = dsl.query("users")
            .count { it["age"] as Int > 25 }

        assertEquals(2, count)  // Alice(30) and Charlie(35)
    }

    @Test
    fun testQueryDslBuilder() = runTest {
        val dsl = createTestDsl()

        val result = dsl.queryDsl("users") { cursor ->
            cursor
                .filter { it["age"] as Int > 25 }
                .sortByDesc { it["age"] as Int }
                .take(1)
                .select("id", "name")
        }

        assertEquals(1, result.size)
        val accessor = result[0].toRowAccessor()
        assertEquals("Charlie", accessor["name"])
    }

    @Test
    fun testPredicateComposition() {
        // Test that predicates compose correctly
        val p1 = eq("name", "Alice")
        val p2 = gt("age", 30)

        val andPred = p1 and p2
        val orPred = p1 or p2
        val notPred = !p1

        val row1 = mapOf("name" to "Alice", "age" to 35)
        val row2 = mapOf("name" to "Bob", "age" to 30)

        assertTrue(andPred(row1))
        assertTrue(!andPred(row2))
        assertTrue(orPred(row1))
        assertTrue(!orPred(row2))
        assertTrue(notPred(row2))
        assertTrue(!notPred(row1))
    }

    @Test
    fun testInnerJoin() = runTest {
        val dsl = createTestDsl()

        val users = dsl.query("users")
        val orders = dsl.query("orders")

        // Join users with orders on user_id
        val results = users.innerJoin(orders) { user, order ->
            user["id"] == order["user_id"]
        }

        // Alice (id=1) has 2 orders, Bob (id=2) has 1 order
        assertEquals(3, results.size)
        
        // Check that we have the right data
        val row0 = results[0].toRowAccessor()
        assertTrue(row0.containsKey("id"))
        assertTrue(row0.containsKey("order_id"))
    }

    @Test
    fun testLeftJoin() = runTest {
        val dsl = createTestDsl()

        val users = dsl.query("users")
        val orders = dsl.query("orders")

        // Left join: all users, even those without orders
        val results = users.leftJoin(orders) { user, order ->
            user["id"] == order["user_id"]
        }

        // All 3 users should be present
        // Alice (2 orders) + Bob (1 order) + Charlie (0 orders)
        assertEquals(4, results.size)  // 2 + 1 + 1 (Charlie with null order)
    }

    @Test
    fun testRightJoin() = runTest {
        val dsl = createTestDsl()

        val users = dsl.query("users")
        val orders = dsl.query("orders")

        // Right join: all orders, even those without users
        val results = users.rightJoin(orders) { user, order ->
            user["id"] == order["user_id"]
        }

        // All 3 orders should be present
        assertEquals(3, results.size)
    }

    @Test
    fun testFullJoin() = runTest {
        val dsl = createTestDsl()

        val users = dsl.query("users")
        val orders = dsl.query("orders")

        // Full join: all users and all orders
        val results = users.fullJoin(orders) { user, order ->
            user["id"] == order["user_id"]
        }

        // 3 matching pairs + Charlie (no orders) = 4
        assertEquals(4, results.size)
    }

    @Test
    fun testGroupBy() = runTest {
        val dsl = createTestDsl()

        val users = dsl.query("users")
        val orders = dsl.query("orders")

        // Join and group by user
        val joined = users.innerJoin(orders) { user, order ->
            user["id"] == order["user_id"]
        }

        val grouped = joined.groupBy { it["name"] as String }

        // Should have 2 groups (Alice and Bob)
        assertEquals(2, grouped.size)
        
        val (name1, cursor1) = grouped[0]
        val (name2, cursor2) = grouped[1]
        
        assertTrue(name1 == "Alice" || name2 == "Alice")
        assertTrue(name1 == "Bob" || name2 == "Bob")
    }

    @Test
    fun testGroupByWithAggregation() = runTest {
        val dsl = createTestDsl()

        val users = dsl.query("users")
        val orders = dsl.query("orders")

        // Join and group by user
        val joined = users.innerJoin(orders) { user, order ->
            user["id"] == order["user_id"]
        }

        val grouped = joined.groupBy { it["name"] as String }
        
        // Count orders per user
        val counts = grouped.aggregateGroups { cursor ->
            cursor.count().toDouble()
        }

        assertEquals(2, counts.size)
        
        // Convert to map for easier verification
        val countMap = mutableMapOf<String, Double>()
        for (i in 0 until counts.size) {
            val (name, count) = counts[i]
            countMap[name] = count
        }
        
        assertEquals(2.0, countMap["Alice"])  // Alice has 2 orders
        assertEquals(1.0, countMap["Bob"])    // Bob has 1 order
    }

    @Test
    fun testComplexJoinQuery() = runTest {
        val dsl = createTestDsl()

        val users = dsl.query("users")
        val orders = dsl.query("orders")

        // Complex query: users with orders > 50, sorted by total
        val results = users
            .innerJoin(orders) { user, order ->
                user["id"] == order["user_id"]
            }
            .filter { it["total"] as Double > 50.0 }
            .sortByDesc { it["total"] as Double }

        assertEquals(2, results.size)  // Only Alice's orders are > 50
        
        val accessor0 = results[0].toRowAccessor()
        val accessor1 = results[1].toRowAccessor()
        
        // Check ordering (highest total first)
        assertTrue((accessor0["total"] as Double) >= (accessor1["total"] as Double))
    }

    /**
     * Factory method to create a test DSL implementation.
     */
    private fun createTestDsl(): MiniSqlDsl {
        return object : MiniSqlDsl {
            private val tables = mutableMapOf<String, List<Map<String, Any?>>>()

            init {
                // Initialize test data for users table
                tables["users"] = listOf(
                    mapOf("id" to 1, "name" to "Alice", "age" to 30),
                    mapOf("id" to 2, "name" to "Bob", "age" to 25),
                    mapOf("id" to 3, "name" to "Charlie", "age" to 35)
                )

                // Initialize test data for orders table
                tables["orders"] = listOf(
                    mapOf("order_id" to 101, "user_id" to 1, "total" to 75.50),
                    mapOf("order_id" to 102, "user_id" to 1, "total" to 99.99),
                    mapOf("order_id" to 103, "user_id" to 2, "total" to 45.00)
                )
            }

            override suspend fun query(table: String): borg.trikeshed.cursor.Cursor {
                val rows = tables[table] ?: return borg.trikeshed.lib.emptySeries()

                // Convert List<Map<String, Any?>> to Cursor (Series<RowVec>)
                // Create a mock cursor with column metadata
                return rows.size j { rowIdx ->
                    val row = rows[rowIdx]
                    val keys = row.keys.toList()
                    keys.size j { colIdx ->
                        val value = row[keys[colIdx]]
                        // Create a simple metadata provider
                        value j { 
                            object : borg.trikeshed.isam.meta.ColumnMeta {
                                override val name = keys[colIdx]
                                override val type = borg.trikeshed.isam.meta.IOMemento.IoString
                            }
                        }
                    }
                }
            }
        }
    }
}
