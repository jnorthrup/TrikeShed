package borg.trikeshed.lcnc.reduction

import borg.trikeshed.lib.*

/**
 * Result with intermediate stage outputs.
 */
data class ReductionResult<Out>(
    val output: Out,
    val stageOutputs: Map<ReductionPhase, Any>  // phase → intermediate carrier
)

/**
 * Unified reduction pipeline parameterized by four algebras.
 *
 * @param K   Key type (composite for hierarchy, hash for CRMS)
 * @param V   Input value type (raw row, byte, TraceEvent)
 * @param Acc Accumulator type (reduced value, partial tree, ConflictCell)
 * @param Out Final output type (CascadeOutputRow, Cursor, List<ConflictCell>)
 */
interface LcncReduction<K : Any, V : Any, Acc : Any, Out : Any> {

    /** Key algebra — extraction, hierarchy, ordering. */
    val keyAlg: KeyAlg<K>

    /** Value algebra — fold, merge, builtin reducers. */
    val valueAlg: ValueAlg<V, Acc>

    /** Phase algebra — allowed stages and transitions. */
    val phaseAlg: PhaseAlg

    /** Carrier algebra — abstract over Series/Ring/Array/Cursor. */
    val carrierAlg: CarrierAlg<V>

    /** Execute reduction on a carrier. Accepts any carrier (cast internally to [V]). */
    fun execute(input: ReductionCarrier<*>): Out

    /** Execute with phase checkpoints for inspection/debugging. */
    fun executeWithCheckpoints(input: ReductionCarrier<*>): ReductionResult<Out>
}

/**
 * Default execution template using the four algebras.
 * Implementations can override execute/executeWithCheckpoints for custom logic.
 */
abstract class AbstractLcncReduction<K : Any, V : Any, Acc : Any, Out : Any>(
    override val keyAlg: KeyAlg<K>,
    override val valueAlg: ValueAlg<V, Acc>,
    override val phaseAlg: PhaseAlg,
    override val carrierAlg: CarrierAlg<V>
) : LcncReduction<K, V, Acc, Out> {

    @Suppress("UNCHECKED_CAST")
    override fun execute(input: ReductionCarrier<*>): Out {
        val typed = input as ReductionCarrier<V>
        return executeWithCheckpoints(typed).output
    }

    @Suppress("UNCHECKED_CAST")
    override fun executeWithCheckpoints(input: ReductionCarrier<*>): ReductionResult<Out> {
        val typedInput = input as ReductionCarrier<V>
        val stageOutputs = mutableMapOf<ReductionPhase, Any>()

        // Phase 1: MAP — extract key + value
        val mapped = mapPhase(typedInput)
        stageOutputs[ReductionPhase.MAP] = mapped

        // Phase 2: REDUCE — groupBy key → fold values
        val reduced = reducePhase(mapped)
        stageOutputs[ReductionPhase.REDUCE] = reduced

        // Phase 3: REREDUCE — merge partials if needed (Forge)
        val rereduced = rereducePhase(reduced)
        if (rereduced !== reduced) {
            stageOutputs[ReductionPhase.REREDUCE] = rereduced
        }

        // Final output formatting
        val output = formatOutput(rereduced)

        return ReductionResult(output, stageOutputs)
    }

    /** Phase MAP: extract key and pair with value. Override for custom mapping. */
    protected open fun mapPhase(input: ReductionCarrier<V>): ReductionCarrier<Join<K, V>> {
        return input.map { v -> keyAlg.extractor.extract(v) j v }
    }

    /** Phase REDUCE: groupBy key and fold values. Override for custom reduction. */
    protected open fun reducePhase(mapped: ReductionCarrier<Join<K, V>>): ReductionCarrier<Join<K, Acc>> {
        val grouped = mapped.groupBy({ it.a }) { it.b }
        val reduced = grouped.map { (key, carrier) ->
            key j carrier.fold(valueAlg.initial, valueAlg.folder)
        }
        return SeriesCarrier(reduced.size j { i -> reduced[i] })
    }

    /** Phase REREDUCE: merge partial accumulators. Override for custom rereduce. */
    protected open fun rereducePhase(reduced: ReductionCarrier<Join<K, Acc>>): ReductionCarrier<Join<K, Acc>> = reduced

    /** Format final output. Must be implemented by concrete reduction. */
    protected abstract fun formatOutput(reduced: Any): Out
}
