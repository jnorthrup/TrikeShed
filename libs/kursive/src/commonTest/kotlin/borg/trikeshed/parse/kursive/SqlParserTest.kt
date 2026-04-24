package borg.trikeshed.parse.kursive.sql

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

class SqlParserTest {
    @Test
    fun parseSimpleSelect() {
        val sql = "SELECT id, name AS username FROM users WHERE age > 30 AND status = 'active'"
        val stmt = SqlParser.parse(sql.toSeries())
        assertNotNull(stmt)
        assertEquals("users", stmt?.from?.name?.asString())
        assertEquals(2, stmt?.columns?.size)
        val col1 = stmt?.columns?.get(0)
        val col2 = stmt?.columns?.get(1)
        assertEquals("id", (col1?.expr as ColumnRef).id.asString())
        assertEquals("name", ((col2?.expr as ColumnRef).id).asString())
        assertEquals("username", col2?.alias?.asString())
    }
}
