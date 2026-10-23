package borg.trikeshed.lcnc

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.query.TpchLineitem
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.parse.kursive.sql.*
import kotlin.test.*

/** The sql.* vocabulary and its codec: lift then lower is the identity on every statement the strict parser reads. */
class SqlGraphTest {
    val corpus = listOf(
        "SELECT a FROM t",
        "SELECT * FROM t",
        "SELECT a, b AS bee FROM t WHERE a > 1 AND b <= 2.5 OR c = 'x'",
        "SELECT a, '*' FROM t",
        "SELECT g, SUM(v) AS total, COUNT(*) FROM t GROUP BY g HAVING SUM(v) > 10 ORDER BY total DESC, g LIMIT 5 OFFSET 2",
        "SELECT a.x, SUM(b.y) FROM a JOIN b ON a.k = b.k WHERE b.y > 3 AND a.doc->>'t' = 'q' GROUP BY a.x ORDER BY 2 DESC LIMIT 5",
        "SELECT a.x FROM a LEFT JOIN b ON a.k = b.k AND b.z > 1 JOIN c ON a.k = c.k",
        "SELECT k + 1, k * 2.5, k / 2, n % 4 FROM t",
        "SELECT x || '-' || k FROM t",
        "SELECT doc->'a'->>0, doc#>>'{a,1}', doc:t FROM t",
        "SELECT k::double / 2, x::text FROM t",
        "SELECT id FROM items ORDER BY embedding <=> '[1,0,0]' LIMIT 2",
        "SELECT id, embedding <-> '[1,0,0]' AS d FROM items ORDER BY d LIMIT 3",
        "SELECT '[1,2]'::vector <#> '[2,3]' FROM t",
        "SELECT x FROM t WHERE x LIKE 'gr%'",
        "SELECT a > 1 FROM t",
        "SELECT a FROM t WHERE flag",
        "SELECT doc->>'it''s' FROM t", // the SQL doubled quote, the one escape under standard_conforming_strings
        TpchLineitem.Q1,
        TpchLineitem.Q6,
    )

    fun strict(sql: String): SelectStmt = assertNotNull(SqlParser.parse(sql.toSeries()), "the strict parser reads: $sql")
    fun rows(c: Cursor): List<List<Any?>> = (0 until c.size).map { r -> c[r].values.let { v -> (0 until v.size).map { v[it] } } }
    fun nodesOf(p: LcncProgram): List<LcncNode> = (0 until p.nodes.size).map { p.nodes[it] }
    fun wiresOf(p: LcncProgram): List<LcncWire> = (0 until p.wires.size).map { p.wires[it] }
    fun rebuilt(p: LcncProgram, nodes: List<LcncNode> = nodesOf(p), wires: List<LcncWire> = wiresOf(p)) =
        p.copy(nodes = nodes.toSeries(), wires = wires.toSeries())
    fun refused(code: String, program: LcncProgram) = assertEquals(code, assertFailsWith<SqlGraphError> { SqlGraph.lower(program) }.code)

    @Test fun everyStatementRoundTripsAndTypeChecks() {
        for (sql in corpus) {
            val stmt = strict(sql)
            val program = SqlGraph.lift(stmt, "q")
            assertEquals(emptyList(), LcncTypeCheck.check(program, strict = true).map { it.render() }, sql)
            assertEquals(SqlGraph.signature(stmt), SqlGraph.signature(SqlGraph.lower(program)), sql)
        }
    }

    @Test fun parseIsLiftOfTheStrictParser() {
        val program = SqlGraph.parse("SELECT a FROM t WHERE a > 1", "p")
        assertEquals("p", program.name)
        assertTrue(program.controls.inspectionOnly, "a syntax graph is never dispatched")
        assertEquals(
            listOf(SqlGraph.ITEM_EXPR, SqlGraph.COL, SqlGraph.SELECT, SqlGraph.TABLE_NAME, SqlGraph.COL, SqlGraph.LIT, SqlGraph.CMP).toSet(),
            nodesOf(program).map { it.type }.toSet(),
        )
    }

    @Test fun theLoweredStatementPlansLikeTheOriginal() {
        val data = TpchLineitem.generate(2_000)
        for (sql in listOf(TpchLineitem.Q1, TpchLineitem.Q6)) {
            val stmt = strict(sql)
            val lowered = SqlGraph.lower(SqlGraph.lift(stmt))
            assertEquals(rows(stmt.plan({ data }).result), rows(lowered.plan({ data }).result), sql)
        }
        val json = """[{"k": 1, "x": "red", "doc": {"t": "q"}}, {"k": 2, "x": "green", "doc": {"t": "z"}}, {"k": 3, "x": "grey", "doc": {"t": "q"}}]"""
        val table: Cursor = confixDoc(json).index.facet(ConfixIndexK.Columns)
        for (sql in listOf("SELECT k, x FROM t WHERE doc->>'t' = 'q' ORDER BY k DESC", "SELECT k + 1, x FROM t WHERE k > 1 AND x LIKE 'gr%'", "SELECT x, COUNT(*) FROM t GROUP BY x ORDER BY x")) {
            val stmt = strict(sql)
            val lowered = SqlGraph.lower(SqlGraph.lift(stmt))
            assertEquals(rows(stmt.plan({ table }).result), rows(lowered.plan({ table }).result), sql)
        }
    }

    @Test fun theConfixDocumentOfAProgramLowersToTheSameStatement() {
        for (sql in corpus) {
            val program = SqlGraph.lift(strict(sql), "q")
            val back = LcncProgramConfix.fromJson("q", LcncProgramConfix.toJson(program))
            assertEquals(SqlGraph.signature(strict(sql)), SqlGraph.signature(SqlGraph.lower(back)), sql)
            assertTrue(LcncProgramConfix.sameDefinition(program, back), sql)
        }
    }

    @Test fun wireOrderMovesOnlyGroupByAndNothingTheListsOwn() {
        val stmt = strict("SELECT a, b FROM t GROUP BY a, b ORDER BY b, a")
        val program = SqlGraph.lift(stmt)
        val flipped = SqlGraph.lower(rebuilt(program, wires = wiresOf(program).reversed()))
        assertEquals(
            SqlGraph.signature(stmt).replace("group by col(\"a\"), col(\"b\")", "group by col(\"b\"), col(\"a\")"),
            SqlGraph.signature(flipped),
            "select list and ORDER BY follow ord; GROUP BY follows wire order",
        )
    }

    @Test fun aNodeWithoutOrdAppendsAfterTheNumberedOnes() {
        val program = SqlGraph.lift(strict("SELECT a, b FROM t"))
        val hub = nodesOf(program).single { it.type == SqlGraph.SELECT }
        val extra = listOf(
            LcncNode("new-item", SqlGraph.ITEM_EXPR), LcncNode("new-col", SqlGraph.COL, mapOf("name" to "c")),
        )
        val grown = rebuilt(program, nodesOf(program) + extra, wiresOf(program) + listOf(
            LcncWire("new-col", "expr", "new-item", "expr"), LcncWire("new-item", "item", hub.id, "items"),
        ))
        assertEquals("select col(\"a\"), col(\"b\"), col(\"c\") from \"t\"", SqlGraph.signature(SqlGraph.lower(grown)))
    }

    @Test fun graphsTheStatementCannotSayAreRefusedAtTheNode() {
        val base = SqlGraph.lift(strict("SELECT a FROM t"))
        val hub = nodesOf(base).single { it.type == SqlGraph.SELECT }
        // * beside another item
        refused("ambiguous-star", rebuilt(base, nodesOf(base) + LcncNode("star", SqlGraph.ITEM_STAR),
            wiresOf(base) + LcncWire("star", "item", hub.id, "items")))
        // a second statement
        refused("multiple-roots", rebuilt(base, nodesOf(base) + LcncNode("other", SqlGraph.SELECT)))
        // a node nothing consumes
        refused("orphan-node", rebuilt(base, nodesOf(base) + LcncNode("loose", SqlGraph.COL, mapOf("name" to "z"))))
        // an unwired FROM
        refused("missing-input", rebuilt(base, wires = wiresOf(base).filterNot { it.toPort == "from" }))
        // a ring
        refused("unsupported-in-ast", rebuilt(base, nodesOf(base).map { if (it.id == hub.id) it.copy(children = listOf(LcncNode("c", SqlGraph.COL)).toSeries()) else it }))
        // a join whose right side is a join: (t JOIN u) moved to the right of v
        val joined = SqlGraph.lift(strict("SELECT a FROM t JOIN u ON t.k = u.k JOIN v ON t.k = v.k"))
        val outer = nodesOf(joined).last { it.type == SqlGraph.JOIN }
        val inner = nodesOf(joined).first { it.type == SqlGraph.JOIN }
        val v = wiresOf(joined).single { it.toNode == outer.id && it.toPort == "right" }.fromNode
        val swapped = wiresOf(joined).map {
            when {
                it.toNode == outer.id && it.toPort == "left" -> LcncWire(v, "table", outer.id, "left")
                it.toNode == outer.id && it.toPort == "right" -> LcncWire(inner.id, "table", outer.id, "right")
                else -> it
            }
        }
        refused("unsupported-in-ast", rebuilt(joined, wires = swapped))
    }

    @Test fun picklistParamsAreStrictAndBlankMeansTheDefault() {
        val program = SqlGraph.lift(strict("SELECT a FROM t JOIN u ON t.k = u.k ORDER BY a DESC"))
        fun with(type: String, key: String, value: String) =
            rebuilt(program, nodesOf(program).map { if (it.type == type) it.copy(params = it.params + (key to value)) else it })
        refused("bad-param", with(SqlGraph.JOIN, "join", "RIGHT"))
        refused("bad-param", with(SqlGraph.SORT_KEY, "direction", "DESCENDING"))
        assertTrue(SqlGraph.lower(with(SqlGraph.SORT_KEY, "direction", "desc")).orderBy[0].descending, "case-folded")
        assertFalse(SqlGraph.lower(with(SqlGraph.SORT_KEY, "direction", "")).orderBy[0].descending, "blank is the default ASC")
        refused("bad-param", with(SqlGraph.SORT_KEY, "ord", "first"))
    }

    @Test fun functionNamesAndCastTypesLowerAsTheParserSpellsThem() {
        val program = SqlGraph.lift(strict("SELECT COUNT(*), k::INT FROM t"))
        val upper = rebuilt(program, nodesOf(program).map {
            when (it.type) {
                SqlGraph.CALL -> it.copy(params = it.params + ("name" to "COUNT"))
                SqlGraph.CAST -> it.copy(params = it.params + ("type" to "INT"))
                else -> it
            }
        })
        assertEquals("select count(*), cast(col(\"k\") as int) from \"t\"", SqlGraph.signature(SqlGraph.lower(upper)))
    }

    @Test fun aSyntaxTreeFeedsEachNodeToOneConsumer() {
        val program = SqlGraph.lift(strict("SELECT a + b FROM t"))
        val arith = nodesOf(program).single { it.type == SqlGraph.ARITH }
        val a = wiresOf(program).single { it.toNode == arith.id && it.toPort == "left" }.fromNode
        val shared = wiresOf(program).filterNot { it.toNode == arith.id && it.toPort == "right" } + LcncWire(a, "expr", arith.id, "right")
        refused("shared-node", rebuilt(program, nodesOf(program).filterNot { n -> n.id == wiresOf(program).single { it.toNode == arith.id && it.toPort == "right" }.fromNode }, shared))
    }

    @Test fun pathStepsTheGraphCannotCarryAreRefusedAtLift() {
        for (sql in listOf("SELECT doc->'' FROM t", "SELECT doc->-1 FROM t", "SELECT doc#>>'{}' FROM t")) {
            val stmt = runCatching { SqlParser.parse(sql.toSeries()) }.getOrNull() ?: continue
            assertEquals("unsupported-in-ast", assertFailsWith<SqlGraphError>(sql) { SqlGraph.lift(stmt) }.code, sql)
        }
        assertEquals("parse", assertFailsWith<SqlGraphError> { SqlGraph.parse("SELECT doc:a[ FROM t") }.code)
    }

    @Test fun aCycleIsRefusedNotLooped() {
        // A node that feeds itself and its parent feeds two inputs: the tree check names it before any traversal.
        val program = SqlGraph.lift(strict("SELECT a + b FROM t"))
        val arith = nodesOf(program).single { it.type == SqlGraph.ARITH }
        val a = wiresOf(program).single { it.toNode == arith.id && it.toPort == "left" }.fromNode
        val rewired = wiresOf(program).filterNot { it.toNode == arith.id && it.toPort == "left" } + LcncWire(arith.id, "expr", arith.id, "left")
        refused("shared-node", rebuilt(program, nodesOf(program).filterNot { it.id == a }, rewired))
    }

    @Test fun theGuardRefusesWhatTheStrictParserMisreads() {
        for ((sql, code) in listOf(
            "SELECT DISTINCT a FROM t" to "reserved-word",
            "SELECT x::double precision FROM t" to "reserved-word",
            "SELECT a FROM t NATURAL JOIN u" to "reserved-word",
            "SELECT foo(a, b) FROM t" to "unsupported-in-ast",
            "SELECT 'n=' || a + b FROM t" to "ambiguous-precedence",
            "SELECT a <=> b || c FROM t" to "ambiguous-precedence",
        )) {
            val stmt = SqlParser.parse(sql.toSeries()) ?: continue // a statement the strict parser rejects needs no guard
            assertEquals(code, assertFailsWith<SqlGraphError>(sql) { SqlGraph.lift(stmt) }.code, sql)
        }
        assertFailsWith<SqlGraphError> { SqlGraph.parse("this is not sql") }
    }

    @Test fun theVocabularyIsWellFormed() {
        val contracts = SqlGraph.contracts()
        assertEquals(SqlGraph.TYPES, contracts.map { it.type })
        val kinds = setOf(SqlGraph.QUERY, SqlGraph.TABLE, SqlGraph.EXPR, SqlGraph.COND, SqlGraph.ITEM, SqlGraph.KEY)
        for (c in contracts) {
            for (port in c.inputs) assertTrue(c.inputKinds[port.removeSuffix("?")] in kinds, "${c.type}.$port has an IBM kind")
            for (port in c.outputs) {
                assertTrue(c.outputKinds[port] in kinds, "${c.type}.$port has an IBM kind")
                assertFalse(LcncKinds.isLiteral(c, port), "${c.type}.$port must not read as a literal")
            }
            assertTrue(c.cardinality.keys.all { it in c.inputs.map { p -> p.removeSuffix("?") } }, "${c.type} cardinality is keyed by bare port names")
            assertTrue(LcncKinds.isCcekType(c.inputKinds.values.firstOrNull() ?: SqlGraph.EXPR))
        }
        val all = LcncContracts.all().map { it.type }
        assertTrue(SqlGraph.TYPES.all { it in all }, "the vocabulary is registered")
        assertEquals(SqlGraph.TYPES.size, SqlGraph.TYPES.count { LcncNodeKey.of(it) != null }, "every node type has its key")
    }

    @Test fun liftedNodesAreLaidOutRightToLeft() {
        val program = SqlGraph.lift(strict("SELECT a, b FROM t WHERE a > 1"))
        val nodes = nodesOf(program)
        val hub = nodes.single { it.type == SqlGraph.SELECT }
        assertTrue(nodes.all { it.x <= hub.x }, "the statement is the rightmost node")
        assertEquals(nodes.size, nodes.map { it.x to it.y }.toSet().size, "no two nodes share a position")
    }
}
