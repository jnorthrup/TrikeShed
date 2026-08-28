package borg.trikeshed.lcnc

import borg.trikeshed.ccek.PanelVote
import borg.trikeshed.ccek.VoteBallot
import borg.trikeshed.ccek.VoteVerdict
import borg.trikeshed.ccek.triage
import borg.trikeshed.collections._m
import borg.trikeshed.lib.j

/**
 * `panel.vote` — the workgroup convening widget over [PanelVote.reduce]
 * (wise-micali step 11: the legal preset's vote seam; Seats + quorum +
 * humanOversight are the advisory-board shape). In-process and quota-free:
 * ballots in, verdict out, no model call anywhere on the path.
 *
 * Convening semantics (the topology ruling):
 *  - ODD membership decides cleanly — a strict majority of the non-abstaining
 *    weight accepts (the default quorum when none is declared).
 *  - EVEN membership can TIE, and a tie is a SIGNAL, never a forced verdict:
 *    `tie: true`, `triage: "research"` — further research, more work, another
 *    convening. The reducer's own refusal stands (an unmet quorum reads as
 *    REJECTED downstream); the tie flag is the widget's honest annotation.
 *  - Failed ballots are abstentions (zero weight, in the transcript) — they
 *    never manufacture or break a tie.
 */
object PanelVoteNode {

    fun registry(): Map<String, LcncNodeRunner> = _m[
        "panel.vote" j LcncNodeRunner { node, inputs ->
            val ballots = parseBallots(inputs["ballots"])
            var approve = 0.0
            var reject = 0.0
            for (b in ballots) {
                if (!b.ok) continue
                if (b.approve) approve += 1.0 else reject += 1.0
            }
            // Declared quorum wins; blank = strict majority of the OK weight.
            val quorum = node.params["quorum"]?.toDoubleOrNull()
                ?: ((approve + reject) / 2.0 + 1e-9)
            val result = PanelVote.reduce(ballots, quorum)
            val tie = approve == reject && approve > 0.0
            _m[
                "verdict" j result.verdict.name,
                "accepted" j (result.verdict == VoteVerdict.ACCEPTED),
                "tie" j tie,
                "triage" j (if (tie) "research" else result.triage()),
                "tally" j _m[
                    "approve" j result.tally.approveWeight,
                    "reject" j result.tally.rejectWeight,
                    "abstain" j result.tally.abstainWeight,
                ],
                "dissent" j result.dissent,
                "seats" j ballots.size,
                "quorum" j result.quorumRequired,
            ]
        },
    ]

    /** Tolerant of the shapes JSON parsing actually produces (List or Array, string booleans). */
    private fun parseBallots(raw: Any?): List<VoteBallot> {
        val items: List<*> = when (raw) {
            is List<*> -> raw
            is Array<*> -> raw.toList()
            else -> emptyList<Any?>()
        }
        return items.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val seat = m["seat"]?.toString() ?: return@mapNotNull null
            VoteBallot(
                seat = seat,
                role = m["role"]?.toString() ?: "seat",
                model = m["model"]?.toString(),
                ok = truthy(m["ok"], default = true),
                content = m["content"]?.toString(),
                approve = truthy(m["approve"], default = false),
                error = m["error"]?.toString(),
            )
        }
    }

    private fun truthy(v: Any?, default: Boolean): Boolean = when (v) {
        null -> default
        is Boolean -> v
        else -> v.toString() == "true"
    }
}
