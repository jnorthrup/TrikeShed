package borg.trikeshed.lcnc

import borg.trikeshed.collections._m
import borg.trikeshed.lib.*
import kotlin.math.nextUp

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
 *  - Failed ballots retain their abstention weight and transcript, contributing
 *    neither approval nor rejection. They never manufacture or break a tie.
 */
object PanelVoteNode {

    fun registry(): Map<String, LcncNodeRunner> = _m[
        "panel.vote" j LcncNodeRunner { node, inputs ->
            val ballots = parseBallots(inputs["ballots"])
            var approve = 0.0
            var reject = 0.0
            for (b in ballots) {
                if (!b.ok) continue
                if (b.approve) approve += b.weight else reject += b.weight
            }
            // Declared quorum wins; blank = strict majority of the OK weight.
            val quorum = node.params["quorum"]?.takeUnless { it.isBlank() }?.let {
                requireNotNull(it.toDoubleOrNull()) { "Invalid panel quorum" }
            } ?: ((approve + reject) / 2.0).nextUp()
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
                "transcript" j result.transcript.map { ballot -> _m[
                    "seat" j ballot.seat, "role" j ballot.role, "model" j ballot.model,
                    "ok" j ballot.ok, "content" j ballot.content, "approve" j ballot.approve,
                    "error" j ballot.error, "weight" j ballot.weight,
                ] },
                "seats" j ballots.size,
                "quorum" j result.quorumRequired,
                "quorumMet" j result.quorumMet,
            ]
        },
    ]

    /** JSON and in-process arrays share one ballot schema; malformed members cannot alter quorum. */
    private fun parseBallots(raw: Any?): List<VoteBallot> {
        val items: List<*> = when (raw) {
            is List<*> -> raw
            is Array<*> -> raw.toList()
            else -> throw IllegalArgumentException("Panel ballots must be a list or array")
        }
        return items.map { item ->
            val m = requireNotNull(item as? Map<*, *>) { "Panel ballot must be an object" }
            val seat = requireNotNull(text(m, "seat")) { "Panel ballot requires a seat" }
            val ok = boolean(m, "ok", true)
            val weight = if ("weight" !in m) 1.0 else when (val value = m["weight"]) {
                is Number -> value.toDouble()
                is String -> requireNotNull(value.toDoubleOrNull()) { "Invalid ballot weight" }
                else -> throw IllegalArgumentException("Invalid ballot weight")
            }
            VoteBallot(
                seat = seat,
                role = text(m, "role") ?: "seat",
                model = text(m, "model"),
                ok = ok,
                content = text(m, "content"),
                approve = boolean(m, "approve", if (ok) null else false),
                error = text(m, "error"),
                weight = weight,
            )
        }
    }

    private fun text(value: Map<*, *>, key: String): String? = value[key]?.let {
        requireNotNull(it as? String) { "Invalid ballot $key" }
    }

    private fun boolean(value: Map<*, *>, key: String, default: Boolean?): Boolean = when (val v = value[key]) {
        null -> if (key !in value) requireNotNull(default) { "Ballot requires $key" }
            else throw IllegalArgumentException("Invalid ballot $key")
        is Boolean -> v
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("Invalid ballot $key")
    }
}
