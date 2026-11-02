package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.nlp.NlpDocument
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
 * [EvidenceLedger] slot. Exact repeated input lines share an in-memory source id across calls,
 * preventing replay from inflating evidence. This conservative guard also merges identical text
 * from independent sources and does not detect paraphrases or dependent sources with different
 * text; it is not a substitute for stable source provenance. The bounded registry refuses new
 * mappable lines when full; it never resets identities while this lane is alive. These limits
 * bound the replay registry only, not the ledger, term index, parser, or caller input. [promote] admits the confident,
 * affirmative slots to [ClassRete]; [ask] matches a class by one Roaring AND of its self+ancestor
 * ids against the admitted antecedents, then weighs each match by NAL deduction revised with the
 * class's own direct observations.
 */
class ClassRuleLane(private val nlp: () -> CoreNlpRuntime = { CoreNlpRuntime() }) : AutoCloseable {
    private var maxSources: Int = 4_096
    private var maxLineChars: Int = 16_384
    private var maxSourceChars: Long = 4_194_304
    private var readDocument: ((String) -> NlpDocument)? = null
    private var classLookup: ((String) -> Int?)? = null

    /** Internal deterministic reader and bounded-registry seam; the public constructor remains unchanged. */
    internal constructor(
        maxSources: Int,
        maxLineChars: Int = 16_384,
        maxSourceChars: Long = 4_194_304,
        readDocument: ((String) -> NlpDocument)? = null,
        classLookup: ((String) -> Int?)? = null,
    ) : this() {
        require(maxSources > 0 && maxLineChars > 0 && maxSourceChars > 0)
        this.maxSources = maxSources
        this.maxLineChars = maxLineChars
        this.maxSourceChars = maxSourceChars
        this.readDocument = readDocument
        this.classLookup = classLookup
    }

    val sumo by lazy { SumoCorpus.classifier }
    val ledger = EvidenceLedger()
    private val terms = ArrayList<String>()
    private val termIds = HashMap<String, Int>()
    private var nextSource = 0
    private val sourceByExactLine = HashMap<String, Int>()
    private var sourceChars = 0L
    private var runtime: CoreNlpRuntime? = null
    var rete = ClassRete(LongArray(0), LongArray(0))
        private set

    /** One observed candidate: the ledger slot it revised, or the reason it was not observed. */
    class Observed(val source: Int, val line: String, val slot: Int, val miss: String?)

    /** One answer to [ask]: the rule row, the rule's evidence as read here ([rule]), the class's own (or null), and the pooled belief. */
    class Answer(val row: Int, val rule: EvidenceCoord, val observed: EvidenceCoord?, val belief: EvidenceCoord)

    fun term(id: Int): String = terms[id]
    fun className(id: Int): String = sumo.className(SumoClassId(id))

    private fun termId(t: String): Int = termIds.getOrPut(t) { terms.size.also { terms.add(t) } }

    private fun classOf(lemma: String): Int? = lemma.lowercase().let { l ->
        val lookup = classLookup
        if (lookup != null) lookup(l)
        else SumoCorpus.nounClassId(l).takeIf { it >= 0 } ?: SumoCorpus.nounClassId(l.removeSuffix("s")).takeIf { it >= 0 }
    }

    /** Parse source-linked candidate readings without adding evidence or promoting rules. */
    fun candidates(text: String): List<NlpcoreAxiom> {
        require(text.length.toLong() <= maxSourceChars) { "candidate text exceeds source length limit" }
        if (text.isBlank()) return emptyList()
        val doc = readDocument?.invoke(text) ?: (runtime ?: nlp().also { runtime = it }).analyze(text)
        require(doc.text == text) { "reader returned different text" }
        return NlpcoreAxiomatics.recognize(doc).values()
    }

    fun observe(lines: List<String>): List<Observed> {
        val out = ArrayList<Observed>()
        for (line in lines) {
            if (line.length > maxLineChars) {
                out.add(Observed(-1, line, -1, "line exceeds source length limit"))
                continue
            }
            val doc = readDocument?.invoke(line) ?: (runtime ?: nlp().also { runtime = it }).analyze(line)
            if (doc.text != line) {
                out.add(Observed(-1, line, -1, "reader returned different text"))
                continue
            }
            val lemmaOf = HashMap<String, String>()
            for (s in doc.sentences.values()) for (t in s.tokens.values()) lemmaOf[t.word] = t.lemma.ifBlank { t.word }
            val axioms = NlpcoreAxiomatics.recognize(doc).values()
            if (axioms.isEmpty()) out.add(Observed(-1, line, -1, "no candidate"))
            for (a in axioms) {
                val cls = classOf(lemmaOf[a.antecedent] ?: a.antecedent)
                if (cls == null) { out.add(Observed(-1, line, -1, "no class for ${a.antecedent}")); continue }
                val known = sourceByExactLine[line]
                if (known == null && (sourceByExactLine.size >= maxSources || line.length.toLong() > maxSourceChars - sourceChars)) {
                    out.add(Observed(-1, line, -1, "source capacity exhausted"))
                    continue
                }
                val source = known ?: nextSource.also {
                    nextSource++
                    sourceByExactLine[line] = it
                    sourceChars += line.length
                }
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

    /**
     * Answers for SUMO class [name], or null when it is not a class. A rule stated of a class above it is read on the
     * sphere ([Inheritance.read]): pooled with the class's own evidence when the two rows cannot be told apart, left out
     * when they can (the class is an exception), and read whole when the class has none of its own.
     */
    fun ask(name: String): List<Answer>? {
        val self = sumo.classId(name)?.value ?: return null
        val rete = rete
        return rete.fire(sumo.mask(name, SumoMask.ANCESTORS) or RoaringSeries.singleton(self)).toList().mapNotNull { r: Int ->
            val rule = rete.evidence(r)
            if (rete.antecedent(r) == self) return@mapNotNull Answer(r, rule, null, rule)
            val direct = ledger.slot(packInts(self, rete.consequent(r)))
            val observed = if (direct < 0) null else ledger.evidence(direct)
            val read = Inheritance.read(observed, if (direct < 0) null else ledger.basis(direct),
                longArrayOf(rule.packed), listOf(ledger.basis(ledger.slot(rete.key(r))))) ?: return@mapNotNull null
            Answer(r, rule, observed, read.belief)
        }
    }

    override fun close() { runtime?.close(); runtime = null }
}
