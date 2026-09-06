package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.vitals.JvmVitals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Page identity gate: blackboard, Graal, and panels are distinct surfaces with shared wires. */
class BlackboardPageTest {
    @Test
    fun blackboardGraalAndPanelsServeTheirOwnCanvases() = runTest {
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
            assertTrue("/patch-camera.js" in html && "/patch-camera.css" in html)
            assertTrue("/patch-shake.js" in html)
            assertTrue("id=\"cancelRun\"" in html)
            val script = javaClass.classLoader.getResource("web/harness.js")!!.readText()
            assertEquals(1, Regex("new EventSource\\(").findAll(script).count())
            assertTrue("/blackboard/board" in script && "stream.addEventListener(\"reset\"" in script)

            val graal = GraalWire(JvmVitals(), null, null, scope)
                .route("GET", "/graal", "", null)
            assertEquals(200, graal?.status)
            val graalHtml = graal!!.payloadBytes.decodeToString()
            assertTrue("<title>Graal Console</title>" in graalHtml)
            assertTrue("id=\"map\"" in graalHtml, "/graal needs its own terrain canvas")
            assertTrue("/api/graal/vitals" in graalHtml && "/api/graal/events" in graalHtml)
            assertTrue("BLACKBOARD" !in graalHtml, "/graal must not serve the blackboard harness")

            val server = borg.trikeshed.litebike.JvmKanbanServer()
            val harness = server.routeHttp("GET /harness HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray())
            assertEquals(html, harness.body, "/harness keeps the blackboard harness")
            for (path in listOf("/panels", "/panels.html")) {
                val panels = server.routeHttp("GET $path HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray())
                assertEquals(200, panels.status)
                assertTrue("<title>Construction Panels</title>" in panels.body)
                assertTrue("id=\"viewport\"" in panels.body && "id=\"world\"" in panels.body)
                assertTrue("/api/panels" in panels.body && "/patch-shake.js" in panels.body)
                assertTrue("localTreeshake" !in panels.body && "program:G" !in panels.body)
                assertTrue(panels.body != html, "$path must not serve the blackboard harness")
                assertTrue("/patch-camera.js" in panels.body && "/patch-camera.css" in panels.body)
            }
            for (path in listOf("/patch-camera.js", "/patch-camera.css", "/patch-shake.js")) {
                assertEquals(200, server.routeHttp("GET $path HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray()).status)
            }
        } finally {
            scope.cancel()
        }
    }
}
