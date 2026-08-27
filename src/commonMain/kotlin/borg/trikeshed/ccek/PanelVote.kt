package borg.trikeshed.ccek

/**
 * W5.1: the panel vote reducer — genuinely new machinery; nothing in the
 * repo voted before this. Pure and suspend-free by design: collection of
 * ballots happens through CCEK agent fan-out (Phase 1), reduction is this
 * deterministic fold.
 *
 * A ballot carries presence facts only — no credential ever crosses here.
 */
data class VoteBallot(
    /** The seat that spoke — unique per assembly run. */
    val seat: String,
    val role: String,
    /** Model that produced the answer, for provenance. Null on failure. */
    val model: String? = null,
    /** true = the seat completed a turn; false = transport/model failure ⇒ abstention. */
    val ok: Boolean,
    /** The answer body. */
    val content: String? = null,
    /** Affirmative iff the seat voted FOR the motion under consideration. */
    val approve: Boolean = false,
    val error: String? = null,
)

enum class VoteVerdict { ACCEPTED, REJECTED }

data class VoteTally(
    val approveWeight: Double,
    val rejectWeight: Double,
    val abstainWeight: Double,
)

data class VoteResult(
    val verdict: VoteVerdict,
    val quorumRequired: Double,
    val tally: VoteTally,
    /** Content of the seats that voted against an accepted motion (or for a rejected one). */
    val dissent: List<String>,
    /** Every ballot in arrival order — failures included. This is the study record. */
    val transcript: List<VoteBallot>,
)

object PanelVote {

    /**
     * Reduce [ballots] against a quorum measured in WEIGHTED votes.
     *
     *  - Failed ballots are ABSTENTIONS: they carry zero weight either way and
     *    land in the transcript, never in tally approve/reject sides.
     *  - ACCEPTED iff approveWeight >= [quorum]; anything else is REJECTED —
     *    the motion needed the support and did not get it. There is no silent
     *    third state: an unmet quorum must read as refusal downstream.
     *  - Dissent pairs with the verdict: on ACCEPTED, dissent = the explicit
     *    reject ballots' contents; on REJECTED, dissent = the explicit approve
     *    ballots' contents (the minority position worth reading).
     *  - Duplicate seats are refused: one seat, one turn, one vote.
     */
    fun reduce(
        ballots: List<VoteBallot>,
        quorum: Double,
        weightOf: (VoteBallot) -> Double = { 1.0 },
    ): VoteResult {
        val seen = HashSet<String>()
        for (b in ballots) {
            if (!seen.add(b.seat)) throw IllegalArgumentException("duplicate ballot from seat '${b.seat}'")
        }
        var approve = 0.0
        var reject = 0.0
        var abstain = 0.0
        for (b in ballots) {
            val w = weightOf(b)
            when {
                !b.ok -> abstain += w
                b.approve -> approve += w
                else -> reject += w
            }
        }
        val verdict = if (approve >= quorum) VoteVerdict.ACCEPTED else VoteVerdict.REJECTED
        val dissent = ballots.filter { b ->
            b.ok && b.approve == (verdict == VoteVerdict.REJECTED)
        }.mapNotNull { it.content }
        return VoteResult(
            verdict = verdict,
            quorumRequired = quorum,
            tally = VoteTally(approve, reject, abstain),
            dissent = dissent,
            transcript = ballots.toList(),
        )
    }
}

/**
 * W5.1 `panel.triage`: classify a reduced vote into the next FSM verb —
 * continue working, or stop. Kept beside the reducer so every consumer
 * shares ONE mapping instead of re-deciding locally.
 */
fun VoteResult.triage(): String = when {
    verdict == VoteVerdict.ACCEPTED -> "advance"
    tally.abstainWeight > tally.rejectWeight -> "retry"
    else -> "abort"
}
