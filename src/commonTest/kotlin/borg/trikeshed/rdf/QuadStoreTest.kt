package borg.trikeshed.rdf

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.query.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.kursive.sql.*
import kotlin.test.*

/** Terms interned once, quads as code columns, the two Virtuoso tables over one scan. */
class QuadStoreTest {
    private fun rows(c: Cursor): List<List<Any?>> = List(c.size) { r -> c[r].values.let { v -> List(v.size) { v[it] } } }

    @Test fun termsAreInternedOnceAndTheScanSharesOneDictionary() {
        val store = QuadStore()
        store.add("<urn:a>", "<urn:p>", "<urn:b>")
        store.add("<urn:b>", "<urn:p>", "<urn:a>", "<urn:g>")
        store.add("<urn:a>", "<urn:p>", "\"1\"^^<${RdfVocab.XSD}integer>")
        assertEquals(3, store.size)
        assertEquals(6, store.terms, "a, p, b, the default graph, g, the literal")
        assertEquals(store.intern("<urn:a>"), store.intern("<urn:a>"))
        assertEquals(listOf("<urn:g>"), store.graphs())
        val scan = store.scan()
        assertSame(scan, store.scan(), "the scan is cached until the next write")
        assertEquals(QuadStore.COLUMNS, scan.metas.map { it.name.toString() })
        val dictionaries = (0 until 4).map { (scan.dense(it).data as DictColumn).dictionary }
        assertTrue(dictionaries.all { it === dictionaries[0] })
        assertEquals(listOf(QuadStore.DEFAULT_GRAPH, "<urn:a>", "<urn:p>", "<urn:b>"), rows(scan)[0])
        store.add("<urn:c>", "<urn:p>", "<urn:d>")
        assertNotSame(scan, store.scan())
        assertEquals(4, store.scan().size)
        store.add("<urn:c>", "<urn:p>", "<urn:d>")
        assertEquals(4, store.size, "a graph is a set: the same quad twice is one row")
        assertTrue(store.remove("<urn:c>", "<urn:p>", "<urn:d>")); assertFalse(store.remove("<urn:c>", "<urn:p>", "<urn:d>"))
        assertEquals(3, store.size)
    }

    @Test fun aGraphRoundTripsThroughTheStore() {
        val graph = TurtleRdf.parse(SparqlQueryTest.FIXTURE)
        val store = QuadStore().load(graph)
        assertEquals(graph.triples.size + graph.quads.size, store.size)
        val back = store.toGraph()
        assertEquals(graph.triples, back.triples)
        assertEquals(graph.quads, back.quads)
        assertEquals(RdfTerm.Literal("A", lang = "en"), store.term(store.intern("\"A\"@en")))
        assertEquals(listOf("<urn:ex:g1>", "<urn:ex:g2>"), store.graphs())
    }

    @Test fun theQuadTableAndTheTriplesViewReadOneStoreThroughSql() {
        val store = QuadStore().load(SparqlQueryTest.FIXTURE)
        val catalog = Catalog(listOf({ store.tables() }))
        assertEquals(listOf("DB.DBA.RDF_QUAD", "triples"), catalog.tables().map { it.name })
        assertNotNull(catalog.table("db.dba.rdf_quad"), "Virtuoso's table name is case-insensitive")
        val sql = SqlParser.parse("SELECT S, O FROM DB.DBA.RDF_QUAD WHERE G = '<urn:ex:g1>' AND P = '<urn:ex:p>' ORDER BY S".toSeries())!!.plan(catalog::resolve)
        assertEquals(listOf(listOf("<urn:ex:a>", "<urn:ex:b>"), listOf("<urn:ex:b>", "<urn:ex:c>")), rows(sql))
        val sparql = SparqlQuery.parse("SELECT ?s ?o WHERE { GRAPH <urn:ex:g1> { ?s <urn:ex:p> ?o } } ORDER BY ?s").plan(catalog.resolve(QuadStore.RDF_QUAD))
        assertEquals(rows(sql), rows(sparql), "SQL over RDF_QUAD and SPARQL over the same graph agree")
        // triples mode: the union of every graph, graph-blind
        val triples = SqlParser.parse("SELECT COUNT(*) FROM triples WHERE P = '<urn:ex:p>'".toSeries())!!.plan(catalog::resolve)
        assertEquals(4L, triples[0].values[0])
        assertEquals(listOf("S", "P", "O"), catalog.table("triples")!!.columns().map { it.name.toString() })
        assertEquals(listOf("text", "text", "text", "text"), catalog.table(QuadStore.RDF_QUAD)!!.columns().map(::pgType))
        // a typed literal reads as a number through a cast
        val numeric = SqlParser.parse("SELECT S FROM DB.DBA.RDF_QUAD WHERE P = '<urn:ex:score>' AND O::float8 > 0.5".toSeries())!!.plan(catalog::resolve)
        assertEquals(listOf(listOf("<urn:ex:a>")), rows(numeric))
    }
}
