package borg.trikeshed.narsese

/**
 * TruthCoord — NARS truth value packed in a Long.
 *
 * Layout: 32 bits frequency (fixed-point, 2^32−1 scale) in the high word,
 * 32 bits confidence in the low word. Storage IS the packed form.
 *
 * NARS semantics: frequency f = w+/(w+ + w−), confidence c = w/(w + k) where
 * w = w+ + w− is total evidence and k is the evidential horizon (default 1).
 * Confidence is sublinear in evidence and never saturates to 1.0 — the
 * property Semantica's LLM self-reported confidences lack entirely.
 */
@JvmInline
value class TruthCoord(val packed: Long) {

    val frequency: Float get() = ((packed ushr 32) and SCALE).toFloat() / SCALE
    val confidence: Float get() = (packed and SCALE).toFloat() / SCALE

    /** Truth expectation: the value NARS uses for choice among competing beliefs. */
    fun expectation(): Float = confidence * (frequency - 0.5f) + 0.5f

    companion object {
        const val SCALE: Long = 0xFFFFFFFFL

        operator fun invoke(frequency: Float, confidence: Float): TruthCoord {
            val fBits = (frequency.coerceIn(0f, 1f) * SCALE).toLong()
            val cBits = (confidence.coerceIn(0f, 1f) * SCALE).toLong()
            return TruthCoord((fBits shl 32) or cBits)
        }

        /** Construct from evidence counts. k = evidential horizon (NARS default 1). */
        fun fromEvidence(positive: Long, negative: Long, k: Float = 1f): TruthCoord {
            val w = (positive + negative).toFloat()
            if (w <= 0f) return TruthCoord(0L)
            val f = positive.toFloat() / w
            val c = w / (w + k)
            return invoke(f, c)
        }
    }
}

/**
 * EvidenceCoord — raw evidence counts packed in a Long (32 bits w+ high,
 * 32 bits w− low). Evidence is the permanent record; TruthCoord is derived.
 * This is the NARS invariant the old ManifoldConcept violated by decaying q:
 * evidence never decays; only attention does.
 */
@JvmInline
value class EvidenceCoord(val packed: Long) {

    val positive: Long get() = (packed ushr 32) and 0xFFFFFFFFL
    val negative: Long get() = packed and 0xFFFFFFFFL

    val total: Long get() = positive + negative

    fun truth(k: Float = 1f): TruthCoord = TruthCoord.fromEvidence(positive, negative, k)

    companion object {
        operator fun invoke(positive: Long, negative: Long): EvidenceCoord =
            EvidenceCoord((positive shl 32) or negative)

        val EMPTY: EvidenceCoord = EvidenceCoord(0L)
    }
}

/**
 * NARS revision: merge two independent evidence bases. Pure function — this
 * is the operation that makes belief evolution a supersede receipt instead
 * of a mutation. w+ = w1+ + w2+, w− = w1− + w2−.
 */
fun revise(a: EvidenceCoord, b: EvidenceCoord): EvidenceCoord =
    EvidenceCoord(a.positive + b.positive, a.negative + b.negative)
