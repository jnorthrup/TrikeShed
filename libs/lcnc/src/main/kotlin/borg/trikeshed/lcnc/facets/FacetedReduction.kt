package borg.trikeshed.lcnc.facets

import borg.trikeshed.lib.*
import borg.trikeshed.lcnc.reduction.*
import borg.trikeshed.miniduck.v2.FacetedCursor
import borg.trikeshed.usersignals.facets.*
import kotlinx.coroutines.flow.Flow

/**
 * LCNC Reduction Algebra over FacetedSignals.
 * 
 * Wires lcnc's reduction pipeline (ForgeReducers, LcncPhaseAlg, LcncKeyAlg, etc.)
 * over user-signals FacetedSignal facets.
 * 
 * Shape:
 *   FacetedSignal (user-signals) → FacetedCursor (miniduck v2) → ReductionPipeline (lcnc)
 * 
 * Valhalla shapes: inline value classes for reduction carriers, dense twins for keys.
 */
@DslMarker
annotation class ReductionDsl

/**
 * FacetedCursorAdapter — bridges FacetedSignal to FacetedCursor.
 * 
 * Each facet becomes a column in the cursor; rows are signal values.
 */
class FacetedCursorAdapter {
    
    inline fun <reified I : Signal<*>> toCursor(signal: FacetedSignal<I>): FacetedCursor {
        val base = signal.materialize()
        val cursor = FacetedCursor(Cursor.empty())
        
        base.forEach { (key, facetSignal) ->
            cursor.addFacet(key.interned) {
                Facet.Project(columns = listOf(key.interned, "value"))
            }
            // Convert signal sequence to cursor rows
            facetSignal.sequence()
                .mapIndexed { idx, value ->
                    MiniDuckSeries.build(ColumnSchema(
                        listOf(key.interned, "value"),
                        listOf(ColumnType.STRING, ColumnType.JSON)
                    )) {
                        this[key.interned] = facetSignal.signalType
                        this["value"] = value
                    }
                }
                .forEach { row -> cursor.addBlock(Block.mutable().apply { append(row) }.seal()) }
        }
        
        return cursor
    }
}

/**
 * ReductionCarrier for FacetedCursor — wraps lcnc's SeriesCarrier.
 */
inline  class FacetedCursorCarrier(
    val cursor: FacetedCursor,
) : ReductionCarrier<FacetedCursor> {

    override fun zero(): FacetedCursor = FacetedCursor(Cursor.empty())
    
    override fun combine(a: FacetedCursor, b: FacetedCursor): FacetedCursor {
        val merged = FacetedCursor(Cursor.empty())
        a.materialize().forEach { (name, fc) -> merged.addFacet(name, fc) }
        b.materialize().forEach { (name, fc) -> 
            if (merged.facetNames.contains(name)) {
                // Merge facet data
                val existing = merged.facet(name)
                val combined = Existing + fc.toList()
                merged.addBlock(Block.mutable().apply { combined.forEach(::append) }.seal())
            } else {
                merged.addFacet(name, fc)
            }
        }
        return merged
    }
}

/**
 * ForgeReducers over FacetedSignal facets.
 * 
 * Reuses lcnc's ForgeReducer algebra but operates on signal facets.
 */
class FacetForgeReducers {

    /** Sum reducer over a numeric facet. */
    inline fun <reified I : Signal<*>> sumFacet(
        facetKey: FacetKey,
        extractor: (Signal<*>) -> Signal<Number>,
    ): FacetFn<I, Signal<Number>> = FacetFn { signal ->
        val facet = signal.getFacet<Signal<Number>>(facetKey)
        facet?.let { extractor(it).reduce { a, b -> a.toDouble() + b.toDouble() } }
            ?: Signal.Const(0)
    }

    /** Average reducer. */
    inline fun <reified I : Signal<*>> avgFacet(
        facetKey: FacetKey,
        extractor: (Signal<*>) -> Signal<Number>,
    ): FacetFn<I, Signal<Number>> = FacetFn { signal ->
        facetKey("avg") { 
            val facet = signal.getFacet<Signal<Number>>(facetKey)
            if (facet != null) {
                val (sum, count) = extractor(facet).fold(0.0 to 0L) { (s, c), v ->
                    (s + v.toDouble()) to (c + 1)
                }
                if (count > 0) sum / count else 0.0
            } else 0.0
        }
    }

    /** Count reducer. */
    inline fun <reified I : Signal<*>> countFacet(facetKey: FacetKey): FacetFn<I, Signal<Long>> = FacetFn { signal ->
        signal.getFacet<Signal<*>>(facetKey)?.let { it.fold(0L) { c, _ -> c + 1 } } ?: 0L
    }

    /** Min/Max reducer. */
    inline fun <reified I : Signal<*>> minMaxFacet(
        facetKey: FacetKey,
        extractor: (Signal<*>) -> Signal<Comparable<*>>,
    ): FacetFn<I, Signal<Join<Comparable<*>, Comparable<*>>>> = FacetFn { signal ->
        val facet = signal.getFacet<Signal<Comparable<*>>>(facetKey)
        facet?.let { extractor(it).fold(
            Join(Comparable.MAX_VALUE, Comparable.MIN_VALUE)
        ) { (min, max), v ->
            Join(minOf(min, v), maxOf(max, v))
        } } ?: Join(Comparable.MIN_VALUE, Comparable.MAX_VALUE)
    }
}

/**
 * LcncPhaseAlg over FacetedSignal — multi-phase reduction.
 * 
 * Phase 1: Extract key facets
 * Phase 2: Apply ForgeReducers per phase
 * Phase 3: Re-reduce across partitions
 * Phase 4: Materialize result facets
 */
class FacetPhaseAlg<I : Signal<*>>(
    private val phases: List<FacetFn<I, I>>,
) {

    fun addPhase(fn: FacetFn<I, I>): FacetPhaseAlg<I> = FacetPhaseAlg(phases + fn)

    /** Execute all phases sequentially. */
    fun execute(input: I): I = phases.fold(input) { acc, fn -> fn(acc) }

    /** Execute with cursor bridge for large datasets. */
    fun executeCursor(input: I, adapter: FacetedCursorAdapter): FacetedCursor {
        var signal = input
        for (phase in phases) {
            signal = phase(signal)
            val cursor = adapter.toCursor(signal)
            // Process cursor through phase-specific reduction
            signal = processPhaseCursor(cursor, signal)
        }
        return adapter.toCursor(signal)
    }

    private fun processPhaseCursor(cursor: FacetedCursor, signal: I): I = signal
}

/**
 * DSL for building reduction pipelines over facets.
 */
@ReductionDsl
class ReductionPipelineBuilder<I : Signal<*>>(private val input: I) {
    private val phases = mutableListOf<FacetFn<I, I>>()

    inline fun <reified O : Signal<*>> phase(crossinline fn: (I) -> O): ReductionPipelineBuilder<I> {
        phases += fn as FacetFn<I, I>
        return this
    }

    inline fun <reified I : Signal<*>> sumFacet(
        key: FacetKey,
        crossinline extractor: (Signal<*>) -> Signal<Number>,
    ): ReductionPipelineBuilder<I> {
        val reducers = FacetForgeReducers()
        return phase(reducers.sumFacet(key, extractor) as FacetFn<I, I>)
    }

    fun execute(): I = FacetPhaseAlg(phases).execute(input)

    fun executeCursor(adapter: FacetedCursorAdapter): FacetedCursor = FacetPhaseAlg(phases).executeCursor(input, adapter)
}

inline fun <reified I : Signal<*>> reductionPipeline(
    input: I,
    block: ReductionPipelineBuilder<I>.() -> Unit,
): I = ReductionPipelineBuilder(input).apply(block).execute()

inline fun <reified I : Signal<*>> reductionPipelineCursor(
    input: I,
    adapter: FacetedCursorAdapter,
    block: ReductionPipelineBuilder<I>.() -> Unit,
): FacetedCursor = ReductionPipelineBuilder(input).apply(block).executeCursor(adapter)

/**
 * LcncKeyAlg over FacetedSignal — key extraction and grouping.
 */
class FacetKeyAlg<I : Signal<*>>(
    private val keyExtractors: Map<String, (I) -> Signal<*>>,
) {

    /** Group by a facet key. */
    inline fun <reified K> groupBy(key: String, crossinline extract: (I) -> K): Map<K, I> = TODO()

    /** Join two faceted signals on a key. */
    fun join(other: I, thisKey: String, otherKey: String): I = TODO()

    /** Deduplicate by key. */
    fun distinctByKey(key: String): I = TODO()
}

/**
 * LcncValueAlg over FacetedSignal — value aggregation algebra.
 */
class FacetValueAlg<I : Signal<*>>(
    private val valueExtractors: Map<String, (I) -> Signal<Number>>,
) {

    fun sum(key: String): Signal<Number> = TODO()
    fun avg(key: String): Signal<Number> = TODO()
    fun min(key: String): Signal<Number> = TODO()
    fun max(key: String): Signal<Number> = TODO()
    fun variance(key: String): Signal<Number> = TODO()
}