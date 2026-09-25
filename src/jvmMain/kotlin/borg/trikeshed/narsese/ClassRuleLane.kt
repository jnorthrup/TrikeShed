package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.lib.packInts
import borg.trikeshed.ontology.SumoClassId
import borg.trikeshed.ontology.SumoCorpus
import borg.trikeshed.ontology.SumoMask

/**
 * CoreNLP → NAL evidence → eternal promotion → [ClassRete], with SUMO as the alpha network.
 *
 * Each line is parsed by CoreNLP; [NlpcoreAxiomatics] yields candidate implications. The
 * antecedent lemma resolves to a SUMO preorder class id through the WordNet noun mapping; the
 * consequent keeps its lemma. Candidates with the same (class id, lemma) revise one
 * [EvidenceLedger] slot, keyed by a source id that is unique across every [observe] call, so
 * evidence accumulates across batches and no line counts twice. [promote] admits the confident,
 * affirmative slots to [ClassRete]; [ask] matches a class by one Roaring AND of its self+ancestor
 * ids against the admitted antecedents, then weighs each match by NAL deduction revised with the
 * class's own direct observations.
 */
class ClassRuleLane(private val nlp: () -> CoreNlpRuntime = { CoreNlpRuntime() }) : AutoCloseable {
    val sumo by lazy { SumoCorpus.classifier }
    val ledger = EvidenceLedger()
    private val terms = ArrayList<String>()
    private val termIds = HashMap<String, Int>()
    private var nextSource = 0
    private var runtime: CoreNlpRuntime? = null
    var rete = ClassRete(LongArray(0), LongArray(0))
        private set

    /** One observed candidate: the ledger slot it revised, or the reason it was not observed. */
    class Observed(val source: Int, val line: String, val slot: Int, val miss: String?)

    /** One answer to [ask]: the rule row, the deduced, directly observed (or null) and revised belief. */
    class Answer(val row: Int, val deduced: EvidenceCoord, val observed: EvidenceCoord?, val belief: EvidenceCoord)

    fun term(id: Int): String = terms[id]
    fun className(id: Int): String = sumo.className(SumoClassId(id))

    private fun termId(t: String): Int = termIds.getOrPut(t) { terms.size.also { terms.add(t) } }

    private fun classOf(lemma: String): Int? = lemma.lowercase().let { l ->
        SumoCorpus.nounClassId(l).takeIf { it >= 0 } ?: SumoCorpus.nounClassId(l.removeSuffix("s")).takeIf { it >= 0 }
    }

    fun observe(lines: List<String>): List<Observed> {
        val nlp = runtime ?: nlp().also { runtime = it }
        val out = ArrayList<Observed>()
        for (line in lines) {
            val source = nextSource++
            val doc = nlp.analyze(line)
            val lemmaOf = HashMap<String, String>()
            for (s in doc.sentences.values()) for (t in s.tokens.values()) lemmaOf[t.word] = t.lemma.ifBlank { t.word }
            val axioms = NlpcoreAxiomatics.recognize(doc).values()
            if (axioms.isEmpty()) out.add(Observed(source, line, -1, "no candidate"))
            for (a in axioms) {
                val cls = classOf(lemmaOf[a.antecedent] ?: a.antecedent)
                if (cls == null) { out.add(Observed(source, line, -1, "no class for ${a.antecedent}")); continue }
                val term = termId((lemmaOf[a.consequent] ?: a.consequent).lowercase())
                out.add(Observed(source, line, ledger.observe(packInts(cls, term), source, a.rule.evidence), null))
            }
        }
        return out
    }

    /** Admit slots with confidence ≥ [threshold] and frequency > 0.5; a confident negative is knowledge, not a rule. */
    fun promote(threshold: Float): ClassRete {
        val eternal = ledger.eternal(threshold, minFrequency = 0.5f)
        rete = ClassRete(LongArray(eternal.size) { ledger.key(eternal[it]) }, LongArray(eternal.size) { ledger.evidence(eternal[it]).packed })
        return rete
    }

    /** Answers for SUMO class [name], or null when it is not a class. */
    fun ask(name: String): List<Answer>? {
        val self = sumo.classId(name)?.value ?: return null
        val rete = rete
        return rete.fire(sumo.mask(name, SumoMask.ANCESTORS) or RoaringSeries.singleton(self)).map { r ->
            val deduced = if (rete.antecedent(r) == self) rete.evidence(r) else Nal.deduce(Nal.truthOf(rete.evidence(r)), IS_A)
            val ruleBasis = ledger.basis(ledger.slot(rete.key(r)))
            val direct = ledger.slot(packInts(self, rete.consequent(r)))
            val observed = if (direct < 0) null else ledger.evidence(direct)
            val belief = when {
                direct < 0 -> deduced
                ledger.basis(direct).intersects(ruleBasis) -> observed!!
                else -> revise(observed!!, deduced)
            }
            Answer(r, deduced, observed, belief)
        }
    }

    override fun close() { runtime?.close(); runtime = null }

    companion object {
        /** SUMO subclass/instance edges are axiomatic: near-certain premises for deduction. */
        val IS_A = TruthCoord(1f, 0.99f)
    }
}
