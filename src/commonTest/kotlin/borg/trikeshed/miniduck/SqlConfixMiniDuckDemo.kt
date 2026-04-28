package borg.trikeshed.miniduck

import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.exec.*
import borg.trikeshed.miniduck.plan.*
import borg.trikeshed.miniduck.schema.*
import borg.trikeshed.miniduck.sql.*
import borg.trikeshed.parse.kursive.sql.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration demo: Confix SQL parsing + MiniDuck planning + MiniDuck execution.
 *
 * Pipeline:
 *   SQL string
 *     -> SqlParser.parse()        [Confix: kursive recursive-descent + regex fallback]
 *     -> transformSelect()         [MiniDuck planner: returns PlanNode tree]
 *     -> PlanNode.open(execCtx)   [MiniDuck executor: returns Cursor<DocRowVec>]
 *     -> cursor.next() / cursor.row [DocRowVec rows flow through cursor algebra]
 *
 * Every component is real production code from libs/miniduck and
 * src/parse/kursive/sql/.  In-memory TableSource provides the backing store.
 */

class SqlConfixMiniDuckDemo {

    @Test
    fun selectWithoutFrom_emitsOneRow() {
        runBlocking {
            val sql = "SELECT 1, 'hello'"
            val stmt = SqlParser.parse(sql)
            assertNotNull(stmt, "parse failed for: $sql")
            assertEquals(2, stmt.columns.size)

            val schema = InMemorySchemaManager()
            val tableSrc = EmptyTableSource()
            val execCtx = ExecutionContext(schema, PlannerConfig(), tableSrc)

            val plan = transformSelect(stmt, PlannerContext(schema))
            val cursor = plan.open(execCtx)

            assertTrue(cursor.next())
            // Numeric literals become Double via SqlParser
            assertEquals(1.0, cursor.row.get(0))
            assertEquals("hello", cursor.row.get(1))
            assertFalse(cursor.next())
            cursor.close()
        }
    }

    @Test
    fun selectStar_allColumnsReturned() {
        runBlocking {
            val sql = "SELECT * FROM users"
            val stmt = SqlParser.parse(sql)
            assertNotNull(stmt)

            val schema = InMemorySchemaManager()
            val users = listOf(
                docOf("id" to 1, "name" to "alice", "age" to 30),
                docOf("id" to 2, "name" to "bob",   "age" to 25),
            )
            val tableSrc = InMemoryTableSource("users" to users)
            val execCtx = ExecutionContext(schema, PlannerConfig(), tableSrc)

            val plan = transformSelect(stmt, PlannerContext(schema))
            val cursor = plan.open(execCtx)

            assertTrue(cursor.next())
            assertEquals(1,  cursor.row.get("id"))
            assertEquals("alice", cursor.row.get("name"))
            assertEquals(30, cursor.row.get("age"))

            assertTrue(cursor.next())
            assertEquals(2,  cursor.row.get("id"))
            assertEquals("bob",   cursor.row.get("name"))
            assertEquals(25, cursor.row.get("age"))

            assertFalse(cursor.next())
            cursor.close()
        }
    }

    @Test
    fun selectWithWhere_filterApplied() {
        runBlocking {
            val sql = "SELECT name, age FROM users WHERE age >= 30"
            val stmt = SqlParser.parse(sql)
            assertNotNull(stmt)

            val schema = InMemorySchemaManager()
            val users = listOf(
                docOf("id" to 1, "name" to "alice", "age" to 30),
                docOf("id" to 2, "name" to "bob",   "age" to 25),
                docOf("id" to 3, "name" to "carol", "age" to 35),
            )
            val tableSrc = InMemoryTableSource("users" to users)
            val execCtx = ExecutionContext(schema, PlannerConfig(), tableSrc)

            val plan = transformSelect(stmt, PlannerContext(schema))
            val cursor = plan.open(execCtx)

            assertTrue(cursor.next())
            assertEquals("alice", cursor.row.get("name"))
            assertEquals(30, cursor.row.get("age"))

            assertTrue(cursor.next())
            assertEquals("carol", cursor.row.get("name"))
            assertEquals(35, cursor.row.get("age"))

            assertFalse(cursor.next())  // bob filtered out
            cursor.close()
        }
    }

    @Test
    fun selectWithProjection_twoColumnsProjected() {
        runBlocking {
            val sql = "SELECT id, name FROM users"
            val stmt = SqlParser.parse(sql)
            assertNotNull(stmt)

            val schema = InMemorySchemaManager()
            val users = listOf(
                docOf("id" to 1, "name" to "alice", "age" to 30),
            )
            val tableSrc = InMemoryTableSource("users" to users)
            val execCtx = ExecutionContext(schema, PlannerConfig(), tableSrc)

            val plan = transformSelect(stmt, PlannerContext(schema))
            val cursor = plan.open(execCtx)

            assertTrue(cursor.next())
            assertEquals(1,      cursor.row.get(0))
            assertEquals("alice", cursor.row.get(1))
            assertFalse(cursor.next())
            cursor.close()
        }
    }

    @Test
    fun predicateDSL_andOrNot_combinators() {
        val p: Predicate = col("name") eq "alice" and (col("age") ge 30)

        val alice = docOf("name" to "alice", "age" to 30)
        val bob   = docOf("name" to "bob",   "age" to 30)
        val carol = docOf("name" to "carol", "age" to 25)

        assertTrue(p.matches(alice))
        assertFalse(p.matches(bob))   // bob's name != alice
        assertFalse(p.matches(carol)) // carol's age < 30
    }

    @Test
    fun predicateDSL_betweenAndInList() {
        val ageBetween = col("age") between (25 to 35)
        val bob   = docOf("name" to "bob",   "age" to 25)
        val carol = docOf("name" to "carol", "age" to 35)

        assertTrue(ageBetween.matches(bob))
        assertTrue(ageBetween.matches(carol))
        assertFalse(ageBetween.matches(docOf("name" to "dan", "age" to 40)))

        val nameIn = col("name") inList listOf("alice", "carol")
        assertTrue(nameIn.matches(docOf("name" to "alice")))
        assertTrue(nameIn.matches(docOf("name" to "carol")))
        assertFalse(nameIn.matches(docOf("name" to "bob")))
    }

    @Test
    fun docRowVec_cellAndChildAccess() {
        val doc = DocRowVec(
            keys = listOf("id", "name", "city"),
            cells = listOf(1, "alice", "nyc"),
        )
        // Scalar access via index
        assertEquals(1,     doc[0])
        assertEquals("alice", doc[1])
        assertEquals("nyc",  doc[2])
        // Scalar access via key
        assertEquals(1,     doc["id"])
        assertEquals("alice", doc["name"])
        // Missing key
        assertNull(doc["unknown"])

        // DocRowVec has no children by default
        assertNull(doc.child)
    }

    @Test
    fun docRowVec_keyValueAccess() {
        runBlocking {
            val doc = DocRowVec(
                keys = listOf("city", "zip"),
                cells = listOf("nyc", "10001"),
            )
            // Scalar access via index
            assertEquals("nyc", doc[0])
            assertEquals("10001", doc[1])
            // Scalar access via key
            assertEquals("nyc", doc["city"])
            assertEquals("10001", doc["zip"])
        }
    }

    @Test
    fun sqlParser_fallbackRegex_coversCommonCases() {
        val stmt1 = SqlParser.parse("SELECT id, name FROM users WHERE age > 30")
        assertNotNull(stmt1)
        assertEquals(2, stmt1.columns.size)
        assertEquals("users", stmt1.from?.name?.s)

        val stmt2 = SqlParser.parse("SELECT * FROM orders WHERE status = 'pending'")
        assertNotNull(stmt2)
        assertEquals(1, stmt2.columns.size)  // SELECT *
        assertEquals("orders", stmt2.from?.name?.s)
    }

    @Test
    fun sqlParser_rejectsInsertAndComplexDml() {
        // No SELECT keyword -> null
        assertNull(SqlParser.parse("INSERT INTO users VALUES (1)"))
        // Valid SELECT without FROM (column ref only)
        assertNotNull(SqlParser.parse("SELECT id"))
    }

    @Test
    fun compiledPredicate_equalsFilter() {
        runBlocking {
            val sql = "SELECT * FROM users WHERE name = 'alice'"
            val stmt = SqlParser.parse(sql)!!
            val schema = InMemorySchemaManager()
            val users = listOf(
                docOf("name" to "alice"),
                docOf("name" to "bob"),
            )
            val tableSrc = InMemoryTableSource("users" to users)
            val execCtx = ExecutionContext(schema, PlannerConfig(), tableSrc)

            val plan = transformSelect(stmt, PlannerContext(schema))
            val cursor = plan.open(execCtx)

            assertTrue(cursor.next())
            assertEquals("alice", cursor.row.get("name"))
            assertFalse(cursor.next())  // bob filtered out
            cursor.close()
        }
    }

    @Test
    fun compiledPredicate_orCondition() {
        runBlocking {
            val sql = "SELECT * FROM users WHERE age < 30 OR age >= 60"
            val stmt = SqlParser.parse(sql)!!
            val schema = InMemorySchemaManager()
            val users = listOf(
                docOf("name" to "alice", "age" to 30),  // excluded by both
                docOf("name" to "bob",   "age" to 25),  // age < 30, included
                docOf("name" to "carol", "age" to 60),  // age >= 60, included
                docOf("name" to "dan",   "age" to 59),  // excluded
            )
            val tableSrc = InMemoryTableSource("users" to users)
            val execCtx = ExecutionContext(schema, PlannerConfig(), tableSrc)

            val plan = transformSelect(stmt, PlannerContext(schema))
            val cursor = plan.open(execCtx)

            assertTrue(cursor.next())
            assertEquals("bob", cursor.row.get("name"))
            assertTrue(cursor.next())
            assertEquals("carol", cursor.row.get("name"))
            assertFalse(cursor.next())
            cursor.close()
        }
    }
}

/* =============================================================================
 * Test helpers -- in-memory implementations of MiniDuck executor interfaces
 * ============================================================================= */

/** A DocRowVec from a varargs key/value list. */
fun docOf(vararg pairs: Pair<String, Any?>): DocRowVec {
    val keys = pairs.map { it.first }
    val cells = pairs.map { it.second }
    return DocRowVec(keys, cells)
}

/** TableSource that serves a map of table names -> row lists. */
class InMemoryTableSource(private val data: Map<String, List<DocRowVec>>) : TableSource {
    constructor(vararg tables: Pair<String, List<DocRowVec>>) : this(tables.toMap())

    override fun open(execCtx: ExecutionContext, tableName: String): Cursor {
        val rows = data[tableName] ?: emptyList()
        return InMemoryCursor(rows)
    }
}

/** TableSource that returns an empty cursor (used for SELECT without FROM). */
class EmptyTableSource : TableSource {
    override fun open(execCtx: ExecutionContext, tableName: String): Cursor = EmptyCursor
}

/** Cursor over an in-memory list of DocRowVec. */
class InMemoryCursor(private val rows: List<DocRowVec>) : Cursor {
    private var pos = 0
    private var current: DocRowVec? = null

    override fun next(): Boolean {
        current = if (pos < rows.size) rows[pos++] else null
        return current != null
    }

    override val row: RowAccessor
        get() = object : RowAccessor {
            private val r = current!!

            override fun get(index: Int): Any? = r[index]

            override fun get(name: String): Any? {
                val idx = r.keys.indexOf(name)
                return if (idx >= 0) r[idx] else null
            }
        }

    override fun close() {}
}

/** Empty cursor that returns false immediately. */
object EmptyCursor : Cursor {
    override fun next(): Boolean = false
    override val row: RowAccessor = object : RowAccessor {
        override fun get(index: Int): Any? = null
        override fun get(name: String): Any? = null
    }
    override fun close() {}
}
