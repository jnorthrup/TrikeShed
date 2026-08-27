package borg.trikeshed.ccek

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Phase 6 gate, W5.1: the vote reducer. Quorum is weighted; failures abstain;
 * dissent pairs with verdict; unmet quorum reads as REJECTED (never silent).
 */
class PanelVoteTest {

    private fun ok(seat: String, role: String, approve: Boolean, content: String = "…") =
        VoteBallot(seat = seat, role = role, model = "m", ok = true, content = content, approve = approve)

    private fun failed(seat: String, role: String, err: String) =
        VoteBallot(seat = seat, role = role, ok = false, error = err)

    @Test
    fun simpleMajorityAccepts() {
        val r = PanelVote.reduce(
            ballots = listOf(
                ok("s1", "legal", approve = true),
                ok("s2", "opposing", approve = false),
                ok("s3", "researcher", approve = true),
            ),
            quorum = 2.0,
        )
        assertEquals(VoteVerdict.ACCEPTED, r.verdict)
        assertEquals(2.0, r.tally.approveWeight)
        assertEquals(1.0, r.tally.rejectWeight)
        // Dissent on ACCEPTED = the rejecting seats' contents.
        assertTrue(r.dissent.isNotEmpty())
    }

    @Test
    fun unmetQuorumIsRejectedNotSilent() {
        val r = PanelVote.reduce(
            ballots = listOf(ok("s1", "a", true), ok("s2", "b", true)),
            quorum = 3.0,
        )
        assertEquals(VoteVerdict.REJECTED, r.verdict, "approve=2 < quorum=3 must read REJECTED")
    }

    @Test
    fun failuresAbstainAndCarryWeightNowhere() {
        val r = PanelVote.reduce(
            ballots = listOf(
                ok("s1", "a", true),
                failed("s2", "b", "provider timeout"),
                failed("s3", "c", "quota exhausted"),
            ),
            quorum = 2.0,
        )
        assertEquals(VoteVerdict.REJECTED, r.verdict, "one approve cannot meet quorum 2")
        assertEquals(2.0, r.tally.abstainWeight, "both failures abstained with weight")
        assertEquals(0.0, r.tally.rejectWeight, "failure is not a no-vote")
        assertEquals(3, r.transcript.size, "failures stay in the transcript for study")
        assertTrue(r.transcript.any { it.error == "provider timeout" })
    }

    @Test
    fun dissentFlipsWithVerdict() {
        val rejected = PanelVote.reduce(
            listOf(ok("s1", "a", true, content = "I favor"), ok("s2", "b", false), ok("s3", "c", false)),
            quorum = 2.0,
        )
        assertEquals(VoteVerdict.REJECTED, rejected.verdict)
        assertEquals(listOf("I favor"), rejected.dissent, "minority FOR position preserved")

        val accepted = PanelVote.reduce(
            listOf(ok("s1", "a", true), ok("s2", "b", true), ok("s3", "c", false, content = "nay")),
            quorum = 2.0,
        )
        assertEquals(VoteVerdict.ACCEPTED, accepted.verdict)
        assertEquals(listOf("nay"), accepted.dissent)
    }

    @Test
    fun weightedVotesChangeOutcome() {
        val judge = ok("judge", "judge", true)
        val layAgainst = ok("lay", "observer", false)
        val weighted = PanelVote.reduce(listOf(judge, layAgainst), quorum = 1.5, weightOf = { b ->
            if (b.role == "judge") 2.0 else 1.0
        })
        assertEquals(VoteVerdict.ACCEPTED, weighted.verdict, "judge's double weight carries")
    }

    @Test
    fun duplicateSeatIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            PanelVote.reduce(listOf(ok("s1", "a", true), ok("s1", "a", true)), quorum = 1.0)
        }
    }

    @Test
    fun triageMapsVerdictPlusAbstentionsToVerbs() {
        val advance = PanelVote.reduce(listOf(ok("s1", "a", true), ok("s2", "b", true)), 2.0).triage()
        assertEquals("advance", advance)

        val retry = PanelVote.reduce(
            listOf(failed("s1", "a", "x"), failed("s2", "b", "y"), failed("s3", "c", "z")), 1.0,
        ).triage()
        assertEquals("retry", retry, "all-abstain leans retry, not abort")

        val abort = PanelVote.reduce(
            listOf(ok("s1", "a", false), ok("s2", "b", false), failed("s3", "c", "x")), 1.0,
        ).triage()
        assertEquals("abort", abort, "explicit rejection dominates abstention in triage")
    }
}
