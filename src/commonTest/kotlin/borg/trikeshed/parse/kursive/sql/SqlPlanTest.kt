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
