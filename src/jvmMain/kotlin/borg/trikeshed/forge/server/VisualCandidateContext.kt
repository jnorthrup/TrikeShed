package borg.trikeshed.forge.server

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.values
import borg.trikeshed.cursor.query.names
import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentCurationFacts
import borg.trikeshed.rdf.RdfTerm
import borg.trikeshed.rdf.SparqlResults

/**
 * Optional context for explicitly projected live document-proposal identities.
 * These are reported fact fields, not verified CAS quotations or query witnesses.
 * The ontology-backed book lane and review decisions are separate.
 */
internal object VisualCandidateContext {
    private const val LIMIT = 4
    private const val FIELD_CHARS = 128
    private val fields = setOf("curationCid", "originalCid", "textCid", "proposalCid",
        "quotationReceiptCid", "quote", "begin", "end", "quotationBegin", "quotationEnd",
        "sourceRecordId", "sourceRecordCid", "sourceCurrent", "subject", "predicate", "object", "polarity", "modality")

    fun extract(variable: String, results: Any?, live: Cursor): Map<String, Any?> {
        val root = results as? Map<*, *>
        val data = root?.get("results") as? Map<*, *>
        val bindings = data?.get("bindings") as? List<*> ?: emptyList<Any?>()
        val projected = LinkedHashSet<String>()
        for (row in bindings) {
            val value = (row as? Map<*, *>)?.get(variable) as? Map<*, *> ?: continue
            if (value["type"] != "uri") continue
            val iri = value["value"] as? String ?: continue
            val id = PlaneFacts.factIdOf(RdfTerm.Iri(iri)) ?: continue
            if (id.a == DocumentCurationFacts.PARTITION) {
                projected += iri
                if (projected.size > LIMIT) break
            }
        }
        val selected = projected.take(LIMIT).toSet()
        val found = LinkedHashMap<String, MutableMap<String, MutableSet<String>>>()
        val omitted = LinkedHashMap<String, MutableSet<String>>()
        if (live.size > 0 && selected.isNotEmpty()) {
            val names = live.names
            fun column(name: String) = (0 until names.size).firstOrNull {
                names[it].contentEquals(name) || names[it].endsWith(".$name")
            } ?: error("live facts have no $name column")
            val s = column("S"); val p = column("P"); val o = column("O")
            for (r in 0 until live.size) {
                val values = live[r].values
                val subject = SparqlResults.termOf(values[s]) ?: continue
                if (subject.type != "uri" || subject.value !in selected) continue
                val predicate = SparqlResults.termOf(values[p]) ?: continue
                if (predicate.type != "uri" || !predicate.value.startsWith(PlaneFacts.FIELD_NS)) continue
                val key = predicate.value.removePrefix(PlaneFacts.FIELD_NS)
                if (key != PlaneFacts.KIND && key !in fields) continue
                val value = SparqlResults.termOf(values[o]) ?: continue
                if (value.type != "literal") continue
                val valuesForField = found.getOrPut(subject.value) { LinkedHashMap() }.getOrPut(key) { LinkedHashSet() }
                // Keep at most two distinct values so conflicts remain visible. Report
                // omitted values explicitly; never present a clipped quote as complete.
                if (value.value.length > FIELD_CHARS || value.value !in valuesForField && valuesForField.size >= 2)
                    omitted.getOrPut(subject.value) { LinkedHashSet() }.add(key)
                else valuesForField.add(value.value)
            }
        }
        val candidates = ArrayList<Map<String, Any?>>()
        val unresolved = ArrayList<String>()
        for (iri in projected.take(LIMIT)) {
            val data = found[iri]
            if (data == null || data[PlaneFacts.KIND] != setOf(DocumentCurationFacts.PROPOSAL) ||
                PlaneFacts.KIND in omitted[iri].orEmpty()) {
                unresolved += iri
                continue
            }
            candidates += mapOf("fact" to iri,
                "reviewStatus" to "not-established",
                "fields" to data.filterKeys { it in fields }.mapValues { it.value.toList() },
                "omittedFields" to omitted[iri].orEmpty().filter { it in fields })
        }
        return mapOf("variable" to variable, "candidates" to candidates, "unresolved" to unresolved,
            "truncated" to (projected.size > LIMIT),
            "scope" to "Related live proposal fields only; quotations and sourceCurrent are not independently verified; approval and query witnesses are not established. The live snapshot and quad store are sampled separately.")
    }
}
