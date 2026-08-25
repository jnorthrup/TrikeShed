package borg.trikeshed.narsese

/**
 * NAL truth functions, expressed in the EVIDENCE domain: [EvidenceCoord] is the
 * permanent storage, [TruthCoord] is derived (the invariant recorded in
 * TruthCoord.kt). Derived weights are fractional, so all outputs use one global
 * fixed-point scale: **1 observation = [UNIT] milli-evidence**. `observe` mints
 * exactly one unit; strong/weak rule outputs scale their fractional weights by
 * the same UNIT so `revise` (plain addition) composes them soundly. The floor
 * is 1 milli-unit — weights below 1e-3 of an observation vanish; that is the
 * documented precision floor, not a bug.
 *
 * Authority ladder (see plan): machine observation = 1×UNIT, user assertion =
 * 1000×UNIT, guest self-assertion clamped to 1×UNIT at the pen.
 */
object Nal {

    /** One observation in milli-evidence units. */
    const val UNIT: Long = 1000L

    /** User-asserted evidence: three orders over a machine observation. */
    const val USER_UNIT: Long = 1000L * UNIT

    /**
     * Deduction (strong): M→P `t1`, S→M `t2` ⊢ S→P.
     * Truth-domain f = f1·f2, c = f1·f2·c1·c2 — converted to evidence via the
     * horizon inversion w = k·c/(1−c), split by f.
     */
    fun deduce(t1: TruthCoord, t2: TruthCoord, k: Float = 1f): EvidenceCoord {
        val f = t1.frequency * t2.frequency
        val c = t1.frequency * t2.frequency * t1.confidence * t2.confidence
        return fromTruth(f, c, k)
    }

    /**
     * Induction (weak): M→P `t1`, M→S `t2` ⊢ S→P.
     * Evidence-domain: w+ = f1·f2·c1·c2, w = f2·c1·c2 (per-observation), scaled by UNIT.
     */
    fun induce(t1: TruthCoord, t2: TruthCoord): EvidenceCoord {
        val wPlus = t1.frequency * t2.frequency * t1.confidence * t2.confidence
        val w = t2.frequency * t1.confidence * t2.confidence
        return fromWeights(wPlus, w)
    }

    /**
     * Abduction (weak): P→M `t1`, S→M `t2` ⊢ S→P.
     * Evidence-domain: w+ = f1·f2·c1·c2, w = f1·c1·c2, scaled by UNIT.
     */
    fun abduce(t1: TruthCoord, t2: TruthCoord): EvidenceCoord {
        val wPlus = t1.frequency * t2.frequency * t1.confidence * t2.confidence
        val w = t1.frequency * t1.confidence * t2.confidence
        return fromWeights(wPlus, w)
    }

    /** Single-event observation induction: one outcome, one unit of evidence. */
    fun observe(success: Boolean): EvidenceCoord =
        if (success) EvidenceCoord(UNIT, 0L) else EvidenceCoord(0L, UNIT)

    /** Horizon inversion: recover evidence weights from a derived truth value. */
    fun weights(t: TruthCoord, k: Float = 1f): EvidenceCoord =
        fromTruth(t.frequency, t.confidence, k)

    /**
     * Truth of milli-scaled evidence with the horizon expressed in OBSERVATION
     * units (k=1 observation, not 1 milli-unit). `EvidenceCoord.truth()` with its
     * default treats raw counts as observations; on Nal-minted evidence use this.
     */
    fun truthOf(e: EvidenceCoord, k: Float = 1f): TruthCoord =
        TruthCoord.fromEvidence(e.positive, e.negative, k * UNIT)

    private fun fromTruth(f: Float, c: Float, k: Float): EvidenceCoord {
        val cc = c.coerceIn(0f, MAX_CONFIDENCE)
        val w = k * cc / (1f - cc)
        return fromWeights(f * w, w)
    }

    private fun fromWeights(wPlus: Float, w: Float): EvidenceCoord {
        val total = (w.coerceAtLeast(0f) * UNIT).toLong()
        val positive = (wPlus.coerceIn(0f, w) * UNIT).toLong().coerceAtMost(total)
        return EvidenceCoord(positive, total - positive)
    }

    /** c=1 is unreachable in NARS (confidence never saturates); cap the inversion. */
    private const val MAX_CONFIDENCE = 0.9999f
}
