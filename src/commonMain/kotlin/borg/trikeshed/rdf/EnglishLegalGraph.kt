package borg.trikeshed.rdf

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.DocumentQuotationCheck
import borg.trikeshed.narsese.DocumentSource

/** Reified language candidates. Subject/object roles are observations, not world predicates. */
object EnglishLegalGraph {
    const val VOCAB = "urn:trikeshed:english:legal:"
    const val MAX_QUADS = 20_000
    fun graph(cid: ContentId) = "urn:trikeshed:english:legal:${cid.hex}"

    fun project(candidateCid: ContentId, inspectionCid: ContentId, payload: Map<String, Any?>, source: DocumentSource): QuadStore {
        require(ContentId.of(CanonicalCbor.encodeMap(payload)) == candidateCid) { "Legal candidate payload CID differs" }
        require(payload["schema"] == "trikeshed.english-legal-patterns.v1" && payload["inspectionCid"] == inspectionCid.value &&
            payload["reviewStatus"] == "unreviewed" && payload["authority"] == "parser-role-and-quantity-candidates-only" &&
            payload["parserExecution"] == "not-reverified" && payload["producerAuthority"] == "not-established" &&
            payload["originalCid"] == source.originalCid.value && payload["extractedTextCid"] == source.extractedTextCid.value) {
            "Unsupported legal candidate source or authority"
        }
        val store = QuadStore(); val named = RdfTerm.Iri(graph(candidateCid)); val root = RdfTerm.Iri(named.iri + "/candidates")
        val sourceText = RdfTerm.Iri("urn:sha256:${source.extractedTextCid.hex}")
        fun emit(subject: RdfTerm.Iri, predicate: String, value: RdfTerm) {
            require(store.size < MAX_QUADS) { "Legal candidate graph exceeds 20000 quads; inspect a smaller text range" }
            val lexeme = buildString { for (ch in value.toTurtle()) if (ch.code < 32) append("\\u" + ch.code.toString(16).padStart(4, '0')) else append(ch) }
            require(TurtleRdf.parseTerm(lexeme) == value) { "RDF literal cannot preserve legal source characters" }
            store.add(subject.toTurtle(), RdfTerm.Iri(VOCAB + predicate).toTurtle(), lexeme, named.toTurtle())
        }
        fun text(subject: RdfTerm.Iri, predicate: String, value: Any?) = emit(subject, predicate, RdfTerm.Literal(EnglishObservationGraph.string(value)))
        fun integer(subject: RdfTerm.Iri, predicate: String, value: Any?) = emit(subject, predicate,
            RdfTerm.Literal(EnglishObservationGraph.integer(value).toString(), RdfVocab.XSD + "integer"))
        fun provenance(subject: RdfTerm.Iri, sentence: RdfTerm.Iri?) {
            emit(subject, "candidateReceipt", RdfTerm.Iri("urn:sha256:${candidateCid.hex}"))
            emit(subject, "inspectionReceipt", RdfTerm.Iri("urn:sha256:${inspectionCid.hex}"))
            emit(subject, "sourceText", sourceText)
            if (sentence != null) emit(subject, "sentence", sentence)
        }
        fun span(subject: RdfTerm.Iri, begin: Any?, end: Any?, literal: Any?) {
            val first = EnglishObservationGraph.integer(begin); val last = EnglishObservationGraph.integer(end)
            val quote = EnglishObservationGraph.string(literal)
            require(DocumentQuotationCheck.exactSpan(source.text, first, last, quote)) { "Legal candidate quote differs from source" }
            integer(subject, "begin", first); integer(subject, "end", last); text(subject, "quote", quote)
        }
        fun reasons(subject: RdfTerm.Iri, value: Any?) {
            for (reason in EnglishObservationGraph.list(value)) text(subject, "unresolved", reason)
        }
        provenance(root, null); text(root, "kind", "legal-pattern-inventory")
        text(root, "candidateCid", candidateCid.value); text(root, "inspectionCid", inspectionCid.value)
        text(root, "originalCid", source.originalCid.value); text(root, "extractedTextCid", source.extractedTextCid.value)
        text(root, "reviewStatus", "unreviewed"); text(root, "authority", "parser-role-and-quantity-candidates-only")
        text(root, "producerAuthority", "not-established"); text(root, "parserExecution", "not-reverified")
        text(root, "sameSentenceJoin", "cooccurrence-only-not-applicability"); reasons(root, payload["unresolved"])
        for (rawSentence in EnglishObservationGraph.list(payload["sentences"])) {
            val row = EnglishObservationGraph.map(rawSentence); val ordinal = EnglishObservationGraph.integer(row["index"])
            val sentence = RdfTerm.Iri(named.iri + "/sentence/$ordinal")
            provenance(sentence, null); text(sentence, "kind", "sentence-observation"); span(sentence, row["begin"], row["end"], row["quote"])
            reasons(sentence, row["unresolved"])
            val nodeResources = mutableMapOf<String, RdfTerm.Iri>()
            for (rawNode in EnglishObservationGraph.list(row["nodes"])) {
                val node = EnglishObservationGraph.map(rawNode)
                val index = EnglishObservationGraph.integer(node["index"]); val copy = EnglishObservationGraph.integer(node["copy"])
                val id = "$index:$copy"; require(index > 0 && node["id"] == id && copy <= 1024) { "Invalid legal pattern node identity" }
                val resource = RdfTerm.Iri(sentence.iri + "/node/$id")
                require(nodeResources.put(id, resource) == null) { "Duplicate legal pattern node" }
                provenance(resource, sentence); text(resource, "kind", "observed-node"); text(resource, "nodeKind", node["kind"])
                integer(resource, "index", index); integer(resource, "copy", copy); span(resource, node["begin"], node["end"], node["word"])
                for (field in s_["word", "lemma", "pos", "ner"].view) text(resource, field, node[field])
            }
            val virtual = RdfTerm.Iri(sentence.iri + "/node/0:0"); nodeResources["0:0"] = virtual
            provenance(virtual, sentence); text(virtual, "kind", "virtual-root")
            fun node(value: Any?) = requireNotNull(nodeResources[EnglishObservationGraph.string(value)]) { "Legal pattern refers to an unknown node" }
            fun bindingKey(edge: Map<String, Any?>) = ContentId.of(CanonicalCbor.encodeMap(_m[
                "governorNode" j edge["governorNode"], "dependentNode" j edge["dependentNode"], "relation" j edge["relation"]])).hex
            val dependencies = mutableMapOf<String, RdfTerm.Iri>()
            for (rawEdge in EnglishObservationGraph.list(row["dependencies"])) {
                val edge = EnglishObservationGraph.map(rawEdge); val key = bindingKey(edge)
                val resource = RdfTerm.Iri(sentence.iri + "/edge/$key")
                require(dependencies.put(key, resource) == null) { "Duplicate legal dependency" }
                provenance(resource, sentence); text(resource, "kind", "dependency-observation"); text(resource, "relation", edge["relation"])
                emit(resource, "governor", node(edge["governorNode"])); emit(resource, "dependent", node(edge["dependentNode"]))
            }
            fun dependency(value: Any?): RdfTerm.Iri = requireNotNull(dependencies[bindingKey(EnglishObservationGraph.map(value))]) { "Legal role dependency is not observed" }
            for ((i, rawMarker) in EnglishObservationGraph.list(row["markers"]).withIndex()) {
                val marker = EnglishObservationGraph.map(rawMarker); val resource = RdfTerm.Iri(sentence.iri + "/marker/$i")
                provenance(resource, sentence); text(resource, "kind", "lexical-marker"); text(resource, "markerKind", marker["kind"])
                text(resource, "status", "lexical-candidate"); span(resource, marker["begin"], marker["end"], marker["quote"])
            }
            for ((i, rawAction) in EnglishObservationGraph.list(row["modalActions"]).withIndex()) {
                val action = EnglishObservationGraph.map(rawAction); val resource = RdfTerm.Iri(sentence.iri + "/action/$i")
                provenance(resource, sentence); text(resource, "kind", "modal-action-candidate"); text(resource, "status", action["status"])
                text(resource, "modalLiteral", action["modalLiteral"]); text(resource, "modalLemma", action["modalLemma"])
                emit(resource, "modalNode", node(EnglishObservationGraph.map(action["modal"])["id"]))
                emit(resource, "actionNode", node(EnglishObservationGraph.map(action["action"])["id"]))
                emit(resource, "modalDependency", dependency(action["dependency"])); reasons(resource, action["unresolved"])
                for ((plural, kind) in s_["subjects" j "subject", "objects" j "object"].view) {
                    for ((j, rawRole) in EnglishObservationGraph.list(action[plural]).withIndex()) {
                        val role = EnglishObservationGraph.map(rawRole); val roleResource = RdfTerm.Iri(resource.iri + "/$kind/$j")
                        provenance(roleResource, sentence); text(roleResource, "kind", "parser-role"); text(roleResource, "roleKind", kind)
                        integer(roleResource, "ordinal", j); text(roleResource, "relation", role["relation"])
                        emit(roleResource, "node", node(EnglishObservationGraph.map(role["node"])["id"]))
                        emit(roleResource, "dependency", dependency(role["dependency"])); emit(resource, "role", roleResource)
                    }
                }
                for (edge in EnglishObservationGraph.list(action["relatedDependencies"])) emit(resource, "relatedDependency", dependency(edge))
            }
            for ((i, rawDuration) in EnglishObservationGraph.list(row["durations"]).withIndex()) {
                val duration = EnglishObservationGraph.map(rawDuration); val resource = RdfTerm.Iri(sentence.iri + "/duration/$i")
                provenance(resource, sentence); text(resource, "kind", "duration-language-candidate"); text(resource, "status", duration["status"])
                span(resource, duration["begin"], duration["end"], duration["quote"])
                text(resource, "quantityLiteral", duration["quantityLiteral"]); integer(resource, "quantityBegin", duration["quantityBegin"])
                integer(resource, "quantityEnd", duration["quantityEnd"]); text(resource, "quantityAuthority", duration["quantityAuthority"])
                duration["integerValue"]?.let { integer(resource, "integerValue", it) }
                text(resource, "unitLiteral", duration["unitLiteral"]); text(resource, "unitLemma", duration["unitLemma"])
                reasons(resource, duration["unresolved"])
                for (unit in EnglishObservationGraph.list(duration["unitNodes"])) emit(resource, "unitNode", node(EnglishObservationGraph.map(unit)["id"]))
                duration["temporalMarker"]?.let { emit(resource, "temporalMarker", node(EnglishObservationGraph.map(it)["id"])) }
                for ((j, rawModifier) in EnglishObservationGraph.list(duration["modifiers"]).withIndex()) {
                    val modifier = RdfTerm.Iri(resource.iri + "/modifier/$j")
                    provenance(modifier, sentence); text(modifier, "kind", "unit-modifier"); integer(modifier, "ordinal", j)
                    emit(modifier, "node", node(EnglishObservationGraph.map(rawModifier)["id"])); emit(resource, "modifier", modifier)
                }
                for ((j, rawReading) in EnglishObservationGraph.list(duration["numberReadings"]).withIndex()) {
                    val reading = EnglishObservationGraph.map(rawReading); val form = RdfTerm.Iri(resource.iri + "/number-form/$j")
                    provenance(form, sentence); text(form, "kind", "number-form-reading"); integer(form, "ordinal", j)
                    span(form, reading["begin"], reading["end"], reading["quote"])
                    reading["integerValue"]?.let { integer(form, "integerValue", it) }; emit(resource, "numberForm", form)
                }
                for ((field, predicate) in s_["numberDependencies" j "numberDependency", "markerDependencies" j "markerDependency", "attachments" j "attachment"].view)
                    for (edge in EnglishObservationGraph.list(duration[field])) emit(resource, predicate, dependency(edge))
            }
        }
        return store
    }
}
