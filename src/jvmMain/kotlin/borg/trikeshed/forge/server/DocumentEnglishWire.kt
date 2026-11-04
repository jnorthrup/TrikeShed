<<<<<<< HEAD
package borg.trikeshed.forge.server

import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.narsese.*
import borg.trikeshed.nlp.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.*

/** Local parser observations only: no model, mining, review authority or reasoning admission. */
class DocumentEnglishWire(
    private val ctx: ModuleContext,
    private val corpus: ProjectCorpus,
    /** Host construction seam for a reader; requests cannot select or configure executable code. */
    readerFactory: (() -> NlpReader)? = null,
) {
    private val requests = HeadhunterRequests(CoroutineScope(ctx.scope.coroutineContext + ctx.muxContext.minusKey(Job) + fileIoContext))
    private val suppliedReader = readerFactory != null
    private val reader = lazy { readerFactory?.invoke() ?: CoreNlpRuntime(CoreNlpRuntime.Profile.SYNTAX) }
    private val observations = EnglishObservationWire(ctx, corpus)

    fun attach() { ctx.routes.claim(OWNER, PATH, route = ::route); observations.attach() }

    /** CoreNLP's synchronous call has no wall deadline; accepted calls finish before its owner closes. */
    suspend fun drain() {
        ctx.routes.release(OWNER)
        observations.drain()
        requests.drain()
        if (reader.isInitialized()) (reader.value as? AutoCloseable)?.close()
    }

    private fun available() = suppliedReader || "corenlp" in GuestModules.installed()
    private fun json(value: Any?, status: Int = 200) = WireHttpResponse(status, jsonOf(value), "application/json; charset=utf-8")

    internal suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)?): WireHttpResponse {
        if (path.substringBefore('?') != PATH) return json(_m["error" j "not_found"], 404)
        if (method == "GET") return json(_m["available" j available(), "requiresModel" j false,
            "module" j "corenlp", "profile" j "syntax-v1", "maxChars" j MAX_CHARS,
            "semantics" j "not-established", "parseWallDeadline" j null])
        if (method != "POST") return json(_m["error" j "method_not_allowed"], 405)
        return try { requests.call {
            val input = DocumentReviewWire.taxonomyRequest(text.substringAfter("\r\n\r\n", text))
            require(input.keys == _s["project", "id", "cid", "rev", "seq", "begin", "end"]) { "Unexpected or missing English inspection fields" }
            fun field(key: String) = (input[key] as? String)?.takeIf { it.isNotBlank() && it.length <= 4096 } ?: error("$key required")
            val expected = ProjectDoc(field("project"), field("id"), field("cid"), field("rev"),
                DocumentReviewWire.revision(input["seq"]), 0, "")
            ContentId(expected.cid)
            fun offset(key: String): Int = DocumentReviewWire.revision(input[key]).let {
                require(it <= Int.MAX_VALUE) { "$key exceeds integer range" }; it.toInt()
            }
            val begin = offset("begin"); val end = offset("end")
            require(end > begin && end - begin <= MAX_CHARS) { "Select 1..16384 UTF-16 characters" }
            if (!available()) return@call json(_m["available" j false, "requiresModel" j false,
                "error" j "Local CoreNLP module is not installed; install it before English inspection."], 503)
            inspect(expected, begin, end)
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: UnsupportedOperationException) { json(_m["error" j failure.message], 503) }
        catch (failure: Exception) { json(_m["error" j (failure.message ?: "English inspection refused")], 409) }
    }

    private suspend fun current(expected: ProjectDoc): ProjectDoc {
        val row = corpus.document(expected.project, expected.id)
        require(row != null && row.cid == expected.cid && row.rev == expected.rev && row.seq == expected.seq) {
            "Selected source revision changed or is unavailable"
        }
        return row
    }

    private fun bounded(row: ProjectDoc, limit: Int): ByteArray {
        require(row.length in 0..limit.toLong()) { "Retained source exceeds $limit bytes" }
        val cas = ctx.casStore as? BoundedCasReader ?: throw UnsupportedOperationException("Bounded retained reads unavailable")
        val bytes = requireNotNull(cas.getBounded(ContentId(row.cid), limit)) { "Retained source bytes unavailable" }
        require(bytes.size.toLong() == row.length && ContentId.of(bytes).value == row.cid) { "Retained source identity mismatch" }
        return bytes
    }

    private data class Source(val value: DocumentSource, val revisions: Series<ProjectDoc>)

    private suspend fun source(expected: ProjectDoc): Source {
        val original = current(expected)
        require(original.length in 0..MAX_ORIGINAL.toLong()) { "Original source exceeds 32MiB" }
        val identityText = original.id.substringAfterLast('.', "").lowercase() in _s["txt", "md"]
        require(!identityText || original.length <= MAX_TEXT) { "Source text exceeds 4MiB" }
        val snapshots = ctx.casStore as? PackCasStore
        val cas = ctx.casStore as? BoundedCasReader ?: throw UnsupportedOperationException("Bounded retained reads unavailable")
        if (identityText && cas.getBounded(ContentId(original.cid), MAX_TEXT) == null && corpus is JvmProjectCorpus && snapshots != null) {
            // Primary snapshots have a separate Pack owner; its existing checked boundary copies exact bytes.
            val retained = corpus.retainPrimaryText(original, snapshots)
            current(expected)
            return Source(retained, s_[original])
        }
        val bytes = bounded(original, if (identityText) MAX_TEXT else MAX_ORIGINAL)
        val revision = DocumentRevision(original.project, original.id, original.cid, original.rev, original.seq)
        if (identityText) {
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            require(text.encodeToByteArray().contentEquals(bytes)) { "Source is not exact UTF-8" }
            current(expected)
            return Source(DocumentSource(ContentId(original.cid), ContentId(original.cid), text, original.id, original.contentType,
                "${original.project}/${original.id}@${original.rev}", category = "document",
                provenance = _m["extractionKind" j "identity-utf8", "reviewStatus" j "unreviewed"],
                project = revision, extraction = revision), s_[original])
        }
        // Do not call corpus.extract: it may launch a miner. Only an already retained completion is inspected.
        val extracted = requireNotNull(corpus.document(original.project, original.id + ProjectNodes.EXTRACT_SUFFIX)) {
            "No retained text extraction is available; English inspection does not run extraction or OCR"
        }
        val lineage = requireNotNull(corpus.document(original.project, original.id + ProjectNodes.EXTRACTION_SUFFIX)) { "Extraction lineage is unavailable" }
        val origin = requireNotNull(corpus.document(original.project, original.id + ProjectNodes.EXTRACT_ORIGIN_SUFFIX)) { "Extraction origin is unavailable" }
        val extractedBytes = bounded(extracted, MAX_TEXT)
        val text = extractedBytes.decodeToString(throwOnInvalidSequence = true)
        require(text.encodeToByteArray().contentEquals(extractedBytes)) { "Extraction is not exact UTF-8" }
        val lineageBytes = bounded(lineage, MAX_LINEAGE)
        val completion = DocumentReviewWire.taxonomyRequest(lineageBytes.decodeToString(throwOnInvalidSequence = true))
        require((completion["version"] as? Number)?.toDouble() == 1.0) { "Unsupported extraction lineage version" }
        require(completion["originalCid"] == original.cid && completion["originalRev"] == original.rev &&
            completion["extractedTextCid"] == extracted.cid) { "Extraction lineage differs from selected source" }
        val originFields = bounded(origin, 1024).decodeToString(throwOnInvalidSequence = true).split('\t')
        require(originFields == _l[original.cid, extracted.cid]) { "Extraction origin differs from selected source" }
        val anchors = (completion["anchors"] as? List<*>)?.map(DocumentAnchor::fromMap) ?: error("Extraction anchors unavailable")
        val unresolved = (completion["unresolved"] as? List<*>)?.map { it as? String ?: error("Invalid unresolved extraction field") }
            ?: error("Extraction unresolved fields unavailable")
        val source = DocumentSource(ContentId(original.cid), ContentId(extracted.cid), text, original.id, original.contentType,
            "${original.project}/${original.id}@${original.rev}", category = "document",
            provenance = _m["extractionKind" j "retained-twin", "extractionLineageCid" j lineage.cid, "reviewStatus" j "unreviewed"],
            project = revision, extraction = DocumentRevision(extracted.project, extracted.id, extracted.cid, extracted.rev, extracted.seq),
            anchors = anchors, unresolved = unresolved)
        val revisions = s_[original, extracted, lineage, origin]
        for (row in revisions.view) current(row)
        return Source(source, revisions)
    }

    private suspend fun inspect(expected: ProjectDoc, begin: Int, end: Int): WireHttpResponse {
        val retained = source(expected)
        val source = retained.value
        require(begin >= 0 && end <= source.text.length) { "Selected range is outside retained extracted text" }
        source.quotation(begin, end) // Checks scalar boundaries before invoking the parser.
        val parsed = reader.value.read(source.text.substring(begin, end))
        for (row in retained.revisions.view) current(row)
        val inventory = EnglishInspection.inspect(source, parsed, begin, end)
        val receipt = inventory + _m["selection" j _m["project" j expected.project, "id" j expected.id,
            "cid" j expected.cid, "rev" j expected.rev, "seq" j expected.seq],
            "source" j (DocumentCuratorCodec.source(source) - "text"),
            "sourceSnapshots" j (retained.revisions α { it.toMap() }).toList()]
        val bytes = CanonicalCbor.encodeMap(receipt)
        require(bytes.size <= MAX_RECEIPT) { "English inspection receipt exceeds 8MiB" }
        val cid = ctx.casStore.put(bytes); ctx.casStore.sync()
        val readback = requireNotNull((ctx.casStore as BoundedCasReader).getBounded(cid, MAX_RECEIPT)) { "English inspection receipt unavailable" }
        require(readback.contentEquals(bytes) && ContentId.of(readback) == cid) { "English inspection receipt retention failed" }
        for (row in retained.revisions.view) current(row)
        current(expected)
        return json(receipt + _m["receiptCid" j cid.value])
    }

    companion object {
        const val PATH = "/api/documents/english"
        const val OWNER = "document-english"
        const val MAX_CHARS = 16_384
        const val MAX_ORIGINAL = 32 * 1024 * 1024
        const val MAX_TEXT = 4 * 1024 * 1024
        const val MAX_LINEAGE = 65_536
        const val MAX_RECEIPT = 8 * 1024 * 1024
    }
}
||||||| a39e91f9e
=======
package borg.trikeshed.forge.server

import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.WireHttpResponse
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.narsese.*
import borg.trikeshed.nlp.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.*

/** Local parser observations only: no model, mining, review authority or reasoning admission. */
class DocumentEnglishWire(
    private val ctx: ModuleContext,
    private val corpus: ProjectCorpus,
    /** Host construction seam for a reader; requests cannot select or configure executable code. */
    readerFactory: (() -> NlpReader)? = null,
) {
    private val requests = HeadhunterRequests(CoroutineScope(ctx.scope.coroutineContext + ctx.muxContext.minusKey(Job) + fileIoContext))
    private val suppliedReader = readerFactory != null
    private val reader = lazy { readerFactory?.invoke() ?: CoreNlpRuntime(CoreNlpRuntime.Profile.SYNTAX) }
    private val observations = EnglishObservationWire(ctx, corpus)

    fun attach() { ctx.routes.claim(OWNER, PATH, route = ::route); observations.attach() }

    /** CoreNLP's synchronous call has no wall deadline; accepted calls finish before its owner closes. */
    suspend fun drain() {
        ctx.routes.release(OWNER)
        observations.drain()
        requests.drain()
        if (reader.isInitialized()) (reader.value as? AutoCloseable)?.close()
    }

    private fun available() = suppliedReader || "corenlp" in GuestModules.installed()
    private fun json(value: Any?, status: Int = 200) = WireHttpResponse(status, jsonOf(value), "application/json; charset=utf-8")

    internal suspend fun route(method: String, path: String, text: String, respond: (suspend (ByteArray) -> Unit)?): WireHttpResponse {
        if (path.substringBefore('?') != PATH) return json(_m["error" j "not_found"], 404)
        if (method == "GET") return json(_m["available" j available(), "requiresModel" j false,
            "module" j "corenlp", "profile" j "syntax-v1", "maxChars" j MAX_CHARS,
            "semantics" j "not-established", "parseWallDeadline" j null])
        if (method != "POST") return json(_m["error" j "method_not_allowed"], 405)
        return try { requests.call {
            val input = DocumentReviewWire.taxonomyRequest(text.substringAfter("\r\n\r\n", text))
            require(input.keys == _s["project", "id", "cid", "rev", "seq", "begin", "end"]) { "Unexpected or missing English inspection fields" }
            fun field(key: String) = (input[key] as? String)?.takeIf { it.isNotBlank() && it.length <= 4096 } ?: error("$key required")
            val expected = ProjectDoc(field("project"), field("id"), field("cid"), field("rev"),
                DocumentReviewWire.revision(input["seq"]), 0, "")
            ContentId(expected.cid)
            fun offset(key: String): Int = DocumentReviewWire.revision(input[key]).let {
                require(it <= Int.MAX_VALUE) { "$key exceeds integer range" }; it.toInt()
            }
            val begin = offset("begin"); val end = offset("end")
            require(end > begin && end - begin <= MAX_CHARS) { "Select 1..16384 UTF-16 characters" }
            if (!available()) return@call json(_m["available" j false, "requiresModel" j false,
                "error" j "Local CoreNLP module is not installed; install it before English inspection."], 503)
            inspect(expected, begin, end)
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: UnsupportedOperationException) { json(_m["error" j failure.message], 503) }
        catch (failure: Exception) { json(_m["error" j (failure.message ?: "English inspection refused")], 409) }
    }

    private suspend fun current(expected: ProjectDoc): ProjectDoc {
        val row = corpus.document(expected.project, expected.id)
        require(row != null && row.cid == expected.cid && row.rev == expected.rev && row.seq == expected.seq) {
            "Selected source revision changed or is unavailable"
        }
        return row
    }

    private fun bounded(row: ProjectDoc, limit: Int): ByteArray {
        require(row.length in 0..limit.toLong()) { "Retained source exceeds $limit bytes" }
        val cas = ctx.casStore as? BoundedCasReader ?: throw UnsupportedOperationException("Bounded retained reads unavailable")
        val bytes = requireNotNull(cas.getBounded(ContentId(row.cid), limit)) { "Retained source bytes unavailable" }
        require(bytes.size.toLong() == row.length && ContentId.of(bytes).value == row.cid) { "Retained source identity mismatch" }
        return bytes
    }

    private data class Source(val value: DocumentSource, val revisions: Series<ProjectDoc>)

    private suspend fun source(expected: ProjectDoc): Source {
        val original = current(expected)
        require(original.length in 0..MAX_ORIGINAL.toLong()) { "Original source exceeds 32MiB" }
        val identityText = original.id.substringAfterLast('.', "").lowercase() in _s["txt", "md"]
        require(!identityText || original.length <= MAX_TEXT) { "Source text exceeds 4MiB" }
        val snapshots = ctx.casStore as? PackCasStore
        val cas = ctx.casStore as? BoundedCasReader ?: throw UnsupportedOperationException("Bounded retained reads unavailable")
        if (identityText && cas.getBounded(ContentId(original.cid), MAX_TEXT) == null && corpus is JvmProjectCorpus && snapshots != null) {
            // Primary snapshots have a separate Pack owner; its existing checked boundary copies exact bytes.
            val retained = corpus.retainPrimaryText(original, snapshots)
            current(expected)
            return Source(retained, s_[original])
        }
        val bytes = bounded(original, if (identityText) MAX_TEXT else MAX_ORIGINAL)
        val revision = DocumentRevision(original.project, original.id, original.cid, original.rev, original.seq)
        if (identityText) {
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            require(text.encodeToByteArray().contentEquals(bytes)) { "Source is not exact UTF-8" }
            current(expected)
            return Source(DocumentSource(ContentId(original.cid), ContentId(original.cid), text, original.id, original.contentType,
                "${original.project}/${original.id}@${original.rev}", category = "document",
                provenance = _m["extractionKind" j "identity-utf8", "reviewStatus" j "unreviewed"],
                project = revision, extraction = revision), s_[original])
        }
        // Do not call corpus.extract: it may launch a miner. Only an already retained completion is inspected.
        val extracted = requireNotNull(corpus.document(original.project, original.id + ProjectNodes.EXTRACT_SUFFIX)) {
            "No retained text extraction is available; English inspection does not run extraction or OCR"
        }
        val lineage = requireNotNull(corpus.document(original.project, original.id + ProjectNodes.EXTRACTION_SUFFIX)) { "Extraction lineage is unavailable" }
        val origin = requireNotNull(corpus.document(original.project, original.id + ProjectNodes.EXTRACT_ORIGIN_SUFFIX)) { "Extraction origin is unavailable" }
        val extractedBytes = bounded(extracted, MAX_TEXT)
        val text = extractedBytes.decodeToString(throwOnInvalidSequence = true)
        require(text.encodeToByteArray().contentEquals(extractedBytes)) { "Extraction is not exact UTF-8" }
        val lineageBytes = bounded(lineage, MAX_LINEAGE)
        val completion = DocumentReviewWire.taxonomyRequest(lineageBytes.decodeToString(throwOnInvalidSequence = true))
        require((completion["version"] as? Number)?.toDouble() == 1.0) { "Unsupported extraction lineage version" }
        require(completion["originalCid"] == original.cid && completion["originalRev"] == original.rev &&
            completion["extractedTextCid"] == extracted.cid) { "Extraction lineage differs from selected source" }
        val originFields = bounded(origin, 1024).decodeToString(throwOnInvalidSequence = true).split('\t')
        require(originFields == _l[original.cid, extracted.cid]) { "Extraction origin differs from selected source" }
        val anchors = (completion["anchors"] as? List<*>)?.map(DocumentAnchor::fromMap) ?: error("Extraction anchors unavailable")
        val unresolved = (completion["unresolved"] as? List<*>)?.map { it as? String ?: error("Invalid unresolved extraction field") }
            ?: error("Extraction unresolved fields unavailable")
        val source = DocumentSource(ContentId(original.cid), ContentId(extracted.cid), text, original.id, original.contentType,
            "${original.project}/${original.id}@${original.rev}", category = "document",
            provenance = _m["extractionKind" j "retained-twin", "extractionLineageCid" j lineage.cid, "reviewStatus" j "unreviewed"],
            project = revision, extraction = DocumentRevision(extracted.project, extracted.id, extracted.cid, extracted.rev, extracted.seq),
            anchors = anchors, unresolved = unresolved)
        val revisions = s_[original, extracted, lineage, origin]
        for (row in revisions.view) current(row)
        return Source(source, revisions)
    }

    private suspend fun inspect(expected: ProjectDoc, begin: Int, end: Int): WireHttpResponse {
        val retained = source(expected)
        val source = retained.value
        require(begin >= 0 && end <= source.text.length) { "Selected range is outside retained extracted text" }
        source.quotation(begin, end) // Checks scalar boundaries before invoking the parser.
        val parsed = reader.value.read(source.text.substring(begin, end))
        for (row in retained.revisions.view) current(row)
        val inventory = EnglishInspection.inspect(source, parsed, begin, end)
        val receipt = inventory + _m["selection" j _m["project" j expected.project, "id" j expected.id,
            "cid" j expected.cid, "rev" j expected.rev, "seq" j expected.seq],
            "source" j (DocumentCuratorCodec.source(source) - "text"),
            "sourceSnapshots" j (retained.revisions α { it.toMap() }).toList()]
        val bytes = CanonicalCbor.encodeMap(receipt)
        require(bytes.size <= MAX_RECEIPT) { "English inspection receipt exceeds 8MiB" }
        val cid = ctx.casStore.put(bytes); ctx.casStore.sync()
        val readback = requireNotNull((ctx.casStore as BoundedCasReader).getBounded(cid, MAX_RECEIPT)) { "English inspection receipt unavailable" }
        require(readback.contentEquals(bytes) && ContentId.of(readback) == cid) { "English inspection receipt retention failed" }
        for (row in retained.revisions.view) current(row)
        current(expected)
        return json(receipt + _m["receiptCid" j cid.value])
    }

    companion object {
        const val PATH = "/api/documents/english"
        const val OWNER = "document-english"
        const val MAX_CHARS = 16_384
        const val MAX_ORIGINAL = 32 * 1024 * 1024
        const val MAX_TEXT = 4 * 1024 * 1024
        const val MAX_LINEAGE = 65_536
        const val MAX_RECEIPT = 8 * 1024 * 1024
    }
}
>>>>>>> 8ac324fc91785c8a0511e0761df38744136942a3
