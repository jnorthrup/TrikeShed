package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.lib.TwInt
import borg.trikeshed.lib.j
import borg.trikeshed.ontology.SumoClassId
import borg.trikeshed.ontology.SumoCorpus
import borg.trikeshed.ontology.SumoMask
import java.io.File

/**
 * CoreNLP → NAL evidence → eternal promotion → rete, with SUMO as the alpha network.
 *
 * Each line is parsed by CoreNLP; [NlpcoreAxiomatics] yields candidate implications. The
 * antecedent lemma resolves to a SUMO preorder class id through the WordNet noun mapping;
 * the consequent keeps its lemma. Candidates with the same (class id, lemma) revise one
 * [EvidenceLedger] entry, the line number as source id; a rule whose NAL confidence reaches `promote` is admitted to [ClassRete].
 * An `ask` class matches rules by one Roaring AND of its self+ancestor ids against the
 * admitted antecedent ids.
 *
 * Usage: CorenlpReteCli <corpus.txt> <promote 0..1> <askClass>...
 */
object CorenlpReteCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val lines = File(args[0]).readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val promote = args[1].toFloat()
        val asks = args.drop(2)

        val tS = System.nanoTime()
        val sumo = SumoCorpus.classifier
        val tL = System.nanoTime()
        SumoCorpus.nounClassId("dog")
        println("[sumo] ${sumo.classCount} classes, ${sumo.termCount} terms: classifier ${(tL - tS) / 1_000_000}ms, lexicon ${(System.nanoTime() - tL) / 1_000_000}ms")
        fun classOf(lemma: String): Int? = lemma.lowercase().let { l ->
            SumoCorpus.nounClassId(l).takeIf { it >= 0 } ?: SumoCorpus.nounClassId(l.removeSuffix("s")).takeIf { it >= 0 }
        }
        val terms = ArrayList<String>()
        val termIds = HashMap<String, Int>()
        fun termId(t: String): Int = termIds.getOrPut(t) { terms.size.also { terms.add(t) } }
        fun name(id: Int) = sumo.className(SumoClassId(id))

        val ledger = EvidenceLedger<TwInt>()
        val t0 = System.nanoTime()
        CoreNlpRuntime().use { nlp ->
            for ((source, line) in lines.withIndex()) {
                val doc = nlp.analyze(line)
                val lemmaOf = HashMap<String, String>()
                for (s in doc.sentences.values()) for (t in s.tokens.values()) lemmaOf[t.word] = t.lemma.ifBlank { t.word }
                val axioms = NlpcoreAxiomatics.recognize(doc).values()
                if (axioms.isEmpty()) println("[nlp] no candidate: $line")
                for (a in axioms) {
                    val cls = classOf(lemmaOf[a.antecedent] ?: a.antecedent)
                    if (cls == null) { println("[sumo] no class for ${a.antecedent}: $line"); continue }
                    val key = cls j termId((lemmaOf[a.consequent] ?: a.consequent).lowercase())
                    val entry = ledger.observe(key, source, a.rule.evidence)
                    println("[nal] ${name(cls)} ==> ${terms[key.second]}  c=${"%.3f".format(Nal.truthOf(entry.evidence).confidence)}  ← #$source $line")
                }
            }
        }
        val nlpMs = (System.nanoTime() - t0) / 1_000_000

        // Promotion: confident AND affirmative. A confident negative belief is knowledge, not a rule.
        val eternal = ledger.eternal(promote).filter { Nal.truthOf(it.second.evidence).frequency > 0.5f }
            .map { (k, e) -> ClassRule(k, e.evidence) }
        println("[rete] promoted ${eternal.size}/${ledger.size} at c>=$promote, f>0.5: " +
            eternal.joinToString { "${name(it.antecedent)}==>${terms[it.consequent]}" })
        val rete = ClassRete(eternal.size j { i: Int -> eternal[i] })
        // SUMO subclass/instance edges are axiomatic: near-certain premises for deduction.
        val isA = TruthCoord(1f, 0.99f)
        fun show(e: EvidenceCoord) = Nal.truthOf(e).let { "f=${"%.2f".format(it.frequency)} c=${"%.2f".format(it.confidence)}" }

        for (ask in asks) {
            val self = sumo.classId(ask)?.value
            if (self == null) { println("[ask] $ask: not a SUMO class"); continue }
            val t1 = System.nanoTime()
            val fired = rete.fire(sumo.mask(ask, SumoMask.ANCESTORS) or RoaringSeries.singleton(self)).values()
            val us = (System.nanoTime() - t1) / 1_000
            if (fired.isEmpty()) println("[ask] $ask: no rule  (${us}µs)")
            for (f in fired) {
                val rule = f.a
                // Bitset proposes; NAL deduction weighs the inherited rule; the ledger's direct
                // observations of this class revise it. Specific evidence is never overruled by the bitset.
                val deduced = if (rule.antecedent == self) rule.evidence else Nal.deduce(Nal.truthOf(rule.evidence), isA)
                val ruleBasis = ledger[rule.key]!!.basis
                val direct = ledger[self j rule.consequent]
                val belief = when {
                    direct == null -> deduced
                    direct.basis.intersects(ruleBasis) -> direct.evidence
                    else -> revise(direct.evidence, deduced)
                }
                println("[ask] $ask ==> ${terms[rule.consequent]}  via ${name(rule.antecedent)}  deduced ${show(deduced)}" +
                    (direct?.let { "  observed ${show(it.evidence)}" } ?: "") + "  belief ${show(belief)}  (${us}µs, model calls 0)")
            }
        }
        println("[time] corenlp ${nlpMs}ms for ${lines.size} lines")
    }
}
