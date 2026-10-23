package borg.trikeshed.parse.kursive.sql

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.query.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import kotlin.test.*

class SqlPlanTest {
    val a = """[
        {"k": 1, "x": "red",   "doc": {"t": "q"}},
        {"k": 2, "x": "green", "doc": {"t": "q"}},
        {"k": 3, "x": "red",   "doc": {"t": "z"}},
        {"k": 4, "x": "blue",  "doc": {"t": "q"}}
    ]"""
    val b = """[
        {"k": 1, "y": 5},
        {"k": 1, "y": 7},
        {"k": 2, "y": 4},
        {"k": 3, "y": 9},
        {"k": 4, "y": 2},
        {"k": 4, "y": 10}
    ]"""
    val tables = mapOf("a" to a, "b" to b)
    fun resolve(name: String): Cursor = confixDoc(tables.getValue(name)).index.facet(ConfixIndexK.Columns)

    fun rows(cursor: Cursor): List<List<Any?>> = (0 until cursor.size).map { r -> cursor[r].values.let { v -> (0 until v.size).map { v[it] } } }

    @Test
    fun joinFilterPathGroupOrderLimit() {
        val stmt = assertNotNull(SqlParser.parse(
            "SELECT a.x, SUM(b.y) FROM a JOIN b ON a.k = b.k WHERE b.y > 3 AND a.doc->>'t' = 'q' " +
                "GROUP BY a.x ORDER BY 2 DESC LIMIT 5"
        ))
        val result = stmt.plan(::resolve)
        assertEquals(listOf("x", "sum"), result.columnNames.view.map { it.toString() })
        // red: k=1 (5,7) → 12; k=3 filtered by t='z'. green: k=2 → 4. blue: k=4 → 10 (2 filtered by y>3).
        assertEquals(listOf(listOf("red", 12L), listOf("blue", 10L), listOf("green", 4L)), rows(result))
    }

    @Test
    fun subselectsCommaFromQuantifiersAndUnion() {
        // an uncorrelated subselect is planned once; a correlated one is bound to each outer row
        assertEquals(listOf(listOf(1L, 10L), listOf(2L, 10L), listOf(3L, 10L), listOf(4L, 10L)), rows(SqlParser.parse("SELECT k, (SELECT MAX(y) FROM b) FROM a ORDER BY k")!!.plan(::resolve)))
        val correlated = SqlParser.parse("SELECT a.k, (SELECT SUM(b.y) FROM b WHERE b.k = a.k) FROM a ORDER BY a.k")!!.plan(::resolve)
        assertEquals(listOf("k", "sum"), correlated.columnNames.view.map { it.toString() }, "a scalar subselect is named after its column, as pg names it")
        assertEquals(listOf(listOf(1L, 12L), listOf(2L, 4L), listOf(3L, 9L), listOf(4L, 12L)), rows(correlated))
        assertTrue(assertFailsWith<IllegalStateException> { rows(SqlParser.parse("SELECT (SELECT y FROM b)")!!.plan(::resolve)) }.message!!.startsWith("more than one row returned by a subquery"))
        assertTrue(assertFailsWith<IllegalArgumentException> { rows(SqlParser.parse("SELECT (SELECT k, y FROM b WHERE k = 2)")!!.plan(::resolve)) }.message!!.startsWith("subquery must return only one column"))
        val array = rows(SqlParser.parse("SELECT a.k, ARRAY(SELECT b.y FROM b WHERE b.k = a.k ORDER BY b.y) FROM a WHERE a.k = 1")!!.plan(::resolve))
        assertEquals(1, array.size)
        assertEquals(1L, array[0][0])
        assertEquals(listOf<Any?>(5L, 7L), (array[0][1] as Array<*>).toList())
        // the comma FROM and CROSS JOIN are the Cartesian product; a JOIN without ON does not parse
        assertEquals(listOf(listOf(24L)), rows(SqlParser.parse("SELECT COUNT(*) FROM a, b")!!.plan(::resolve)))
        assertEquals(listOf(listOf(3L, 9L), listOf(4L, 10L)), rows(SqlParser.parse("SELECT a.k, b.y FROM a, b WHERE a.k = b.k AND b.y > 8 ORDER BY a.k")!!.plan(::resolve)))
        assertEquals("SELECT count(*) FROM a CROSS JOIN b", SqlParser.parse("SELECT COUNT(*) FROM a, b")!!.render())
        assertNull(SqlParser.parse("SELECT a.k FROM a JOIN b".toSeries()), "a JOIN without ON is a syntax error to the strict parser")
        // ANY and ALL over a subselect read as its column
        assertEquals(listOf(listOf(3L), listOf(4L)), rows(SqlParser.parse("SELECT a.k FROM a WHERE a.k = ANY (SELECT b.k FROM b WHERE b.y > 8) ORDER BY a.k")!!.plan(::resolve)))
        assertEquals(listOf(listOf(4L)), rows(SqlParser.parse("SELECT a.k FROM a WHERE a.k >= ALL (SELECT b.k FROM b WHERE b.y < 5)")!!.plan(::resolve)))
        // UNION ALL keeps every row under the head's names, UNION is distinct, the arity is checked
        val union = SqlParser.parse("SELECT k FROM a UNION ALL SELECT y FROM b ORDER BY 1 LIMIT 3")!!.plan(::resolve)
        assertEquals(listOf("k"), union.columnNames.view.map { it.toString() })
        assertEquals(listOf(listOf(1L), listOf(2L), listOf(2L)), rows(union))
        assertEquals(3, rows(SqlParser.parse("SELECT x FROM a UNION SELECT x FROM a")!!.plan(::resolve)).size)
        assertTrue(assertFailsWith<IllegalArgumentException> { rows(SqlParser.parse("SELECT k FROM a UNION ALL SELECT k, y FROM b")!!.plan(::resolve)) }.message!!.startsWith("each UNION query must have the same number of columns"))
        // unnest of a pg array literal in FROM, its column named by the alias
        assertEquals(listOf(listOf("1"), listOf("2")), rows(SqlParser.parse("SELECT x FROM unnest('{1,2}') x ORDER BY 1")!!.plan(::resolve)))
    }

    @Test
    fun leftJoinKeepsUnmatchedRows() {
        val stmt = assertNotNull(SqlParser.parse("SELECT a.k, b.y FROM a LEFT JOIN b ON a.k = b.k AND b.y > 8 ORDER BY a.k"))
        assertEquals(listOf(listOf(1L, null), listOf(2L, null), listOf(3L, 9L), listOf(4L, 10L)), rows(stmt.plan(::resolve)))
    }

    @Test
    fun havingAndExpressionsOverAggregates() {
        val stmt = assertNotNull(SqlParser.parse(
            "SELECT b.k, COUNT(*) AS n, SUM(b.y) * 2 AS twice FROM b GROUP BY b.k HAVING COUNT(*) > 1 ORDER BY b.k"
        ))
        val result = stmt.plan(::resolve)
        assertEquals(listOf("k", "n", "twice"), result.columnNames.view.map { it.toString() })
        assertEquals(listOf(listOf(1L, 2L, 24L), listOf(4L, 2L, 24L)), rows(result))
    }

    @Test
    fun aggregateWithoutGroupingOverEmptyInput() {
        val stmt = assertNotNull(SqlParser.parse("SELECT COUNT(*), SUM(y) FROM b WHERE y > 100"))
        assertEquals(listOf(listOf(0L, null)), rows(stmt.plan(::resolve)))
    }

    @Test
    fun snowflakeColonPathAndLike() {
        val stmt = assertNotNull(SqlParser.parse("SELECT k FROM a WHERE doc:t = 'z' OR x LIKE 'gr%' ORDER BY k DESC"))
        assertEquals(listOf(listOf(3L), listOf(2L)), rows(stmt.plan(::resolve)))
    }

    @Test
    fun offsetAndStar() {
        val stmt = assertNotNull(SqlParser.parse("SELECT * FROM b ORDER BY y LIMIT 2 OFFSET 1"))
        val result = stmt.plan(::resolve)
        assertEquals(listOf("k", "y"), result.columnNames.view.map { it.toString() })
        assertEquals(listOf(listOf(2L, 4L), listOf(1L, 5L)), rows(result))
    }

    @Test
    fun caseRegexScalarCallsAndFromLessSelect() {
        fun plan(sql: String) = assertNotNull(SqlParser.parse(sql.toSeries()), sql).plan(::resolve)
        // y ascending: 2, 4, 5, 7, 9, 10
        assertEquals(listOf(listOf("tiny"), listOf("tiny"), listOf("small")),
            rows(plan("SELECT CASE WHEN y < 5 THEN 'tiny' WHEN y < 9 THEN 'small' ELSE 'big' END AS size FROM b ORDER BY y LIMIT 3")))
        // the arms are the parsed literals, so small integers stay Int as every literal does
        assertEquals(listOf(listOf(1), listOf(null), listOf(1), listOf(2)), rows(plan("SELECT CASE x WHEN 'red' THEN 1 WHEN 'blue' THEN 2 END FROM a ORDER BY k")))
        assertEquals(listOf("case"), plan("SELECT CASE x WHEN 'red' THEN 1 END FROM a").columnNames.view.map { it.toString() })
        assertEquals(listOf(listOf(1L), listOf(3L)), rows(plan("SELECT k FROM a WHERE x ~ '^r' AND x !~* 'GREEN' ORDER BY k")))
        assertEquals(listOf(listOf(2L), listOf(4L)), rows(plan("SELECT k FROM a WHERE x !~ 'd\$' ORDER BY k")))
        val scalars = plan("SELECT version(), current_schema(), session_user, 1 + 1, upper('a') AS u")
        assertEquals(listOf("version", "current_schema", "session_user", "?column?", "u"), scalars.columnNames.view.map { it.toString() })
        assertEquals(listOf(listOf("PostgreSQL 14.0 (TrikeShed)", "public", "trikeshed", 2L, "A")), rows(scalars))
        assertEquals(listOf(listOf("it's", "RED", 3L)), rows(plan("SELECT 'it''s', upper(x), length(x) FROM a WHERE k = 1")))
        assertEquals(listOf(listOf(4L)), rows(plan("SELECT COUNT(*) FROM a WHERE pg_catalog.pg_table_is_visible(k) AND coalesce(NULL, k) IS NOT NULL")))
        assertEquals(listOf(listOf("bigint", "trikeshed")), rows(plan("SELECT format_type(20, -1), pg_get_userbyid(10)")))
        // the plan is lazy: an unknown function is refused when the rows are read
        assertTrue(assertFailsWith<IllegalStateException> { rows(plan("SELECT nope(1) FROM a")) }.message!!.startsWith("function nope(...) does not exist"))
        assertTrue(assertFailsWith<IllegalStateException> { rows(plan("SELECT upper(x, x) FROM a")) }.message!!.contains("2 arguments"))
        // CASE reads no arm past the first match: the division never runs
        assertEquals(listOf(listOf(0)), rows(plan("SELECT CASE WHEN k = 1 THEN 0 ELSE 10 / (k - 1) END FROM a WHERE k = 1")))
        assertEquals(listOf(listOf(2)), rows(plan("SELECT CASE NULL WHEN TRUE THEN 1 ELSE 2 END")), "a NULL operand is compared, never truth-tested")
        // scalar functions over aggregates; the other operators bind tighter than comparisons; a NULL pattern is NULL; psql's OPERATOR() spelling
        assertEquals(listOf(listOf(37L, "some")), rows(plan("SELECT coalesce(sum(y), 0), CASE WHEN count(*) > 0 THEN 'some' ELSE 'none' END FROM b")))
        assertEquals(listOf(listOf(true), listOf(false), listOf(true), listOf(false)), rows(plan("SELECT x ~ '^r' = TRUE FROM a ORDER BY k")))
        assertEquals(listOf(listOf(null)), rows(plan("SELECT x ~ NULL FROM a WHERE k = 1")))
        assertEquals(listOf(listOf(1L), listOf(3L)), rows(plan("SELECT k FROM a WHERE x OPERATOR(pg_catalog.~) '^(red)$' COLLATE pg_catalog.default ORDER BY k")))
        assertTrue(assertFailsWith<IllegalStateException> { rows(plan("SELECT k FROM a WHERE x ~ '('")) }.message!!.startsWith("invalid regular expression"))
    }

    @Test
    fun parserRejectsTrailingInput() {
        assertNull(SqlParser(CharSeries("SELECT k FROM a ORDER BY k garbage".toSeries()).trim).parseSelect())
    }
}
