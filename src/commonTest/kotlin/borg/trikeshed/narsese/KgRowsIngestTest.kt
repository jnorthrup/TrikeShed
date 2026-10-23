package borg.trikeshed.narsese

import borg.trikeshed.cursor.query.rowsCursor
import borg.trikeshed.cursor.values
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.kursive.sql.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Query rows become beliefs under the query's receipt, and the ledger and bag read back as tables. */
class KgRowsIngestTest {
    private fun count(sql: String, table: borg.trikeshed.cursor.Cursor): Long = SqlParser.parse(sql.toSeries())!!.plan({ table })[0].values[0] as Long

    @Test fun rowsMintBeliefsUnderOneReceiptAndTheBagReadsBackAsATable() = runTest {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        try {
            val minted = ArrayList<BeliefEvent.Minted>()
            val listener = launch(start = CoroutineStart.UNDISPATCHED) { bag.beliefEvents.collect { if (it is BeliefEvent.Minted) minted += it } }
            val rows = rowsCursor(
                listOf("subject", "predicate", "object", "confidence"),
                listOf(
                    mapOf("subject" to "cursor", "predicate" to "type", "object" to "algebra", "confidence" to 0.9),
                    mapOf("subject" to "join", "predicate" to "causes", "object" to "widening", "confidence" to 0.8),
                    mapOf("subject" to "series", "predicate" to "sameAs", "object" to "column", "confidence" to 0.7),
                ),
            )
            val receipt = ContentId.of("SELECT subject, predicate, object FROM facts".encodeToByteArray())
            val report = bag.ingestKg(rows, receipt)
            assertEquals(3, report["statements"]); assertEquals(3, report["minted"]); assertEquals(0, report["skipped"])
            assertEquals(mapOf("INHERITANCE" to 1, "IMPLICATION" to 1, "SIMILARITY" to 1), report["copulas"])
            assertEquals(receipt.value, report["receiptCid"])
            advanceUntilIdle() // the bag's writer emits from its own dispatcher; let the collector drain the flow
            assertEquals(3, minted.size)
            assertTrue(minted.all { it.receiptCid == receipt }, "every belief carries the query's receipt")
            listener.cancel()

            val beliefs = bag.cursor()
            assertEquals(bag.size, beliefs.size)
            assertEquals(bag.size.toLong(), count("SELECT COUNT(*) FROM nars_beliefs", beliefs))
            val provenance = SqlParser.parse("SELECT provenanceCid, subjectCid FROM nars_beliefs".toSeries())!!.plan({ beliefs })
            for (r in 0 until provenance.size) {
                assertEquals(receipt.value, provenance[r].values[0], "the receipt is each belief's provenance")
                assertEquals(receipt.value, provenance[r].values[1], "the receipt names the body the belief came from")
            }
            assertEquals(1L, count("SELECT COUNT(*) FROM nars_beliefs WHERE relation = 'CAUSALITY'", beliefs))
            // A replay of the same rows renews attention without adding beliefs.
            bag.ingestKg(rows, receipt)
            assertEquals(beliefs.size, bag.cursor().size)
        } finally {
            bag.drain()
        }
    }

    @Test fun theEvidenceLedgerReadsBackAsATable() {
        val ledger = EvidenceLedger()
        ledger.observe(packInts(1, 2), 0, EvidenceCoord(3, 1))
        ledger.observe(packInts(5, 6), 1, EvidenceCoord(1, 0))
        ledger.observe(packInts(1, 2), 2, EvidenceCoord(2, 0))
        val table = ledger.cursor()
        assertEquals(ledger.size, table.size)
        assertEquals(ledger.size.toLong(), count("SELECT COUNT(*) FROM nars_evidence", table))
        assertEquals(6L, count("SELECT SUM(positive) FROM nars_evidence", table))
        assertEquals(2L, count("SELECT basis FROM nars_evidence WHERE key = ${packInts(1, 2)}", table))
    }
}
