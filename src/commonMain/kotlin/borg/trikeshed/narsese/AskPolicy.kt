package borg.trikeshed.narsese

import kotlin.math.pow

/**
 * Which unresolved sense reads are worth a question to Jev. A read the caps cannot answer ([SenseRow.production] is -1)
 * gets a score, and it is asked when the score reaches the run's bound (set by its ask budget).
 *
 * The score is pressure × nearness:
 * - pressure P: an eligibility trace over the (lemma, locality)'s unresolved reads since its last ask: each unresolved
 *   read [charge]s it by 1 after it decays by exp(-gap/[TAU]) over the corpus reads since the previous one, saturating
 *   at [P_CAP]; an ask resets it.
 * - nearness, for the row's leading class at margin z ([SenseRow.margin]): [ABOVE] when it is already past the cap edge
 *   (z > 0) but still refused because it does not hold, so one answer settles it; (1/(1 - z))^[BELOW_K] below the edge;
 *   [NEW_WORD] for a word with no row, so a first sighting waits for recurrence.
 *
 * The constants are the ask-policy hill-climb's kept point (hillclimb/asks round 5, stratified search over these five
 * axes; cold replay of 414,297 Jev judgments at equal asks, correct coverage over 16 held-out word cells 0.1430 ->
 * 0.1633, precision 0.904 -> 0.912).
 */
object AskPolicy {
    const val NEW_WORD = 0.48
    const val TAU = 4342.0
    const val BELOW_K = 1.846
    const val ABOVE = 4.036
    const val P_CAP = 13.56

    fun nearness(row: SenseRow?): Double {
        if (row == null) return NEW_WORD
        val t = row.top
        if (t < 0) return 1.0
        val z = row.margin(t)
        return if (z > 0) ABOVE else (1.0 / (1.0 - z)).pow(BELOW_K)
    }

    /** The pressure after one more unresolved read, [gap] corpus reads after the context's previous one. */
    fun charge(pressure: Double, gap: Long): Double = minOf(P_CAP, pressure * kotlin.math.exp(-gap / TAU) + 1.0)

    /** The score of an unresolved read carrying [pressure]. */
    fun score(pressure: Double, row: SenseRow?): Double = pressure * nearness(row)
}
