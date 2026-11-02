package borg.trikeshed.lcnc

import borg.trikeshed.finance.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.csv.CSVUtil
import kotlinx.coroutines.currentCoroutineContext

/** Source-backed review reports. Reads current original bytes; no extraction, posting or approval. */
object FinanceNodes {
    const val RECONCILE = "finance.reconcile"
    const val MAX_BYTES = 1_048_576
    const val PARSER = "strict-delimited-records-v1"
    fun servedTypes(): Set<String> = _s[RECONCILE]
    fun registry(corpus: ProjectCorpus): Map<String, LcncNodeRunner> = _m[
        RECONCILE j boundLcnc(ProjectCorpusKey(corpus)) { service, node, inputs -> run(service.value, node, inputs) }]

    private data class Source(val doc: ProjectDoc, val bytes: ByteArray, val text: String, val format: String,
        val records: Series<CSVUtil.Record>, val headers: Series<CSVUtil.Cell>)

    private fun integral(value: Any?): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        else -> throw IllegalArgumentException("mandatory integral document metadata required")
    }
    private fun selection(value: Any?): ProjectDoc {
        val map = value as? Map<*, *> ?: error("ProjectDoc selection required")
        fun field(name: String) = (map[name] as? String)?.takeIf { it.isNotBlank() && it.length <= 1024 }
            ?: throw IllegalArgumentException("ProjectDoc $name required")
        val cid = ContentId(field("cid")).value
        val sequence = integral(map["seq"])
        val length = integral(map["length"])
        require(sequence >= 0 && length in 0..MAX_BYTES.toLong()) { "document sequence/byte limit" }
        return ProjectDoc(field("project"), field("id"), cid, field("rev"), sequence, length, field("contentType"), map["contentStatus"] as? String)
    }
    private suspend fun read(corpus: ProjectCorpus, selected: ProjectDoc): Source {
        val doc = requireNotNull(corpus.document(selected.project, selected.id)) { "source missing" }
        require(doc == selected) { "stale source selection" }
        val format = when (doc.contentType.substringBefore(';').trim().lowercase()) {
            "text/csv" -> "CSV"
            "text/tab-separated-values" -> "TSV"
            else -> throw IllegalArgumentException("declared CSV/TSV source required")
        }
        val bytes = requireNotNull(corpus.bytes(doc.project, doc.id)) { "original source bytes unavailable" }
        require(bytes.size <= MAX_BYTES && bytes.size.toLong() == doc.length) { "source byte length mismatch or limit" }
        require(ContentId.of(bytes).value == doc.cid) { "source bytes do not match CID" }
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        require(text.encodeToByteArray().contentEquals(bytes)) { "source must roundtrip strict UTF-8" }
        currentCoroutineContext()[LcncConsumedLedger]?.consumed(LcncConsumedLedger.PROJECT,
            "${doc.project}/${doc.id}", doc.cid, doc.seq, doc.rev)
        val records = CSVUtil.records(text, if (format == "CSV") ',' else '\t',
            RECONCILE_ROWS + 1, RECONCILE_RAW_CHARS, 64, MAX_BYTES)
        require(records.size > 0) { "source requires canonical headers" }
        val headers = records[0].cells
        val names = headers.view.map { it.value }
        require(names.all { it.isNotBlank() } && names.distinct().size == names.size) { "missing/duplicate headers" }
        require(names.containsAll(_l["id", "account", "reference", "currency", "date", "amount"])) { "required finance headers absent" }
        for (i in 1 until records.size) require(records[i].cells.size == headers.size) { "row/header arity mismatch at record ${i + 1}" }
        return Source(doc, bytes, text, format, records, headers)
    }
    private fun rows(source: Source): Series<FinanceRow> = (source.records.size - 1) j { index: Int ->
        val record = source.records[index + 1]
        fun field(name: String): String {
            for (column in 0 until source.headers.size) if (source.headers[column].value == name) return record.cells[column].value
            error("header absent")
        }
        FinanceRow(field("id"), field("account"), field("reference"), field("currency"), field("date"), field("amount"),
            ContentId(source.doc.cid), index + 2, source.text.substring(record.begin, record.end))
    }
    private fun metadata(source: Source): Map<String, Any?> = _m[
        "project" j source.doc.project, "id" j source.doc.id, "cid" j source.doc.cid, "revision" j source.doc.rev,
        "sequence" j source.doc.seq.toString(), "byteLength" j source.doc.length.toString(),
        "format" j source.format, "parserIdentity" j PARSER]
    private fun totals(value: ReconcileTotals): Map<String, Any?> = _m[
        "inputMinorUnits" j value.input?.toString(), "matchedMinorUnits" j value.matched?.toString(),
        "reviewMinorUnits" j value.review?.toString(), "conserved" j value.conserved]
    private fun evidence(entry: ReconcileEntry, source: Source): Map<String, Any?> {
        val record = source.records[entry.row.rowOrdinal - 1]
        val cells = (0 until source.headers.size).toSeries() α { column ->
            val cell = record.cells[column]
            val header = source.headers[column]
            _m["name" j header.value, "value" j cell.value, "begin" j cell.begin, "end" j cell.end,
                "headerBegin" j header.begin, "headerEnd" j header.end]
        }
        return _m["role" j entry.role.name, "id" j entry.row.id, "account" j entry.row.account,
            "reference" j entry.row.reference, "currency" j entry.row.currency, "date" j entry.row.date,
            "amount" j entry.row.amount, "minorUnits" j entry.units?.toString(), "disposition" j entry.disposition.name,
            "reason" j entry.reason, "counterpartOrdinal" j entry.counterpartOrdinal,
            "source" j metadata(source), "record" j entry.row.rowOrdinal, "rawRow" j entry.row.rawRow,
            "recordBegin" j record.begin, "recordEnd" j record.end, "offsetUnit" j "UTF-16-half-open",
            "cells" j cells.view.toList()]
    }
    private suspend fun verify(corpus: ProjectCorpus, source: Source) {
        require(corpus.document(source.doc.project, source.doc.id) == source.doc) { "source changed during report" }
        val bytes = requireNotNull(corpus.bytes(source.doc.project, source.doc.id)) { "source removed during report" }
        require(bytes.size <= MAX_BYTES && bytes.contentEquals(source.bytes) && ContentId.of(bytes).value == source.doc.cid) { "source bytes changed during report" }
        require(corpus.document(source.doc.project, source.doc.id) == source.doc) { "source revision changed during readback" }
    }
    private suspend fun run(corpus: ProjectCorpus, node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> {
        fun param(name: String) = node.params[name]?.takeIf { it.isNotBlank() } ?: error("finance.reconcile: $name required")
        val currency = CurrencyScale(param("currency"), param("scale").toInt())
        val includeDate = when (param("includeDate")) { "true" -> true; "false" -> false; else -> error("includeDate must be explicit true/false") }
        val policy = ReconcilePolicy(param("policyId"), includeDate, param("accountScope"))
        val bank = read(corpus, selection(inputs["bank"]))
        val expenditure = read(corpus, selection(inputs["expenditure"]))
        fun source(value: Source, role: SourceRole) = ReconcileSource(role, ContentId(value.doc.cid), value.doc.rev, PARSER + ":" + value.format)
        val report = reconcile(source(bank, SourceRole.BANK), source(expenditure, SourceRole.EXPENDITURE), rows(bank), rows(expenditure), currency, policy)
        val review = report.entries.view.map { evidence(it, if (it.role == SourceRole.BANK) bank else expenditure) }
        val value = _m["status" j "as-of-read-candidates-only", "complete" j report.complete,
            "authority" j "review-only; caller-declared source roles; no posting or approval",
            "retention" j "current source identity and readback; no durable source retention or atomic cross-source snapshot",
            "currency" j currency.code, "scale" j currency.scale, "accountScope" j policy.accountScope,
            "includeDate" j policy.includeDate, "policyId" j policy.identity, "parserIdentity" j PARSER,
            "bankSource" j metadata(bank), "expenditureSource" j metadata(expenditure),
            "bankTotals" j totals(report.bankTotals), "expenditureTotals" j totals(report.expenditureTotals),
            "signConvention" j report.signConvention, "issues" j report.issues.view.toList(), "rows" j review]
        verify(corpus, bank)
        verify(corpus, expenditure)
        return _m["report" j value, "rows" j review, "complete" j report.complete, "status" j "as-of-read-candidates-only"]
    }
}
