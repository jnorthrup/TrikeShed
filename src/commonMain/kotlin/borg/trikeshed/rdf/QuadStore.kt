package borg.trikeshed.rdf

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.query.*

/**
 * Quads as four code columns over one term dictionary: every term — IRI, literal with its language
 * or datatype, blank node — is interned by its Turtle lexeme once, and `G`, `S`, `P`, `O` hold its
 * code. A graph-less triple is filed under [DEFAULT_GRAPH]. [scan] is the store as a [ColumnCursor]
 * whose columns are [DictColumn]s sharing the dictionary, which is what the routed graph stage joins
 * on; [table] is Virtuoso's `DB.DBA.RDF_QUAD` (quads mode) and [triplesTable] its graph-blind `S P O`
 * reading (triples mode: the union of every graph). In memory, loaded from [RdfGraph]s or Turtle.
 */
class QuadStore {
    private val codeOf = HashMap<String, Int>()
    private val lexemes = ArrayList<String>()
    private var g = IntArray(256)
    private var s = IntArray(256)
    private var p = IntArray(256)
    private var o = IntArray(256)
    var size: Int = 0
        private set
    private var snapshot: ColumnCursor? = null
    private val present = HashSet<QuadKey>()

    /** One quad's codes; the set that keeps the store free of duplicates. */
    data class QuadKey(val g: Int, val s: Int, val p: Int, val o: Int)

    /** The number of distinct terms. */
    val terms: Int get() = lexemes.size

    /** The code of [lexeme], minted on first sight. */
    fun intern(lexeme: String): Int = codeOf.getOrPut(lexeme) { lexemes.add(lexeme); snapshot = null; lexemes.size - 1 }

    fun lexeme(code: Int): String = lexemes[code]

    /** The term under [code], read back from its lexeme. */
    fun term(code: Int): RdfTerm = TurtleRdf.parseTerm(lexemes[code]) ?: RdfTerm.Iri(lexemes[code])

    /** Adds one quad by lexemes, once (a graph is a set); [graph] defaults to [DEFAULT_GRAPH]. */
    fun add(s: String, p: String, o: String, graph: String = DEFAULT_GRAPH): QuadStore {
        val key = QuadKey(intern(graph), intern(s), intern(p), intern(o))
        if (!present.add(key)) return this
        if (size == g.size) {
            val n = size * 2
            g = g.copyOf(n); this.s = this.s.copyOf(n); this.p = this.p.copyOf(n); this.o = this.o.copyOf(n)
        }
        g[size] = key.g; this.s[size] = key.s; this.p[size] = key.p; this.o[size] = key.o
        size++
        snapshot = null
        return this
    }

    fun contains(s: String, p: String, o: String, graph: String = DEFAULT_GRAPH): Boolean {
        val key = QuadKey(codeOf[graph] ?: return false, codeOf[s] ?: return false, codeOf[p] ?: return false, codeOf[o] ?: return false)
        return key in present
    }

    /** Removes every quad equal to the given lexemes; true when at least one was present. */
    fun remove(s: String, p: String, o: String, graph: String = DEFAULT_GRAPH): Boolean {
        val gc = codeOf[graph] ?: return false
        val sc = codeOf[s] ?: return false
        val pc = codeOf[p] ?: return false
        val oc = codeOf[o] ?: return false
        if (!present.remove(QuadKey(gc, sc, pc, oc))) return false
        var w = 0
        for (r in 0 until size) {
            if (g[r] == gc && this.s[r] == sc && this.p[r] == pc && this.o[r] == oc) continue
            g[w] = g[r]; this.s[w] = this.s[r]; this.p[w] = this.p[r]; this.o[w] = this.o[r]
            w++
        }
        if (w == size) return false
        size = w
        snapshot = null
        return true
    }

    fun add(quad: RdfQuad): QuadStore = add(quad.s.toTurtle(), quad.p.toTurtle(), quad.o.toTurtle(), quad.g?.toTurtle() ?: DEFAULT_GRAPH)

    fun add(triple: RdfTriple, graph: RdfTerm.Iri? = null): QuadStore = add(RdfQuad(triple.s, triple.p, triple.o, graph))

    /** Every triple of [graph] under the default graph and every quad under its own. */
    fun load(graph: RdfGraph): QuadStore {
        for (t in graph.triples) add(t)
        for (q in graph.quads) add(q)
        return this
    }

    fun load(turtle: String): QuadStore = load(TurtleRdf.parse(turtle))

    /** The named graphs, as lexemes, in order of first appearance. */
    fun graphs(): List<String> {
        val seen = LinkedHashSet<String>()
        for (r in 0 until size) lexemes[g[r]].takeIf { it != DEFAULT_GRAPH }?.let(seen::add)
        return seen.toList()
    }

    /** The store as `G, S, P, O` dictionary columns; cached until the next write. */
    fun scan(): ColumnCursor = snapshot ?: run {
        val dictionary = lexemes.toTypedArray()
        val n = size
        ColumnCursor.of(
            Array(4) { ColumnMeta(COLUMNS[it], IOMemento.IoString) },
            arrayOf(g, s, p, o).map { DenseColumn(DictColumn(it.copyOf(n), dictionary)) }.toTypedArray(),
        ).also { snapshot = it }
    }

    /** The quads back as a graph: default-graph rows as triples, the rest as quads. */
    fun toGraph(): RdfGraph {
        val triples = ArrayList<RdfTriple>()
        val quads = ArrayList<RdfQuad>()
        for (r in 0 until size) {
            val predicate = term(p[r]) as? RdfTerm.Iri ?: RdfTerm.Iri(lexemes[p[r]])
            val graph = lexemes[g[r]]
            if (graph == DEFAULT_GRAPH) triples += RdfTriple(term(s[r]), predicate, term(o[r]))
            else quads += RdfQuad(term(s[r]), predicate, term(o[r]), term(g[r]) as? RdfTerm.Iri ?: RdfTerm.Iri(graph))
        }
        return RdfGraph(triples, quads)
    }

    /** Virtuoso's quad table, `DB.DBA.RDF_QUAD`: G, S, P, O as term lexemes. */
    fun table(): CatalogTable = CursorTable(RDF_QUAD) { scan() }

    /** Triples mode: `S, P, O` over the union of every graph (a graph-blind view of the same rows). */
    fun triplesTable(): CatalogTable = CursorTable(TRIPLES) {
        val quads = scan()
        ColumnCursor.of(Array(3) { quads.metas[it + 1] }, Array(3) { quads.dense(it + 1) })
    }

    /** Both tables, for a catalog source. */
    fun tables(): List<CatalogTable> = listOf(table(), triplesTable())

    companion object {
        const val RDF_QUAD = "DB.DBA.RDF_QUAD"
        const val TRIPLES = "triples"
        const val DEFAULT_GRAPH = QueryK.Graph.DEFAULT_GRAPH
        val COLUMNS = listOf("G", "S", "P", "O")
    }
}
