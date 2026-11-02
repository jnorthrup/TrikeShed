package borg.trikeshed.finance

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import kotlin.test.*

class ReconciliationTest {
    val usd = CurrencyScale("USD", 2)
    val bank = ReconcileSource(SourceRole.BANK, ContentId.of("bank".encodeToByteArray()), "bank-v1", "canonical-csv-v1")
    val approved = ReconcileSource(SourceRole.EXPENDITURE, ContentId.of("approved".encodeToByteArray()), "approved-v1", "canonical-csv-v1")
    val policy = ReconcilePolicy("account-reference-currency-signed-date-v1", true, "cash")
    fun row(source: ReconcileSource, id: String = "1", ordinal: Int = 1, amount: String = "0.10", reference: String = "r", date: String = "2026-11-02", code: String = "USD") =
        FinanceRow(id, "cash", reference, code, date, amount, source.sourceCid, ordinal, "$id,cash,$reference,$code,$date,$amount")

    @Test fun decimalArithmeticNeverRoundsOrUsesFloatingPoint(): Unit {
        assertEquals("0.30", renderMinorUnits(addMinorUnits(parseMinorUnits("0.10", usd), parseMinorUnits("0.20", usd)), usd))
        assertEquals(-123L, parseMinorUnits("-1.23", usd))
        assertEquals("-1.23", renderMinorUnits(-123L, usd))
        assertEquals("0.00", renderMinorUnits(parseMinorUnits("-0.00", usd), usd))
        assertEquals(Long.MIN_VALUE, parseMinorUnits("-92233720368547758.08", usd))
        assertEquals(Long.MAX_VALUE, parseMinorUnits("92233720368547758.07", usd))
        assertEquals("-92233720368547758.08", renderMinorUnits(Long.MIN_VALUE, usd))
        assertEquals("92233720368547758.07", renderMinorUnits(Long.MAX_VALUE, usd))
        for (units in s_[Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 1L].view) assertEquals(units, parseMinorUnits(renderMinorUnits(units, usd), usd))
        for (text in s_["", "1.001", "+1.00", "01.00", "1.", ".10", " 1.00", "1e2", "1,00", "NaN", "92233720368547758.08", "-92233720368547758.09"].view) assertFails { parseMinorUnits(text, usd) }
        assertFails { addMinorUnits(Long.MAX_VALUE, 1L) }
        assertFails { addMinorUnits(Long.MIN_VALUE, -1L) }
        assertFails { CurrencyScale("usd", 2) }
        assertFails { CurrencyScale("USD", 10) }
        assertEquals("1", renderMinorUnits(parseMinorUnits("1", CurrencyScale("JPY", 0)), CurrencyScale("JPY", 0)))
    }

    @Test fun uniqueExactMatchesConserveSignedSourceTotalsAndEvidence(): Unit {
        val a = s_[row(bank), row(bank, "2", 2, "-0.20", "refund"), row(bank, "3", 3, "0.00", "zero")]
        val b = s_[row(approved), row(approved, "2", 2, "-0.20", "refund"), row(approved, "3", 3, "0.00", "zero")]
        val report = reconcile(bank, approved, a, b, usd, policy)
        assertTrue(report.complete)
        assertEquals(6, report.entries.size)
        assertTrue(report.entries.view.all { it.disposition == ReconcileDisposition.EXACT })
        assertEquals(-10L, report.bankTotals.input)
        assertEquals(-10L, report.bankTotals.matched)
        assertEquals(0L, report.bankTotals.review)
        assertTrue(report.bankTotals.conserved && report.approvedTotals.conserved)
        assertEquals(a[0].rawRow, report.entries[0].row.rawRow)
        assertEquals(bank, report.bankSource)
        assertEquals(policy, report.policy)
    }

    @Test fun duplicateIdsAndExactTiesStayVisibleWithoutArbitraryPartners(): Unit {
        val report = reconcile(bank, approved, s_[row(bank), row(bank, "2", 2)], s_[row(approved)], usd, policy)
        assertEquals(3, report.entries.size)
        assertTrue(report.entries.view.all { it.disposition == ReconcileDisposition.AMBIGUOUS && it.counterpartOrdinal == null })
        assertEquals(20L, report.bankTotals.review)
        assertTrue(report.bankTotals.conserved)
        val duplicates = reconcile(bank, approved, s_[row(bank), row(bank, ordinal = 2)], s_[row(approved)], usd, policy)
        assertEquals(2, duplicates.entries.view.count { it.disposition == ReconcileDisposition.DUPLICATE })
        assertEquals(ReconcileDisposition.AMBIGUOUS, duplicates.entries[2].disposition)
    }

    @Test fun invalidCurrencyAmountEvidenceAndOverflowMarkIncomplete(): Unit {
        val invalid = reconcile(bank, approved, s_[row(bank, code = "EUR"), row(bank, "2", 2, "0.001"), row(bank, "3", 3).copy(sourceCid = approved.sourceCid)], s_[], usd, policy)
        assertFalse(invalid.complete)
        assertTrue(invalid.entries.view.all { it.disposition == ReconcileDisposition.INVALID })
        assertNull(invalid.bankTotals.input)
        val overflow = reconcile(bank, approved, s_[row(bank, amount = "92233720368547758.07"), row(bank, "2", 2, "0.01", "other")], s_[], usd, policy)
        assertFalse(overflow.complete)
        assertNull(overflow.bankTotals.input)
        assertFalse(overflow.bankTotals.conserved)
        assertEquals(2, overflow.entries.size)
        val underflow = reconcile(bank, approved, s_[row(bank, amount = "-92233720368547758.08"), row(bank, "2", 2, "-0.01", "other")], s_[], usd, policy)
        assertFalse(underflow.complete)
        assertNull(underflow.bankTotals.input)
        assertFalse(underflow.bankTotals.conserved)
    }

    @Test fun referencesDatesAndSourceVersionsRemainAuthoritative(): Unit {
        val differentReference = reconcile(bank, approved, s_[row(bank)], s_[row(approved, reference = "other")], usd, policy)
        assertTrue(differentReference.entries.view.all { it.disposition == ReconcileDisposition.UNMATCHED })
        val differentDate = reconcile(bank, approved, s_[row(bank)], s_[row(approved, date = "2026-11-03")], usd, policy)
        assertTrue(differentDate.entries.view.all { it.disposition == ReconcileDisposition.UNMATCHED })
        val withoutDate = reconcile(bank, approved, s_[row(bank)], s_[row(approved, date = "2026-11-03")], usd, ReconcilePolicy("no-date-v1", false, "cash"))
        assertTrue(withoutDate.entries.view.all { it.disposition == ReconcileDisposition.EXACT })
        val badDate = reconcile(bank, approved, s_[row(bank, date = "2026-02-30")], s_[], usd, policy)
        assertFalse(badDate.complete)
        val replaySource = approved.copy(sourceCid = bank.sourceCid)
        val replay = reconcile(bank, replaySource, s_[row(bank)], s_[row(replaySource)], usd, policy)
        assertFalse(replay.complete)
        assertTrue(replay.entries.view.all { it.disposition == ReconcileDisposition.INVALID })
        assertTrue(replay.issues.view.any { it.contains("replay") })
        assertFails { ReconcilePolicy("", true, "cash") }
        assertFails { bank.copy(revision = "") }
        assertFails { bank.copy(parserIdentity = "") }
        assertFails { policy.copy(accountScope = "") }
        val account = reconcile(bank, approved, s_[row(bank).copy(account = "other")], s_[], usd, policy)
        assertEquals(ReconcileDisposition.INVALID, account.entries[0].disposition)
        val oversized = reconcile(bank, approved, s_[row(bank).copy(rawRow = "x".repeat(RECONCILE_RAW_CHARS + 1))], s_[], usd, policy)
        assertFalse(oversized.complete)
        val tooMany = (RECONCILE_ROWS + 1) j { _: Int -> row(bank) }
        assertFails { reconcile(bank, approved, tooMany, s_[], usd, policy) }
    }
}
