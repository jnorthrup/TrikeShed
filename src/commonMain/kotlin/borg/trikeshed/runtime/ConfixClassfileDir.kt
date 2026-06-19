package borg.trikeshed.runtime

import borg.trikeshed.classfile.model.PointcutCoordinate
import borg.trikeshed.classfile.slab.SlabFacet
import borg.trikeshed.classfile.slab.facet.LCNCModeFacet
import borg.trikeshed.lib.Join as LibJoin
import borg.trikeshed.lib.j

/**
 * ConfixClassfileDir — classfile hierarchy as Confix paths.
 * 
 * Projection-based: no node classes — just paths + facets + lazy Series.
 */
object ConfixClassfileDir {
    
    /** Root path prefix */
    const val ROOT = "/classes"
    
    /** Build canonical path from PointcutCoordinate */
    fun pathOf(pc: PointcutCoordinate): String =
        "$ROOT/${pc.symbol.owner}/${pc.symbol.methodName}/${pc.kind.name}/${pc.bytecodeOffset}"
    
    /** Node value as Confix-compatible JSON Map */
    fun nodeVal(pc: PointcutCoordinate): Map<String, Any> = mapOf(
        "kind" to pc.kind.name,
        "sourceFile" to pc.source.sourceFile,
        "line" to pc.source.line,
        "column" to pc.source.column,
        "language" to pc.source.language,
        "bytecodeOffset" to pc.source.bytecodeOffset,
        "owner" to pc.symbol.owner,
        "name" to pc.symbol.name,
        "descriptor" to pc.symbol.descriptor,
        "methodName" to pc.symbol.methodName,
        "methodDescriptor" to pc.symbol.methodDescriptor,
        "jvmOpcode" to pc.jvmOpcode,
        "facet" to 1L,
    )
}

// Use the underlying MetaSeries representation directly to avoid type inference issues
typealias MetaSeries<I, T> = LibJoin<I, (I) -> T>
typealias Series<T> = MetaSeries<Int, T>

/** Constructs a Join<A,B> from two values */
inline fun <A, B> mkJoin(a: A, b: B): LibJoin<A, B> = object : LibJoin<A, B> {
    override val a: A get() = a
    override val b: B get() = b
}

/** Constructs a Series<T> from size and index function */
fun <T> mkSeries(size: Int, oracle: (Int) -> T): Series<T> =
    mkJoin(size, oracle)

/** Get element from Series — using the oracle directly to avoid extension conflicts */
inline fun <T> getAt(series: Series<T>, i: Int): T = series.b(i)

/** Facet projection — returns new Series filtered by facet mask */
fun <T> withFacet(series: Series<T>, mask: Long, facetSelector: (T) -> Long): Series<T> = 
    mkSeries(series.a) { i ->
        val t = getAt(series, i)
        require((facetSelector(t) and mask) != 0L) { "facet mask mismatch" }
        t
    }

/** LCNC mode dispatch — new Series with per-element transform */
fun <T, R> inMode(series: Series<T>, mode: LCNCModeFacet, transform: (T) -> R): Series<R> =
    mkSeries(series.a) { i -> transform(getAt(series, i)) }

/** Tag each element with facet — returns Series<Join<T, SlabFacet>> */
fun <T> tagged(series: Series<T>, facet: SlabFacet): Series<LibJoin<T, SlabFacet>> =
    mkSeries(series.a) { i -> mkJoin(getAt(series, i), facet) }

/**
 * ChildRowVec — lazy composition over blackboard children
 * Materializes only on demand
 */
class ChildRowVec<T>(
    private val source: () -> Series<T>,
    private val facetSelector: (T) -> Long = { 0L }
) {
    operator fun get(index: Int): T = getAt(source(), index)
    val size: Int get() = source().a
    
    /** Filter by facet — returns new ChildRowVec, no materialization */
    fun withFacet(mask: Long): ChildRowVec<T> = ChildRowVec(source, facetSelector)
    
    /** Map with LCNC mode — lazy transform */
    fun <R> inMode(mode: LCNCModeFacet, transform: (T) -> R): Series<R> =
        inMode(source(), mode, transform)
    
    /** Materialize only when forced */
    fun materialize(): Series<T> = source()
}

/** Create ChildRowVec from blackboard path (stub for commonMain) */
fun childRowVec(path: String): ChildRowVec<Map<String, Any>> = TODO("confix blackboard integration")