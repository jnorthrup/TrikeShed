package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.test.*

class BertSenseIndexOwnershipTest {
    private fun rows(): MutableList<BertSenseEvidence> {
        val rows = mutableListOf<BertSenseEvidence>()
        for (i in 0 until 7) rows.add(BertSenseEvidence("source-$i", "binding",
            BertVector(BertToken("contract", "context $i"), BertSpan.Exact, _a[1f, 0f])))
        return rows
    }

    private fun query() = BertVector(BertToken("contract", "query context"), BertSpan.Exact, _a[1f, 0f])

    @Test fun constructionReadsEachOracleRowOnce() {
        val rows = rows()
        var reads = 0
        val index = BertSenseIndex("fixture", rows.size j { i: Int ->
            reads++
            if (reads <= rows.size) rows[i] else rows[i].copy(sense = "different")
        })
        assertEquals(rows.size, reads)
        assertEquals(BertSenseStatus.Supported, index.sense(query(), "query", s_["binding"]).status)
        assertEquals(rows.size, reads)
    }

    @Test fun indexRetainsEvidenceIdentityAndValuesAtConstruction() {
        val rows = rows()
        val index = BertSenseIndex("fixture", rows.size j { i: Int -> rows[i] })
        rows[0].vector.values!![0] = -1f
        rows[0] = rows[0].copy(sense = "different")

        val result = index.sense(query(), "query", s_["binding", "different"])
        assertEquals(BertSenseStatus.Supported, result.status)
        assertEquals("binding", result.sense)
        val retained = result.neighbors.view.first { it.evidence.source == "source-0" }
        assertEquals("binding", retained.evidence.sense)
        assertContentEquals(_a[1f, 0f], retained.evidence.vector.values)
    }

    @Test fun returnedNeighborVectorsCannotChangeLaterReceipts() {
        val rows = rows()
        val index = BertSenseIndex("fixture", rows.size j { i: Int -> rows[i] })
        val first = index.sense(query(), "query", s_["binding"])
        first.neighbors[0].evidence.vector.values!![0] = -9f

        val second = index.sense(query(), "query", s_["binding"])
        assertEquals(BertSenseStatus.Supported, second.status)
        assertContentEquals(_a[1f, 0f], second.neighbors[0].evidence.vector.values)
    }
}
