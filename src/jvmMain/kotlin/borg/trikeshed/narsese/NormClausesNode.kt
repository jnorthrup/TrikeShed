package borg.trikeshed.narsese

import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lib.packInts

/**
 * `norm.clauses` — text → sections → [NormClauses] → NAL evidence by section → bank tuples.
 *
 * Sections are paragraphs unless `heading` (a multiline regex) marks them. Each clause is evidence
 * for ⟨subject ⇒ predicate⟩ sourced by section ordinal: affirmative positive, negated negative, so a
 * clause restated across sections gains confidence and a contradicted one loses frequency. Beliefs at
 * confidence ≥ `promote` are told as
 *
 *     (norm "subject" "predicate" "f" "c")
 */
object NormClausesNode {
    const val TYPE = "norm.clauses"

    fun register(runners: MutableMap<String, LcncNodeRunner>, bank: KifKnowledgeBase) {
        runners[TYPE] = runner { kif -> runCatching { bank.assertKif(kif) } }
    }

    /** Sections of [text]: split at [heading] matches, else at blank lines. */
    fun sections(text: String, heading: Regex?): List<String> {
        val parts = if (heading == null) text.split(Regex("""\n\s*\n"""))
        else heading.findAll(text).map { it.range.first }.toList().let { starts ->
            starts.mapIndexed { i, s -> text.substring(s, if (i + 1 < starts.size) starts[i + 1] else text.length) }
        }
        return parts.map { it.replace(Regex("""(\w)-\n(\w)"""), "$1$2").replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }
    }

    private fun q(s: String) = "\"" + s.replace("\"", "'") + "\""

    fun runner(into: (String) -> Unit): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val text = (inputs["text"] ?: inputs["text?"]) as? String ?: ""
        val heading = node.params["heading"]?.takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.MULTILINE) }
        val generic = (node.params["generic"] ?: "").split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val promote = node.params["promote"]?.toFloatOrNull() ?: 0.6f
        val secs = sections(text, heading)
        val terms = ArrayList<String>(); val ids = HashMap<String, Int>()
        fun term(t: String) = ids.getOrPut(t) { terms.size.also { terms.add(t) } }
        val ledger = EvidenceLedger()
        var clauses = 0
        CoreNlpRuntime().use { nlp ->
            for ((ordinal, sec) in secs.withIndex()) for (c in NormClauses.extract(nlp.analyze(sec), generic)) {
                clauses++
                ledger.observe(packInts(term(c.subject), term(c.predicate)), ordinal,
                    if (c.affirmative) EvidenceCoord(Nal.UNIT, 0) else EvidenceCoord(0, Nal.UNIT))
            }
        }
        val beliefs = ledger.eternal(promote).map { s ->
            val k = ledger.key(s); val t = Nal.truthOf(ledger.evidence(s))
            val subj = terms[(k ushr 32).toInt()]; val pred = terms[k.toInt()]
            into("(norm ${q(subj)} ${q(pred)} ${q("%.2f".format(t.frequency))} ${q("%.2f".format(t.confidence))})")
            mapOf("subject" to subj, "predicate" to pred, "frequency" to t.frequency, "confidence" to t.confidence,
                "sections" to ledger.basis(s).toIntArray().toList())
        }.sortedByDescending { it["confidence"] as Float }
        // Affirmative beliefs are admissible law, shaped for nal.rule.admit; confidence is the discount.
        val rules = beliefs.filter { (it["frequency"] as Float) > 0.5f }.map {
            mapOf("antecedent" to it["subject"], "consequent" to it["predicate"], "copula" to "==>", "discount" to it["confidence"])
        }
        mapOf("beliefs" to beliefs, "rules" to rules, "clauses" to clauses, "sections" to secs.size)
    }
}
