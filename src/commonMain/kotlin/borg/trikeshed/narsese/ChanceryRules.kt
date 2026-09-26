package borg.trikeshed.narsese

import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpToken

/**
 * Normative rules of a chancery treatise, read off the dependency parse.
 *
 * A rule is a clause whose head verb carries a deontic or habitual modal (`will`, `must`, `may`,
 * `shall`, `should`, `cannot`), or a present-tense generic statement whose subject is the court
 * (`Equity acts upon the person`). The subject and object are their lemma phrases (compound and
 * adjectival modifiers kept, determiners dropped); `not`/`never`/`no` on the verb flips polarity.
 *
 *     Equity will not suffer a wrong without a remedy.
 *       → ChanceryRule(subject="equity", modal="will", polarity=-, verb="suffer", object="wrong", oblique="without remedy")
 */
data class ChanceryRule(
    val subject: String,
    val modal: String,
    val affirmative: Boolean,
    val verb: String,
    val obj: String?,
    val oblique: String?,
    val sentence: String,
) {
    /** The predicate as one term: verb, object and oblique joined. */
    val predicate: String get() = listOfNotNull(verb, obj, oblique).joinToString("_") { it.replace(' ', '_') }
}

object ChanceryRules {
    private val modals = setOf("will", "must", "may", "shall", "should", "can", "cannot", "would", "ought")
    private val negators = setOf("not", "never", "no", "n't")
    private val court = setOf("equity", "chancery", "court", "chancellor")
    private val modifiers = setOf("compound", "amod", "nmod:poss")

    fun extract(doc: NlpDocument): List<ChanceryRule> {
        val out = ArrayList<ChanceryRule>()
        for (s in doc.sentences.values()) {
            val tokens = s.tokens.values().associateBy { it.index }
            val deps = s.dependencies.values()
            fun kids(head: Int, vararg rel: String) = deps.filter { d -> d.governor == head && rel.any { d.relation.lowercase().startsWith(it) } }
            fun phrase(t: NlpToken): String {
                val mods = kids(t.index, *modifiers.toTypedArray()).mapNotNull { tokens[it.dependent] }
                    .filter { it.index < t.index }.sortedBy { it.index }
                // A possessive pronoun keeps its own form: CoreNLP lemmatises "his" to "he".
                return (mods + t).joinToString(" ") { (if (it.tag == "PRP$") it.word else it.lemma).lowercase() }
            }
            val text = doc.text.substring(s.begin, s.end).replace(Regex("\\s+"), " ").trim()
            for (verb in tokens.values.filter { it.tag.startsWith("VB") }) {
                val subj = kids(verb.index, "nsubj").mapNotNull { tokens[it.dependent] }.firstOrNull() ?: continue
                if (!subj.tag.startsWith("NN")) continue
                val aux = kids(verb.index, "aux").mapNotNull { tokens[it.dependent] }
                val modal = aux.firstOrNull { it.lemma.lowercase() in modals || it.word.lowercase() in modals }
                val generic = modal == null && verb.tag in setOf("VBZ", "VBP") && subj.lemma.lowercase() in court
                if (modal == null && !generic) continue
                val negated = kids(verb.index, "advmod", "neg", "det").mapNotNull { tokens[it.dependent] }
                    .any { it.lemma.lowercase() in negators } ||
                    modal?.word?.lowercase() == "cannot"
                val obj = kids(verb.index, "obj", "dobj", "xcomp").mapNotNull { tokens[it.dependent] }.firstOrNull()
                val obl = kids(verb.index, "obl").mapNotNull { tokens[it.dependent] }.firstOrNull()?.let { o ->
                    val case = kids(o.index, "case").mapNotNull { tokens[it.dependent] }.firstOrNull()
                    listOfNotNull(case?.lemma?.lowercase(), phrase(o)).joinToString(" ")
                }
                out.add(ChanceryRule(
                    subject = phrase(subj),
                    modal = modal?.lemma?.lowercase()?.let { if (it == "can" && negated) "cannot" else it } ?: "generic",
                    affirmative = !negated,
                    verb = verb.lemma.lowercase(),
                    obj = obj?.let(::phrase),
                    oblique = obl,
                    sentence = text,
                ))
            }
        }
        return out
    }
}
