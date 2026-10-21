package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.ontology.CorpusOntology
import borg.trikeshed.parse.reifyStrict

/** Retention is synchronous CAS publication within the admitted LCNC invocation. */
object CorpusOntologyNode {
    const val RETAIN = "ontology.retain"
    const val ALIGN = "ontology.align"

    fun register(runners: MutableMap<String, LcncNodeRunner>, cas: CasStore) {
        for (type in arrayOf(RETAIN, ALIGN)) runners[type] = LcncNodeRunner { node, inputs ->
            fun value(key: String): Any? = inputs[key] ?: inputs["$key?"] ?: node.params[key]?.takeIf(String::isNotBlank)
            fun structure(key: String): Any? = value(key)?.let { if (it is String) reifyStrict(it) else it }
            val mappings = (structure("mappings") as? Map<*, *>).orEmpty().entries.associate { (k, v) ->
                k.toString() to when (v) { is List<*> -> v.map { it.toString() }; is String -> listOf(v)
                    else -> error("Ontology mappings must name a class or class array") }
            }
            val sources = (structure("sourceCids") as? List<*>).orEmpty().map { ContentId(it.toString()) }
            val ontology = CorpusOntology.retain(cas, requireNotNull(value("corpus")?.toString()),
                requireNotNull(value("version")?.toString()), requireNotNull(value("kif")?.toString()),
                mappings, sources, value("sumoAlignmentCid")?.toString()?.let(::ContentId),
                if (type == ALIGN) CorpusOntology.ALIGNMENT else CorpusOntology.KIND)
            mapOf("ontologyCid" to ontology.cid.value, "ontology" to mapOf("cid" to ontology.cid.value,
                "corpus" to ontology.corpus, "version" to ontology.version, "mappings" to mappings,
                "sumoAlignmentCid" to ontology.alignment?.cid?.value))
        }
    }
}
