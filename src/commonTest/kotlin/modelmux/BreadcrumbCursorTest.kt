package modelmux

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.query.QueryK
import borg.trikeshed.cursor.query.facet
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.kursive.sql.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** A query recorded as a breadcrumb reads back as a row of the `breadcrumbs` table whose heritage is its result. */
class BreadcrumbCursorTest {
    @Test fun aRecordedQueryIsABreadcrumbRowWithResolvableHeritage() = runTest {
        val cas = CasStore.inMemory()
        val element = BreadcrumbElement({ BreadcrumbContext({ 1_000L }, index = BreadcrumbIndex(cas)) }, cas = cas)
        val docs: Cursor = confixDoc("""[{"id": 1, "x": "a"}, {"id": 2, "x": "b"}, {"id": 3, "x": "a"}]""").index.facet(ConfixIndexK.Columns)
        val q = SqlParser.parse("SELECT id FROM docs WHERE x = 'a' ORDER BY id".toSeries())!!.plan({ docs })
        val result = confixDoc(jsonOf(List(q.size) { r -> mapOf("id" to q[r].values[0]) }))
        val record = element.recordQuery("session-1", q.facet(QueryK.Receipt), result)
        assertEquals("query:cursor", record.source)
        assertEquals(q.receipt.statement, record.content)
        // A second recording of the same statement is the same breadcrumb.
        assertEquals(record.id, element.recordQuery("session-1", q.receipt, result).id)
        element.transact("session-1") { it.put("note", "an unrelated breadcrumb") }

        val table = element.transact("session-1") { BreadcrumbCursor.of(it.index).cursor() }
        assertEquals(2, table.size)
        val rows = SqlParser.parse("SELECT title, source, heritageCid, chars, createdAt FROM breadcrumbs WHERE source LIKE 'query:%'".toSeries())!!.plan({ table })
        assertEquals(1, rows.size)
        val row = rows[0].values
        assertEquals(q.receipt.statement, row[0])
        assertEquals("query:cursor", row[1])
        assertEquals(q.receipt.statement.length.toLong(), row[3])
        assertEquals(1_000L, row[4], "createdAt is the context clock's reading")
        val heritageCid = assertNotNull(row[2]?.toString(), "the recorded result is the breadcrumb's heritage")
        val expected = ContentId.of(CanonicalCbor.encode(result))
        assertTrue(expected.hex in heritageCid, "heritage cid $heritageCid names the result $expected")
        assertTrue(cas.holds(expected), "the result is in the shared CAS")
        val heritage = element.transact("session-1") { it.index[record.id]!!.heritage }!!
        val replay = SqlParser.parse("SELECT id FROM heritage".toSeries())!!.plan({ heritage.index.facet(ConfixIndexK.Columns) })
        assertEquals(listOf(1L, 3L), List(replay.size) { replay[it].values[0] })
    }
}
