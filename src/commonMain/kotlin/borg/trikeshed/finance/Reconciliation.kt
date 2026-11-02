package borg.trikeshed.finance

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*

enum class SourceRole { BANK, EXPENDITURE }
data class ReconcileSource(val role: SourceRole, val sourceCid: ContentId, val revision: String, val parserIdentity: String) {
    init { require(revision.isNotBlank() && parserIdentity.isNotBlank() && revision.length <= 512 && parserIdentity.length <= 512) }
}
data class ReconcilePolicy(val identity: String, val includeDate: Boolean, val accountScope: String) {
    init { require(identity.isNotBlank() && identity.length <= 512 && accountScope.isNotBlank() && accountScope.length <= 512) }
}
/** Raw CSV cells and logical record ordinal; source bytes remain owned by the caller's source store. */
data class FinanceRow(val id: String, val account: String, val reference: String, val currency: String,
    val date: String, val amount: String, val sourceCid: ContentId, val rowOrdinal: Int, val rawRow: String)
enum class ReconcileDisposition { EXACT, UNMATCHED, AMBIGUOUS, DUPLICATE, INVALID }
data class ReconcileEntry(val role: SourceRole, val row: FinanceRow, val units: Long?,
    val disposition: ReconcileDisposition, val reason: String, val counterpartOrdinal: Int? = null)
data class ReconcileTotals(val input: Long?, val matched: Long?, val review: Long?, val conserved: Boolean)
data class ReconcileReport(val bankSource: ReconcileSource, val expenditureSource: ReconcileSource,
    val policy: ReconcilePolicy, val currency: CurrencyScale, val entries: Series<ReconcileEntry>,
    val bankTotals: ReconcileTotals, val expenditureTotals: ReconcileTotals, val complete: Boolean,
    val issues: Series<String>, val signConvention: String = "Signed source movements; no debit/credit conversion or posting")

data class ReconcileKey(val account: String, val reference: String, val currency: String, val units: Long, val date: String?)

fun reconciliationDate(text: String): Boolean {
    if (text.length != 10 || text[4] != '-' || text[7] != '-' || text.indices.any { it != 4 && it != 7 && text[it] !in '0'..'9' }) return false
    val year = text.substring(0, 4).toInt()
    val month = text.substring(5, 7).toInt()
    val day = text.substring(8, 10).toInt()
    if (year == 0 || month !in 1..12) return false
    val days = when (month) { 2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
    return day in 1..days
}

const val RECONCILE_ROWS = 4096
const val RECONCILE_RAW_CHARS = 65_536

/** Review-only exact candidates, not authorization or proof of source authenticity.
 * Every admitted row appears once. Source retention/authentication belongs to the host.
 * At JSON boundaries encode Long units/totals as decimal STRINGS, never JS Number.
 */
fun reconcile(bankSource: ReconcileSource, expenditureSource: ReconcileSource,
    bankRows: Series<FinanceRow>, expenditureRows: Series<FinanceRow>, currency: CurrencyScale,
    policy: ReconcilePolicy): ReconcileReport {
    require(bankSource.role == SourceRole.BANK && expenditureSource.role == SourceRole.EXPENDITURE)
    require(bankRows.size in 0..RECONCILE_ROWS && expenditureRows.size in 0..RECONCILE_ROWS) { "row count limit" }
    val issues = mutableListOf<String>()
    val replay = bankSource.sourceCid == expenditureSource.sourceCid
    if (replay) issues.add("same-source replay is not independent evidence")
    fun admit(rows: Series<FinanceRow>, source: ReconcileSource): MutableList<ReconcileEntry> {
        val duplicateIds = rows.view.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        val duplicateOrdinals = rows.view.groupingBy { it.rowOrdinal }.eachCount().filterValues { it > 1 }.keys
        return rows.view.map { row ->
            val units = if (row.currency == currency.code) runCatching { parseMinorUnits(row.amount, currency) }.getOrNull() else null
            val invalid = when {
                replay -> "same-source replay"
                row.sourceCid != source.sourceCid -> "source CID mismatch"
                row.rowOrdinal < 1 -> "invalid row ordinal"
                row.rawRow.length > RECONCILE_RAW_CHARS || row.id.length > 512 || row.account.length > 512 || row.reference.length > 512 || row.date.length > 32 -> "row field limit"
                row.account != policy.accountScope -> "account outside declared scope"
                row.id.isBlank() || row.account.isBlank() || row.reference.isBlank() || row.rawRow.isEmpty() -> "missing required evidence"
                units == null -> "invalid currency or decimal amount"
                policy.includeDate && !reconciliationDate(row.date) -> "invalid required date"
                else -> null
            }
            val duplicate = row.id in duplicateIds || row.rowOrdinal in duplicateOrdinals
            ReconcileEntry(source.role, row, units,
                if (invalid != null) ReconcileDisposition.INVALID else if (duplicate) ReconcileDisposition.DUPLICATE else ReconcileDisposition.UNMATCHED,
                invalid ?: if (duplicate) "duplicate source ID or row ordinal" else "no exact counterpart")
        }.toMutableList()
    }
    val bank = admit(bankRows, bankSource)
    val expenditure = admit(expenditureRows, expenditureSource)
    fun key(entry: ReconcileEntry) = ReconcileKey(entry.row.account, entry.row.reference, entry.row.currency,
        requireNotNull(entry.units), if (policy.includeDate) entry.row.date else null)
    // Duplicate rows remain in candidate cardinality so they cannot make another row appear unique.
    fun candidates(entries: List<ReconcileEntry>) = entries.withIndex().filter { it.value.disposition != ReconcileDisposition.INVALID }.groupBy { key(it.value) }
    val a = candidates(bank)
    val b = candidates(expenditure)
    for (matchKey in a.keys + b.keys) {
        val left = a[matchKey].orEmpty()
        val right = b[matchKey].orEmpty()
        if (left.size == 1 && right.size == 1 && left[0].value.disposition != ReconcileDisposition.DUPLICATE && right[0].value.disposition != ReconcileDisposition.DUPLICATE) {
            bank[left[0].index] = left[0].value.copy(disposition = ReconcileDisposition.EXACT, reason = "unique exact policy match", counterpartOrdinal = right[0].value.row.rowOrdinal)
            expenditure[right[0].index] = right[0].value.copy(disposition = ReconcileDisposition.EXACT, reason = "unique exact policy match", counterpartOrdinal = left[0].value.row.rowOrdinal)
        } else if (left.isNotEmpty() && right.isNotEmpty()) {
            for (entry in left) if (entry.value.disposition != ReconcileDisposition.DUPLICATE) bank[entry.index] = entry.value.copy(disposition = ReconcileDisposition.AMBIGUOUS, reason = "multiple or duplicate candidates")
            for (entry in right) if (entry.value.disposition != ReconcileDisposition.DUPLICATE) expenditure[entry.index] = entry.value.copy(disposition = ReconcileDisposition.AMBIGUOUS, reason = "multiple or duplicate candidates")
        }
    }
    fun totals(entries: List<ReconcileEntry>): ReconcileTotals {
        fun sum(selected: List<ReconcileEntry>): Long? = try { selected.fold(0L) { total, entry -> addMinorUnits(total, requireNotNull(entry.units)) } } catch (_: IllegalArgumentException) { null }
        val input = sum(entries)
        val matched = sum(entries.filter { it.disposition == ReconcileDisposition.EXACT })
        val review = sum(entries.filter { it.disposition != ReconcileDisposition.EXACT })
        val conserved = input != null && matched != null && review != null && runCatching { addMinorUnits(matched, review) == input }.getOrDefault(false)
        if (!conserved) issues.add("${entries.firstOrNull()?.role ?: "empty"} totals incomplete: invalid amount or overflow")
        return ReconcileTotals(input, matched, review, conserved)
    }
    val bankTotals = totals(bank)
    val expenditureTotals = totals(expenditure)
    val entries = (bank + expenditure).toSeries()
    val complete = issues.isEmpty() && entries.view.none { it.disposition == ReconcileDisposition.INVALID }
    return ReconcileReport(bankSource, expenditureSource, policy, currency, entries, bankTotals, expenditureTotals, complete, issues.toSeries())
}
