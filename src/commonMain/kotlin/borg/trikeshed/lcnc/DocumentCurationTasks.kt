package borg.trikeshed.lcnc

import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.BoardApply
import borg.trikeshed.kanban.BoardCol
import borg.trikeshed.kanban.BoardIntake
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.kanban.CardRow
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Document issues are ordinary board cards. Their immutable source envelope rides the existing spec. */
class DocumentCurationTasks(
    private val cas: CasStore,
    private val sourceCurrent: (DocumentSource) -> Boolean = { true },
) {
    companion object {
        const val TASKS = "document.tasks"
        const val CORRECT = "document.correct"
        private const val SPEC = "DOCUMENT: "
        private const val TAG = "document-curation"
    }

    private val gate = Mutex()

    fun registry(store: BoardStoreElement): Map<String, LcncNodeRunner> = mapOf(
        TASKS to boundLcnc(store) { board, node, inputs ->
            tasks(board, ContentId(required(node, inputs, "receiptCid")))
        },
        CORRECT to boundLcnc(store) { board, node, inputs ->
            correct(board, required(node, inputs, "taskId"), required(node, inputs, "expectedRevision").toDouble().let {
                require(it.isFinite() && it in 0.0..9_007_199_254_740_991.0 && it % 1.0 == 0.0) {
                    "A whole task revision is required"
                }; it.toLong()
            }, ContentId(required(node, inputs, "priorReceiptCid")),
                objectOf(inputs["source"] ?: inputs["source?"], "Corrected source"))
        },
    )

    /** No model call: retain the correction, then let the authored graph run its document.curate node. */
    suspend fun correct(
        store: BoardStoreElement, taskId: String, expectedRevision: Long,
        priorReceiptCid: ContentId, input: Map<String, Any?>,
    ): Map<String, Any?> = gate.withLock {
        val row = store.card(taskId) ?: error("Unknown document task")
        val envelope = envelope(row)
        val prior = record(priorReceiptCid)
        require(envelope["receiptCid"] == priorReceiptCid.value) { "The task's curation receipt changed" }
        val oldSource = objectOf(envelope["source"], "Task source")
        val source = source(input, oldSource)
        require(source.originalCid == prior.source.originalCid) { "A correction must retain its original document CID" }
        require(family(source, prior) == envelope["family"]) { "A correction must retain its source and recipe identity" }
        require(sourceCurrent(source)) { "The corrected source version is no longer current" }
        val correction = sourceReference(source)
        val previousCorrection = envelope["correction"] as? Map<*, *>
        if (row.revision != expectedRevision) {
            require(row.revision == expectedRevision + 1 &&
                (previousCorrection?.get("baseRevision") as? Number)?.toLong() == expectedRevision &&
                previousCorrection?.get("source") == correction) { "The task revision changed; read it before correcting" }
            return@withLock correctionOutput(row, envelope, prior.instructions)
        }
        val next = envelope + mapOf("previousCid" to envelopeCid(row).value,
            "correction" to mapOf("baseRevision" to expectedRevision, "source" to correction))
        val applied = command(store, row, taskId, "acknowledge", next)
        correctionOutput(applied, next, prior.instructions)
    }

    /** An exact, retained curation result can advance only the correction revision that admitted it. */
    suspend fun tasks(store: BoardStoreElement, receiptCid: ContentId): Map<String, Any?> = gate.withLock {
        val result = record(receiptCid)
        require(sourceCurrent(result.source)) { "The curation source version is no longer current" }
        val family = family(result.source, result)
        val issues = issues(result)
        val tasks = mutableListOf<Map<String, Any?>>()
        val completed = mutableListOf<Map<String, Any?>>()
        val reopened = mutableListOf<Map<String, Any?>>()
        val unchanged = mutableListOf<Map<String, Any?>>()
        val linkedId = result.source.metadata["taskId"]?.singleOrNull()
        if (linkedId != null) {
            val row = store.card(linkedId) ?: error("The corrected task is unavailable")
            val old = envelope(row)
            require(old["family"] == family) { "The curation recipe or source differs from the task" }
            if (old["receiptCid"] == receiptCid.value) {
                unchanged.add(reference(row, old))
            } else {
                val revision = result.source.metadata["taskRevision"]?.singleOrNull()?.toLongOrNull()
                require(revision == row.revision) { "Delayed curation result: the task revision changed" }
                require(result.source.metadata["priorReceiptCid"]?.singleOrNull() == old["receiptCid"]) {
                    "Delayed curation result: the task's prior receipt changed"
                }
                val correction = objectOf(old["correction"], "Task correction")
                require(correction["source"] == sourceReference(result.source, taskMetadata = false)) {
                    "The result does not describe the admitted correction"
                }
                val issue = issues.firstOrNull { it["key"] == old["issueKey"] }
                val resolved = issue == null && resolves(objectOf(old["issue"], "Task issue"), result)
                val verb = when {
                    resolved && row.col != BoardCol.DONE -> "complete"
                    !resolved && row.col == BoardCol.DONE -> "retry"
                    else -> "acknowledge"
                }
                val next = old + mapOf("previousCid" to envelopeCid(row).value,
                    "receiptCid" to receiptCid.value, "source" to sourceReference(result.source, taskMetadata = false),
                    "issue" to (issue ?: old["issue"]), "correction" to null,
                    "resolved" to resolved)
                val applied = command(store, row, linkedId, verb, next)
                val ref = reference(applied, next)
                tasks.add(ref)
                when (verb) { "complete" -> completed.add(ref); "retry" -> reopened.add(ref) }
            }
        }
        for (issue in issues) {
            val id = "document/task/$family/${issue.getValue("key")}"
            if (id == linkedId) continue
            val row = store.card(id)
            if (row != null) {
                // An unlinked read cannot close or reopen a card using an unordered historical model result.
                unchanged.add(reference(row, envelope(row)))
                continue
            }
            val envelope = mapOf("version" to 1, "family" to family,
                "issueKey" to issue["key"], "issue" to issue,
                "receiptCid" to receiptCid.value, "source" to sourceReference(result.source, taskMetadata = false),
                "recipeCid" to recipe(result).value, "resolved" to false)
            val applied = command(store, null, id, "submit", envelope)
            tasks.add(reference(applied, envelope))
        }
        mapOf("tasks" to tasks, "completed" to completed, "reopened" to reopened, "unchanged" to unchanged,
            "ignored" to result.proposals.view.flatMap { it.reasons.view }.distinct().filter { category(it) == null })
    }

    private fun correctionOutput(row: CardRow, envelope: Map<String, Any?>, instructions: String): Map<String, Any?> {
        val correction = objectOf(envelope["correction"], "Task correction")
        val source = objectOf(correction["source"], "Corrected source")
        val metadata = objectOf(source["metadata"], "Source metadata") + mapOf(
            "taskId" to listOf(row.jobId), "taskRevision" to listOf(row.revision.toString()),
            "priorReceiptCid" to listOf(envelope["receiptCid"] as String))
        return mapOf("source" to (source + ("metadata" to metadata)), "taskId" to row.jobId,
            "revision" to row.revision, "priorReceiptCid" to envelope["receiptCid"], "instructions" to instructions)
    }

    private suspend fun command(
        store: BoardStoreElement, row: CardRow?, id: String, verb: String, envelope: Map<String, Any?>,
    ): CardRow {
        val cid = cas.put(CanonicalCbor.encodeMap(envelope))
        val issue = objectOf(envelope["issue"], "Task issue")
        val reply = CompletableDeferred<BoardApply>()
        store.intake.send(BoardIntake(mapOf("type" to verb, "jobId" to id,
            "expectedRevision" to (row?.revision ?: 0L),
            "idempotencyKey" to "$id:$verb:${row?.revision ?: 0L}:${cid.value}",
            "title" to issue["title"], "owner" to (row?.owner ?: TAG), "actor" to TAG,
            "tags" to (row?.tags ?: listOf(TAG)),
            "spec" to "GOAL: ${issue["question"]}\nMUST: Resolve this issue with a retained curation result.\n$SPEC${cid.value}",
            "documentTaskCid" to cid.value, "receiptCid" to envelope["receiptCid"]), reply))
        when (val applied = reply.await()) {
            is BoardApply.Rejected -> error("Document task was not changed: ${applied.reason}")
            is BoardApply.Committed -> return checkNotNull(store.card(id)).also {
                check(it.revision == applied.revision) { "The task changed after its correction committed" }
            }
        }
    }

    private fun envelopeCid(row: CardRow): ContentId = ContentId(row.spec.lineSequence()
        .firstOrNull { it.startsWith(SPEC) }?.removePrefix(SPEC) ?: error("The task has no retained document lineage"))

    private fun envelope(row: CardRow): Map<String, Any?> = CanonicalCbor.decodeMap(verified(envelopeCid(row)))

    private fun reference(row: CardRow, envelope: Map<String, Any?>): Map<String, Any?> = mapOf(
        "jobId" to row.jobId, "revision" to row.revision, "column" to row.col.wire,
        "cid" to row.commandCid?.value, "issueKey" to envelope["issueKey"], "title" to row.title,
        "receiptCid" to envelope["receiptCid"], "source" to envelope["source"], "issue" to envelope["issue"])

    private fun record(cid: ContentId): DocumentCurationRecord = DocumentCuratorCodec.decode(verified(cid))

    private fun verified(cid: ContentId): ByteArray = (cas.get(cid) ?: error("Retained content is unavailable: ${cid.value}"))
        .also { require(ContentId.of(it) == cid) { "Retained content CID mismatch" } }

    private fun source(input: Map<String, Any?>, previous: Map<String, Any?>): DocumentSource {
        val fields = previous + input
        val original = ContentId(fields["originalCid"] as? String ?: error("Original CID is required"))
        verified(original)
        val text = input["text"] as? String
        val cid = (input["extractedTextCid"] as? String)?.let(::ContentId)
            ?: text?.let { cas.put(it.encodeToByteArray()) }
            ?: ContentId(previous["extractedTextCid"] as String)
        val bytes = verified(cid)
        require(text == null || ContentId.of(text.encodeToByteArray()) == cid) { "Corrected text CID mismatch" }
        require(bytes.isNotEmpty()) { "Corrected source text is empty" }
        val metadata = objectOf(fields["metadata"], "Source metadata").mapValues { (_, value) ->
            (value as? List<*>)?.map { it as? String ?: error("Source metadata values must be strings") }
                ?: error("Source metadata values must be string arrays")
        } - setOf("taskId", "taskRevision", "priorReceiptCid")
        return DocumentSource(original, cid, bytes.decodeToString(throwOnInvalidSequence = true), fields["name"] as? String ?: "Document",
            fields["mediaType"] as? String ?: "text/plain", fields["correlation"] as? String ?: original.value, metadata)
    }

    private fun sourceReference(source: DocumentSource, taskMetadata: Boolean = false): Map<String, Any?> =
        DocumentCuratorCodec.source(source).minus("text") + ("metadata" to if (taskMetadata) source.metadata
            else source.metadata - setOf("taskId", "taskRevision", "priorReceiptCid"))

    private fun recipe(record: DocumentCurationRecord): ContentId = ContentId.of(CanonicalCbor.encodeMap(
        mapOf("instructions" to record.instructions, "modelId" to record.modelId)))

    private fun family(source: DocumentSource, record: DocumentCurationRecord): String = ContentId.of(CanonicalCbor.encodeMap(
        mapOf("source" to (source.metadata["evidenceId"]?.singleOrNull() ?: source.originalCid.value),
            "recipeCid" to recipe(record).value))).hex

    /** Admission restrictions are not defects in the author's language and never request a rewrite. */
    private fun category(reason: String): String? = when {
        reason.startsWith("model:") || reason.startsWith("model-json:") || reason.startsWith("proposal:") ||
            reason == "unmapped alternative in model response" -> "model"
        reason.startsWith("nlp:") || reason == "nlp unavailable" || reason == "nlp text differs from extracted text" ||
            reason == "invalid NLP token offsets or coverage" || reason == "incomplete token coverage" -> "nlp"
        reason == "source CID absent or extracted text CID mismatch" || reason == "source is not CAS-grounded" -> "source"
        reason == "missing span" || reason == "quote does not match UTF-16 span" -> "quotation"
        reason == "conflicting or alternative reading" || reason == "model terms disagree with NLP token roles or lemma" -> "interpretation"
        else -> null
    }

    private fun issues(record: DocumentCurationRecord): List<Map<String, Any?>> {
        val groups = linkedMapOf<String, MutableList<DocumentProposal>>()
        if (!sourceValid(record.source)) groups["source"] = mutableListOf()
        if (record.curationIndex().facet(DocumentCurationIndexK.NlpStatus) != DocumentNlpStatus.AVAILABLE)
            groups["nlp"] = mutableListOf()
        for (reason in record.reasons.view) category(reason)?.let { groups.getOrPut(it) { mutableListOf() } }
        for (proposal in record.proposals.view) for (reason in proposal.reasons.view) {
            val kind = category(reason) ?: continue
            if (kind == "quotation" && DocumentCuratorGrounding.quotation(record.source, proposal).quotationBegin != null) continue
            val anchor = if (kind in setOf("quotation", "interpretation"))
                ContentId.of(CanonicalCbor.encodeMap(mapOf("quote" to proposal.quote))).hex else ""
            val key = if (anchor.isEmpty()) kind else "$kind/$anchor"
            val group = groups.getOrPut(key) { mutableListOf() }
            if (proposal !in group) group.add(proposal)
        }
        return groups.map { (key, proposals) ->
            val kind = key.substringBefore('/')
            val question = when (kind) {
                "quotation" -> "Locate the exact supporting passage or supply a corrected source without changing its intended meaning"
                "interpretation" -> "Clarify the conflicting source interpretation using supporting evidence"
                "nlp" -> "Restore valid NLP annotations for the retained source text"
                "source" -> "Recover the exact original and extracted text from retained content"
                else -> "Obtain a readable model curation result for the retained source"
            }
            mapOf("key" to ContentId.of(key.encodeToByteArray()).hex, "kind" to kind,
                "title" to "$question: ${record.source.name}", "question" to question,
                "quotes" to proposals.mapNotNull { it.quote }.distinct(),
                "terms" to proposals.map { mapOf("subject" to it.subject, "predicate" to it.predicate, "object" to it.obj) }.distinct(),
                "proposalCids" to proposals.mapNotNull { it.receiptCid?.value }.distinct(),
                "reasons" to (proposals.flatMap { it.reasons.view } + record.reasons.view.filter { category(it) == kind }).distinct())
        }
    }

    private fun resolves(issue: Map<String, Any?>, record: DocumentCurationRecord): Boolean {
        if (!sourceValid(record.source) || record.model == null || record.nlp == null || record.proposals.size == 0 ||
            record.proposals.view.any { it.subject == null } || record.reasons.view.any { category(it) != null }) return false
        val kind = issue["kind"]
        if (kind == "nlp" && record.curationIndex().facet(DocumentCurationIndexK.NlpStatus) != DocumentNlpStatus.AVAILABLE) return false
        if (kind == "model" || kind == "nlp" || kind == "source")
            return record.proposals.view.none { p -> p.reasons.view.any { category(it) == kind } }
        val terms = issue["terms"] as? List<*> ?: return false
        val supported: (Any?) -> Boolean = { raw ->
            val term = objectOf(raw, "Issue terms")
            record.proposals.view.any { p -> p.subject == term["subject"] && p.predicate == term["predicate"] && p.obj == term["object"] &&
                DocumentCuratorGrounding.quotation(record.source, p).quotationBegin != null &&
                p.reasons.view.none { category(it) == kind || category(it) == "quotation" } }
        }
        return terms.isNotEmpty() && if (kind == "interpretation") terms.any(supported) else terms.all(supported)
    }

    private fun sourceValid(source: DocumentSource): Boolean = runCatching {
        verified(source.originalCid)
        verified(source.extractedTextCid).contentEquals(source.text.encodeToByteArray())
    }.getOrDefault(false)

    private fun required(node: LcncNode, inputs: Map<String, Any?>, name: String): String =
        (inputs[name] ?: inputs["$name?"] ?: node.params[name])?.toString()?.takeIf(String::isNotBlank)
            ?: error("${node.type}: $name is required")

    private fun objectOf(value: Any?, label: String): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.associate { (key, item) ->
            (key as? String ?: error("$label fields must have names")) to item
        } ?: error("$label is required")
}
