package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.ReifiedSplitSeries2
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*

/**
 * What a [QueryCursor] answers to `facet(QueryK.Receipt)`: the statement's canonical text, the content
 * ids of its sources (when the catalog knows them), the plan's stages and the rows it produced.
 * [statementCid] identifies the statement over its sources; [canonicalCid] adds the cardinality, so it
 * tells apart runs of one statement that produced different numbers of rows (the rows themselves are
 * the breadcrumb's heritage, under their own content id). Both are SHA-256 over canonical CBOR of the
 * same document, so equal statements over equal sources agree everywhere the engine runs.
 */
data class QueryReceipt(val statement: String, val sources: Map<String, ContentId?>, val stages: List<String>, val rows: Int) {
    init { require(statement.isNotBlank()) { "a receipt names a statement" } }
    val document: Map<String, Any?> = mapOf("statement" to statement, "sources" to sources.mapValues { it.value?.value }, "stages" to stages)
    val statementCid: ContentId = ContentId.of(CanonicalCbor.encodeMap(document))
    val canonicalCid: ContentId = ContentId.of(CanonicalCbor.encodeMap(document + ("rows" to rows)))
}

/** A column's metadata with the content id of what produced it; the [RecordMeta.provenance] of a computed column. */
class ProvenanceMeta(val base: ColumnMeta, val provenance: ContentId) : ColumnMeta by base

/** The content id of what produced this column, or null for a source column. */
val ColumnMeta.provenance: ContentId?
    get() = when (this) {
        is ProvenanceMeta -> provenance
        is RecordMeta -> provenance
        else -> null
    }

/**
 * This column's metadata carrying [cid] as its provenance. A [RecordMeta] stays a RecordMeta (offsets, codecs,
 * grouping and pool intact) so the ISAM metafile gates still recognise it; anything else is wrapped.
 */
fun ColumnMeta.carrying(cid: ContentId): ColumnMeta = when (this) {
    is RecordMeta -> RecordMeta(name, type, begin, end, decoder, encoder, child, cid).also {
        it.groupId = groupId
        it.groupName = groupName
        it.pool = pool
    }
    else -> ProvenanceMeta(this, cid)
}

/** This column's metadata under [name], keeping its provenance. */
fun ColumnMeta.renamed(name: String): ColumnMeta = ColumnMeta(name, type, child).let { m -> provenance?.let { ProvenanceMeta(m, it) } ?: m }

/**
 * The same rows with every column carrying [cid] as its provenance. A [ColumnCursor] keeps its arrays
 * (and carries provenance even with no rows, since its columns are known); a row-shaped cursor shares
 * one meta series across the rows that share the first row's, and wraps any other row's own.
 */
fun Cursor.withProvenance(cid: ContentId): Cursor {
    if (this is ColumnCursor) return renamed(Array(width) { metas[it].carrying(cid) })
    if (size == 0) return this
    val exemplar = this[0].metas
    val shared: Series<`ColumnMeta↻`> = exemplar.size j { c: Int -> exemplar[c]().carrying(cid).let { m -> { m } } }
    return size j { r: Int ->
        val row = this[r]
        val own = row.metas
        ReifiedSplitSeries2(row.values, if (own === exemplar) shared else own.size j { c: Int -> { own[c]().carrying(cid) } })
    }
}
