package borg.trikeshed.lcnc.reduction

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.lcnc.LcncKeyAlg.*
import borg.trikeshed.lcnc.LcncValueAlg.*

/**
 * Pre-configured LcncReduction instances for each system.
 */
object LcncReductions {

    // ── Forge OperationalCascade as LcncReduction ─────────────────

    /**
     * Forge OperationalCascade reduction.
     * Key: List<String> (composite from keyHierarchy columns)
     * Value: Map<String, Any> (raw JSON row)
     * Acc: MultiMetricAccumulator
     * Out: List<CascadeOutputRow>
     */
    fun forgeCascade(
        keyHierarchy: List<String>,
        metrics: List<Pair<String, BuiltinReducer>>
    ): LcncReduction<List<String>, Map<String, Any>, MultiMetricAccumulator, List<CascadeOutputRow>> {
        val keyAlg = object : KeyAlg<List<String>> {
            override val extractor: KeyExtractor<Map<String, Any>, List<String>> = object : KeyExtractor<Map<String, Any>, List<String>> {
                override fun extract(input: Map<String, Any>): List<String> =
                    keyHierarchy.map { (input[it] as? String) ?: "" }
            }
            override val hierarchy: KeyHierarchy<List<String>> = LcncKeyAlg.forgeKeyHierarchy(keyHierarchy)
            override val order: KeyOrder<List<String>> = object : KeyOrder<List<String>> {
                override fun compare(a: List<String>, b: List<String>): Int {
                    for (i in 0 until maxOf(a.size, b.size)) {
                        val av = a.getOrElse(i) { "" }
                        val bv = b.getOrElse(i) { "" }
                        val c = av.compareTo(bv)
                        if (c != 0) return c
                    }
                    return 0
                }
            }
        }

        val valueAlg = object : ValueAlg<Map<String, Any>, MultiMetricAccumulator> {
            override val folder: Folder<Map<String, Any>, MultiMetricAccumulator> = LcncValueAlg.forgeMultiMetricReducer(metrics)
            override val merger: Merger<MultiMetricAccumulator> = LcncValueAlg.forgeMerger()
            override val initial: MultiMetricAccumulator = LcncValueAlg.emptyMultiMetricAccumulator()
        }

        val phaseAlg = LcncPhaseAlg.forgePhaseAlg
        val carrierAlg = LcncCarrierAlg.seriesCarrierAlg()

        return object : AbstractLcncReduction<List<String>, Map<String, Any>, MultiMetricAccumulator, List<CascadeOutputRow>>(
            keyAlg, valueAlg, phaseAlg, carrierAlg
        ) {
            override protected fun rereducePhase(reduced: ReductionCarrier<Join<List<String>, MultiMetricAccumulator>>): ReductionCarrier<Join<List<String>, MultiMetricAccumulator>> {
                // Merge all partials into single accumulator per key
                val grouped = reduced.groupBy({ it.a }) { it.b }
                val merged = grouped.mapValues { (_, carrier) ->
                    carrier.fold(valueAlg.initial, valueAlg.merger::merge)
                }
                return merged.toSeriesCarrier()
            }

            override protected fun formatOutput(reduced: Any): List<CascadeOutputRow> {
                val carrier = reduced as ReductionCarrier<Join<List<String>, MultiMetricAccumulator>>
                return carrier.map { (key, acc) -> CascadeOutputRow(key, acc) }.toList()
            }
        }
    }

    data class CascadeOutputRow(val key: List<String>, val accumulator: MultiMetricAccumulator)

    // ── Confix Parser as LcncReduction ────────────────────────────

    /**
     * Confix parser reduction.
     * Key: ConfixStructuralKey (depth, open, close)
     * Value: Byte (raw input)
     * Acc: TreeBuilderState
     * Out: Cursor
     */
    fun confixParse(): LcncReduction<ConfixStructuralKey, Byte, TreeBuilderState, Cursor> {
        val keyAlg = object : KeyAlg<ConfixStructuralKey> {
            override val extractor: KeyExtractor<Byte, ConfixStructuralKey> = object : KeyExtractor<Byte, ConfixStructuralKey> {
                override fun extract(input: Byte): ConfixStructuralKey {
                    // This is a placeholder — actual scan0 produces SpanEvents
                    return ConfixStructuralKey(0, 0, 0)
                }
            }
            override val hierarchy: KeyHierarchy<ConfixStructuralKey> = LcncKeyAlg.confixStructuralKey()
            override val order: KeyOrder<ConfixStructuralKey> = object : KeyOrder<ConfixStructuralKey> {
                override fun compare(a: ConfixStructuralKey, b: ConfixStructuralKey): Int =
                    a.depth.compareTo(b.depth).let { if (it != 0) it else a.open.compareTo(b.open) }
            }
        }

        val valueAlg = object : ValueAlg<Byte, TreeBuilderState> {
            override val folder: Folder<Byte, TreeBuilderState> = object : Folder<Byte, TreeBuilderState> {
                override fun fold(acc: TreeBuilderState, input: Byte): TreeBuilderState {
                    // Placeholder — actual scan0 produces SpanEvents
                    return acc
                }
            }
            override val merger: Merger<TreeBuilderState> = object : Merger<TreeBuilderState> {
                override fun merge(partials: Series<TreeBuilderState>): TreeBuilderState {
                    // Tree merging not needed for Confix (single-pass)
                    return partials[0]
                }
            }
            override val initial: TreeBuilderState = TreeBuilderState()
        }

        val phaseAlg = LcncPhaseAlg.confixPhaseAlg
        val carrierAlg = LcncCarrierAlg.seriesCarrierAlg()

        return object : AbstractLcncReduction<ConfixStructuralKey, Byte, TreeBuilderState, Cursor>(
            keyAlg, valueAlg, phaseAlg, carrierAlg
        ) {
            override protected fun mapPhase(input: ReductionCarrier<Byte>): ReductionCarrier<Join<ConfixStructuralKey, Byte>> {
                // scan0 phase: token recognition
                val tokens = scan0(input)
                return tokens.map { span -> span.j(span.span.start.toByte()) }  // placeholder
            }

            override protected fun reducePhase(mapped: ReductionCarrier<Join<ConfixStructuralKey, Byte>>): ReductionCarrier<Join<ConfixStructuralKey, TreeBuilderState>> {
                // buildTree phase: fold spans → TreeBuilderState
                val treeState = mapped.fold(TreeBuilderState()) { acc, join ->
                    // Convert join to SpanEvent and fold
                    acc // placeholder
                }
                return SeriesCarrier(1 j { _ -> ConfixStructuralKey(0,0,0) j treeState })
            }

            override protected fun formatOutput(reduced: Any): Cursor {
                val state = (reduced as ReductionCarrier<Join<ConfixStructuralKey, TreeBuilderState>>)[0].b
                return buildTree(state)
            }
        }
    }

    /** Placeholder scan0 — actual implementation in Confix parser. */
    private fun scan0(input: ReductionCarrier<Byte>): ReductionCarrier<SpanEvent> =
        SeriesCarrier(emptySeriesOf())

    /** Placeholder buildTree — actual implementation in Confix parser. */
    private fun buildTree(state: TreeBuilderState): Cursor = emptySeriesOf()

    // ── CRMS Fold as LcncReduction ────────────────────────────────

    /**
     * CRMS fold reduction.
     * Key: Int (callsiteHash)
     * Value: TraceEvent
     * Acc: ConflictCell
     * Out: List<ConflictCell>
     */
    fun crmsFold(): LcncReduction<Int, TraceEvent, ConflictCell, List<ConflictCell>> {
        val keyAlg = object : KeyAlg<Int> {
            override val extractor: KeyExtractor<TraceEvent, Int> = LcncKeyAlg.crmsCallsiteHash()
            override val hierarchy: KeyHierarchy<Int> = object : KeyHierarchy<Int> {
                override val levels: List<KeyExtractor<*, Int>> = listOf(keyAlg.extractor)
                override fun compositeKey(input: Any): List<Int> = listOf(keyAlg.extractor.extract(input as TraceEvent))
                override fun prefix(key: List<Int>, depth: Int): List<Int> = key.take(minOf(depth, key.size))
            }
            override val order: KeyOrder<Int> = LcncKeyAlg.naturalKeyOrder()
        }

        val valueAlg = object : ValueAlg<TraceEvent, ConflictCell> {
            override val folder: Folder<TraceEvent, ConflictCell> = LcncValueAlg.crmsPairAndEigsort()
            override val merger: Merger<ConflictCell> = LcncValueAlg.crmsMerger()
            override val initial: ConflictCell = ConflictCell.init()
        }

        val phaseAlg = LcncPhaseAlg.crmsPhaseAlg
        val carrierAlg = object : CarrierAlg<TraceEvent> {
            override val carrier: (Any) -> ReductionCarrier<TraceEvent> = { input ->
                when (input) {
                    is Array<*> -> LcncCarrierAlg.arrayCarrier(input as Array<TraceEvent>)
                    is RingSeries<*> -> LcncCarrierAlg.ringCarrier(input as RingSeries<TraceEvent>, 2048)
                    else -> throw IllegalArgumentException("CRMS expects Array or RingSeries carrier")
                }
            }
        }

        return object : AbstractLcncReduction<Int, TraceEvent, ConflictCell, List<ConflictCell>>(
            keyAlg, valueAlg, phaseAlg, carrierAlg
        ) {
            override protected fun reducePhase(mapped: ReductionCarrier<Join<Int, TraceEvent>>): ReductionCarrier<Join<Int, ConflictCell>> {
                val grouped = mapped.groupBy({ it.a }) { it.b }
                return grouped.map { (hash, carrier) ->
                    hash j carrier.fold(ConflictCell.init(), valueAlg.folder)
                }.toSeriesCarrier()
            }

            override protected fun rereducePhase(reduced: ReductionCarrier<Join<Int, ConflictCell>>): ReductionCarrier<Join<Int, ConflictCell>> {
                // CRMS: eigsort by depth desc
                val sorted = reduced.toList().sortedByDescending { it.b.depth }
                return SeriesCarrier(sorted.size j { i -> sorted[i] })
            }

            override protected fun formatOutput(reduced: Any): List<ConflictCell> {
                val carrier = reduced as ReductionCarrier<Join<Int, ConflictCell>>
                return carrier.map { it.b }.toList()
            }
        }
    }
}

/** Placeholder for RingSeries. */
interface RingSeries<T> {
    val head: Int
    val count: Int
    operator fun get(index: Int): T
}