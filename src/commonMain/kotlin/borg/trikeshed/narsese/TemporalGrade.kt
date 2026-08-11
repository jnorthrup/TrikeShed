package borg.trikeshed.narsese

/**
 * TemporalGrade — Semantica's temporal-confidence calibration rubric
 * (methods.py:1846-1855) ported as a weight on evidence.
 *
 * The rubric grades a temporal signal's precision; in NARS terms it scales
 * the evidence contribution of a temporally-qualified statement. A full ISO
 * date is strong evidence (w = 1.0); a vague relative ("recently") is weak
 * (w = 0.35); no temporal signal contributes nothing.
 *
 * Each grade also carries the NAL temporal copula it implies: dated signals
 * are predictive (=/>, something became true at a time), absent signals are
 * atemporal inheritance (-->).
 */
enum class TemporalGrade(val weight: Float, val description: String) {
    /** Full ISO date: "2022-03-15", "March 15, 2022" */
    ISO_DATE(1.00f, "full ISO date"),

    /** Explicit year + month: "March 2022", "2022-03" */
    YEAR_MONTH(0.90f, "explicit year+month"),

    /** Explicit year only: "in 2022", "since 2021", "from 2019" */
    YEAR(0.85f, "explicit year"),

    /** Quarter: "Q3 2023", "Q2 2021" */
    QUARTER(0.75f, "quarter"),

    /** Named season or approximate range: "summer 2022", "early 2020s" */
    SEASON(0.65f, "season or approximate range"),

    /** Vague relative with computable anchor: "last year", "three months ago" */
    RELATIVE(0.50f, "vague relative with computable anchor"),

    /** Highly vague relative: "recently", "years ago", "in the past" */
    VAGUE(0.35f, "highly vague relative"),

    /** No temporal signal present */
    NONE(0.0f, "no temporal signal"),
    ;

    /** NAL copula implied by this grade. */
    val impliedCopula: NalCopula get() =
        if (this == NONE) NalCopula.INHERITANCE else NalCopula.PREDICTIVE_IMPLICATION
}

/**
 * TemporalSignal — a graded temporal extraction bound to a verbatim source span.
 *
 * @param grade the calibration grade
 * @param validFrom ISO 8601 or exact phrase; null when grade == NONE
 * @param validUntil ISO 8601 or exact phrase; null when open-ended
 * @param sourceCid ContentId of the verbatim source span (LineCas-anchored).
 *                  Never a fabricated span: if the extractor can't anchor it,
 *                  the signal is UNANCHORED and sourceCid is null.
 */
data class TemporalSignal(
    val grade: TemporalGrade,
    val validFrom: String? = null,
    val validUntil: String? = null,
    val sourceCid: String? = null,
) {
    val isAnchored: Boolean get() = sourceCid != null

    /** Evidence contribution: the grade weight as positive evidence, 0 negative. */
    fun evidence(): EvidenceCoord =
        EvidenceCoord((grade.weight * 1000).toLong(), 0L)
}
