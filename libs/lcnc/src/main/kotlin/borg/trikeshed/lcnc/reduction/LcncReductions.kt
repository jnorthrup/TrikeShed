package borg.trikeshed.lcnc.reduction

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

/**
 * Pre-configured LcncReduction instances for each system.
 */
object LcncReductions {

    // ── Forge OperationalCascade as LcncReduction ─────────────────

    data class CascadeOutputRow(val key: List<String>, val accumulator: MultiMetricAccumulator)

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
            override val extractor: KeyExtractor<Any, List<String>> = KeyExtractor { input ->
                val map = input as? Map<String, Any> ?: emptyMap()
                keyHierarchy.map { (map[it] as? String) ?: "" }
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
            override fun rereducePhase(reduced: ReductionCarrier<Join<List<String>, MultiMetricAccumulator>>): ReductionCarrier<Join<List<String>, MultiMetricAccumulator>> {
                // Merge all partials into a single accumulator per key
                val grouped = reduced.groupBy({ it.a }) { it.b }
                val merged = grouped.map { (key, carrier) ->
                    val partials = carrier.size j { i -> carrier[i] }
                    key j valueAlg.merger.merge(partials)
                }
                return SeriesCarrier(merged.size j { i -> merged[i] })
            }

            override fun formatOutput(reduced: Any): List<CascadeOutputRow> {
                @Suppress("UNCHECKED_CAST")
                val carrier = reduced as ReductionCarrier<Join<List<String>, MultiMetricAccumulator>>
                return carrier.map { (key, acc) -> CascadeOutputRow(key, acc) }.toList()
            }
        }
    }

    // ── Confix Parser as LcncReduction ────────────────────────────

    /**
     * Confix parser reduction (placeholder pipeline — scan0/buildTree live in the Confix
     * parser; here we expose the reduction shape so it composes with the lcnc axis).
     */
    @Suppress("UNCHECKED_CAST")
    fun confixParse(): LcncReduction<ConfixStructuralKey, Byte, TreeBuilderState, Cursor> {
        val keyAlg = object : KeyAlg<ConfixStructuralKey> {
            override val extractor: KeyExtractor<Any, ConfixStructuralKey> =
                KeyExtractor { ConfixStructuralKey(0, 0, 0) }
            override val hierarchy: KeyHierarchy<ConfixStructuralKey> = LcncKeyAlg.confixStructuralKey()
            override val order: KeyOrder<ConfixStructuralKey> = object : KeyOrder<ConfixStructuralKey> {
                override fun compare(a: ConfixStructuralKey, b: ConfixStructuralKey): Int =
                    a.depth.compareTo(b.depth).let { if (it != 0) it else a.open.compareTo(b.open) }
            }
        }

        val valueAlg = object : ValueAlg<Byte, TreeBuilderState> {
            override val folder: Folder<Byte, TreeBuilderState> = Folder { acc, _ -> acc }
            override val merger: Merger<TreeBuilderState> = Merger { partials -> partials[0] }
            override val initial: TreeBuilderState = TreeBuilderState()
        }

        val phaseAlg = LcncPhaseAlg.confixPhaseAlg
        val carrierAlg = LcncCarrierAlg.seriesCarrierAlg()

        return object : AbstractLcncReduction<ConfixStructuralKey, Byte, TreeBuilderState, Cursor>(
            keyAlg, valueAlg, phaseAlg, carrierAlg
        ) {
            override fun mapPhase(input: ReductionCarrier<Byte>): ReductionCarrier<Join<ConfixStructuralKey, Byte>> =
                input.map { b -> ConfixStructuralKey(0, 0, 0) j b }

            override fun reducePhase(mapped: ReductionCarrier<Join<ConfixStructuralKey, Byte>>): ReductionCarrier<Join<ConfixStructuralKey, TreeBuilderState>> {
                val grouped = mapped.groupBy({ it.a }) { it.b }
                val reduced = grouped.map { (k, carrier) ->
                    k j carrier.fold(TreeBuilderState()) { acc, _ -> acc }
                }
                return SeriesCarrier(reduced.size j { i -> reduced[i] })
            }

            override fun formatOutput(reduced: Any): Cursor = emptySeriesOf()
        }
    }

    // ── CRMS Fold as LcncReduction ────────────────────────────────

    /**
     * CRMS fold reduction.
     * Key: Int (callsiteHash over methodIdx+siteIdx — opcode distinguishes BEFORE/AFTER within a callsite)
     * Value: TraceEvent
     * Acc: ConflictCell
     * Out: List<ConflictCell>
     */
    @Suppress("UNCHECKED_CAST")
    fun crmsFold(): LcncReduction<Int, TraceEvent, ConflictCell, List<ConflictCell>> {
        // Callsite key = FNV-1a over (methodIdx, siteIdx) — groups BEFORE+AFTER of the same callsite.
        val callsiteExtractor: KeyExtractor<Any, Int> = KeyExtractor { input ->
            val e = input as TraceEvent
            var hash = -2128831035  // 0x811c9dc5
            hash = (hash xor e.methodIdx) * 0x01000193
            hash = (hash xor e.siteIdx) * 0x01000193
            hash
        }
        val keyAlg = object : KeyAlg<Int> {
            override val extractor: KeyExtractor<Any, Int> = callsiteExtractor
            override val hierarchy: KeyHierarchy<Int> = object : KeyHierarchy<Int> {
                override val levels: List<KeyExtractor<Any, Int>> = listOf(callsiteExtractor)
                override fun compositeKey(input: Any): List<Int> = listOf(callsiteExtractor.extract(input))
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
                    is RingSeries<*> -> LcncCarrierAlg.ringCarrier(input as Series<TraceEvent>, 2048)
                    else -> throw IllegalArgumentException("CRMS expects Array or RingSeries carrier")
                }
            }
        }

        return object : AbstractLcncReduction<Int, TraceEvent, ConflictCell, List<ConflictCell>>(
            keyAlg, valueAlg, phaseAlg, carrierAlg
        ) {
            override fun reducePhase(mapped: ReductionCarrier<Join<Int, TraceEvent>>): ReductionCarrier<Join<Int, ConflictCell>> {
                val grouped = mapped.groupBy({ it.a }) { it.b }
                val reduced = grouped.map { (hash, carrier) ->
                    hash j carrier.fold(ConflictCell.init(), valueAlg.folder)
                }
                return SeriesCarrier(reduced.size j { i -> reduced[i] })
            }

            override fun rereducePhase(reduced: ReductionCarrier<Join<Int, ConflictCell>>): ReductionCarrier<Join<Int, ConflictCell>> {
                // CRMS: eigsort by depth desc
                val sorted = reduced.toList().sortedByDescending { it.b.depth }
                return SeriesCarrier(sorted.size j { i -> sorted[i] })
            }

            override fun formatOutput(reduced: Any): List<ConflictCell> {
                val carrier = reduced as ReductionCarrier<Join<Int, ConflictCell>>
                return carrier.map { it.b }.toList()
            }
        }
    }
}
