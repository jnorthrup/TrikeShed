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
            assertTrue("id=\"landscape\"" in html)
            assertTrue("/graal-terrain.js" in html && "/patch.js" in html)
            assertTrue("/landscape-navigation.js" in html)
            assertTrue("id=\"cancelRun\"" in html)
            val script = javaClass.classLoader.getResource("web/harness.js")!!.readText()
            assertEquals(1, Regex("new EventSource\\(").findAll(script).count())
            assertTrue("/blackboard/board" in script && "stream.addEventListener(\"reset\"" in script)
            val server = borg.trikeshed.litebike.JvmKanbanServer()
            for (path in listOf("/panels", "/panels.html", "/harness")) {
                val other = server.routeHttp("GET $path HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray())
                assertEquals(html, other.body, "$path must share the same spatial surface")
            }
        } finally {
            scope.cancel()
        }
    }
}
