package borg.trikeshed.viewserver

import kotlin.test.Test
import kotlin.test.assertEquals

class CommonViewServerTest {
    @Test
    fun testReset() {
        val server = CommonViewServer()
        server.reset()
        assertEquals("[]", server.mapDoc("{}"))
    }

    @Test
    fun testReduce() {
        val server = CommonViewServer()
        server.reset()
        server.addTool("couchdbcascade/byOrganization", "{}")

        val keysAndValues = "[[[1,2,2024,5,10,12,30], 3500]]"
        val result = server.reduce(listOf("couchdbcascade/byOrganization"), keysAndValues)
        assertEquals("[{\"count\":1,\"sum\":3500.0,\"min\":3500.0,\"max\":3500.0,\"sumsqr\":0}]", result)
    }
}
