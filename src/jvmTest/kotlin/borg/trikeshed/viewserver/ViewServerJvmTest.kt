package borg.trikeshed.viewserver

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import borg.trikeshed.couch.viewserver.GraalVmViewServer

class ViewServerJvmTest {
    @Test
    fun testViewServer() {
        val server = GraalVmViewServer()
        assertEquals("Hello", server.evalJs("'Hello'"))
    }
}
