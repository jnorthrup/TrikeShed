package borg.trikeshed.narsese

import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lib.packInts

/** Unreviewed clause readings. The registered node never publishes KIF or admits rules. */
object NormClausesNode {
    const val TYPE = "norm.clauses"

    @Suppress("UNUSED_PARAMETER")
    fun register(runners: MutableMap<String, LcncNodeRunner>, bank: KifKnowledgeBase) {
        runners[TYPE] = runner()
    }

    /** Deterministic test seam; the bank is deliberately not used. */
    @Suppress("UNUSED_PARAMETER")
    internal fun register(runners: MutableMap<String, LcncNodeRunner>, bank: KifKnowledgeBase,
        read: (List<String>, Set<String>) -> List<List<NormClause>>) {
        runners[TYPE] = candidateRunner(read)
    }

    /** Sections of [text], at the heading shape the text itself evidences (else paragraphs). */
    fun sections(text: String): List<String> = SectionShapes.sections(text).texts

    private fun read(sections: List<String>, generic: Set<String>): List<List<NormClause>> =
        if (sections.isEmpty()) emptyList() else CoreNlpRuntime().use { nlp ->
            sections.map { NormClauses.extract(nlp.analyze(it), generic) }
        }

    fun runner(): LcncNodeRunner = candidateRunner(::read)

    /** Section and sentence text are previews only: original offsets and retained identity are unavailable. */
    internal fun candidateRunner(read: (List<String>, Set<String>) -> List<List<NormClause>>): LcncNodeRunner =
        LcncNodeRunner { node, inputs ->
            val text = (inputs["text"] ?: inputs["text?"]) as? String ?: ""
            val generic = generic(node.params["generic"])
            val secs = sections(text)
            val readings = read(secs, generic)
            require(readings.size == secs.size) { "Clause reader returned a different section count" }
            val candidates = readings.flatMapIndexed { index, clauses -> clauses.map { c ->
                mapOf<String, Any?>("subject" to c.subject, "modal" to c.modal,
                    "affirmative" to c.affirmative, "verb" to c.verb, "object" to c.obj,
                    "oblique" to c.oblique, "condition" to c.condition, "sentencePreview" to c.sentence,
                    "head" to c.head, "ner" to c.ner, "reified" to c.reified,
                    "sectionIndex" to index, "reviewStatus" to "unreviewed", "sourceRetained" to false,
                    "sourceEvidence" to mapOf("kind" to "unretained-section-text", "offsets" to "unavailable"))
            } }
            mapOf<String, Any?>("candidates" to candidates, "beliefs" to emptyList<Any>(),
                "rules" to emptyList<Any>(), "clauses" to candidates.size, "sections" to secs.size,
                "coverage" to "not-established", "publicationStatus" to "deferred-unreviewed")
        }

    private fun generic(value: String?): Set<String> =
        (value ?: "").split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    private fun q(s: String) = "\"" + s.replace("\"", "'") + "\""

    /** Explicit host-only compatibility experiment. These aggregated findings are not reviewed legal facts. */
    internal fun legacyRunner(into: (String) -> Unit,
        read: (List<String>, Set<String>) -> List<List<NormClause>> = ::read): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val text = (inputs["text"] ?: inputs["text?"]) as? String ?: ""
        val secs = sections(text)
        val readings = read(secs, generic(node.params["generic"]))
        require(readings.size == secs.size) { "Clause reader returned a different section count" }
        val promote = node.params["promote"]?.toFloatOrNull() ?: 0.6f
        val terms = ArrayList<String>(); val ids = HashMap<String, Int>()
        fun term(t: String) = ids.getOrPut(t) { terms.size.also { terms.add(t) } }
        val ledger = EvidenceLedger()
        for ((ordinal, clauses) in readings.withIndex()) for (c in clauses) {
            ledger.observe(packInts(term(c.subject), term(c.predicate)), ordinal,
                if (c.affirmative) EvidenceCoord(Nal.UNIT, 0) else EvidenceCoord(0, Nal.UNIT))
        }
        val beliefs = ledger.eternal(promote).map { s ->
            val k = ledger.key(s); val t = Nal.truthOf(ledger.evidence(s))
            val subj = terms[(k ushr 32).toInt()]; val pred = terms[k.toInt()]
            into("(norm ${q(subj)} ${q(pred)} ${q("%.2f".format(t.frequency))} ${q("%.2f".format(t.confidence))})")
            mapOf<String, Any?>("subject" to subj, "predicate" to pred, "frequency" to t.frequency,
                "confidence" to t.confidence, "sections" to ledger.basis(s).toIntArray().toList())
        }.sortedByDescending { it["confidence"] as Float }
        val rules = beliefs.filter { (it["frequency"] as Float) > 0.5f }.map {
            mapOf("antecedent" to it["subject"], "consequent" to it["predicate"], "copula" to "==>", "discount" to it["confidence"])
        }
        mapOf<String, Any?>("candidates" to emptyList<Any>(), "beliefs" to beliefs, "rules" to rules,
            "clauses" to readings.sumOf { it.size }, "sections" to secs.size, "coverage" to "not-established",
            "publicationStatus" to "legacy-host-policy")
    }
}
