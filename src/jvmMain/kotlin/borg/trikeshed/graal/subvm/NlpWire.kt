package borg.trikeshed.graal.subvm

import borg.trikeshed.lib.*
import borg.trikeshed.nlp.NlpDependency
import borg.trikeshed.nlp.NlpDocument
import borg.trikeshed.nlp.NlpMention
import borg.trikeshed.nlp.NlpMetadata
import borg.trikeshed.nlp.NlpRelation
import borg.trikeshed.nlp.NlpSentence
import borg.trikeshed.nlp.NlpToken
import borg.trikeshed.parse.jsonOf

/** [NlpDocument] across the CoreNLP worker's pipe: every field, rows as positional arrays. */
internal object NlpWire {
    fun encode(doc: NlpDocument): String = jsonOf(mapOf(
        "text" to doc.text,
        "sentences" to List(doc.sentences.size) { k -> val s = doc.sentences[k]; listOf(
            s.index, s.begin, s.end,
            List(s.tokens.size) { i -> val t = s.tokens[i]; listOf(t.index, t.begin, t.end, t.word, t.lemma, t.tag, t.ner) },
            List(s.dependencies.size) { i -> val d = s.dependencies[i]; listOf(d.governor, d.dependent, d.relation) },
            s.relations.map(::relation), s.kbp.map(::relation),
        ) },
        "metadata" to doc.metadata?.let { m -> mapOf("processor" to m.processor, "implementation" to m.implementation,
            "runtime" to m.runtime, "configuration" to m.configuration) },
        "mentions" to doc.mentions.map { m -> listOf(m.sentence, m.span.first, m.span.last, m.text, m.chain, m.representative) },
    ))

    private fun relation(r: NlpRelation): List<Any?> = listOf(
        r.subject.first, r.subject.last, r.relation.first, r.relation.last, r.`object`.first, r.`object`.last,
        r.subjectText, r.relationText, r.objectText, r.confidence)

    private fun int(v: Any?): Int = (v as Number).toInt()
    private fun list(v: Any?): List<*> = v as List<*>

    private fun relation(v: Any?): NlpRelation = list(v).let { r -> NlpRelation(
        int(r[0])..int(r[1]), int(r[2])..int(r[3]), int(r[4])..int(r[5]),
        r[6] as String, r[7] as String, r[8] as String, (r[9] as Number).toDouble()) }

    fun decode(m: Map<String, Any?>): NlpDocument {
        val sentences = list(m["sentences"]).map { v -> list(v).let { s ->
            val tokens = list(s[3]).map { t -> list(t).let { NlpToken(int(it[0]), int(it[1]), int(it[2]),
                it[3] as String, it[4] as String, it[5] as String, it[6] as String) } }
            val deps = list(s[4]).map { d -> list(d).let { NlpDependency(int(it[0]), int(it[1]), it[2] as String) } }
            NlpSentence(int(s[0]), int(s[1]), int(s[2]), tokens.size j { tokens[it] }, deps.size j { deps[it] },
                list(s[5]).map(::relation), list(s[6]).map(::relation))
        } }
        @Suppress("UNCHECKED_CAST")
        val metadata = (m["metadata"] as? Map<String, Any?>)?.let { d -> NlpMetadata(d["processor"] as String,
            d["implementation"] as String, d["runtime"] as Map<String, String>, d["configuration"] as Map<String, String>) }
        val mentions = list(m["mentions"]).map { v -> list(v).let { NlpMention(int(it[0]), int(it[1])..int(it[2]),
            it[3] as String, int(it[4]), it[5] as Boolean) } }
        return NlpDocument(m["text"] as String, sentences.size j { sentences[it] }, metadata, mentions)
    }
}
