package borg.trikeshed.narsese

import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpToken

/**
 * The parties a contract defines for itself, read off CoreNLP's tokens: a head (a pronoun, or a named organization's
 * run of tokens) followed by a parenthesis of quoted terms, as `between you (also referred to herein as "User," or
 * "customer") and Coinbase, Inc. ("Coinbase," "we," "us," and "our")`. A definition names a party only when it binds
 * a personal pronoun. The party's noun is its first quoted term that is not a pronoun, as a lemma; every quoted term,
 * the head and each bound pronoun's lemma (CoreNLP lemmatises "your" to "you", "our" and "us" to "we") alias it.
 * The party's named-entity tag is its definition's (`ORGANIZATION` for "Coinbase, Inc."), `O` where none is named.
 */
object PartyAliases {
    class Party(val noun: String, val ner: String)

    /** Lower-cased lemma → the party. */
    fun of(doc: NlpDocument): Map<String, Party> {
        val out = LinkedHashMap<String, Party>()
        for (s in doc.sentences.values()) {
            val t = s.tokens.values()
            for (open in t.indices) {
                if (t[open].tag != "-LRB-" || open == 0) continue
                val close = (open + 1 until t.size).firstOrNull { t[it].tag == "-RRB-" } ?: continue
                // Quoted terms: a quote opens; its next quote closes, whichever way the tokenizer turned a straight one.
                val terms = ArrayList<List<NlpToken>>()
                var q = -1
                for (k in open + 1 until close) {
                    if (t[k].tag != "``" && t[k].tag != "''") continue
                    val words = if (q >= 0) t.subList(q + 1, k).filter { it.tag != "," && it.tag != "." } else emptyList()
                    if (words.isNotEmpty()) { terms.add(words); q = -1 } else q = if (t[k].tag == "``") k else -1
                }
                if (terms.isEmpty()) continue
                // The head: the pronoun or noun run just before the parenthesis, a named entity's run whole ("Coinbase , Inc.").
                val last = t[open - 1]
                var h = open - 1
                while (h >= 0 && (t[h].tag.startsWith("NN") || t[h].tag.startsWith("PRP") || last.ner != "O" && t[h].ner == last.ner)) h--
                val head = t.subList(h + 1, open).filter { it.tag.startsWith("NN") || it.tag.startsWith("PRP") }
                val all = terms + listOf(head)
                // A personal pronoun binds a party; a possessive ("your Coinbase Account") only marks a defined term.
                if (all.none { term -> term.any { it.tag == "PRP" } }) continue
                val noun = (terms + listOf(head)).firstOrNull { term -> term.isNotEmpty() && term.none { it.tag.startsWith("PRP") } }?.let(::lemma) ?: continue
                val party = Party(noun, all.flatten().firstOrNull { it.ner != "O" }?.ner ?: "O")
                for (term in all) if (term.isNotEmpty()) out.getOrPut(lemma(term)) { party }
            }
        }
        return out
    }

    private fun lemma(term: List<NlpToken>): String = term.joinToString(" ") { it.lemma.lowercase() }
}
