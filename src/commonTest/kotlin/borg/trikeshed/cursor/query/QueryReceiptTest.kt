package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.parse.kursive.sql.*
import kotlin.test.*

/** A QueryCursor knows what it is: statement, sources, stages and rows under one content id, carried on every column. */
class QueryReceiptTest {
    private val docs: Cursor = confixDoc("""[{"id": 1, "x": "a"}, {"id": 2, "x": "b"}, {"id": 3, "x": "a"}]""").index.facet(ConfixIndexK.Columns)
    private val cid = ContentId.of("docs v1".encodeToByteArray())

    private fun plan(sql: String, provenance: (String) -> ContentId? = { cid }) = SqlParser.parse(sql.toSeries())!!.plan({ docs }, provenance = provenance)

    @Test fun theReceiptIdentifiesTheStatementOverItsSourcesAndThisRun() {
        val q = plan("SELECT id FROM docs WHERE id > 1 ORDER BY id DESC")
        val receipt = q.facet(QueryK.Receipt)
        assertSame(receipt, q.receipt)
        assertEquals("SELECT id FROM docs WHERE (id > 1) ORDER BY id DESC", receipt.statement)
        assertEquals(mapOf("docs" to cid), receipt.sources)
        assertEquals(listOf("Filter", "Order", "Project"), receipt.stages)
        assertEquals(2, receipt.rows)
        // The same statement over the same source is the same statement; a different source or statement is not.
        assertEquals(receipt.statementCid, plan("SELECT id FROM docs WHERE id > 1 ORDER BY id DESC").receipt.statementCid)
        assertEquals(receipt.canonicalCid, plan("SELECT id FROM docs WHERE id > 1 ORDER BY id DESC").receipt.canonicalCid)
        assertNotEquals(receipt.statementCid, plan("SELECT id FROM docs WHERE id > 2 ORDER BY id DESC").receipt.statementCid)
        assertNotEquals(receipt.statementCid, plan("SELECT id FROM docs WHERE id > 1 ORDER BY id DESC") { null }.receipt.statementCid)
        assertNotEquals(receipt.statementCid, receipt.canonicalCid)
        // Numbers render the same on every target: the lexeme, canonicalised; a planner-made literal, as plain decimal.
        assertEquals("SELECT id FROM docs WHERE (id > 0.0001)", plan("SELECT id FROM docs WHERE id > 0.0001").receipt.statement)
        assertEquals("SELECT id FROM docs WHERE (id > 2.5)", plan("SELECT id FROM docs WHERE id > 02.50").receipt.statement)
        assertEquals("0.0001", renderNumber(1.0E-4)); assertEquals("12345678.5", renderNumber(12345678.5)); assertEquals("2.0", renderNumber(2.0)); assertEquals("7", renderNumber(7))
        // TRUE, FALSE and NULL are distinct statements.
        assertNotEquals(plan("SELECT id FROM docs WHERE x IS NULL").receipt.statementCid, plan("SELECT id FROM docs WHERE x IS NOT NULL").receipt.statementCid)
    }

    @Test fun everyResultColumnCarriesTheStatementAsProvenance() {
        val q = plan("SELECT id, x || '!' AS y FROM docs")
        val meta0 = q[0].meta(0)
        val meta1 = q[0].meta(1)
        assertEquals(q.receipt.statementCid, meta0.provenance)
        assertEquals(q.receipt.statementCid, meta1.provenance)
        assertEquals("id", meta0.name.toString()); assertEquals("y", meta1.name.toString())
        assertEquals(IOMemento.IoString, meta1.type)
        // A plan built without a statement carries none; a source column carries none; RecordMeta can carry one.
        assertNull(QueryCursor(docs, s_[QueryK.Filter(SqlParser.parse("SELECT id FROM t WHERE id > 1".toSeries())!!.where!!)])[0].meta(0).provenance)
        assertNull(docs[0].meta(0).provenance)
        assertEquals(cid, RecordMeta("k", borg.trikeshed.isam.meta.IOMemento.IoLong, provenance = cid).provenance)
        assertNull(RecordMeta("k", borg.trikeshed.isam.meta.IOMemento.IoLong).provenance)
    }
}
