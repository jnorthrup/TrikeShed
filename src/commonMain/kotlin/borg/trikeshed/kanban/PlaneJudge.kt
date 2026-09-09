package borg.trikeshed.kanban

/**
 * The judge of a claim is the plane, not a second model. It reads the reply
 * shape [PlaneBrief] asked for and checks each MUST's evidence against the facts
 * that exist. No prose is weighed; a MUST is met when the reply says so, names a
 * fact the plane holds, AND that fact answers *that* criterion.
 *
 * Delta: existence alone was not judgement. A reply could satisfy every MUST by
 * citing the same one fact that happened to be on the plane, and the judge closed
 * the card — verification that only checks a citation resolves is a citation
 * checker, not a judge. Two pure rules close it, both falsifiable and neither
 * asking a second model:
 *
 *  1. **Anchored.** A criterion naming something concrete — a path, a dotted
 *     symbol or test name, a route, a quoted string — only accepts evidence whose
 *     id or text carries one of those anchors. A criterion that names nothing
 *     concrete has nothing to match on, so it falls back to the existence check
 *     and the reason says it did: the judge never pretends to a rigour the
 *     criterion did not give it.
 *  2. **Distinct.** Two anchored MUSTs may not lean on the same fact. Citing one
 *     receipt for the whole spec is the cheapest way to fake a pass.
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
    @Deprecated("Pass planeText so evidence can be matched to the criterion, not merely resolved.")
    fun decide(spec: PlaneBrief.Spec, humanTag: Boolean, brainOk: Boolean, replyText: String, planeIds: Set<String>): Decision =
        decide(spec, humanTag, brainOk, replyText, planeIds, emptyMap())

    /**
     * @param planeText evidence id → the fact's searchable text. Absent text still
     *   matches on the id itself, so a fact whose id carries the path or symbol the
     *   criterion names is anchored even when the caller has no body to offer.
     */
    fun decide(
        spec: PlaneBrief.Spec,
        humanTag: Boolean,
        brainOk: Boolean,
        replyText: String,
        planeIds: Set<String>,
        planeText: Map<String, String>,
        ownCriteria: Boolean = true,
    ): Decision {
        if (!brainOk) return Decision(Outcome.RETRY, "brain call failed", null)
        val reply = parse(replyText) ?: return Decision(Outcome.RETRY, "no VERDICT line in the reply", null)
        if (spec.humanReview || humanTag) return Decision(Outcome.REVIEW, "the card asks for a person", reply)
        if (reply.verdict == "NEEDS-HUMAN") return Decision(Outcome.REVIEW, "reply: NEEDS-HUMAN", reply)
        if (reply.verdict != "MET") return Decision(Outcome.RETRY, "reply: ${reply.verdict}", reply)
        val byLabel = reply.lines.associateBy { it.label }
        val spent = HashMap<String, String>()   // evidence token -> the label that already claimed it
        var unanchored = 0
        for (c in spec.musts) {
            val l = byLabel[c.label] ?: return Decision(Outcome.RETRY, "${c.label} not answered", reply)
            if (!l.met) return Decision(Outcome.RETRY, "${c.label} NOT-MET", reply)
            if (l.evidence.isEmpty()) return Decision(Outcome.RETRY, "${c.label} MET without evidence", reply)
            if (!onPlane(l.evidence, planeIds)) return Decision(Outcome.RETRY, "${c.label} evidence '${l.evidence}' is not a fact on the plane", reply)
            // A fan-out child answers the parent's criteria verbatim, and those are worded for the
            // MERGE: "cite one blackboard/kanban/claim/ receipt id" names the receipts the children
            // themselves produce, so no child can ever satisfy it. Holding a child to wording aimed
            // at its parent blocks the whole fan-out, which is worse than the looseness it closes.
            // The parent is still judged strictly, and the parent is what closes the work.
            val anchors = if (ownCriteria) anchors(c.text) else emptySet()
            if (anchors.isEmpty()) { unanchored++; continue }
            val haystack = haystack(l.evidence, planeIds, planeText)
            val hit = anchors.any { haystack.contains(it) }
            if (!hit) return Decision(
                Outcome.RETRY,
                "${c.label} cites '${l.evidence}', which is on the plane but does not answer it — " +
                    "the criterion names ${anchors.take(3).joinToString(", ") { "'" + it + "'" }} and that fact carries none of them",
                reply,
            )
            val already = spent.put(l.evidence, c.label)
            if (already != null) return Decision(
                Outcome.RETRY,
                "${c.label} and $already both cite '${l.evidence}'; one fact cannot answer two criteria",
                reply,
            )
        }
        val reason = when {
            unanchored == 0 -> "every MUST met with evidence that answers it"
            !ownCriteria -> "every MUST met with evidence on the plane; inherited criteria are judged at the merge"
            else -> "every MUST met with evidence on the plane; $unanchored named nothing concrete to match against"
        }
        return Decision(Outcome.DONE, reason, reply)
    }

    /**
     * The concrete things a criterion names, lowercased: quoted or backticked spans,
     * paths, and dotted symbol/test names. Bare prose words are deliberately excluded —
     * matching on those would let any fact mentioning "the" satisfy anything.
     */
    fun anchors(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (m in QUOTED.findAll(text)) m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.let { out += it.lowercase() }
        for (m in PATH.findAll(text)) out += m.value.lowercase()
        for (m in DOTTED.findAll(text)) out += m.value.lowercase()
        return out.filter { it.length >= MIN_ANCHOR }.toSet()
    }

    /** The id plus whatever text the caller holds for it, lowercased, for anchor matching. */
    private fun haystack(evidence: String, planeIds: Set<String>, planeText: Map<String, String>): String {
        val e = evidence.trim().removePrefix("fact:")
        val full = if (e in planeIds) e else planeIds.firstOrNull { it.substringAfter('/') == e } ?: e
        return (full + " " + e + " " + planeText[full].orEmpty() + " " + planeText[e].orEmpty()).lowercase()
    }

    private const val MIN_ANCHOR = 4
    private val QUOTED = Regex("[\"`]([^\"`\n]{2,120})[\"`]")
    private val PATH = Regex("[A-Za-z0-9_.-]*/[A-Za-z0-9_./-]{2,}")
    private val DOTTED = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")

    private fun onPlane(evidence: String, planeIds: Set<String>): Boolean {
        val e = evidence.trim().removePrefix("fact:")
        if (e in planeIds) return true
        // the id without its partition, when the reply dropped it
        return planeIds.any { it.substringAfter('/') == e }
    }
}
