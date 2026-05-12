package borg.trikeshed.parse.kursive.sql

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SqlParserTest {
    @Test
    fun parseSimpleSelect() {
        val sql = "SELECT id, name AS username FROM users WHERE age > 30 AND status = 'active'"
        val stmt = SqlParser.parse(sql )!!
        assertNotNull(stmt)
        assertEquals("users", stmt.from!!.name.s)
        assertEquals(2, stmt.columns.size)
        val col1 = stmt.columns[0]
        val col2 = stmt.columns[1]
        assertEquals<CharSequence>("id", (col1.expr as ColumnRef).id.s)
        assertEquals<CharSequence>("name", (col2.expr as ColumnRef).id.s)
        assertEquals("username", col2.alias?.asString())
    }
}
