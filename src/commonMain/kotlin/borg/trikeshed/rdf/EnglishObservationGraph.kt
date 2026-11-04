package borg.trikeshed.rdf

import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.nlp.*
import borg.trikeshed.parse.*

/** Recorded observations are queryable claims, never proof that the named parser executed. */
object EnglishObservationGraph {
    const val VOCAB = "urn:trikeshed:english:observation:"
    const val MAX_QUADS = 20_000
    const val MAX_RECEIPT = 8 * 1024 * 1024

    class Snapshot internal constructor(val receiptCid: ContentId, val source: DocumentSource,
        val inventory: Map<String, Any?>, val selection: DocumentRevision,
        val sourceSnapshots: List<ProjectDoc>?, val legacyIdentity: Boolean,
        /** Reconstructed and source-checked observations, not a newly executed parser result. */
        val parsed: NlpDocument) {
        val graph: String get() = "urn:trikeshed:english:${receiptCid.hex}"
        fun descriptor(mode: String): Map<String, Any?> = _m["selected" j "english-receipt-observations",
            "receiptCid" j receiptCid.value, "namedGraph" j graph,
            "originalCid" j source.originalCid.value, "extractedTextCid" j source.extractedTextCid.value,
            "mode" j mode, "historical" j (mode == "snapshot"), "reviewStatus" j "unreviewed",
            "scope" j "recorded-lexical-and-parser-claims", "semanticAuthority" j "not-established",
            "producerAuthority" j "not-established", "parserExecution" j "not-reverified",
            "dependencyNodeIdentity" j if (legacyIdentity) "legacy-unreported" else "recorded-index-and-copy",
            "parserDependencyNodeIdentity" j map(inventory["coverage"])["parserDependencyNodeIdentity"],
            "parserNodeIdentityReported" j (map(inventory["coverage"])["parserNodeIdentityReported"] == true),
            "bindingProvenance" j "receipt-named-resources-and-exact-source-spans"]
    }

    /** Every read is bounded and hashed. Rebuilding the inventory checks quotations, not execution provenance. */
    fun read(cas: CasStore, cid: ContentId): Snapshot {
        val reader = cas as? BoundedCasReader ?: throw UnsupportedOperationException("Bounded English receipt reads unavailable")
        fun bytes(id: ContentId, cap: Int): ByteArray {
            val value = requireNotNull(reader.getBounded(id, cap)) { "Retained English source or receipt unavailable" }
            require(value.size <= cap && ContentId.of(value) == id) { "English retained CID mismatch" }
            return value
        }
        val receiptBytes = bytes(cid, MAX_RECEIPT)
        val receipt = CanonicalCbor.decodeMapBounded(receiptBytes, MAX_RECEIPT, 32, 500_000)
        require(CanonicalCbor.encodeMap(receipt).contentEquals(receiptBytes)) { "English receipt is not canonical CBOR" }
        val encodedSource = map(receipt["source"])
        val extractedCid = ContentId(string(encodedSource["extractedTextCid"]))
        val textBytes = bytes(extractedCid, 4 * 1024 * 1024)
        val text = textBytes.decodeToString(throwOnInvalidSequence = true)
        require(text.encodeToByteArray().contentEquals(textBytes)) { "English source is not exact UTF-8" }
        val source = DocumentCuratorCodec.decodeSource(encodedSource + _m["text" j text])
        require(CanonicalCbor.encodeMap(encodedSource).contentEquals(CanonicalCbor.encodeMap(DocumentCuratorCodec.source(source) - "text"))) {
            "English source metadata differs from its supported schema"
        }
        bytes(source.originalCid, 32 * 1024 * 1024)
        val selected = map(receipt["selection"])
        require(selected.keys == _s["project", "id", "cid", "rev", "seq"]) { "Invalid English selection fields" }
        val selection = revision(selected)
        require(source.project == selection && selection.cid == source.originalCid.value) { "English receipt selection differs from source" }
        val extraction = requireNotNull(source.extraction) { "English receipt extraction revision unavailable" }
        require(extraction.cid == source.extractedTextCid.value && extraction.project == selection.project) { "English extraction identity differs" }
        val range = map(receipt["range"]); val begin = integer(range["begin"]); val end = integer(range["end"])
        require(begin < end && end <= text.length && end - begin <= EnglishInspection.MAX_CHARS) { "Invalid English receipt range" }
        val sentences = list(receipt["sentences"])
        val counts = map(receipt["counts"])
        val legacy = "nodes" !in counts && sentences.all { "nodes" !in map(it) }
        require(legacy || "nodes" in counts && sentences.all { "nodes" in map(it) }) { "Mixed dependency identity formats" }
        val parsed = NlpDocument(text.substring(begin, end), (sentences.toSeries() α { raw ->
            val row = map(raw)
            val tokens = list(row["tokens"]).toSeries() α { rawToken ->
                val token = map(rawToken)
                NlpToken(integer(token["index"]), integer(token["begin"]) - begin, integer(token["end"]) - begin,
                    string(token["word"]), string(token["lemma"]), string(token["pos"]), string(token["ner"]))
            }
            val dependencies = list(row["dependencies"]).toSeries() α { rawEdge ->
                val edge = map(rawEdge)
                if (legacy) require("governorCopy" !in edge && "dependentCopy" !in edge && "governorNode" !in edge && "dependentNode" !in edge) {
                    "Legacy receipt carries unreported dependency identity"
                }
                NlpDependency(integer(edge["governor"]), integer(edge["dependent"]), string(edge["relation"]),
                    if (legacy) 0 else integer(edge["governorCopy"]), if (legacy) 0 else integer(edge["dependentCopy"]))
            }
            NlpSentence(integer(row["index"]), integer(row["begin"]) - begin, integer(row["end"]) - begin, tokens, dependencies)
        }), receipt["parser"]?.let { raw ->
            val parser = map(raw)
            fun strings(value: Any?) = map(value).mapValues { string(it.value) }
            NlpMetadata(string(parser["processor"]), string(parser["implementation"]), strings(parser["runtime"]), strings(parser["configuration"]))
        })
        val regenerated = EnglishInspection.inspect(source, parsed, begin, end)
        val expected = if (legacy) legacy(regenerated) else regenerated
        require(receipt.keys == expected.keys + _s["source", "selection"] ||
            receipt.keys == expected.keys + _s["source", "selection", "sourceSnapshots"]) { "Unexpected English receipt fields" }
        val actual = receipt.filterKeys { it in expected }
        require(CanonicalCbor.encodeMap(actual).contentEquals(CanonicalCbor.encodeMap(expected))) { "English receipt observations differ from exact source reconstruction" }
        val snapshots = receipt["sourceSnapshots"]?.let { raw -> list(raw).map { projectDoc(map(it)) } }
        if (snapshots != null) validateSnapshots(source, selection, snapshots, ::bytes)
        else require(source.originalCid == source.extractedTextCid || source.provenance["extractionLineageCid"]?.let { lineage ->
            validateLineage(source, json(bytes(ContentId(lineage), 65_536))); true
        } == true) { "Historical extraction lineage unavailable" }
        return Snapshot(cid, source, regenerated, selection, snapshots, legacy, parsed)
    }

    /** Reassemble the exact retained payload, including legacy identity spelling, without reparsing. */
    fun retained(snapshot: Snapshot): Map<String, Any?> {
        val inventory = if (snapshot.legacyIdentity) legacy(snapshot.inventory) else snapshot.inventory
        val receipt = inventory + _m["source" j (DocumentCuratorCodec.source(snapshot.source) - "text"),
            "selection" j snapshot.selection.toMap()] +
            (snapshot.sourceSnapshots?.let { _m["sourceSnapshots" j it.map(ProjectDoc::toMap)] } ?: emptyMap())
        require(ContentId.of(CanonicalCbor.encodeMap(receipt)) == snapshot.receiptCid) { "Reopened English receipt identity differs" }
        return receipt
    }

    internal fun validateSnapshots(source: DocumentSource, selected: DocumentRevision, rows: List<ProjectDoc>,
        read: (ContentId, Int) -> ByteArray) {
        require(rows.size in 1..4 && rows.map { it.project j it.id }.distinct().size == rows.size) { "Invalid English source snapshots" }
        val byId = rows.associateBy { it.id }
        require(rows.all { it.project == selected.project }) { "English snapshots cross projects" }
        fun matches(row: ProjectDoc?, revision: DocumentRevision) = row != null && row.project == revision.project &&
            row.id == revision.id && row.cid == revision.cid && row.rev == revision.rev && row.seq == revision.seq
        require(matches(byId[selected.id], selected)) { "Original snapshot differs from selection" }
        val extracted = requireNotNull(source.extraction)
        require(matches(byId[extracted.id], extracted)) { "Extraction snapshot differs from source" }
        val expectedIds = if (extracted.id == selected.id) _s[selected.id] else
            _s[selected.id, extracted.id, selected.id + ProjectNodes.EXTRACTION_SUFFIX, selected.id + ProjectNodes.EXTRACT_ORIGIN_SUFFIX]
        require(byId.keys == expectedIds) { "Unexpected English source snapshots" }
        for (row in rows) {
            val cap = when (row.id) {
                selected.id -> 32 * 1024 * 1024
                extracted.id -> 4 * 1024 * 1024
                selected.id + ProjectNodes.EXTRACTION_SUFFIX -> 65_536
                selected.id + ProjectNodes.EXTRACT_ORIGIN_SUFFIX -> 1024
                else -> error("Unexpected English source snapshot")
            }
            require(row.length <= cap && read(ContentId(row.cid), cap).size.toLong() == row.length) { "Snapshot retained length differs" }
        }
        if (extracted.id == selected.id) {
            require(rows.size == 1 && source.originalCid == source.extractedTextCid) { "Identity extraction snapshots differ" }
        } else {
            require(extracted.id == selected.id + ProjectNodes.EXTRACT_SUFFIX && rows.size == 4 &&
                byId.keys == _s[selected.id, extracted.id, selected.id + ProjectNodes.EXTRACTION_SUFFIX, selected.id + ProjectNodes.EXTRACT_ORIGIN_SUFFIX]) {
                "Complete extraction snapshots required"
            }
            val lineage = byId.getValue(selected.id + ProjectNodes.EXTRACTION_SUFFIX)
            require(source.provenance["extractionLineageCid"] == lineage.cid) { "Extraction lineage snapshot differs" }
            validateLineage(source, json(read(ContentId(lineage.cid), 65_536)))
            val origin = byId.getValue(selected.id + ProjectNodes.EXTRACT_ORIGIN_SUFFIX)
            require(read(ContentId(origin.cid), 1024).decodeToString(throwOnInvalidSequence = true) == "${source.originalCid.value}\t${source.extractedTextCid.value}") { "Extraction origin differs" }
        }
    }

    internal fun validateLineage(source: DocumentSource, lineage: Map<String, Any?>) {
        require(lineage.keys == _s["version", "originalCid", "originalRev", "extractedTextCid", "anchors", "unresolved"]) { "Unsupported extraction lineage fields" }
        require((lineage["version"] as? Number)?.toDouble() == 1.0 && lineage["originalCid"] == source.originalCid.value &&
            lineage["originalRev"] == source.project?.rev && lineage["extractedTextCid"] == source.extractedTextCid.value) { "Extraction lineage differs" }
        val anchors = list(lineage["anchors"]).map(DocumentAnchor::fromMap).map(DocumentAnchor::toMap)
        require(CanonicalCbor.encodeMap(_m["anchors" j anchors, "unresolved" j lineage["unresolved"]]).contentEquals(
            CanonicalCbor.encodeMap(_m["anchors" j source.anchors.map(DocumentAnchor::toMap), "unresolved" j source.unresolved]))) { "Extraction layout lineage differs" }
    }

    /** Stable receipt-local resources; arbitrary dependency labels are literal values, never predicates. */
    fun project(snapshot: Snapshot): QuadStore {
        val store = QuadStore(); val graph = RdfTerm.Iri(snapshot.graph); val root = RdfTerm.Iri(snapshot.graph + "/inspection")
        val source = RdfTerm.Iri("urn:sha256:${snapshot.source.extractedTextCid.hex}")
        fun emit(subject: RdfTerm.Iri, predicate: String, value: RdfTerm) {
            require(store.size < MAX_QUADS) { "English observation graph exceeds 20000 quads; select a smaller text range" }
            val typed = value.toTurtle()
            val lexeme = buildString { for (ch in typed) if (ch.code < 32) append("\\u" + ch.code.toString(16).padStart(4, '0')) else append(ch) }
            require(TurtleRdf.parseTerm(lexeme) == value) { "RDF term cannot preserve English source characters" }
            store.add(subject.toTurtle(), RdfTerm.Iri(VOCAB + predicate).toTurtle(), lexeme, graph.toTurtle())
        }
        fun text(subject: RdfTerm.Iri, predicate: String, value: Any?) = emit(subject, predicate, RdfTerm.Literal(string(value)))
        fun number(subject: RdfTerm.Iri, predicate: String, value: Any?) = emit(subject, predicate,
            RdfTerm.Literal(integer(value).toString(), RdfVocab.XSD + "integer"))
        fun provenance(subject: RdfTerm.Iri, sentence: RdfTerm.Iri?) {
            emit(subject, "inspection", root); emit(subject, "sourceText", source)
            if (sentence != null) emit(subject, "sentence", sentence)
        }
        text(root, "kind", "inspection"); text(root, "receiptCid", snapshot.receiptCid.value)
        text(root, "originalCid", snapshot.source.originalCid.value); text(root, "extractedTextCid", snapshot.source.extractedTextCid.value)
        text(root, "reviewStatus", "unreviewed"); text(root, "authority", "recorded-observations-only")
        text(root, "parserExecution", "not-reverified"); text(root, "producerAuthority", "not-established")
        emit(root, "sourceText", source)
        val range = map(snapshot.inventory["range"])
        for (key in s_["begin", "end", "totalLength"].view) number(root, key, range[key])
        text(root, "coverage", if (range["complete"] == true) "complete-selected-text" else "partial-selected-text")
        text(root, "reportedParser", jsonOf(snapshot.inventory["parser"]))
        for (rawSentence in list(snapshot.inventory["sentences"])) {
            val row = map(rawSentence); val at = integer(row["index"]); val sentence = RdfTerm.Iri(snapshot.graph + "/sentence/$at")
            provenance(sentence, null); text(sentence, "kind", "sentence"); number(sentence, "begin", row["begin"])
            number(sentence, "end", row["end"]); text(sentence, "quote", row["quote"])
            for (rawNode in list(row["nodes"])) {
                val node = map(rawNode); val id = string(node["id"]); val resource = RdfTerm.Iri(sentence.iri + "/node/$id")
                provenance(resource, sentence); text(resource, "kind", node["kind"]); number(resource, "index", node["index"])
                number(resource, "copy", node["copy"]); number(resource, "begin", node["begin"]); number(resource, "end", node["end"])
                for (key in s_["word", "lemma", "pos", "ner"].view) text(resource, key, node[key])
                emit(resource, "surfaceToken", RdfTerm.Iri(sentence.iri + "/node/${integer(node["index"])}:0"))
            }
            val virtual = RdfTerm.Iri(sentence.iri + "/node/0:0")
            provenance(virtual, sentence); text(virtual, "kind", "virtual-root")
            for ((i, rawEdge) in list(row["dependencies"]).withIndex()) {
                val edge = map(rawEdge); val resource = RdfTerm.Iri(sentence.iri + "/dependency/$i")
                provenance(resource, sentence); text(resource, "kind", "dependency"); text(resource, "relation", edge["relation"])
                emit(resource, "governor", RdfTerm.Iri(sentence.iri + "/node/" + string(edge["governorNode"])))
                emit(resource, "dependent", RdfTerm.Iri(sentence.iri + "/node/" + string(edge["dependentNode"])))
            }
            for ((i, rawMarker) in list(row["markers"]).withIndex()) {
                val marker = map(rawMarker); val resource = RdfTerm.Iri(sentence.iri + "/marker/$i")
                provenance(resource, sentence); text(resource, "kind", "marker"); text(resource, "markerKind", marker["kind"])
                text(resource, "label", marker["label"]); text(resource, "quote", marker["quote"])
                text(resource, "status", "lexical-candidate"); number(resource, "begin", marker["begin"]); number(resource, "end", marker["end"])
            }
        }
        return store
    }

    internal fun legacy(inventory: Map<String, Any?>): Map<String, Any?> = inventory + _m[
        "sentences" j list(inventory["sentences"]).map { raw -> val row = map(raw)
            (row - "nodes") + _m["dependencies" j list(row["dependencies"]).map { edge ->
                map(edge) - _s["governorCopy", "dependentCopy", "governorNode", "dependentNode"]
            }]
        }, "counts" j (map(inventory["counts"]) - "nodes"),
        "coverage" j (map(inventory["coverage"]) - _s["dependencyNodeIdentity", "parserDependencyNodeIdentity", "parserNodeIdentityReported"])]

    @Suppress("UNCHECKED_CAST")
    internal fun map(value: Any?): Map<String, Any?> {
        val result = value as? Map<*, *> ?: error("English receipt object required")
        require(result.keys.all { it is String }) { "English receipt key is not text" }
        return result as Map<String, Any?>
    }
    internal fun list(value: Any?): List<Any?> = value as? List<*> ?: error("English receipt array required")
    internal fun string(value: Any?): String = (value as? String)?.takeIf { it.length <= 65_536 } ?: error("English receipt text required")
    internal fun integer(value: Any?): Int = whole(value).also { require(it <= Int.MAX_VALUE) { "English integer exceeds range" } }.toInt()
    internal fun whole(value: Any?): Long {
        val n = (value as? Number)?.toDouble() ?: error("English receipt integer required")
        require(n.isFinite() && n >= 0 && n <= 9_007_199_254_740_991.0 && n % 1.0 == 0.0) { "Invalid English receipt integer" }
        return n.toLong()
    }
    internal fun revision(value: Map<String, Any?>) = DocumentRevision(string(value["project"]), string(value["id"]),
        ContentId(string(value["cid"])).value, string(value["rev"]).also { require(it.isNotBlank()) }, whole(value["seq"]))
    internal fun projectDoc(value: Map<String, Any?>): ProjectDoc {
        require(value.keys == _s["project", "id", "cid", "rev", "seq", "length", "contentType"] ||
            value.keys == _s["project", "id", "cid", "rev", "seq", "length", "contentType", "contentStatus"]) { "Invalid source snapshot fields" }
        val r = revision(value)
        return ProjectDoc(r.project, r.id, r.cid, r.rev, r.seq, whole(value["length"]), string(value["contentType"]), value["contentStatus"]?.let(::string))
    }
    internal fun json(bytes: ByteArray): Map<String, Any?> {
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        var depth = 0; var quoted = false; var escaped = false
        for (ch in text) if (quoted) {
            if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
        } else when (ch) { '"' -> quoted = true; '{', '[' -> require(++depth <= 16); '}', ']' -> require(--depth >= 0) }
        require(depth == 0 && !quoted) { "Invalid extraction lineage JSON" }
        fun unique(value: Any?): Boolean = when (value) {
            is Map<*, *> -> { val keys = mutableSetOf<Any?>(); value.entries.all { it.key is String && keys.add(it.key) && unique(it.value) } }
            is List<*> -> value.all(::unique)
            else -> true
        }
        val result = reifyStrict(text); require(unique(result)) { "Duplicate extraction lineage fields" }; return map(result)
    }
}
