package borg.trikeshed.lcnc

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.CursorScope
import borg.trikeshed.forge.sheet.SheetRef
import borg.trikeshed.forge.sheet.SheetColumn
import borg.trikeshed.forge.sheet.sheetSeed
import borg.trikeshed.graal.subvm.DocumentFeed
import borg.trikeshed.graal.subvm.CamelCatalog
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.modelmux.ToolOntologyScaffold
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.CausalityReteElement
import borg.trikeshed.narsese.DocumentCurationFacts
import borg.trikeshed.narsese.DocumentCurationToolset
import borg.trikeshed.narsese.DocumentCurationIndexK
import borg.trikeshed.narsese.DocumentCurationIndex
import borg.trikeshed.narsese.DocumentCurationRecord
import borg.trikeshed.narsese.DocumentCuratorCodec
import borg.trikeshed.narsese.DocumentCuratorElement
import borg.trikeshed.narsese.DocumentCuratorGrounding
import borg.trikeshed.narsese.DocumentModel
import borg.trikeshed.narsese.DocumentSource
import borg.trikeshed.narsese.curationIndex
import borg.trikeshed.narsese.facet
import borg.trikeshed.narsese.nlpAxioms
import borg.trikeshed.pointcut.PointcutBlackboardAdapter
import borg.trikeshed.userspace.nio.DocumentExtent
import borg.trikeshed.userspace.nio.DocumentContent
import borg.trikeshed.userspace.nio.Volume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext

/** LCNC admission into the host-owned feed or its existing curator; the host owns their drain. */
object DocumentCurationLegos {
    const val CURATE = "document.curate"
    const val SHEET = "document.sheet"

    /** Read-only navigation addresses the same receipt and cursor as the LC node. */
    fun sheetHref(cid: ContentId, view: String = "sentences", sentence: Int = 0, offset: Int = 0, limit: Int = 32): String =
        "/api/documents?cid=${cid.value}&view=sheet&cursor=$view&sentence=$sentence&offset=$offset&limit=$limit"

    fun sheet(cid: ContentId, record: DocumentCurationRecord, query: Map<String, String>): Map<String, Any?> {
        fun ordinal(name: String, default: Int): Int = query[name]?.let {
            requireNotNull(it.toIntOrNull()) { "$SHEET.$name must be an integer" }
        } ?: default
        fun array(name: String): List<*> {
            val raw = query[name] ?: "[]"
            require(raw.length <= 4_096) { "$SHEET.$name exceeds query limit" }
            return requireNotNull(reifyStrict(raw) as? List<*>) { "$SHEET.$name must be an array" }
        }
        val axes = array("axes").map { axis ->
            requireNotNull(axis as? List<*>) { "Each index must list field names" }.map {
                requireNotNull(it as? String) { "Index field must be a string" }
            }.toSeries()
        }.toSeries()
        val selections = array("selections").map { entry ->
            val pair = requireNotNull(entry as? List<*>) { "Selection must be [index, group]" }
            require(pair.size == 2) { "Selection must be [index, group]" }
            fun integer(value: Any?): Int {
                val n = requireNotNull(value as? Number) { "Selection must contain integers" }.toDouble()
                require(n.isFinite() && n >= 0 && n <= Int.MAX_VALUE && n % 1 == 0.0) { "Invalid index selection" }
                return n.toInt()
            }
            integer(pair[0]) j integer(pair[1])
        }.toSeries()
        return sheet(cid, record, query["cursor"] ?: "sentences", ordinal("sentence", 0), ordinal("offset", 0),
            ordinal("limit", 32), axes, selections, ordinal("groupOffset", 0))
    }

    /** Borrow the retained ledger, never re-enter curation to inspect a completed receipt. */
    fun sheet(curator: DocumentCuratorElement): LcncNodeRunner = boundLcnc(curator) { owner, node, inputs ->
        fun value(key: String): Any? = when {
            key in inputs -> inputs[key]
            "$key?" in inputs -> inputs["$key?"]
            else -> node.params[key]
        }
        fun ordinal(key: String, default: Int): Int {
            val raw = value(key) ?: return default
            val number = when (raw) { is Number -> raw.toDouble(); is String -> raw.toDoubleOrNull(); else -> null }
            require(number != null && number.isFinite() && number >= 0 && number <= Int.MAX_VALUE && number % 1 == 0.0) {
                "$SHEET.$key must be a nonnegative integer"
            }
            return number.toInt()
        }
        val cid = ContentId(requireNotNull(value("receiptCid") as? String) { "$SHEET.receiptCid is required" })
        val record = requireNotNull(owner.record(cid)) { "Unknown document curation receipt: ${cid.value}" }
        val result = sheet(cid, record, mapOf("cursor" to (node.params["view"] ?: "sentences"),
            "sentence" to ordinal("sentence", 0).toString(), "offset" to ordinal("offset", 0).toString(),
            "limit" to ordinal("limit", 32).toString(), "axes" to (node.params["axes"] ?: "[]"),
            "selections" to (node.params["selections"] ?: "[]")))
        if (((result["sheet"] as Map<*, *>)["selections"] as List<*>).isNotEmpty()) {
            require(node.params["receiptCid"] == cid.value) {
                "Pin receiptCid to the selected receipt before applying group ordinals; selections cannot follow a changed wired receipt"
            }
        }
        currentCoroutineContext()[LcncConsumedLedger]?.consumed("document-curation", record.source.name, cid.value)
        result
    }

    /** A window of one retained cursor. Requested indexes read its key fields only; unrelated children stay lazy. */
    fun sheet(cid: ContentId, record: DocumentCurationRecord, view: String, sentence: Int, offset: Int, limit: Int,
              axes: Series<Series<String>> = emptySeriesOf(), selections: Series<Twin<Int>> = emptySeriesOf(),
              groupOffset: Int = 0): Map<String, Any?> {
        require(offset >= 0 && limit in 1..128 && sentence >= 0 && groupOffset >= 0) { "Invalid document sheet window" }
        require(axes.size <= 4 && axes.view.all { it.size in 1..3 && it.view.toSet().size == it.size }) {
            "At most four indexes, each with one to three distinct fields"
        }
        require(selections.size <= axes.size && selections.view.map { it.a }.distinct().size == selections.size) {
            "At most one group selection per index"
        }
        val index = record.curationIndex()
        val root = "document/${cid.value}"
        val cursor = when (view) {
            "sentences" -> sentenceCursor(cid, index)
            "proposals" -> index.facet(DocumentCurationIndexK.ProposalCursor)
            "rules" -> index.facet(DocumentCurationIndexK.RuleCursor)
            "tokens", "dependencies" -> {
                require(sentence < index.facet(DocumentCurationIndexK.Sentences).size) { "Sentence outside retained document" }
                if (view == "tokens") index.facet(DocumentCurationIndexK.TokenCursor)(sentence)
                else index.facet(DocumentCurationIndexK.DependencyCursor)(sentence)
            }
            else -> throw IllegalArgumentException("Unknown document sheet view: $view")
        }
        val schema = index.facet(when (view) {
            "sentences" -> DocumentCurationIndexK.SentenceColumns
            "tokens" -> DocumentCurationIndexK.TokenColumns
            "dependencies" -> DocumentCurationIndexK.DependencyColumns
            "rules" -> DocumentCurationIndexK.RuleColumns
            else -> DocumentCurationIndexK.ProposalColumns
        })
        val columns = (schema α {
            SheetColumn(it.name.toString(), it.type.toString())
        }).toList()
        val fields = columns.filter { it.type != "IoArray" && it.type != "IoObject" }.map { it.name }
        val base = CursorScope(cursor).index()
        // On demand only. Independent indexes share this receipt/view scope; the cells stay lazy.
        val ordinals = Array(axes.size) { a -> IntArray(axes[a].size) { c ->
            val field = axes[a][c]
            require(field in fields) { "Not an indexable field in $view: $field" }
            columns.indexOfFirst { it.name == field }
        } }
        val indexes = Array(axes.size) { base.groups(*ordinals[it]) }
        var selected = base
        for ((a, group) in selections.view) {
            require(a in indexes.indices && group in 0 until indexes[a].size) { "Index selection outside retained cursor" }
            selected = selected.intersect(indexes[a][group])
        }
        val end = minOf(selected.size.toLong(), offset.toLong() + limit).toInt()
        val window: Cursor = maxOf(0, end - offset) j { i: Int -> selected.cursor[offset + i] }
        val axisValues = axes.view.map { it.view.toList() }
        val selectionValues = selections.view.map { listOf(it.a, it.b) }
        fun href(row: Int, groups: Int = groupOffset): String = sheetHref(cid, view, sentence, row, limit) +
            if (axes.size == 0) "" else "&axes=" + java.net.URLEncoder.encode(jsonOf(axisValues), "UTF-8") +
                "&selections=" + java.net.URLEncoder.encode(jsonOf(selectionValues), "UTF-8") + "&groupOffset=$groups"
        val seed = sheetSeed(if (view == "sentences") root else if (view in setOf("proposals", "rules")) "$root/$view" else "$root/$sentence/$view",
            if (view == "sentences") record.source.name else view, window,
            if (view == "sentences") null else root, columns)
        // The same returned selection accompanies low-code payloads without duplicating cursor cells or index groups.
        val selection = mapOf("id" to seed.id, "offset" to offset, "sourceRows" to cursor.size,
            "selectedRows" to selected.size, "pageSize" to limit,
            "sourceOrdinals" to (window.size j { i: Int -> selected.sourceRow(offset + i) }).view.toList(),
            "axes" to axisValues, "selections" to selectionValues, "next" to if (end < selected.size) end else null)
        return mapOf("receiptCid" to cid.value, "selection" to selection,
            "sheet" to (seed.toMap() + selection + mapOf<String, Any?>("href" to href(offset), "indexColumns" to fields,
            "indexes" to indexes.mapIndexed { a, groups ->
                val stop = minOf(groups.size.toLong(), groupOffset.toLong() + limit).toInt()
                mapOf("fields" to axisValues[a], "groupCount" to groups.size, "offset" to groupOffset,
                    "groups" to (maxOf(0, stop - groupOffset) j { n: Int ->
                        val group = groups[groupOffset + n]
                        mapOf("group" to groupOffset + n, "count" to group.size,
                            "key" to ordinals[a].map { group.cursor[0][it].a })
                    }).view.toList(), "next" to if (stop < groups.size) stop else null)
            },
            "previousGroupsHref" to if (groupOffset > 0) href(offset, maxOf(0, groupOffset - limit)) else null,
            "nextGroupsHref" to if (indexes.any { groupOffset.toLong() + limit < it.size }) href(offset, groupOffset + limit) else null,
            "views" to listOf(mapOf("sheet" to root, "title" to "Sentences", "href" to sheetHref(cid)),
                mapOf("sheet" to "$root/proposals", "title" to "Proposal tuples", "href" to sheetHref(cid, "proposals")),
                mapOf("sheet" to "$root/rules", "title" to "Rule candidates", "href" to sheetHref(cid, "rules"))),
            "notice" to when (view) {
                "proposals" -> "Model proposals, not verified facts. modelConfidence is the model's score, not parser confidence or approval."
                "rules" -> "Parser candidates, not admitted rules. Simulation uses only this selected page; patternWeight is structural weight, not parser confidence."
                else -> null
            },
            "parentHref" to if (view == "sentences") null else sheetHref(cid),
            "previousHref" to if (offset > 0) href(maxOf(0, offset - limit)) else null,
            "nextHref" to if (end < selected.size) href(end) else null,
            "truncated" to (window.size < selected.size), "limit" to if (window.size < selected.size) "row_window" else null)),
            "rowCount" to cursor.size, "next" to if (end < selected.size) end else null) +
            if (view == "rules") mapOf("ruleCandidates" to (window α { row ->
                row.view.associate { cell -> cell.b().name.toString() to cell.a }
            }).view.toList()) else emptyMap()
    }

    fun sentenceCursor(cid: ContentId, index: DocumentCurationIndex): Cursor {
        val sentences = index.facet(DocumentCurationIndexK.SentenceCursor)
        return sentences.size j { ordinal -> sentences[ordinal] α { cell ->
            when (cell.b().name.toString()) {
                "tokens" -> SheetRef("document/${cid.value}/$ordinal/tokens", sheetHref(cid, "tokens", ordinal)) j cell.b
                "dependencies" -> SheetRef("document/${cid.value}/$ordinal/dependencies", sheetHref(cid, "dependencies", ordinal)) j cell.b
                else -> cell
            }
        } }
    }

    /**
     * Compose and register with explicit storage ownership. No filesystem or volatile volume
     * is selected here. The returned feed must drain before the caller closes its dependencies.
     */
    suspend fun create(
        scope: CoroutineScope,
        volume: Volume,
        cas: CasStore,
        log: DurableAppendLog,
        bag: BeliefBagElement,
        points: PointcutBlackboardAdapter,
        model: DocumentModel,
        modelId: String,
        runners: MutableMap<String, LcncNodeRunner>,
        stagingLba: Long? = null,
        rete: CausalityReteElement? = scope.coroutineContext[CausalityReteElement.Key],
        toolOntology: ToolOntologyScaffold = DocumentCurationToolset.all(
            CamelCatalog.endpoints().map { "available:camel.endpoint=$it" }),
    ): DocumentFeed {
        require(CURATE !in runners) { "$CURATE is already registered" }
        val feed = DocumentFeed.create(scope, volume, cas, log, bag, points, model, modelId,
            stagingLba = stagingLba, rete = rete, toolOntology = toolOntology)
        try {
            runners[CURATE] = curate(feed)
            return feed
        } catch (failure: Throwable) {
            feed.drain()
            throw failure
        }
    }

    /** [boundLcnc] resolves the caller's [DocumentFeed.Key] before borrowing this host default. */
    fun curate(feed: DocumentFeed): LcncNodeRunner = boundLcnc(feed) { owner, node, inputs ->
        require("instructions" !in inputs && "instructions?" !in inputs && node.params["instructions"] == null) {
            "$CURATE.instructions is unsupported on the DocumentFeed path; use retained-source curation"
        }
        val extentInput = inputs["extent"] ?: inputs["extent?"]
        val sourceInput = inputs["source"] ?: inputs["source?"]
        require((extentInput != null) != (sourceInput != null)) {
            "$CURATE requires exactly one of extent or source"
        }
        val receipt = sourceInput?.let { value ->
            val fields = value as? Map<*, *>
            if (fields?.get("originalCid") != null) {
                require(fields["text"] == null && fields["base64"] == null) {
                    "$CURATE.source requires exactly one of text, base64, or originalCid"
                }
                owner.submit(ContentId(requireNotNull(fields["originalCid"] as? String) {
                    "$CURATE.source.originalCid must be a string"
                }), sourceName(fields), sourceMediaType(fields))
            } else {
                val source = content(value)
                owner.submit(source.bytes, source.name, source.mediaType)
            }
        } ?: owner.submit(extent(extentInput))
        output(receipt.cid, receipt.record)
    }

    /** Corrected text already retained in CAS enters the same owned NLP/model/ledger pipeline. */
    fun curate(
        curator: DocumentCuratorElement,
        projection: (ContentId, DocumentCurationRecord) -> Map<String, Any?> = ::output,
    ): LcncNodeRunner = boundLcnc(curator) { owner, node, inputs ->
        val extentInput = inputs["extent"] ?: inputs["extent?"]
        val sourceInput = inputs["source"] ?: inputs["source?"]
        require(extentInput == null && sourceInput != null) {
            "$CURATE requires a retained source with its exact extracted text"
        }
        val source = source(sourceInput)
        val instructionInput = if ("instructions" in inputs) inputs["instructions"] else inputs["instructions?"]
        val instructions = if ("instructions" in inputs || "instructions?" in inputs)
            requireNotNull(instructionInput as? String) { "$CURATE.instructions must be a string" }
        else node.params["instructions"]
        // The run receipt cites what this node read: the exact text cid and the instructions in effect
        // (the node's own, or the curator's default when none were given).
        currentCoroutineContext()[LcncConsumedLedger]?.let { ledger ->
            ledger.consumed(CONSUMED_DOCUMENT, source.name, source.extractedTextCid.value)
            val effective = instructions ?: DocumentCuratorGrounding.instructions
            ledger.consumed(LcncConsumedLedger.PROMPT, "$CURATE.instructions", ContentId.of(effective.encodeToByteArray()).value)
        }
        val receipt = instructions?.let { owner.curate(source, it) } ?: owner.curate(source)
        projection(receipt.recordCid, receipt.record)
    }

    /** Consumed-ledger kind for the retained text a curate run read; not a project document, so no staleness fact. */
    const val CONSUMED_DOCUMENT = "document"

    /** Retain new text or resolve an exact retained source before entering the existing curator. */
    fun retainSource(cas: CasStore, value: Any?): DocumentSource {
        val retained = if (value is DocumentSource) value else {
            val fields = requireNotNull(value as? Map<*, *>) { "$CURATE.source must be an object" }
            require(fields["base64"] == null) { "File bytes require the document extraction feed" }
            val name = sourceName(fields)
            val original = fields["originalCid"]
            val extracted = fields["extractedTextCid"]
            require((original == null) == (extracted == null)) { "Retained documents require both source CIDs" }
            val text = if (fields["text"] != null) requireNotNull(fields["text"] as? String) {
                "$CURATE.source.text must be a string"
            } else {
                val cid = ContentId(requireNotNull(extracted as? String) { "Extracted text CID is required" })
                val bytes = requireNotNull(cas.get(cid)) { "Retained extracted text is unavailable" }
                require(ContentId.of(bytes) == cid) { "Extracted text CID mismatch" }
                bytes.decodeToString(throwOnInvalidSequence = true)
            }
            require(text.encodeToByteArray().size <= 4 * 1024 * 1024) { "Document text exceeds 4 MiB" }
            val cid = ContentId.of(text.encodeToByteArray()).value
            val source = source(fields + mapOf(
                "name" to name, "text" to text,
                "originalCid" to (original ?: cid), "extractedTextCid" to (extracted ?: cid),
                "mediaType" to (fields["mediaType"] ?: "text/plain"),
                "correlation" to (fields["correlation"] ?: java.util.UUID.randomUUID().toString()),
            ))
            if (original == null) cas.put(text.encodeToByteArray())
            source
        }
        val original = requireNotNull(cas.get(retained.originalCid)) { "Original document is unavailable" }
        val extracted = requireNotNull(cas.get(retained.extractedTextCid)) { "Extracted text is unavailable" }
        require(ContentId.of(original) == retained.originalCid && ContentId.of(extracted) == retained.extractedTextCid &&
            extracted.contentEquals(retained.text.encodeToByteArray())) { "Retained source does not match its content identities" }
        return retained
    }

    /** LCNC carries immutable identities; the explicit document read projects the retained contents. */
    fun reference(cid: ContentId, record: DocumentCurationRecord): Map<String, Any?> {
        val href = "/api/documents?cid=${cid.value}"
        val sheet = mapOf("sheet" to "document/${cid.value}", "href" to "$href&view=sheet", "pageHref" to sheetHref(cid))
        return mapOf(
            "receiptCid" to cid.value,
            "record" to mapOf("cid" to cid.value, "href" to href,
                "originalCid" to record.source.originalCid.value,
                "extractedTextCid" to record.source.extractedTextCid.value,
                "proposals" to record.proposals.size, "submitted" to record.submittedReceiptCids.size,
                "quotationsSubmitted" to record.quotationSubmittedReceiptCids.size),
            "nlpStatus" to record.curationIndex().facet(DocumentCurationIndexK.NlpStatus).name,
            "sheet" to sheet, "sheets" to listOf(sheet),
            "ruleCandidates" to DocumentCurationFacts.ruleCandidates(record).toList(),
        )
    }

    /** Shared projection for feed results, corrected-text results and retained receipt reads. */
    fun output(cid: ContentId, record: DocumentCurationRecord): Map<String, Any?> {
        val index = record.curationIndex()
        val rootId = "document/${cid.value}"
        val sentences = index.facet(DocumentCurationIndexK.SentenceCursor)
        // Nested Cursor facets become ordinary grid-in-cell references at the existing UI boundary.
        val references = sentenceCursor(cid, index)
        val root = sheetSeed(rootId, record.source.name, references).toMap()
        val sheets = buildList {
            add(root)
            for (ordinal in 0 until sentences.size) {
                add(sheetSeed("$rootId/$ordinal/tokens", "tokens",
                    index.facet(DocumentCurationIndexK.TokenCursor)(ordinal), rootId).toMap())
                add(sheetSeed("$rootId/$ordinal/dependencies", "dependencies",
                    index.facet(DocumentCurationIndexK.DependencyCursor)(ordinal), rootId).toMap())
            }
        }
        return mapOf(
            "receiptCid" to cid.value,
            "record" to DocumentCuratorCodec.record(record),
            "nlpStatus" to index.facet(DocumentCurationIndexK.NlpStatus).name,
            "nlpAxioms" to nlpAxiomsProjection(record),
            // The same rows the canvas port yields; the whole provenance lives on the documents plane.
            "ruleCandidates" to DocumentCurationFacts.ruleCandidates(record).toList(),
            "sheet" to root,
            "sheets" to sheets,
        )
    }

    /** Source-attributed candidates with the identity of the rule each would become; extraction weights are not parser confidence. */
    private fun nlpAxiomsProjection(record: DocumentCurationRecord): List<Map<String, Any?>> =
        (record.nlpAxioms α { axiom ->
            mapOf(
                "originalCid" to record.source.originalCid.value,
                "extractedTextCid" to record.source.extractedTextCid.value,
                "sentenceIndex" to axiom.sentenceIndex,
                "begin" to axiom.begin,
                "end" to axiom.end,
                "quote" to record.source.text.substring(axiom.begin, axiom.end),
                "antecedent" to axiom.antecedent,
                "predicate" to axiom.predicate,
                "consequent" to axiom.consequent,
                "copula" to axiom.rule.copula.symbol,
                "ruleCid" to axiom.rule.ruleCid.value,
                "provenanceCid" to axiom.rule.provenanceCid,
                "label" to "admission candidate",
            )
        }).toList()

    internal fun source(value: Any?): DocumentSource {
        if (value is DocumentSource) return value
        val fields = requireNotNull(value as? Map<*, *>) { "$CURATE.source must be an object" }
        require(fields["base64"] == null) { "$CURATE requires retained extracted text, not file bytes" }
        fun string(name: String): String = requireNotNull(fields[name] as? String) {
            "$CURATE.source.$name must be a string"
        }
        val metadata = fields["metadata"]?.let { raw ->
            requireNotNull(raw as? Map<*, *>) { "$CURATE.source.metadata must be an object" }.entries.associate { (key, values) ->
                val name = requireNotNull(key as? String) { "Document metadata names must be strings" }
                val items = requireNotNull(values as? List<*>) { "Document metadata values must be string arrays" }
                name to items.map { requireNotNull(it as? String) { "Document metadata values must be strings" } }
            }
        }.orEmpty()
        return DocumentSource(ContentId(string("originalCid")), ContentId(string("extractedTextCid")),
            string("text"), sourceName(fields), string("mediaType"), string("correlation"), metadata)
    }

    internal fun content(value: Any?): DocumentContent {
        if (value is DocumentContent) return value
        val fields = requireNotNull(value as? Map<*, *>) { "$CURATE.source must be an object" }
        val name = sourceName(fields)
        require((fields["text"] != null) != (fields["base64"] != null)) {
            "$CURATE.source requires exactly one of text or base64"
        }
        val bytes = fields["text"]?.let {
            requireNotNull(it as? String) { "$CURATE.source.text must be a string" }.encodeToByteArray()
        } ?: java.util.Base64.getDecoder().decode(requireNotNull(fields["base64"] as? String) {
            "$CURATE.source.base64 must be a string"
        })
        return DocumentContent(bytes, name, sourceMediaType(fields))
    }

    private fun sourceName(fields: Map<*, *>): String {
        val name = requireNotNull(fields["name"] as? String) { "$CURATE.source.name must be a string" }
        require(name.isNotBlank()) { "$CURATE.source.name must not be blank" }
        return name
    }

    private fun sourceMediaType(fields: Map<*, *>): String? = fields["mediaType"]?.let {
            requireNotNull(it as? String) { "$CURATE.source.mediaType must be a string" }
    }

    internal fun extent(value: Any?): DocumentExtent {
        if (value is DocumentExtent) return value
        val fields = requireNotNull(value as? Map<*, *>) { "$CURATE.extent must be an extent object" }
        fun integer(name: String): Long {
            val number = fields[name]
            // reify reifies numeric literals as Double. Accept integral JSON values
            // without truncating fractions or accepting coordinates that lost precision.
            if (number is Double || number is Float) {
                val value = (number as Number).toDouble()
                require(value.isFinite() && value % 1.0 == 0.0 &&
                    value in -9_007_199_254_740_991.0..9_007_199_254_740_991.0) {
                    "$CURATE.extent.$name must be an exactly representable integer"
                }
                return value.toLong()
            }
            return requireNotNull(number?.toString()?.toLongOrNull()) {
                "$CURATE.extent.$name must be an integer"
            }
        }
        val length = integer("byteLength")
        require(length in 0..Int.MAX_VALUE.toLong()) { "$CURATE.extent.byteLength is out of range" }
        val name = requireNotNull(fields["name"] as? String) { "$CURATE.extent.name must be a string" }
        require(name.isNotBlank()) { "$CURATE.extent.name must not be blank" }
        return DocumentExtent(integer("lba"), length.toInt(), name,
            fields["mediaType"]?.let { requireNotNull(it as? String) { "$CURATE.extent.mediaType must be a string" } },
            fields["expectedCid"]?.let { ContentId(requireNotNull(it as? String) { "$CURATE.extent.expectedCid must be a string" }) })
    }
}
