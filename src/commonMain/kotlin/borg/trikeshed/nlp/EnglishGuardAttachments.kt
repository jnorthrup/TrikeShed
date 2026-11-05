package borg.trikeshed.nlp

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.NormConditionIssue

/** Direct reported bindings only. Neither a guard interpretation nor a polarity/quotation resolver. */
object EnglishGuardAttachments {
    const val KIND = "english-guard-attachment-observations/1"
    const val PROFILE = "observed-advcl-mark-bindings/1"
    const val COVERAGE = "all-observed-advcl-edges-and-condition-exception-marker-occurrences"
    const val MAX_ATTACHMENTS = 1024

    private fun map(value: Any?): Map<String, Any?> {
        require(value is Map<*, *> && value.keys.all { it is String }) { "Invalid attachment observation map" }
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }
    private fun rows(value: Any?): List<Map<String, Any?>> {
        require(value is List<*> && value.size <= EnglishInspection.MAX_DEPENDENCIES) { "Attachment observation row limit" }
        return value.map(::map)
    }
    private fun number(value: Any?): Int {
        require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() >= 0) { "Invalid attachment ordinal or span" }
        return value.toInt()
    }
    private fun tuple(edge: Map<String, Any?>) = _m[
        "governorNode" j edge["governorNode"], "dependentNode" j edge["dependentNode"], "relation" j edge["relation"]]

    /** The caller supplies the EXISTING norm diagnostic, never a new interpretation of this section. */
    fun section(sentences: List<Map<String, Any?>>, diagnostics: List<String?>,
        reserveBinding: () -> Unit = {}): Map<String, Any?> {
        require(sentences.size <= EnglishInspection.MAX_SENTENCES && diagnostics.size == sentences.size)
        var attachmentCount = 0; var markerCount = 0; var bindingCount = 0
        var nodeCount = 0; var edgeCount = 0; var observedMarkerCount = 0
        var alignedCount = 0; var cueEdgeCount = 0
        fun binding(edge: Map<String, Any?>): Map<String, Any?> {
            require(bindingCount < EnglishLegalPatterns.MAX_BINDINGS) { "Guard dependency binding limit" }
            reserveBinding(); bindingCount++
            return tuple(edge)
        }
        val result = sentences.mapIndexed { ordinal, sentence ->
            val diagnostic = diagnostics[ordinal]
            require(diagnostic == null || NormConditionIssue.values().any { it.name == diagnostic }) { "Unknown norm diagnostic" }
            val nodes = rows(sentence["nodes"])
            require(nodes.map { it["id"] }.distinct().size == nodes.size) { "Duplicate guard observation node" }
            val edges = rows(sentence["dependencies"])
            val byDependent = edges.groupBy { it["dependentNode"] }
            val markers = rows(sentence["markers"])
            val actions = rows(sentence["modalActions"])
            nodeCount += nodes.size; edgeCount += edges.size; observedMarkerCount += markers.size
            require(nodeCount <= EnglishInspection.MAX_NODES && edgeCount <= EnglishInspection.MAX_DEPENDENCIES &&
                observedMarkerCount <= EnglishInspection.MAX_MARKERS) { "Guard observation inventory limit" }
            val links = edges.filter { (it["relation"] as String).lowercase().let { name -> name == "advcl" || name.startsWith("advcl:") } }
            require(links.size <= MAX_ATTACHMENTS - attachmentCount) { "Guard attachment limit" }
            attachmentCount += links.size
            val quoteIds = nodes.filter { (it["word"] as String).any { ch -> ch in "\"“”‘’" } }.map { it["id"] }
            val coordination = edges.filter { (it["relation"] as String).lowercase().let { name -> name == "cc" || name.startsWith("conj") } }
            val issues = mutableListOf("legal-force-not-established", "guard-and-negation-scope-not-established")
            if (quoteIds.isNotEmpty()) issues.add("quotation-indicators-present-scope-not-established")
            if (coordination.isNotEmpty()) issues.add("coordination-scope-not-established")
            if (diagnostic == NormConditionIssue.COPIED_NODE.name) issues.add("copied-node-interpretation-not-established")
            if (diagnostic == NormConditionIssue.UNREPORTED_IDENTITY.name) issues.add("parser-node-identity-unreported")
            fun aligned(marker: Map<String, Any?>) = nodes.filter {
                val matches = number(it["begin"]) >= number(marker["begin"]) && number(it["end"]) <= number(marker["end"])
                if (matches) require(alignedCount++ < EnglishLegalPatterns.MAX_BINDINGS) { "Guard marker node reference limit" }
                matches
            }
            val markerBindings = markers.withIndex().filter { it.value["kind"] in _s["condition", "exception"] }.map { entry ->
                markerCount++
                val aligned = aligned(entry.value)
                val nodeIds = aligned.map { it["id"] }
                val dependencies = aligned.flatMap { byDependent[it["id"]].orEmpty() }
                    .filter { (it["relation"] as String).lowercase() in _s["mark", "advmod"] }
                val unresolved = mutableListOf<String>()
                if (aligned.isEmpty()) unresolved.add("marker-node-alignment-not-established")
                if (aligned.map { it["index"] }.distinct().size > 1) unresolved.add("multi-token-marker-scope-not-established")
                if (dependencies.isEmpty()) unresolved.add("no-direct-marker-binding")
                if (dependencies.size > 1) unresolved.add("multiple-direct-marker-bindings")
                _m["markerOrdinal" j entry.index, "marker" j entry.value, "nodeIds" j nodeIds,
                    "dependencies" j dependencies.map(::binding), "unresolved" j unresolved]
            }
            val cueEdges = markers.withIndex().filter { it.value["kind"] == "negation" }.flatMap { entry ->
                aligned(entry.value).flatMap { cue -> byDependent[cue["id"]].orEmpty().map { edge ->
                    require(cueEdgeCount++ < EnglishLegalPatterns.MAX_BINDINGS) { "Guard cue reference limit" }
                    _m["markerOrdinal" j entry.index, "cueNodeId" j cue["id"], "governorNodeId" j edge["governorNode"], "dependency" j edge]
                } }
            }
            val attachments = links.mapIndexed { index, edge ->
                val governor = edge["governorNode"]; val subordinate = edge["dependentNode"]
                val linkedMarkers = markerBindings.filter { marker -> rows(marker["dependencies"]).any { it["governorNode"] == subordinate } }
                val markerOrdinals = linkedMarkers.map { it["markerOrdinal"] }
                val actionOrdinals = actions.withIndex().filter { map(it.value["action"])["id"] == governor }.map { it.index }
                val negation = cueEdges.map { cue ->
                        cue + _m["dependency" j binding(map(cue["dependency"])), "location" j when (cue["governorNodeId"]) {
                                governor -> "governor-head"; subordinate -> "subordinate-head"; else -> "other-observed-head"
                            }]
                }
                val unresolved = issues.toMutableList()
                if (markerOrdinals.isEmpty()) unresolved.add("no-direct-guard-marker-binding")
                if (linkedMarkers.map { map(it["marker"]).let { marker -> "${marker["begin"]}:${marker["end"]}" } }.distinct().size > 1)
                    unresolved.add("multiple-distinct-marker-occurrences")
                if (actionOrdinals.isEmpty()) unresolved.add("no-direct-modal-action-owner")
                _m["ordinal" j index, "status" j "parser-attachment-candidate", "dependency" j binding(edge),
                    "governorNodeId" j governor, "subordinateNodeId" j subordinate,
                    "modalActionOrdinals" j actionOrdinals, "markerBindingOrdinals" j markerOrdinals,
                    "directNegationCueBindings" j negation, "coordinationDependencies" j coordination.map(::binding), "unresolved" j unresolved]
            }
            _m["index" j sentence["index"], "normConditionIssue" j diagnostic,
                "normDiagnosticProducer" j "NormClauses.conditionIssue", "markerBindings" j markerBindings,
                "attachments" j attachments, "quotationIndicatorNodeIds" j quoteIds, "unresolved" j issues]
        }
        return _m["kind" j KIND, "profile" j PROFILE, "coverage" j COVERAGE,
            "meaning" j "parser-attachment-candidates-only", "sentences" j result,
            "counts" j _m["attachments" j attachmentCount, "markerOccurrences" j markerCount, "dependencyBindings" j bindingCount]]
    }

    /** Exact projection/link consistency only; diagnostic provenance and parser execution are not authenticated here. */
    fun validate(value: Any?, sentences: List<*>): Map<String, Any?> {
        val actual = map(value)
        require(actual["kind"] == KIND && actual["profile"] == PROFILE) { "Unknown guard attachment section" }
        val rows = rows(actual["sentences"])
        require(rows.size == sentences.size && sentences.size <= EnglishInspection.MAX_SENTENCES) { "Guard attachment sentence coverage differs" }
        val expected = section(sentences.map(::map), rows.map { it["normConditionIssue"]?.let { issue ->
            require(issue is String) { "Invalid norm diagnostic" }; issue
        } })
        require(CanonicalCbor.encodeMap(expected).contentEquals(CanonicalCbor.encodeMap(actual))) {
            "Guard attachment coverage, source references or bindings differ from the observed sentence"
        }
        return actual
    }
}
