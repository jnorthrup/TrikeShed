package borg.trikeshed.kanban

/**
 * The judge of a claim is the plane, not a second model. It reads the reply
 * shape [PlaneBrief] asked for and checks each MUST's evidence id against the
 * facts that exist. No prose is weighed; a MUST is met when the reply says so
 * AND names a fact the plane holds.
 *
 * Outcomes ([Decision.outcome]):
 *  - DONE   every MUST met with evidence on the plane, VERDICT MET, and the card
 *           did not ask for a person (`REVIEW: human` on the spec, or a
 *           `human-review` / `experiment` tag);
 *  - REVIEW the card asked for a person, or the reply said NEEDS-HUMAN;
 *  - RETRY  NOT-MET, no verdict, a MUST without plane evidence, or a failed
 *           brain call — the card goes back to READY with a strike, and the
 *           third strike parks it in BLOCKED (the breaker Hermes' kanban keeps
 *           as consecutive_failures; here it is receipts on the blackboard).
 *
 * Pure. Ethos: causality and the production system — the receipt says which
 * fact ids satisfied which criteria, so the verdict is traceable to facts.
 */
object PlaneJudge {
    enum class Outcome { DONE, REVIEW, RETRY }

    data class Line(val label: String, val met: Boolean, val evidence: String)

    data class Reply(val verdict: String, val lines: List<Line>, val action: String)

    data class Decision(val outcome: Outcome, val reason: String, val reply: Reply?)

    private val VERDICT = Regex("(?im)^\\s*VERDICT\\s*:\\s*(MET|NOT-MET|NOT MET|NEEDS-HUMAN|NEEDS HUMAN)\\b")
    private val LINE = Regex("(?im)^\\s*((?:MUST|SHOULD|MAY)-\\d+)\\s*:\\s*(MET|NOT-MET|NOT MET)\\b[^\\n]*?(?:evidence\\s*[:=]\\s*([^\\s,;]+))?\\s*$")
    private val ACTION = Regex("(?is)^\\s*ACTION\\s*:\\s*(.+)$", RegexOption.MULTILINE)

    /** Parse the reply shape; null when there is no VERDICT line at all. */
    fun parse(text: String): Reply? {
        val v = VERDICT.find(text) ?: return null
        val verdict = v.groupValues[1].uppercase().replace(' ', '-')
        val lines = LINE.findAll(text).map { m ->
            val ev = m.groupValues[3].trim().trimEnd('.', ')', ']')
            Line(m.groupValues[1].uppercase(), m.groupValues[2].uppercase().replace(' ', '-') == "MET", if (ev.equals("none", true)) "" else ev)
        }.toList()
        val action = text.lines().dropWhile { !it.trim().uppercase().startsWith("ACTION") }
            .joinToString("\n").substringAfter(":", "").trim()
        return Reply(verdict, lines, action)
    }

    /**
     * Decide. [planeIds] are `partition/id` tokens of facts that exist; an evidence
     * token matches when it equals one, or equals one with the partition dropped.
     */
    fun decide(spec: PlaneBrief.Spec, humanTag: Boolean, brainOk: Boolean, replyText: String, planeIds: Set<String>): Decision {
        if (!brainOk) return Decision(Outcome.RETRY, "brain call failed", null)
        val reply = parse(replyText) ?: return Decision(Outcome.RETRY, "no VERDICT line in the reply", null)
        if (spec.humanReview || humanTag) return Decision(Outcome.REVIEW, "the card asks for a person", reply)
        if (reply.verdict == "NEEDS-HUMAN") return Decision(Outcome.REVIEW, "reply: NEEDS-HUMAN", reply)
        if (reply.verdict != "MET") return Decision(Outcome.RETRY, "reply: ${reply.verdict}", reply)
        val byLabel = reply.lines.associateBy { it.label }
        for (c in spec.musts) {
            val l = byLabel[c.label] ?: return Decision(Outcome.RETRY, "${c.label} not answered", reply)
            if (!l.met) return Decision(Outcome.RETRY, "${c.label} NOT-MET", reply)
            if (l.evidence.isEmpty()) return Decision(Outcome.RETRY, "${c.label} MET without evidence", reply)
            if (!onPlane(l.evidence, planeIds)) return Decision(Outcome.RETRY, "${c.label} evidence '${l.evidence}' is not a fact on the plane", reply)
        }
        return Decision(Outcome.DONE, "every MUST met with evidence on the plane", reply)
    }

    private fun onPlane(evidence: String, planeIds: Set<String>): Boolean {
        val e = evidence.trim().removePrefix("fact:")
        if (e in planeIds) return true
        // the id without its partition, when the reply dropped it
        return planeIds.any { it.substringAfter('/') == e }
    }
}
