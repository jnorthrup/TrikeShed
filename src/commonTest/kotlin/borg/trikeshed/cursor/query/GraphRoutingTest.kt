package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.rdf.QuadStore
import borg.trikeshed.rdf.SparqlQuery
import borg.trikeshed.rdf.SparqlQueryTest
import kotlin.test.*

/** The routed graph stage (dictionary codes, hash joins) answers exactly what the closure path answers, in the same order. */
class GraphRoutingTest {
    private val store = QuadStore().load(SparqlQueryTest.FIXTURE)
    private val columnar: ColumnCursor = store.scan()
    /** The same quads behind a plain Join: not a ColumnCursor, so the stage takes the closure path. */
    private val boxed: Cursor = columnar.size j { r: Int -> columnar[r] }

    private fun rows(c: Cursor): List<List<Any?>> = List(c.size) { r -> c[r].values.let { v -> List(v.size) { v[it] } } }

    @Test fun routedAndClosureGraphStagesAgree() {
        for (sparql in listOf(
            "SELECT ?x ?y WHERE { ?x <urn:ex:p> ?y }",
            "SELECT ?x ?z WHERE { ?x <urn:ex:p> ?y . ?y <urn:ex:p> ?z }",
            "SELECT ?s ?o ?q WHERE { ?s <urn:ex:p> ?o ; <urn:ex:q> ?q }",
            "SELECT ?g ?x WHERE { GRAPH ?g { ?x <urn:ex:score> ?s } }",
            "SELECT ?x FROM <urn:ex:g1> WHERE { ?x <urn:ex:p> ?y }",
            "SELECT ?x ?n WHERE { ?x <urn:ex:p> ?y . ?z <urn:ex:name> ?n }",
            "SELECT ?x ?y ?z WHERE { ?x <urn:ex:p> ?y . ?y <urn:ex:p> ?z . ?x <urn:ex:q> \"x\" }",
            "SELECT ?x WHERE { ?x <urn:ex:p> <urn:ex:nowhere> }",
            "SELECT ?x WHERE { ?x <urn:ex:self> ?x }",
        )) {
            val query = SparqlQuery.parse(sparql)
            val fast = query.plan(columnar)
            val slow = query.plan(boxed)
            assertEquals(rows(slow), rows(fast), sparql)
            if (fast.size > 0) assertEquals(query.variables, fast.columnNames.view.map { it.toString() }, sparql)
        }
    }

    @Test fun theRoutedResultKeepsTheDictionary() {
        val stage = QueryK.Graph(s_[TriplePattern(PatternTerm("?x"), PatternTerm("<urn:ex:p>"), PatternTerm("?y"))])
        val routed = columnar.facet(stage)
        assertTrue(routed is ColumnCursor, "a ColumnCursor source routes to a ColumnCursor result")
        val column = (routed as ColumnCursor).dense(0).data
        assertTrue(column is DictColumn && column.dictionary === (columnar.dense(0).data as DictColumn).dictionary, "the result shares the store's dictionary")
        assertEquals(4, routed.size)
        assertEquals(rows(boxed.facet(stage)), rows(routed))
    }
}
