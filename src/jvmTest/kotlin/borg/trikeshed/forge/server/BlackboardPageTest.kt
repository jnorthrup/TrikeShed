package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** R7 gate — the consolidated page is a served artifact, not a design note. */
class BlackboardPageTest {
    @Test
    fun consolidatedPageServesEveryPaneFromOneEventEndpoint() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val response = BlackboardWire(ConfixBlackboard.empty(), scope)
                .route("GET", "/blackboard", "")
            assertEquals(200, response?.status)
            assertEquals("text/html; charset=utf-8", response?.contentType)
            val html = response!!.body
            assertTrue("CAS / heap terrain" in html)
            assertTrue("scoring tape" in html)
            assertTrue("VM + commit receipts" in html)
            assertTrue("GC lane" in html)
            assertTrue("pointcut sites / writes" in html)
            assertTrue("ACE chunk cache receipts" in html)
            assertTrue("cache-receipt/" in html && "context-receipt/" in html)
            assertTrue("blackboard snapshot" in html)
            assertTrue("new EventSource('/api/graal/events')" in html)
            assertEquals(1, Regex("new EventSource\\(").findAll(html).count(), "one event endpoint fans every event kind")
            assertTrue("/blackboard/assert" in html, "pointcut writes route through the real assert funnel")
        } finally {
            scope.cancel()
        }
    }
}
