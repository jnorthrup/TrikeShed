package borg.trikeshed.rdf

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import kotlinx.benchmark.*

/**
 * The graph shape of S7: a three-pattern basic graph pattern (a chain closed by a type) over [quads]
 * generated quads in four named graphs, 10,000 subjects, 20 predicates, objects drawn from the subject
 * pool or typed literals. The plan's acceptance is `routed` under 50 ms at 100,000 quads.
 *  - `routed`: [SparqlQuery] over the store's scan — dictionary codes, `where`/`refine` selection,
 *    `hashBuild`/`probe` joins, a [ColumnCursor] result;
 *  - `closure`: the same query over the same rows behind a plain Join — the row-shaped path;
 *  - `load`: interning the quads into a fresh [QuadStore].
 * Numbers go in the commit message whatever they are.
 */
@State(Scope.Benchmark)
open class GraphShapeBench {
    @Param("100000") var quads = 100_000

    private lateinit var source: List<RdfQuad>
    private lateinit var store: QuadStore
    private lateinit var columnar: ColumnCursor
    private lateinit var boxed: Cursor
    private lateinit var query: SparqlQuery

    @Setup
    fun open() {
        var seed = 0x9E3779B97F4A7C15uL.toLong()
        fun lcg(): Double { seed = seed * 6364136223846793005L + 1442695040888963407L; return ((seed ushr 11).toDouble()) / (1L shl 53).toDouble() }
        val subjects = Array(10_000) { RdfTerm.Iri("urn:bench:s$it") }
        val predicates = Array(20) { RdfTerm.Iri("urn:bench:p$it") }
        val graphs = Array(4) { RdfTerm.Iri("urn:bench:g$it") }
        val type = RdfTerm.Iri("urn:bench:T")
        source = List(quads) { i ->
            val s = subjects[(lcg() * subjects.size).toInt()]
            val p = predicates[(lcg() * predicates.size).toInt()]
            val o: RdfTerm = when {
                p === predicates[19] -> type
                lcg() < 0.6 -> subjects[(lcg() * subjects.size).toInt()]
                else -> RdfTerm.Literal((lcg() * 1000).toInt().toString(), datatype = RdfVocab.XSD + "integer")
            }
            RdfQuad(s, p, o, graphs[i and 3])
        }
        store = QuadStore()
        for (q in source) store.add(q)
        columnar = store.scan()
        val rows = columnar
        boxed = rows.size j { r: Int -> rows[r] }
        query = SparqlQuery.parse("SELECT ?a ?c WHERE { ?a <urn:bench:p1> ?b . ?b <urn:bench:p2> ?c . ?c <urn:bench:p19> <urn:bench:T> }")
    }

    @Benchmark
    fun routed(): Int = query.plan(columnar).size

    @Benchmark
    fun closure(): Int = query.plan(boxed).size

    @Benchmark
    fun load(): Int {
        val fresh = QuadStore()
        for (q in source) fresh.add(q)
        return fresh.size
    }
}
