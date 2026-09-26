package borg.trikeshed.narsese

import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpToken

/**
 * A normative clause read off the dependency parse: a subject noun phrase, a deontic or habitual
 * modal (`will`, `must`, `may`, `shall`, `should`, `cannot`, …) or `generic` for a present-tense
 * statement whose subject is one of the caller's generic subjects, a polarity, and the predicate
 * (verb, object phrase, oblique phrase). Passive voice marks the verb `be_<verb>`.
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
) {
    /** The predicate as one term: verb and object. Restatements that differ only in qualification share it. */
    val predicate: String get() = listOfNotNull(verb, obj).joinToString("_") { it.replace(' ', '_') }
}

object NormClauses {
    private val modals = setOf("will", "must", "may", "shall", "should", "can", "cannot", "would", "ought")
    private val negators = setOf("not", "never", "no", "n't")
    private val modifiers = arrayOf("compound", "amod", "nmod:poss")

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
                return (mods + t).joinToString(" ") { (if (it.tag == "PRP$") it.word else it.lemma).lowercase() }
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
                val obl = kids(verb.index, "obl").mapNotNull { tokens[it.dependent] }.firstOrNull()?.let { o ->
                    val case = kids(o.index, "case").mapNotNull { tokens[it.dependent] }.firstOrNull()
                    listOfNotNull(case?.lemma?.lowercase(), phrase(o)).joinToString(" ")
                }
                out.add(NormClause(
                    subject = phrase(subj),
                    modal = modal?.lemma?.lowercase()?.let { if (it == "can" && negated) "cannot" else it } ?: "generic",
                    affirmative = !negated,
                    verb = (if (passive) "be_" else "") + verb.lemma.lowercase(),
                    obj = obj?.let(::phrase),
                    oblique = obl,
                    sentence = text,
                ))
            }
        }
        return out
    }
}
