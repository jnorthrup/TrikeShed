package borg.trikeshed.ontology

import borg.trikeshed.job.*
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.lib.*
import kotlin.collections.filter

enum class OntologyMappingState { RESOLVED, UNRESOLVED, AMBIGUOUS, CONFLICT }

/** A corpus owns its vocabulary. SUMO is a separate, explicitly retained alignment. */
class CorpusOntology(val cid: ContentId, val corpus: String, val version: String,
    val classifier: SumoClassifier, val mappings: Map<String, List<String>>,
    val alignment: CorpusOntology? = null) {

    fun mapping(term: String): Map<String, Any?> {
        val selected = mappings[term.lowercase()] ?: if (classifier.termId(term) >= 0) listOf(term)
            else classifier.terms.view.filter { it.equals(term, ignoreCase = true) }
        val known = selected.filter { classifier.termId(it) >= 0 }
        val closure = known.flatMap { classifier.classesOf(it).view }.distinct()
        val conflict = closure.any { a -> closure.any { b -> classifier.disjoint(a, b) } }
        val minimal = known.filter { a -> known.none { b -> a != b && classifier.subclassOf(b, a) } }
        val status = when {
            conflict -> OntologyMappingState.CONFLICT
            selected.isEmpty() || known.size != selected.size -> OntologyMappingState.UNRESOLVED
            minimal.size > 1 -> OntologyMappingState.AMBIGUOUS
            else -> OntologyMappingState.RESOLVED
        }
        return mapOf("term" to term, "ontologyCid" to cid.value, "corpus" to corpus, "version" to version,
            "status" to status.name.lowercase(), "classes" to known,
            "unresolved" to if (selected.isEmpty()) listOf(term) else (selected - known.toSet()),
            "closure" to closure, "derivation" to "taxonomy", "causalProof" to false,
            "alignment" to alignment?.let { a -> mapOf("ontologyCid" to a.cid.value, "version" to a.version,
                "mappings" to known.map(a::mapping)) })
    }

    companion object {
        const val KIND = "corpus-ontology"
        const val ALIGNMENT = "sumo-alignment"

        fun retain(cas: CasStore, corpus: String, version: String, kif: String,
            mappings: Map<String, List<String>>, sourceCids: List<ContentId> = emptyList(),
            alignmentCid: ContentId? = null, kind: String = KIND): CorpusOntology {
            require(corpus.isNotBlank() && version.isNotBlank()) { "Ontology corpus and version are required" }
            require(kind == KIND || kind == ALIGNMENT)
            val classifier = SumoClassifier.of(KifExpr.parseAll(kif, strict = true))
            require(classifier.classCount > 0) { "Ontology must declare its own classes" }
            require(sourceCids.all { cas.get(it) != null }) { "Ontology source is not retained" }
            val alignment = alignmentCid?.let { read(cas, it, ALIGNMENT) }
            require(kind != ALIGNMENT || alignment == null) { "SUMO alignment cannot contain an alignment" }
            val canonical = mappings.entries.sortedBy { it.key }.associate { (term, classes) ->
                require(term.isNotBlank() && classes.isNotEmpty() && classes.all(String::isNotBlank))
                term.lowercase() to classes.distinct().sorted()
            }
            require(canonical.size == mappings.size) { "Ontology has duplicate case-insensitive terms" }
            val bytes = CanonicalCbor.encodeMap(mapOf("kind" to kind, "corpus" to corpus, "version" to version,
                "kif" to kif, "mappings" to canonical, "sourceCids" to sourceCids.map { it.value },
                "sumoAlignmentCid" to alignmentCid?.value))
            val cid = cas.put(bytes)
            require(cas.get(cid)?.contentEquals(bytes) == true) { "Ontology retention failed" }
            cas.sync()
            return CorpusOntology(cid, corpus, version, classifier, canonical, alignment)
        }

        fun read(cas: CasStore, cid: ContentId, kind: String = KIND): CorpusOntology {
            val m = CanonicalCbor.decodeMap(requireNotNull(cas.get(cid)) { "Ontology is not retained: $cid" })
            require(m["kind"] == kind) { "Retained object is not a $kind" }
            val classifier = SumoClassifier.of(KifExpr.parseAll(m["kif"] as String, strict = true))
            @Suppress("UNCHECKED_CAST")
            val mappings = (m["mappings"] as Map<String, List<String>>)
            val alignment = (m["sumoAlignmentCid"] as? String)?.let { read(cas, ContentId(it), ALIGNMENT) }
            return CorpusOntology(cid, m["corpus"] as String, m["version"] as String, classifier, mappings, alignment)
        }
    }
}
