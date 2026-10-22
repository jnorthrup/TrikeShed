package borg.trikeshed.cursor.query.jit

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.query.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.parse.kursive.sql.*
import kotlin.test.*

class ClassfileExprCompilerTest {
    val json = """[
        {"k": 1, "x": "red",   "n": 10,   "f": 1.5,  "doc": {"t": "q", "a": [1, 2]}},
        {"k": 2, "x": "green", "n": null, "f": 2.5,  "doc": {"t": "z"}},
        {"k": 3, "x": "grey",  "n": 0,    "f": null, "doc": {"t": "q", "a": [3]}},
        {"k": 4, "x": null,    "n": -7,   "f": 0.25, "doc": null}
    ]"""
    val table: Cursor = confixDoc(json).index.facet(ConfixIndexK.Columns)
    val names: Series<CharSequence> = table.columnNames

    fun parse(sql: String): Expr = SqlParser.parse("SELECT $sql FROM t")!!.columns[0].expr

    /** Evaluate [sql] over every row with both compilers. */
    fun both(sql: String, jit: ClassfileExprCompiler): Pair<List<Any?>, List<Any?>> {
        val e = parse(sql)
        val closure = ClosureExprCompiler.compile(e, names)
        val generated = jit.compile(e, names)
        return (0 until table.size).map { norm { closure(table[it]) } } to (0 until table.size).map { norm { generated(table[it]) } }
    }

    /** A thrown error compares by class, and a Confix cell (identity-compared) by its JSON text. */
    fun norm(evaluate: () -> Any?): Any? = runCatching(evaluate).fold(
        { v -> if (v != null && v !is String && confixCellOf(v) != null) scalarText(v) else v },
        { t -> t::class },
    )

    @Test fun generatedCodeEqualsTheClosureCompiler() {
        val jit = ClassfileExprCompiler()
        for (sql in listOf(
            "k + 1", "k * 2.5", "k / 2", "n % 4", "f - 1", "n + f",
            "k = 2", "k != 2", "k < 3", "k <= 3", "k > 1", "k >= 1", "x = 'red'", "n < 5", "f > 0.3",
            "k > 1 AND n < 5", "k > 3 OR x = 'red'", "n > 5 AND f > 1 OR k = 3",
            "x LIKE 'gr%'", "x LIKE '_re_'", "x LIKE x",
            "x || '-' || k",
            "doc->>'t'", "doc->'a'", "doc:t", "doc->'a'->>0", "doc#>>'{a,1}'",
            "doc->>'t' = 'q' AND k > 0",
            "doc->'a' <-> '[1,2]'", "'[1,2]'::vector <=> '[2,3]'::vector", "'[1,2]'::vector <#> '[2,3]'",
            "k::double / 2", "k::text || 'a'", "x::text", "n::int + 1", "f::int", "k::bool",
        )) {
            val (expected, actual) = both(sql, jit)
            assertEquals(expected, actual, sql)
        }
    }

    @Test fun andShortCircuitsPastAFaultingRightSide() {
        val jit = ClassfileExprCompiler()
        // k / (k - k) divides Longs by zero; the left side is false for rows 1-2, so only rows 3-4 may throw... as the closure does.
        val (expected, actual) = both("k > 2 AND k / (k - k) = 1", jit)
        assertEquals(expected, actual)
        assertEquals(false, actual[0])
        assertEquals(ArithmeticException::class, actual[2])
    }

    @Test fun aRepeatedStatementReusesItsClassAndBindsItToTheCas() {
        val jit = ClassfileExprCompiler()
        val e = parse("k + 1 > 2 AND x LIKE 'g%'")
        val first = jit.compile(e, names)
        val second = jit.compile(e, names)
        assertEquals(1, jit.emitted)
        assertEquals(1, jit.reused)
        assertEquals(
            (0 until table.size).map { first(table[it]) },
            (0 until table.size).map { second(table[it]) },
        )
        val bytes = assertNotNull(jit.classBytes(e, names))
        assertTrue(jit.cas.holds(ContentId.of(bytes)), "the class is addressed by its own bytes")
        assertEquals(0xCAFEBABE.toInt(), java.nio.ByteBuffer.wrap(bytes).int, "a real class file")
        // The same text over a different column layout is a different statement.
        val other = jit.compile(e, table.select("x", "k").columnNames)
        assertEquals(2, jit.emitted)
        assertNotNull(other)
    }

    @Test fun uncoveredShapesFallBackToTheClosureCompiler() {
        val jit = ClassfileExprCompiler()
        assertFailsWith<IllegalStateException> { jit.compile(Call("sum", s_[parse("k")]), names) }
        assertEquals(0, jit.emitted)
    }

    @Test fun theExecutorRunsWholeStatementsOnGeneratedCode() {
        val jit = ClassfileExprCompiler()
        val sql = "SELECT k, x FROM t WHERE doc->>'t' = 'q' AND k + 1 > 1 ORDER BY k DESC"
        val fast = SqlParser.parse(sql)!!.plan({ table }, compiler = jit).result
        val slow = SqlParser.parse(sql)!!.plan({ table }).result
        val rows = { c: Cursor -> (0 until c.size).map { r -> c[r].values.let { v -> (0 until v.size).map { v[it] } } } }
        assertEquals(listOf(listOf(3L, "grey"), listOf(1L, "red")), rows(fast))
        assertEquals(rows(slow), rows(fast))
        assertTrue(jit.emitted >= 1)
    }
}
