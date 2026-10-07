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
 * - nearness: for the row's leading class ([SenseRow.margin] z), 1 when it is already past the cap edge (z > 0) but
 *   still refused because it does not hold, 1/(1 + |z|) below the edge; [NEW_WORD] for a word with no row. A row one
 *   answer from settling is asked first (hillclimb/asks round 4: +1.64 pt correct coverage over 1/(1+|z|) both sides).
 */
object AskPolicy {
    /** Nearness of a word with no row yet: a first sighting waits for recurrence (hillclimb/asks round 3: 1 -> 0.25). */
    const val NEW_WORD = 0.25

    fun nearness(row: SenseRow?): Double {
        if (row == null) return NEW_WORD
        val t = row.top
        return if (t < 0) 1.0 else { val z = row.margin(t); if (z > 0) 1.0 else 1.0 / (1.0 - z) }
    }

    /** Reads over which unresolved pressure forgets by 1/e (chosen on train cells, hillclimb/asks round 2). */
    const val TAU = 5_000.0

    /** The pressure after one more unresolved read, [gap] corpus reads after the context's previous one. */
    fun charge(pressure: Double, gap: Long): Double = pressure * kotlin.math.exp(-gap / TAU) + 1.0

    /** The score of an unresolved read carrying [pressure]. */
    fun score(pressure: Double, row: SenseRow?): Double = pressure * nearness(row)
}
