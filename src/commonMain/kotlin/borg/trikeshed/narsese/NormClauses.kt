package borg.trikeshed.narsese

import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpRelation
import borg.trikeshed.nlp.NlpToken

/**
 * A normative clause read off the dependency parse: a subject noun phrase, a deontic or habitual
 * modal (`will`, `must`, `may`, `shall`, `should`, `cannot`, …) or `generic` for a present-tense
 * statement whose subject is one of the caller's generic subjects, a polarity, and the predicate
 * (verb, object phrase, oblique phrase). Passive voice marks the verb `be_<verb>`. A subordinate
 * clause opened by `if`, `when`, `unless`, … is the condition: marker, its subject, its verb.
 *
 *     No one can take advantage of his own wrong.
 *       → NormClause(subject="one", modal="cannot", affirmative=false, verb="take", obj="advantage", oblique="of his own wrong")
 */
data class NormClause(
    val subject: String,
    val modal: String,
    val affirmative: Boolean,
    val verb: String,
    val obj: String?,
    val oblique: String?,
    val sentence: String,
    val condition: String? = null,
    /** The subject's head lemma and its named-entity tag (`O` when none): what types the bearer. */
    val head: String = subject.substringAfterLast(' '),
    val ner: String = "O",
) {
    /** The predicate as one term: verb and object. Restatements that differ only in qualification share it. */
    val predicate: String get() = listOfNotNull(verb, obj).joinToString("_") { it.replace(' ', '_') }
}

object NormClauses {
    private val modals = setOf("will", "must", "may", "shall", "should", "can", "cannot", "would", "ought")
    private val negators = setOf("not", "never", "no", "n't")
    private val modifiers = arrayOf("compound", "amod", "nmod:poss")
    private val conditionMarks = setOf("if", "when", "unless", "until", "where", "whenever", "before", "after", "upon")

    /** Clauses of [doc]; a modal-less present-tense clause counts only when its subject lemma is in [genericSubjects]. */
    fun extract(doc: NlpDocument, genericSubjects: Set<String> = emptySet()): List<NormClause> {
        val out = ArrayList<NormClause>()
        for (s in doc.sentences.values()) {
            val tokens = s.tokens.values().associateBy { it.index }
            val deps = s.dependencies.values()
            fun kids(head: Int, vararg rel: String) = deps.filter { d -> d.governor == head && rel.any { d.relation.lowercase().startsWith(it) } }
            fun phrase(t: NlpToken): String {
                val mods = kids(t.index, *modifiers).mapNotNull { tokens[it.dependent] }.filter { it.index < t.index }.sortedBy { it.index }
                // A possessive pronoun keeps its own form: CoreNLP lemmatises "his" to "he".
                // A possessive pronoun or a demonstrative/quantifying determiner does not name the bearer.
                return (mods.filter { !it.tag.startsWith("PRP") && it.lemma.lowercase() !in setOf("such", "said", "same") } + t)
                    .joinToString(" ") { it.lemma.lowercase() }
            }
            val text = doc.text.substring(s.begin, s.end).replace(Regex("\\s+"), " ").trim()
            for (verb in tokens.values.filter { it.tag.startsWith("VB") }) {
                val subjDep = kids(verb.index, "nsubj").firstOrNull() ?: continue
                val subj = tokens[subjDep.dependent] ?: continue
                if (!subj.tag.startsWith("NN")) continue
                // "the bill will be dismissed": the subject is the patient, so the predicate is passive.
                val passive = subjDep.relation.lowercase().endsWith(":pass")
                // A bare copula ("the case is") carries no clause.
                if (verb.lemma.lowercase() == "be" && kids(verb.index, "obj", "dobj", "xcomp", "obl").isEmpty()) continue
                val modal = kids(verb.index, "aux").mapNotNull { tokens[it.dependent] }
                    .firstOrNull { it.lemma.lowercase() in modals || it.word.lowercase() in modals }
                val generic = modal == null && verb.tag in setOf("VBZ", "VBP") && subj.lemma.lowercase() in genericSubjects
                if (modal == null && !generic) continue
                // Negation on the verb ("will not"), in the modal ("cannot"), or on the subject ("no one").
                val negated = kids(verb.index, "advmod", "neg").mapNotNull { tokens[it.dependent] }.any { it.lemma.lowercase() in negators } ||
                    kids(subj.index, "det", "advmod").mapNotNull { tokens[it.dependent] }.any { it.lemma.lowercase() in negators } ||
                    modal?.word?.lowercase() == "cannot"
                val obj = kids(verb.index, "obj", "dobj", "xcomp").mapNotNull { tokens[it.dependent] }.firstOrNull()
                    // A wh-word or pronoun object names no term: the clause it opens is not this norm's object.
                    ?.takeIf { !it.tag.startsWith("W") && !it.tag.startsWith("PRP") }
                val obl = kids(verb.index, "obl").mapNotNull { tokens[it.dependent] }.firstOrNull()?.let { o ->
                    val case = kids(o.index, "case").mapNotNull { tokens[it.dependent] }.firstOrNull()
                    listOfNotNull(case?.lemma?.lowercase(), phrase(o)).joinToString(" ")
                }
                // The condition is mark + premise; the premise reads as a fact does (subject, copula, head), and
                // a pronoun subject ("when it is due") is the clause's object, else its subject.
                val condition = kids(verb.index, "advcl").mapNotNull { tokens[it.dependent] }.firstNotNullOfOrNull { v ->
                    kids(v.index, "mark", "advmod").mapNotNull { tokens[it.dependent] }.firstOrNull { it.lemma.lowercase() in conditionMarks }?.let { mark ->
                        val s = kids(v.index, "nsubj").mapNotNull { tokens[it.dependent] }.firstOrNull()
                            ?.let { if (it.tag.startsWith("PRP")) obj ?: subj else it }
                        listOfNotNull(mark.lemma.lowercase(), s?.let(::phrase), "be".takeIf { kids(v.index, "cop").isNotEmpty() },
                            v.lemma.lowercase()).joinToString(" ")
                    }
                }
                out.add(NormClause(
                    subject = phrase(subj),
                    modal = modal?.lemma?.lowercase()?.let { if (it == "can" && negated) "cannot" else it } ?: "generic",
                    affirmative = !negated,
                    verb = (if (passive) "be_" else "") + verb.lemma.lowercase(),
                    obj = obj?.let(::phrase),
                    oblique = obl,
                    sentence = text,
                    condition = condition,
                    head = subj.lemma.lowercase(),
                    ner = subj.ner,
                ))
            }
        }
        // Every other asserted clause, as OpenIE reads it: (subject; relation; object) with the relation's
        // own polarity. A modal clause is already above; a coreferent subject reads as its chain's name.
        for (s in doc.sentences.values()) {
            if (s.relations.isEmpty() && s.kbp.isEmpty()) continue
            val tokens = s.tokens.values().associateBy { it.index }
            val text = doc.text.substring(s.begin, s.end).replace(Regex("\\s+"), " ").trim()
            val named = HashMap<IntRange, String>()
            for (m in doc.mentions) if (m.sentence == s.index && !m.representative)
                doc.mentions.firstOrNull { it.chain == m.chain && it.representative }?.let { named[m.span] = it.text }
            // Of the nested spans OpenIE emits for one clause, keep the most confident per subject head and relation.
            val best = LinkedHashMap<Pair<Int, String>, NlpRelation>()
            for (r in s.relations) {
                val rel = r.relation.mapNotNull { tokens[it] }
                if (rel.any { it.lemma.lowercase() in modals || it.word.lowercase() in modals }) continue
                val head = r.subject.lastOrNull { tokens[it]?.tag?.startsWith("NN") == true } ?: continue
                val key = head to rel.filter { it.tag.startsWith("VB") }.joinToString("_") { it.lemma.lowercase() }
                // A bare copula states no relation; its object is the predicate, which the fact tupler reads.
                if (key.second.isEmpty() || key.second == "be") continue
                // An object that is a pronoun or a whole clause is not a term.
                val objTokens = r.`object`.mapNotNull { tokens[it] }
                if (objTokens.isNotEmpty() && objTokens.all { it.tag.startsWith("W") || it.tag.startsWith("PRP") || it.tag == "DT" }) continue
                if (objTokens.any { it.tag.startsWith("VB") } && objTokens.size > 4) continue
                val prior = best[key]
                if (prior == null || r.confidence > prior.confidence ||
                    r.confidence == prior.confidence && r.`object`.count() > prior.`object`.count()) best[key] = r
            }
            for ((key, r) in best) {
                val rel = r.relation.mapNotNull { tokens[it] }
                val head = tokens[key.first]!!
                val negated = rel.any { it.lemma.lowercase() in negators }
                // Determiners, possessives and pronoun modifiers are not part of the bearer's name.
                fun term(ts: List<NlpToken>) = ts.filter { !it.tag.startsWith("DT") && !it.tag.startsWith("PRP") && it.tag != "PDT" && it.tag != "POS" &&
                    it.lemma.lowercase() !in setOf("such", "said", "same") }.joinToString(" ") { it.lemma.lowercase() }
                // A coreferent subject reads as its chain's representative mention.
                val subject = named[r.subject]?.lowercase() ?: term(r.subject.mapNotNull { tokens[it] })
                if (subject.isBlank()) continue
                val obj = term(r.`object`.mapNotNull { tokens[it] }).takeIf { it.isNotBlank() }
                out.add(NormClause(
                    subject = subject.lowercase(), modal = "generic", affirmative = !negated,
                    verb = key.second, obj = obj, oblique = null, sentence = text,
                    head = head.lemma.lowercase(), ner = head.ner,
                ))
            }
            // Stanford relation extraction (KBP): typed relations between named mentions (org:founded_by,
            // per:spouse), asserted facts; the slot name is the verb, the mentions are bearer and object.
            for (r in s.kbp) {
                val head = r.subject.lastOrNull()?.let { tokens[it] } ?: continue
                // Without coref a pronoun names no one: neither bearer nor object is a term then.
                if (head.tag.startsWith("PRP") || r.`object`.mapNotNull { tokens[it] }.all { it.tag.startsWith("PRP") }) continue
                out.add(NormClause(
                    subject = r.subjectText.lowercase(), modal = "generic", affirmative = true,
                    verb = r.relationText, obj = r.objectText.lowercase(), oblique = null, sentence = text,
                    head = head.lemma.lowercase(), ner = head.ner,
                ))
            }
        }
        return out
    }

    /**
     * Facts of [doc] in premise form: per subject, its phrase, `be` when the head is copular, and the
     * head lemma ("The rent is due." → "rent be due"), so a fact matches the premise of a condition.
     */
    fun facts(doc: NlpDocument): Set<String> {
        val out = LinkedHashSet<String>()
        for (s in doc.sentences.values()) {
            val tokens = s.tokens.values().associateBy { it.index }
            val deps = s.dependencies.values()
            fun kids(head: Int, vararg rel: String) = deps.filter { d -> d.governor == head && rel.any { d.relation.lowercase().startsWith(it) } }
            fun phrase(t: NlpToken) = (kids(t.index, *modifiers).mapNotNull { tokens[it.dependent] }.filter { it.index < t.index }.sortedBy { it.index } + t)
                .joinToString(" ") { (if (it.tag == "PRP$") it.word else it.lemma).lowercase() }
            for (d in deps) if (d.relation.lowercase().startsWith("nsubj")) {
                val head = tokens[d.governor] ?: continue
                val subj = tokens[d.dependent]?.takeIf { it.tag.startsWith("NN") } ?: continue
                out.add(listOfNotNull(phrase(subj), "be".takeIf { kids(head.index, "cop").isNotEmpty() }, head.lemma.lowercase()).joinToString(" "))
            }
        }
        return out
    }
}
