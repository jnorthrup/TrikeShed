package borg.trikeshed.narsese

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpToken

/**
 * One parser-grounded, SOURCE-ATTRIBUTED candidate reading — never a claim that
 * the source's statement is universally or factually true. [confidence] is a
 * FIXED STRUCTURAL WEIGHT keyed to which extraction pattern matched (explicit
 * causal verb vs. inferred conditional marker); it is not a parser-reported
 * confidence score and must never be read as one. Promoting a candidate into
 * the live rete as an admitted [EternalRule] is a separate, explicit operation
 * (`nal.rule.admit`) that this recognizer does not perform.
 */
data class NlpcoreAxiom(
    val sentenceIndex: Int,
    val begin: Int,
    val end: Int,
    val antecedent: String,
    val predicate: String,
    val consequent: String,
    val confidence: Float,
    val rule: EternalRule,
)

/**
 * Deterministic NLPCore → NAL candidate recognition.
 *
 * Recognizes exactly two shapes, both unmodified present-tense active SVO:
 *   - a causal verb ROOT: subject CAUSES object.
 *   - an explicit `if`/`provided` mark+advcl conditional: each clause is
 *     subject + present predicate + optional direct object. Both clause terms
 *     keep their predicate and, when present, their object — "if Acme pays
 *     Beta, Gamma ships Delta" must not collapse to "Acme ==> Gamma".
 *
 * Every verb head, subject and object is checked against a whitelist of the
 * dependency relations that shape allows; anything else attached (amod,
 * compound, det, nmod, appos, acl, aux, advmod, neg, conj, csubj, obl, ...)
 * fails the candidate closed rather than being silently dropped. Multiple
 * roots, past/non-finite tense, passive voice, reported/quoted speech and
 * coordination all fail the same way, structurally, not via a word list.
 * `when`/`whenever` are temporal, not eternal implication, and are excluded.
 */
object NlpcoreAxiomatics {
    private val causalVerbs = setOf(
        "cause", "causes", "caused", "causing",
        "lead", "leads", "led", "leading",
        "result", "results", "resulted", "resulting",
        "trigger", "triggers", "triggered", "triggering",
        "produce", "produces", "produced", "producing",
        "imply", "implies", "implied", "implying",
        "entail", "entails", "entailed", "entailing",
    )
    /** `when`/`whenever` read as temporal, not eternal implication — excluded on purpose. */
    private val conditionalMarkers = setOf("if", "provided")
    /** Simple present, finite, active — VBD/VBN/VBG/VB and modal/aux-bearing forms are out of scope. */
    private val presentTags = setOf("VBZ", "VBP")
    private val objectRelations = setOf("obj", "dobj")
    private val quoteChars = setOf('"', '“', '”', '‘', '’')

    /** Exhaustive: a causal-verb ROOT may govern exactly a subject, an object and punctuation. */
    private val rootAllowed = setOf("nsubj", "obj", "dobj", "punct")

    /** Negation of a causal ROOT: UD `advmod` (v2) or `neg` (v1) carrying `not`/`n't`. */
    private val negRelations = setOf("advmod", "neg")
    private val negWords = setOf("not", "n't")

    /** The subordinate `if`-clause verb: subject, optional object, and the mark itself. */
    private val conditionVerbAllowed = setOf("nsubj", "obj", "dobj", "mark", "punct")

    /** The main-clause verb of a conditional: subject, optional object, the advcl link, punctuation. */
    private val conditionalRelations = setOf("advcl", "advcl:if", "advcl:provided")
    private val consequenceVerbAllowed = setOf("nsubj", "obj", "dobj", "punct") + conditionalRelations

    fun recognize(document: NlpDocument, sourceCid: String? = null): Series<NlpcoreAxiom> {
        if (!coversSource(document)) return emptySeriesOf()
        val found = LinkedHashMap<String, NlpcoreAxiom>()
        for (ordinal in 0 until document.sentences.size) {
            val sentence = document.sentences[ordinal]
            if (sentence.begin < 0 || sentence.end <= sentence.begin || sentence.end > document.text.length) continue
            // Reported/quoted material inside this sentence's own span: a source
            // quoting a claim is not the source asserting it. Fail the whole sentence closed.
            if (document.text.substring(sentence.begin, sentence.end).any { it in quoteChars }) continue

            val tokens = sentence.tokens.values()
            val dependencies = sentence.dependencies.values()
            val indices = tokens.map { it.index }
            // Duplicate ids or a token whose text doesn't match its own claimed source span
            // (forged, or out of the sentence/document bounds) invalidate the whole sentence.
            if (indices.size != indices.distinct().size) continue
            if (tokens.any {
                    it.begin < sentence.begin || it.end > sentence.end || it.begin >= it.end ||
                        document.text.substring(it.begin, it.end) != it.word
                }) continue
            val byIndex = tokens.associateBy { it.index }
            if (dependencies.any { it.dependent !in byIndex || (it.governor != 0 && it.governor !in byIndex) }) continue
            // A well-formed dependency tree gives every token exactly one head. Anything
            // else — an orphan token, a token with two heads — is uncovered parser structure.
            val heads = dependencies.groupingBy { it.dependent }.eachCount()
            if (tokens.any { (heads[it.index] ?: 0) != 1 }) continue

            val roots = dependencies.filter { it.governor == 0 && it.relation.lowercase() == "root" }
                .mapNotNull { byIndex[it.dependent] }
            if (roots.size != 1) continue // more than one root is an ambiguous/uncovered parse, not one claim
            val root = roots[0]

            fun childrenOf(head: Int) = dependencies.filter { it.governor == head }
            fun isLeaf(index: Int) = dependencies.none { it.governor == index }
            fun onlySupports(head: Int, allowed: Set<String>) = childrenOf(head).all { it.relation.lowercase() in allowed }
            fun subjectOf(head: Int): NlpToken? = childrenOf(head)
                .filter { it.relation.lowercase() == "nsubj" }.mapNotNull { byIndex[it.dependent] }.singleOrNull()
            fun objectOf(head: Int): NlpToken? = childrenOf(head)
                .filter { it.relation.lowercase() in objectRelations }.mapNotNull { byIndex[it.dependent] }.singleOrNull()
            fun objectCount(head: Int) = childrenOf(head).count { it.relation.lowercase() in objectRelations }
            fun noun(token: NlpToken) = token.tag.uppercase().startsWith("NN") && isLeaf(token.index)
            fun isPresent(token: NlpToken) = token.tag.uppercase() in presentTags
            fun clauseTerm(subject: NlpToken, predicate: String, obj: NlpToken?) =
                if (obj == null) "${subject.word} $predicate" else "${subject.word} $predicate ${obj.word}"
            fun complete(roles: Set<Int>, verbs: Set<Int>, punctuation: Set<String>): Boolean = tokens.all { token ->
                token.index in roles || (token.word in punctuation && isLeaf(token.index) &&
                    dependencies.any { it.dependent == token.index && it.governor in verbs && it.relation == "punct" })
            }

            fun add(
                antecedentToken: NlpToken, predicate: String, consequentToken: NlpToken, confidence: Float,
                antecedentTerm: String = antecedentToken.word, consequentTerm: String = consequentToken.word,
                negated: Boolean = false,
            ) {
                if (antecedentToken.index == consequentToken.index || antecedentTerm.isBlank() || consequentTerm.isBlank()) return
                val rule = EternalRule(
                    antecedent = antecedentTerm,
                    consequent = consequentTerm,
                    copula = NalCopula.IMPLICATION,
                    evidence = (confidence * Nal.UNIT).toLong().coerceAtLeast(1L)
                        .let { w -> if (negated) EvidenceCoord(0L, w) else EvidenceCoord(w, 0L) },
                    provenanceCid = sourceCid,
                )
                val key = "${sentence.index}|${antecedentToken.index}|${consequentToken.index}|${rule.copula.name}"
                if (key !in found) found[key] = NlpcoreAxiom(
                    sentenceIndex = sentence.index,
                    begin = sentence.begin,
                    end = sentence.end,
                    antecedent = antecedentTerm,
                    predicate = predicate,
                    consequent = consequentTerm,
                    confidence = confidence,
                    rule = rule,
                )
            }

            val predicate = (root.lemma.ifBlank { root.word }).lowercase()
            if (predicate in causalVerbs && isPresent(root) && onlySupports(root.index, rootAllowed)) {
                val subject = subjectOf(root.index)
                val obj = objectOf(root.index)
                if (subject != null && obj != null && noun(subject) && noun(obj) &&
                    complete(setOf(root.index, subject.index, obj.index), setOf(root.index), setOf(".")))
                    add(subject, predicate, obj, 0.9f)
            }

            // Negated causal ROOT: subject do/does not CAUSE object — the same claim as
            // negative evidence. The bare verb (VB) carries exactly one present `do` aux
            // and one `not` modifier, both leaves.
            val auxDo = childrenOf(root.index).filter { it.relation.lowercase() == "aux" }.mapNotNull { byIndex[it.dependent] }
            val notMod = childrenOf(root.index).filter { it.relation.lowercase() in negRelations }.mapNotNull { byIndex[it.dependent] }
            if (predicate in causalVerbs && root.tag.uppercase() == "VB" &&
                auxDo.size == 1 && auxDo[0].lemma.lowercase() == "do" && isPresent(auxDo[0]) && isLeaf(auxDo[0].index) &&
                notMod.size == 1 && notMod[0].word.lowercase() in negWords && isLeaf(notMod[0].index) &&
                onlySupports(root.index, rootAllowed + "aux" + negRelations)) {
                val subject = subjectOf(root.index)
                val obj = objectOf(root.index)
                if (subject != null && obj != null && noun(subject) && noun(obj) &&
                    complete(setOf(root.index, subject.index, obj.index, auxDo[0].index, notMod[0].index), setOf(root.index), setOf(".")))
                    add(subject, predicate, obj, 0.9f, negated = true)
            }

            // CoreNLP attaches an explicit conditional marker to the subordinate verb:
            // mark(conditionVerb, if). The consequence verb must be the sentence's one
            // ROOT, linked back to the condition verb by its own advcl edge — a bare
            // mark match without that link can pair unrelated clauses.
            val conditionalMarkersByIndex = tokens.filter {
                it.word.lowercase() in conditionalMarkers || it.lemma.lowercase() in conditionalMarkers
            }.associateBy { it.index }
            for (edge in dependencies.filter { it.relation.lowercase() == "mark" && it.dependent in conditionalMarkersByIndex }) {
                val conditionVerb = byIndex[edge.governor] ?: continue
                if (!isPresent(conditionVerb) || !onlySupports(conditionVerb.index, conditionVerbAllowed)) continue
                if (childrenOf(conditionVerb.index).count { it.relation.lowercase() == "mark" } != 1 ||
                    !isLeaf(edge.dependent)) continue
                val consequenceVerb = root.takeIf { it.index != conditionVerb.index } ?: continue
                if (!isPresent(consequenceVerb) || !onlySupports(consequenceVerb.index, consequenceVerbAllowed)) continue
                if (childrenOf(consequenceVerb.index).count { it.relation.lowercase() in conditionalRelations } != 1 ||
                    objectCount(conditionVerb.index) > 1 || objectCount(consequenceVerb.index) > 1) continue
                val linked = dependencies.any {
                    it.governor == consequenceVerb.index && it.dependent == conditionVerb.index &&
                        it.relation.lowercase() in setOf("advcl", "advcl:${byIndex.getValue(edge.dependent).word.lowercase()}")
                }
                if (!linked) continue
                val antecedent = subjectOf(conditionVerb.index) ?: continue
                val antecedentObj = objectOf(conditionVerb.index)
                val consequent = subjectOf(consequenceVerb.index) ?: continue
                val consequentObj = objectOf(consequenceVerb.index)
                if (!noun(antecedent) || (antecedentObj != null && !noun(antecedentObj))) continue
                if (!noun(consequent) || (consequentObj != null && !noun(consequentObj))) continue
                val roles = setOfNotNull(conditionVerb.index, consequenceVerb.index, edge.dependent,
                    antecedent.index, antecedentObj?.index, consequent.index, consequentObj?.index)
                if (!complete(roles, setOf(conditionVerb.index, consequenceVerb.index), setOf(",", "."))) continue
                val antecedentPredicate = (conditionVerb.lemma.ifBlank { conditionVerb.word }).lowercase()
                val consequentPredicate = (consequenceVerb.lemma.ifBlank { consequenceVerb.word }).lowercase()
                add(
                    antecedent, "implies", consequent, 0.8f,
                    antecedentTerm = clauseTerm(antecedent, antecedentPredicate, antecedentObj),
                    consequentTerm = clauseTerm(consequent, consequentPredicate, consequentObj),
                )
            }
        }
        return found.values.toSeries() // Bolt: Use native Collection.toSeries() instead of intermediate list copy
    }

    fun rules(document: NlpDocument, sourceCid: String? = null): Series<EternalRule> =
        recognize(document, sourceCid).values().map { it.rule }.toSeries()

    private fun coversSource(document: NlpDocument): Boolean {
        var sentenceEnd = 0
        for (i in 0 until document.sentences.size) {
            val sentence = document.sentences[i]
            if (sentence.index != i || sentence.begin < sentenceEnd || sentence.end <= sentence.begin ||
                sentence.end > document.text.length ||
                document.text.substring(sentenceEnd, sentence.begin).any { !it.isWhitespace() }) return false
            var tokenEnd = sentence.begin
            for (j in 0 until sentence.tokens.size) {
                val token = sentence.tokens[j]
                if (token.index != j + 1 || token.begin < tokenEnd || token.end <= token.begin ||
                    token.end > sentence.end || document.text.substring(token.begin, token.end) != token.word ||
                    document.text.substring(tokenEnd, token.begin).any { !it.isWhitespace() }) return false
                tokenEnd = token.end
            }
            if (document.text.substring(tokenEnd, sentence.end).any { !it.isWhitespace() }) return false
            sentenceEnd = sentence.end
        }
        return document.text.substring(sentenceEnd).all { it.isWhitespace() }
    }
}
