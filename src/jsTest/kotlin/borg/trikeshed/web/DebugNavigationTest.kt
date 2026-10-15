package borg.trikeshed.web

import org.w3c.dom.url.URLSearchParams
import kotlin.test.*

class DebugNavigationTest {
    val token = "12345678-1234-1234-1234-123456789abc"
    val run = "87654321-4321-4321-4321-cba987654321"

    @Test fun recoveryPinsBothIdentitiesWithoutAnExecutionPayload() {
        val query = URLSearchParams(DebugNavigation.href(token, run).substringAfter('?'))
        assertTrue(DebugNavigation.requested(query))
        assertEquals(token to run, DebugNavigation.source(query))
        assertEquals(2, query.toString().split('&').size)
        assertFalse(ConfixNavigation.requested(query))
    }

    @Test fun malformedAndAmbiguousRecoveryLinksRemainInertRequests() {
        for (raw in listOf("debug=$token", "run=$run", "debug=&run=$run", "debug=$token&run=latest",
            "debug=$token&run=$run&debug=$token", "debug=$token&run=$run&run=$run") +
            listOf("load", "documentTask", "content", "path").map { "debug=$token&run=$run&$it=" }) {
            val query = URLSearchParams(raw)
            assertTrue(DebugNavigation.requested(query), raw)
            assertFails { DebugNavigation.source(query) }
        }
        assertFalse(DebugNavigation.requested(URLSearchParams("load=preset")))
        assertFails { DebugNavigation.href("javascript:alert(1)", run) }
    }
}
