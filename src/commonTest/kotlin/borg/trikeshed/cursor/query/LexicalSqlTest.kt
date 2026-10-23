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

    @Test fun matchIsALocalPredicate() {
        assertEquals(listOf(1L, 3L, 5L), rows("SELECT id FROM docs WHERE body @@ 'algebra joins' ORDER BY id").map { it[0] })
        assertEquals(listOf(1L, 2L, 3L, 5L), rows("SELECT id FROM docs WHERE body @@ 'Cursor' OR body @@ 'SERIES' ORDER BY id").map { it[0] })
        assertEquals(emptyList(), rows("SELECT id FROM docs WHERE body @@ 'nope'"))
    }
}
