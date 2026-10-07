package borg.trikeshed.narsese

import kotlin.math.abs

/**
 * Which unresolved sense reads are worth a question to Jev. A read the caps cannot answer ([SenseRow.production] is -1)
 * gets a score, and it is asked when the score reaches the run's bound (set by its ask budget).
 *
 * The score is pressure × nearness:
 * - pressure P: an eligibility trace over the (lemma, locality)'s unresolved reads since its last ask: each unresolved
 *   read [charge]s it by 1 after it decays by exp(-gap/[TAU]) over the corpus reads since the previous one; an ask
 *   resets it. Measured on the ask eval, a trace that forgets beats a leakless push on every test cell.
 * - nearness: 1/(1 + |z|) for the row's leading class ([SenseRow.margin]), 1 for a word with no row, so a row near a
 *   cap's edge, where one answer can settle it, is asked first.
 */
object AskPolicy {
    fun nearness(row: SenseRow?): Double {
        if (row == null) return 1.0
        val t = row.top
        return if (t < 0) 1.0 else 1.0 / (1.0 + abs(row.margin(t)))
    }

    /** Reads over which unresolved pressure forgets by 1/e (chosen on train cells, hillclimb/asks round 2). */
    const val TAU = 5_000.0

    /** The pressure after one more unresolved read, [gap] corpus reads after the context's previous one. */
    fun charge(pressure: Double, gap: Long): Double = pressure * kotlin.math.exp(-gap / TAU) + 1.0

    /** The score of an unresolved read carrying [pressure]. */
    fun score(pressure: Double, row: SenseRow?): Double = pressure * nearness(row)
}
