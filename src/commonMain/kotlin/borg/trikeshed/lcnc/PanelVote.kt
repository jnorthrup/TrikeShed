package borg.trikeshed.lcnc

data class PanelVoteTally(
    val approveWeight: Double,
    val rejectWeight: Double,
    val abstainWeight: Double,
)

enum class VoteVerdict {
    ACCEPTED,
    REJECTED,
}

data class VoteBallot(
    val seat: String,
    val role: String,
    val model: String? = null,
    val ok: Boolean = true,
    val content: String? = null,
    val approve: Boolean = false,
    val error: String? = null,
)

data class PanelVoteResult(
    val verdict: VoteVerdict,
    val tally: PanelVoteTally,
    val quorumRequired: Double,
    val dissent: List<String>,
    val transcript: List<VoteBallot>,
) {
    fun triage(): String = when {
        verdict == VoteVerdict.ACCEPTED -> "advance"
        tally.approveWeight == 0.0 && tally.rejectWeight == 0.0 && tally.abstainWeight > 0.0 -> "retry"
        tally.rejectWeight > 0.0 -> "abort"
        else -> "retry"
    }
}

object PanelVote {
    fun reduce(
        ballots: List<VoteBallot>,
        quorum: Double,
        weightOf: (VoteBallot) -> Double = { 1.0 },
    ): PanelVoteResult {
        require(quorum >= 0.0) { "quorum must be non-negative" }
        val seats = HashSet<String>()
        var approve = 0.0
        var reject = 0.0
        var abstain = 0.0
        ballots.forEach { ballot ->
            require(seats.add(ballot.seat)) { "duplicate panel seat: ${ballot.seat}" }
            val weight = weightOf(ballot)
            require(weight >= 0.0) { "vote weight must be non-negative" }
            when {
                !ballot.ok -> abstain += weight
                ballot.approve -> approve += weight
                else -> reject += weight
            }
        }
        val verdict = if (approve >= quorum && approve > reject) VoteVerdict.ACCEPTED else VoteVerdict.REJECTED
        val dissent = ballots.mapNotNull { ballot ->
            val disagrees = ballot.ok && when (verdict) {
                VoteVerdict.ACCEPTED -> !ballot.approve
                VoteVerdict.REJECTED -> ballot.approve
            }
            ballot.content?.takeIf { disagrees && it.isNotBlank() }
        }
        return PanelVoteResult(
            verdict = verdict,
            tally = PanelVoteTally(approve, reject, abstain),
            quorumRequired = quorum,
            dissent = dissent,
            transcript = ballots.toList(),
        )
    }
}
