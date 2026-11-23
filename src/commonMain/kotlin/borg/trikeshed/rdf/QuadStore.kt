package borg.trikeshed.rdf

import borg.trikeshed.collections.IntColumn
import borg.trikeshed.couch.isam.PackedStringpool
import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.query.*

/**
 * Quads as four code columns over one term dictionary: every term — IRI, literal with its language
 * or datatype, blank node — is interned by its Turtle lexeme once, and `G`, `S`, `P`, `O` hold its
 * code. A graph-less triple is filed under [DEFAULT_GRAPH]. [scan] is the store as a [ColumnCursor]
 * whose columns are [DictColumn]s sharing the dictionary, which is what the routed graph stage joins
 * on; [table] is Virtuoso's `DB.DBA.RDF_QUAD` (quads mode) and [triplesTable] its graph-blind `S P O`
 * reading (triples mode: the union of every graph). In memory, loaded from [RdfGraph]s or Turtle.
 *
 * The terms are one [PackedStringpool] and the codes four [IntColumn]s filled a row at a time; [rows] is an
 * open-addressing table over those rows that keeps a quad from being filed twice, so a quad costs its four
 * codes and a slot, never an object. [size] is published after a row's four codes are in. The pool only
 * grows: a term no quad names any more (after [remove] or [drop]) stays in it.
 */
class QuadStore {
    val pool = PackedStringpool()
    val codes = Array(COLUMNS.size) { IntColumn(256) }
    /** Row + 1 by quad hash, 0 empty, half full at most. */
    var rows = IntArray(512)
    var size: Int = 0
    var snapshot: ColumnCursor? = null

    /** The number of distinct terms. */
    val terms: Int get() = pool.size

    /** The code of [lexeme], minted on first sight. */
    fun intern(lexeme: String): Int {
        val before = pool.size
        return pool.put(lexeme).also { if (pool.size != before) snapshot = null }
    }

    fun lexeme(code: Int): String = pool[code] ?: throw IndexOutOfBoundsException("term $code, terms ${pool.size}")

    /** The term under [code], read back from its lexeme. */
    fun term(code: Int): RdfTerm = TurtleRdf.parseTerm(lexeme(code)) ?: RdfTerm.Iri(lexeme(code))

    /** The slot of the quad with these codes: the one that holds its row + 1, or the empty one where it would go. */
    fun slotOf(g: Int, s: Int, p: Int, o: Int): Int {
        val mask = rows.size - 1
        var h = g * -1640531535 + s
        h = h * -1640531535 + p
        h = h * -1640531535 + o
        var at = (h xor (h ushr 15)) and mask
        while (rows[at] != 0) {
            val r = rows[at] - 1
            if (codes[G][r] == g && codes[S][r] == s && codes[P][r] == p && codes[O][r] == o) return at
            at = (at + 1) and mask
        }
        return at
    }

    /** The table made again from the rows, sized for twice their number. */
    fun reindex() {
        var capacity = 512
        while (capacity < size shl 1) capacity = capacity shl 1
        rows = IntArray(capacity)
        for (r in 0 until size) rows[slotOf(codes[G][r], codes[S][r], codes[P][r], codes[O][r])] = r + 1
    }

    /** Adds one quad by lexemes, once (a graph is a set); [graph] defaults to [DEFAULT_GRAPH]. */
    fun add(s: String, p: String, o: String, graph: String = DEFAULT_GRAPH): QuadStore {
        val gc = intern(graph); val sc = intern(s); val pc = intern(p); val oc = intern(o)
        val at = slotOf(gc, sc, pc, oc)
        if (rows[at] != 0) return this
        codes[G].add(gc); codes[S].add(sc); codes[P].add(pc); codes[O].add(oc)
        rows[at] = size + 1
        size++
        snapshot = null
        if (size shl 1 > rows.size) reindex()
        return this
    }

    fun contains(s: String, p: String, o: String, graph: String = DEFAULT_GRAPH): Boolean {
        val gc = pool.find(graph); val sc = pool.find(s); val pc = pool.find(p); val oc = pool.find(o)
        if (gc < 0 || sc < 0 || pc < 0 || oc < 0) return false
        return rows[slotOf(gc, sc, pc, oc)] != 0
    }

    /** Removes the quad equal to the given lexemes; true when it was present. */
    fun remove(s: String, p: String, o: String, graph: String = DEFAULT_GRAPH): Boolean {
        val gc = pool.find(graph); val sc = pool.find(s); val pc = pool.find(p); val oc = pool.find(o)
        if (gc < 0 || sc < 0 || pc < 0 || oc < 0) return false
        val at = slotOf(gc, sc, pc, oc)
        if (rows[at] == 0) return false
        val r = rows[at] - 1
        for (column in codes) { column.a.copyInto(column.a, r, r + 1, size); column.n-- }
        size--
        reindex()
        snapshot = null
        return true
    }

    /** Removes every quad whose subject's lexeme starts with [prefix]; the number removed. */
    fun drop(prefix: String): Int {
        val verdict = ByteArray(pool.size)
        var kept = 0
        for (r in 0 until size) {
            val subject = codes[S][r]
            if (verdict[subject] == UNREAD) verdict[subject] = if (lexeme(subject).startsWith(prefix)) HIT else MISS
            if (verdict[subject] == HIT) continue
            if (kept != r) for (column in codes) column[kept] = column[r]
            kept++
        }
        val removed = size - kept
        if (removed > 0) {
            for (column in codes) column.n = kept
            size = kept
            reindex()
            snapshot = null
        }
        return removed
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
        for (r in 0 until size) lexeme(codes[G][r]).takeIf { it != DEFAULT_GRAPH }?.let(seen::add)
        return seen.toList()
    }

    /** The store as `G, S, P, O` dictionary columns; cached until the next write. */
    fun scan(): ColumnCursor = snapshot ?: run {
        val n = size
        val dictionary = Array(pool.size) { lexeme(it) }
        ColumnCursor.of(
            Array(COLUMNS.size) { ColumnMeta(COLUMNS[it], IOMemento.IoString) },
            Array(COLUMNS.size) { DenseColumn(DictColumn(codes[it].a.copyOf(n), dictionary)) },
        ).also { snapshot = it }
    }

    /** The quads back as a graph: default-graph rows as triples, the rest as quads. */
    fun toGraph(): RdfGraph {
        val triples = ArrayList<RdfTriple>()
        val quads = ArrayList<RdfQuad>()
        for (r in 0 until size) {
            val predicate = term(codes[P][r]) as? RdfTerm.Iri ?: RdfTerm.Iri(lexeme(codes[P][r]))
            val graph = lexeme(codes[G][r])
            if (graph == DEFAULT_GRAPH) triples += RdfTriple(term(codes[S][r]), predicate, term(codes[O][r]))
            else quads += RdfQuad(term(codes[S][r]), predicate, term(codes[O][r]), term(codes[G][r]) as? RdfTerm.Iri ?: RdfTerm.Iri(graph))
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
        const val G = 0
        const val S = 1
        const val P = 2
        const val O = 3
        /** What [drop] has read of a subject: nothing yet, that it starts with the prefix, that it does not. */
        const val UNREAD: Byte = 0
        const val HIT: Byte = 1
        const val MISS: Byte = 2
    }
}
