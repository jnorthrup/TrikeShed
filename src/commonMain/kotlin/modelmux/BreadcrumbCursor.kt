package modelmux

import borg.trikeshed.cursor.ColumnCursor
import borg.trikeshed.cursor.query.QueryReceipt
import borg.trikeshed.cursor.query.rowsCursor
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.value

/**
 * The breadcrumb index as a typed table, `breadcrumbs`: id, ref, cid, spineCid, title, source, chars,
 * createdAt, heritageCid — the index's metadata document per entry, read into columns (the
 * `BoardCursor` shape). A recorded query ([recordQuery]) is a row whose source is `query:cursor`,
 * whose title is the statement and whose heritage holds its result.
 */
class BreadcrumbCursor(val entries: Series<BreadcrumbIndexed>) {
    fun cursor(): ColumnCursor = rowsCursor(COLUMNS, entries.view.map(::row), TYPES)

    companion object {
        val COLUMNS = listOf("id", "ref", "cid", "spineCid", "title", "source", "chars", "createdAt", "heritageCid")
        val TYPES = mapOf("chars" to "int8", "createdAt" to "int8", "heritageCid" to "text")

        fun row(e: BreadcrumbIndexed): Map<String, Any?> = mapOf(
            "id" to e.id.value, "ref" to e.ref.value, "cid" to e.cid.value, "spineCid" to e.spine.value,
            "title" to e.title, "source" to e.source,
            "chars" to longOf(e.metadata.value("chars")),
            "createdAt" to longOf(e.metadata.value("createdAt")),
            "heritageCid" to e.metadata.value("heritageCid")?.toString(),
        )

        /** Every context's breadcrumbs as one table, `session` (the context id) first: what the daemon's catalog serves as `breadcrumbs`. */
        fun union(contexts: Map<String, BreadcrumbContext>): ColumnCursor = rowsCursor(
            listOf("session") + COLUMNS,
            contexts.entries.flatMap { (session, context) -> context.index.all().view.map { e -> mapOf("session" to session) + row(e) } },
            TYPES,
        )

        /** The index writes `chars` as a number and `createdAt` as a decimal string; both read as int8. */
        fun longOf(value: Any?): Long? = when (value) {
            is Number -> value.toLong()
            is CharSequence -> value.toString().toLongOrNull()
            else -> null
        }

        fun of(index: BreadcrumbIndex): BreadcrumbCursor = BreadcrumbCursor(index.all())
    }
}

/**
 * Records a query in [session]'s breadcrumbs: the statement is the breadcrumb (source `query:cursor`,
 * title the statement), [result] its heritage. The id is the statement's content id, so the same
 * statement recorded twice is one breadcrumb, keeping its first heritage.
 */
suspend fun BreadcrumbElement.recordQuery(session: String, receipt: QueryReceipt, result: ConfixDoc? = null): BreadcrumbRecord =
    transact(session) { context ->
        context.put(BreadcrumbId("query-" + receipt.statementCid.hex.take(24)), "query:cursor", receipt.statement, title = receipt.statement, heritage = result)
    }
