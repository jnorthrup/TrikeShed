package borg.trikeshed.cursor.query

import borg.trikeshed.cas.TermPostings
import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.parse.kursive.sql.*
import kotlin.test.*

/** Evoke's SQL surface over the one planner: `evoke_query(index, query[, k])` and `@@`. */
class LexicalSqlTest {
    private val bodies = listOf(
        "the cursor algebra joins series", "cursor cursor cursor", "an algebra of series and joins",
        "nothing relevant here at all", "join the cursor to the algebra and the cursor again",
    )
    private val docs: Cursor = confixDoc(
        bodies.mapIndexed { i, b -> """{"id": ${i + 1}, "body": "$b", "owner": "${if (i % 2 == 0) "u1" else "u2"}"}""" }.joinToString(",", "[", "]"),
    ).index.facet(ConfixIndexK.Columns)

    private fun rows(sql: String): List<List<Any?>> {
        val cursor = SqlParser.parse(sql.toSeries())!!.plan({ docs })
        return List(cursor.size) { r -> cursor[r].values.let { v -> List(v.size) { v[it] } } }
    }

    private fun reference(query: String): Map<Int, Double> {
        val postings = TermPostings()
        bodies.forEachIndexed { i, b -> postings.upsert(i + 1, b) }
        return postings.scores(query).mapKeys { it.key as Int }
    }

    @Test fun theMarkerScoresTheWholeTableBeforeWhereAndReadsAsAColumn() {
        val expected = reference("cursor algebra")
        val ranked = rows("SELECT id, evoke_query('body', 'cursor algebra') AS score FROM docs ORDER BY score DESC, id LIMIT 3")
        assertEquals(expected.entries.sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key }).take(3).map { it.key.toLong() }, ranked.map { it[0] })
        for (row in ranked) assertEquals(expected.getValue((row[0] as Long).toInt()), row[1])
        // WHERE filters the scored rows: the scores keep the full corpus statistics, non-matching rows are gone.
        val filtered = rows("SELECT id, evoke_query('body', 'cursor algebra') AS score FROM docs WHERE owner = 'u1' ORDER BY score DESC")
        val u1 = expected.filterKeys { it % 2 == 1 }.entries.sortedByDescending { it.value }.map { it.key.toLong() }
        assertEquals(u1, filtered.map { it[0] })
        for (row in filtered) assertEquals(expected.getValue((row[0] as Long).toInt()), row[1])
    }

    @Test fun theIndexMayBeARegclassLiteralAndKBoundsTheHits() {
        val expected = reference("cursor")
        val top = rows("SELECT id, evoke_query('body'::regclass, 'cursor', 2) AS s FROM docs ORDER BY s DESC")
        assertEquals(2, top.size)
        assertEquals(expected.entries.sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key }).take(2).map { it.key.toLong() }, top.map { it[0] })
        // The same marker in ORDER BY only (no select-list column) still ranks, and the score column stays hidden.
        assertEquals(listOf(listOf(2L), listOf(5L)), rows("SELECT id FROM docs ORDER BY evoke_query('body', 'cursor') DESC, id LIMIT 2"))
        assertFailsWith<IllegalStateException> { rows("SELECT evoke_query(body, 'q') FROM docs") }
    }

    @Test fun theTableFunctionInFromAnswersIdAndScoreAndJoinsBack() {
        val expected = reference("cursor algebra")
        val ranked = expected.entries.sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key })
        // FROM alone: the relation is (id, score), best first, cut to k; the default alias is the function's name
        val top = rows("SELECT * FROM evoke_query('docs', 'cursor algebra', 3)")
        assertEquals(ranked.take(3).map { listOf(it.key.toLong(), it.value) }, top)
        val cursor = SqlParser.parse("SELECT evoke_query.id, evoke_query.score FROM evoke_query('docs.body', 'cursor algebra', 3)".toSeries())!!.plan({ docs })
        assertEquals(listOf("id", "score"), cursor.columnNames.view.map { it.toString() })
        // JOIN back to the table on either side; WHERE on the score keeps the full corpus statistics
        val joined = rows("SELECT d.id, d.owner, h.score FROM evoke_query('docs', 'cursor algebra', 5) AS h JOIN docs d ON d.id = h.id ORDER BY h.score DESC, d.id")
        assertEquals(ranked.map { listOf(it.key.toLong(), if (it.key % 2 == 1) "u1" else "u2", it.value) }, joined)
        assertEquals(joined, rows("SELECT d.id, d.owner, h.score FROM docs d JOIN evoke_query('docs', 'cursor algebra', 5) h ON h.id = d.id ORDER BY h.score DESC, d.id"))
        val threshold = (ranked[1].value + ranked[2].value) / 2
        assertEquals(ranked.take(2).map { it.key.toLong() }.sorted(), rows("SELECT id FROM evoke_query('docs', 'cursor algebra', 5) h WHERE h.score > $threshold ORDER BY id").map { it[0] })
        // the one schema may qualify the index, as it may a table
        assertEquals(top, rows("SELECT * FROM evoke_query('public.docs', 'cursor algebra', 3)"))
        // the receipt names the function and lists the stage, and the index's table is the source
        val receipt = SqlParser.parse("SELECT * FROM evoke_query('docs', 'cursor', 2) h".toSeries())!!.plan({ docs }).receipt
        assertTrue(receipt.statement.endsWith(" FROM evoke_query('docs', 'cursor', 2) h"), receipt.statement)
        assertEquals(listOf("LexicalTopK", "Project", "Project"), receipt.stages)
        assertEquals(listOf("docs"), receipt.sources.keys.toList())
        assertTrue(receipt.statementCid != SqlParser.parse("SELECT * FROM docs h".toSeries())!!.plan({ docs }).receipt.statementCid)
        // refusals: the scalar two-argument form, a non-positive k, a non-constant query, another function
        assertTrue(assertFailsWith<IllegalArgumentException> { rows("SELECT * FROM evoke_query('docs', 'cursor')") }.message!!.contains("three arguments"))
        assertTrue(assertFailsWith<IllegalArgumentException> { rows("SELECT * FROM evoke_query('docs', 'cursor', 0)") }.message!!.contains("positive"))
        assertFailsWith<IllegalStateException> { rows("SELECT * FROM evoke_query('docs', body, 2)") }
        assertTrue(assertFailsWith<IllegalStateException> { rows("SELECT * FROM generate_series(1, 3)") }.message!!.startsWith("function generate_series(...) does not exist"))
        assertNull(SqlParser.parse("SELECT * FROM hit(a, b".toSeries()))
    }

    @Test fun matchIsALocalPredicate() {
        assertEquals(listOf(1L, 3L, 5L), rows("SELECT id FROM docs WHERE body @@ 'algebra joins' ORDER BY id").map { it[0] })
        assertEquals(listOf(1L, 2L, 3L, 5L), rows("SELECT id FROM docs WHERE body @@ 'Cursor' OR body @@ 'SERIES' ORDER BY id").map { it[0] })
        assertEquals(emptyList(), rows("SELECT id FROM docs WHERE body @@ 'nope'"))
    }
}
