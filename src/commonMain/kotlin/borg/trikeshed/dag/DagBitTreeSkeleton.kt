package borg.trikeshed.dag

import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.rdf.RdfGraph
import borg.trikeshed.rdf.RdfQuad
import borg.trikeshed.rdf.RdfTerm
import borg.trikeshed.rdf.RdfTriple
import borg.trikeshed.rdf.RdfVocab
import borg.trikeshed.rdf.TurtleRdf

/**
 * Bit-Tree Skeleton — versioned fixed queryable/fillable abstraction for known DAGs.
 *
 * For *known* DAGs (causal graph, Rete network, Pijul dependency DAG, panama symbol DAG)
 * the *shape* is fixed and content-addressed. Every node position maps to a bit-tree
 * index; missing subtrees are *holes* that stay queryable and become fillable later.
 *
 * Algebra:
 *  - Tree is a complete binary bit-tree of depth D → capacity = 2^D leaves.
 *  - Version = root ContentId (KIF/SUMO: `version` relation). Each fill produces a new
 *    immutable version; old version stays queryable (git-like CID chain).
 *  - Fixed skeleton → no reallocation, O(log N) proof & query via bit ops.
 *  - Queryable: ancestors(point), descendants(point), lca(a,b), isFilled, holes.
 *  - Fillable: `fill(pos, payload) -> newVersion` and `fillAll(map) -> newVersion` batch.
 *
 * Node serialization: TurtleRdf is closest RDF form to a node — each DAG node
 * serializes as a Turtle subject block (forge:dagNode). The bit-tree skeleton
 * itself serializes as quads in the graph `forge:dagSkeleton/<dagId>/<version>`.
 */
class DagBitTreeSkeleton private constructor(
    val dagId: String,
    val depth: Int,
    val version: String,
    internal val filled: BooleanArray,
    internal val payloads: Array<Any?>,
) {
    val capacity: Int get() = 1 shl depth
    val size: Int get() = filled.count { it }

    fun isFilled(pos: Int): Boolean = pos in 0 until capacity && filled[pos]
    fun isHole(pos: Int): Boolean = !isFilled(pos)
    fun holes(): borg.trikeshed.lib.Series<Int> {
        val hs = mutableListOf<Int>()
        for (i in 0 until capacity) if (!filled[i]) hs.add(i)
        return hs.size j { hs[it] }
    }
    fun filledPositions(): borg.trikeshed.lib.Series<Int> {
        val fs = mutableListOf<Int>()
        for (i in 0 until capacity) if (filled[i]) fs.add(i)
        return fs.size j { fs[it] }
    }

    fun payloadAt(pos: Int): Any? = if (isFilled(pos)) payloads[pos] else null

    fun bitAncestors(pos: Int): borg.trikeshed.lib.Series<Int> {
        val out = mutableListOf<Int>()
        var block = 1
        while (block < capacity) {
            val parentBlock = block * 2
            val parentStart = (pos / parentBlock) * parentBlock
            out.add(parentStart)
            block = parentBlock
        }
        return out.size j { out[it] }
    }

    fun isIntervalFilled(l: Int, r: Int): Boolean {
        for (i in l until r) if (!filled[i]) return false
        return true
    }

    fun fill(pos: Int, payload: Any?, newVersion: String): DagBitTreeSkeleton {
        require(pos in 0 until capacity) { "pos $pos out of [0,$capacity)" }
        val nf = filled.copyOf()
        val np = payloads.copyOf()
        nf[pos] = true
        np[pos] = payload
        return DagBitTreeSkeleton(dagId, depth, newVersion, nf, np)
    }

    fun fillAll(entries: Map<Int, Any?>, newVersion: String): DagBitTreeSkeleton {
        val nf = filled.copyOf()
        val np = payloads.copyOf()
        for ((pos, payload) in entries) {
            require(pos in 0 until capacity)
            nf[pos] = true
            np[pos] = payload
        }
        return DagBitTreeSkeleton(dagId, depth, newVersion, nf, np)
    }

    fun withVersion(newVersion: String): DagBitTreeSkeleton =
        DagBitTreeSkeleton(dagId, depth, newVersion, filled, payloads)

    fun toRdfGraph(): RdfGraph {
        val gIri = "${RdfVocab.FORGE}dagSkeleton/$dagId/$version"
        val g = RdfTerm.Iri(gIri)
        val triples = mutableListOf<RdfTriple>()
        val quads = mutableListOf<RdfQuad>()
        val skeletonIri = RdfTerm.Iri("${RdfVocab.FORGE}dagSkeleton/$dagId")
        quads.add(RdfQuad(skeletonIri, RdfTerm.Iri(RdfVocab.RDF + "type"), RdfTerm.Iri(RdfVocab.SUMO + "Collection"), g))
        quads.add(RdfQuad(skeletonIri, RdfTerm.Iri("${RdfVocab.FORGE}depth"), RdfTerm.Literal(depth.toString(), datatype = RdfVocab.XSD + "integer"), g))
        quads.add(RdfQuad(skeletonIri, RdfTerm.Iri("${RdfVocab.FORGE}capacity"), RdfTerm.Literal(capacity.toString(), datatype = RdfVocab.XSD + "integer"), g))
        quads.add(RdfQuad(skeletonIri, RdfTerm.Iri("${RdfVocab.FORGE}version"), RdfTerm.Literal(version), g))
        for (i in 0 until capacity) {
            val nodeIri = RdfTerm.Iri("${RdfVocab.FORGE}dagNode/$dagId/$i")
            quads.add(RdfQuad(nodeIri, RdfTerm.Iri("${RdfVocab.FORGE}position"), RdfTerm.Literal(i.toString(), datatype = RdfVocab.XSD + "integer"), g))
            quads.add(RdfQuad(nodeIri, RdfTerm.Iri("${RdfVocab.FORGE}filled"), RdfTerm.Literal(filled[i].toString(), datatype = RdfVocab.XSD + "boolean"), g))
            payloads[i]?.let { p ->
                quads.add(RdfQuad(nodeIri, RdfTerm.Iri("${RdfVocab.FORGE}payload"), RdfTerm.Literal(p.toString()), g))
            }
        }
        return RdfGraph(triples, quads)
    }

    fun toTurtle(): String = TurtleRdf.emit(toRdfGraph())
    fun toKif(): String = buildString {
        appendLine("(instance $dagId Collection)")
        appendLine("(instance ${dagId}_$version Collection)")
        appendLine("(holdsDuring $version (depth $dagId $depth))")
        for (i in 0 until capacity) if (filled[i]) {
            appendLine("(member ${dagId}_$i $dagId)")
            payloads[i]?.let { appendLine(";; $i payload: $it") }
        }
    }

    companion object {
        fun empty(dagId: String, depth: Int, version: String = "v0"): DagBitTreeSkeleton {
            require(depth in 1..20) { "depth 1..20" }
            val cap = 1 shl depth
            return DagBitTreeSkeleton(dagId, depth, version, BooleanArray(cap), arrayOfNulls(cap))
        }
        fun fromRdfGraph(graph: RdfGraph): DagBitTreeSkeleton? {
            val quads = graph.quads
            if (quads.isEmpty()) return null
            val gIri = quads.firstOrNull()?.g?.iri ?: return null
            val dagId = gIri.substringAfter("dagSkeleton/").substringBefore("/")
            if (dagId.isEmpty()) return null
            val depthQuad = quads.firstOrNull { it.p.iri.endsWith("depth") } ?: return null
            val depth = (depthQuad.o as? RdfTerm.Literal)?.lexical?.toIntOrNull() ?: return null
            val version = (quads.firstOrNull { it.p.iri.endsWith("version") }?.o as? RdfTerm.Literal)?.lexical ?: "v0"
            val skel = empty(dagId, depth, version)
            for (q in quads) {
                if (!q.p.iri.endsWith("payload")) continue
                val sIri = (q.s as? RdfTerm.Iri)?.iri ?: continue
                val pos = sIri.substringAfterLast("/").toIntOrNull() ?: continue
                val lit = (q.o as? RdfTerm.Literal)?.lexical ?: continue
                if (pos in 0 until skel.capacity) {
                    skel.filled[pos] = true
                    skel.payloads[pos] = lit
                }
            }
            return skel
        }
    }
}
