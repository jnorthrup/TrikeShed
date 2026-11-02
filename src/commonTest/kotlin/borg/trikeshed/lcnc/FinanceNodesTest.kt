package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.*

class FinanceNodesTest {
    private val header = "id,account,reference,currency,date,amount\r\n"
    private fun node() = LcncNode("finance", FinanceNodes.RECONCILE,
        _m["currency" j "USD", "scale" j "2", "accountScope" j "cash", "includeDate" j "true", "policyId" j "exact-v1"])
    private fun corpus(bank: String = "b,cash,r,USD,2026-11-02,0.10", expenditure: String = "e,cash,r,USD,2026-11-02,0.10") = InMemoryProjectCorpus().apply {
        put("pilot", "bank.csv", (header + bank).encodeToByteArray())
        put("pilot", "expenditure.csv", (header + expenditure).encodeToByteArray())
    }
    private suspend fun inputs(corpus: ProjectCorpus): Map<String, Any?> = _m[
        "bank" j requireNotNull(corpus.document("pilot", "bank.csv")).toMap(),
        "expenditure" j requireNotNull(corpus.document("pilot", "expenditure.csv")).toMap()]
    private suspend fun execute(corpus: ProjectCorpus, selections: Map<String, Any?>? = null): Map<String, Any?> =
        FinanceNodes.registry(corpus).getValue(FinanceNodes.RECONCILE).run(node(), selections ?: inputs(corpus))

    @Test fun originalQuotedCellsExactAmountsAndConsumedIdentitySurvive(): Unit = runBlocking {
        val bank = "b,cash,\"ref,\"\"quoted\"\"\nline\",USD,2026-11-02,90071992547409.93"
        val expenditure = bank.replaceFirst("b,", "e,")
        val corpus = corpus(bank, expenditure)
        val ledger = LcncConsumedLedger()
        val output = withContext(ledger) { execute(corpus) }
        assertEquals(true, output["complete"])
        val report = output["report"] as Map<*, *>
        val totals = report["bankTotals"] as Map<*, *>
        assertEquals("9007199254740993", totals["matchedMinorUnits"])
        val rows = output["rows"] as List<*>
        assertEquals(2, rows.size)
        val row = rows[0] as Map<*, *>
        assertEquals("EXACT", row["disposition"])
        assertEquals(bank, row["rawRow"])
        val cell = (row["cells"] as List<*>)[2] as Map<*, *>
        assertEquals("ref,\"quoted\"\nline", cell["value"])
        val text = header + bank
        assertEquals("\"ref,\"\"quoted\"\"\nline\"", text.substring(cell["begin"] as Int, cell["end"] as Int))
        val json = jsonOf(output)
        assertTrue(json.contains("\"minorUnits\":\"9007199254740993\""), json)
        assertTrue(json.contains("\"matchedMinorUnits\":\"9007199254740993\""), json)
        assertEquals(2, ledger.entries().size)
        assertEquals(requireNotNull(corpus.document("pilot", "bank.csv")).cid, ledger.entries().first().cid)
        assertTrue(ledger.entries().all { it.sequence != null && !it.rev.isNullOrBlank() })
        assertFalse(FinanceNodes.registry(corpus).getValue(FinanceNodes.RECONCILE).isPure)
    }

    @Test fun staleAndIncompleteSelectionsCannotAuthorizeARead(): Unit = runBlocking {
        val corpus = corpus()
        val selected = inputs(corpus)
        corpus.put("pilot", "bank.csv", (header + "b,cash,r,USD,2026-11-02,0.20").encodeToByteArray())
        assertFails { execute(corpus, selected) }
        val fresh = inputs(corpus)
        val missing = (fresh["bank"] as Map<*, *>).filterKeys { it != "seq" }
        assertFails { execute(corpus, _m["bank" j missing, "expenditure" j fresh["expenditure"]]) }
        val blankRevision = (fresh["bank"] as Map<*, *>).entries.associate { it.key to if (it.key == "rev") "" else it.value }
        assertFails { execute(corpus, _m["bank" j blankRevision, "expenditure" j fresh["expenditure"]]) }
    }

    @Test fun invalidBytesSizeHashAndChangedReadbackAreRefused(): Unit = runBlocking {
        val corpus = corpus()
        corpus.put("pilot", "bank.csv", _a[0xc3.toByte(), 0x28.toByte()])
        assertFails { execute(corpus) }
        corpus.put("pilot", "bank.csv", ByteArray(FinanceNodes.MAX_BYTES + 1))
        assertFails { execute(corpus) }
        val valid = corpus()
        val falseBytes = object : ProjectCorpus by valid {
            override suspend fun bytes(project: String, id: String): ByteArray? =
                valid.bytes(project, id)?.also { if (id == "bank.csv") it[0] = 'X'.code.toByte() }
        }
        assertFails { execute(falseBytes) }
        var reads = 0
        val changed = object : ProjectCorpus by valid {
            override suspend fun bytes(project: String, id: String): ByteArray? {
                if (id == "bank.csv" && ++reads == 2) valid.put(project, id, (header + "b,cash,r,USD,2026-11-02,0.20").encodeToByteArray())
                return valid.bytes(project, id)
            }
        }
        assertFails { execute(changed) }
    }

    @Test fun ambiguousAndInvalidRowsRemainReviewable(): Unit = runBlocking {
        val corpus = corpus("b,cash,r,USD,2026-11-02,0.10\nb2,cash,r,USD,2026-11-02,0.10\nb3,cash,other,USD,2026-11-02,0.001")
        val output = execute(corpus)
        assertEquals(false, output["complete"])
        val rows = (output["rows"] as List<*>).map { it as Map<*, *> }
        assertEquals(4, rows.size)
        assertEquals(3, rows.count { it["disposition"] == "AMBIGUOUS" })
        assertEquals(1, rows.count { it["disposition"] == "INVALID" })
        assertTrue(rows.all { (it["source"] as Map<*, *>)["cid"] is String && it["rawRow"] is String })
        val report = output["report"] as Map<*, *>
        assertEquals("as-of-read-candidates-only", report["status"])
        assertEquals("exact-v1", report["policyId"])
    }

    @Test fun strictHeaderArityAndDeclaredTsvAreEnforced(): Unit = runBlocking {
        val bad = corpus()
        bad.put("pilot", "bank.csv", "id,id,account,reference,currency,date,amount\nb,b,cash,r,USD,2026-11-02,0.10".encodeToByteArray())
        assertFails { execute(bad) }
        bad.put("pilot", "bank.csv", (header + "b,cash,r,USD,2026-11-02").encodeToByteArray())
        assertFails { execute(bad) }
        val tsv = corpus()
        tsv.put("pilot", "bank.csv", (header + "b,cash,r,USD,2026-11-02,0.10").replace(",", "\t").encodeToByteArray(), "text/tab-separated-values")
        val output = execute(tsv)
        assertEquals(true, output["complete"])
        assertEquals("TSV", (((output["report"] as Map<*, *>)["bankSource"] as Map<*, *>)["format"]))
    }
}
