package borg.trikeshed.narsese

/** A sampled-entry summary, not an atomic snapshot, occupancy count, or bag history. */
data class BeliefDiagnostics(
    val snapshotEntries: Int,
    val coordinates: Int,
    val relations: Map<RelationKind, Int>,
    val positiveOnly: Int,
    val negativeOnly: Int,
    val mixed: Int,
    val noEvidence: Int,
    /** Minimum, lower median, and maximum NAL confidence over signals with evidence. */
    val confidence: ConfidenceRange?,
    /** Null means derivation receipts were not supplied, not that no derivations occurred. */
    val derivations: DerivationCounts?,
)

data class ConfidenceRange(val min: Float, val median: Float, val max: Float)

/** Distinct receipt identities by rule; duplicate receipts count once. */
data class DerivationCounts(val distinct: Int, val duplicates: Int, val rules: Map<RuleIdentity, Int>)

/**
 * Inspect supplied snapshot entries and, when available, separately supplied derivation receipts.
 * Evidence is interpreted in [Nal.UNIT] units, as in [Nal.truthOf]. Coordinates are
 * not proposition identities: collisions, lost map entries, and previously evicted beliefs
 * are not visible. Bag traversal is not an atomic point-in-time snapshot; concurrent writes
 * can affect the sampled entries. Receipt counts do not imply those receipts produced them.
 */
fun beliefDiagnostics(
    signals: Iterable<SemanticSignal>,
    receipts: Iterable<DerivationReceipt>? = null,
): BeliefDiagnostics {
    var snapshotEntries = 0
    var positiveOnly = 0
    var negativeOnly = 0
    var mixed = 0
    var noEvidence = 0
    val coordinates = HashSet<Long>()
    val relations = LinkedHashMap<RelationKind, Int>()
    val confidence = ArrayList<Float>()
    for (signal in signals) {
        snapshotEntries++
        coordinates.add(signal.angular)
        relations[signal.relation] = (relations[signal.relation] ?: 0) + 1
        val evidence = signal.evidence
        when {
            evidence.total == 0L -> noEvidence++
            evidence.negative == 0L -> positiveOnly++
            evidence.positive == 0L -> negativeOnly++
            else -> mixed++
        }
        if (evidence.total != 0L) confidence.add(Nal.truthOf(evidence).confidence)
    }
    confidence.sort()
    val confidenceRange = if (confidence.isEmpty()) null else ConfidenceRange(
        confidence.first(), confidence[(confidence.size - 1) / 2], confidence.last(),
    )
    val derivations = receipts?.let { source ->
        val seen = HashSet<String>()
        val rules = LinkedHashMap<RuleIdentity, Int>()
        var duplicates = 0
        for (receipt in source) {
            if (!seen.add(receipt.canonicalCid.value)) {
                duplicates++
            } else {
                rules[receipt.ruleId] = (rules[receipt.ruleId] ?: 0) + 1
            }
        }
        DerivationCounts(seen.size, duplicates, rules)
    }
    return BeliefDiagnostics(snapshotEntries, coordinates.size, relations, positiveOnly, negativeOnly, mixed,
        noEvidence, confidenceRange, derivations)
}
