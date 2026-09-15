package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.vitals.JvmVitals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Page identity gate: blackboard, Graal, and panels are distinct surfaces with shared wires. */
class BlackboardPageTest {
    @Test
    fun compiledKotlinBundlesAreServedFromTheirAppDirectories() = runTest {
        val server = borg.trikeshed.litebike.JvmKanbanServer()
        for (app in listOf("forge", "documents", "spacegraph")) {
            val path = "/kotlin/$app/$app.js"
            val expected = javaClass.getResourceAsStream("/web$path")?.use { it.readBytes() }
            val response = server.routeHttp("GET $path HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray())
            if (expected == null) {
                assertEquals(404, response.status)
            } else {
                assertEquals(200, response.status)
                assertEquals("application/javascript; charset=utf-8", response.contentType)
                assertContentEquals(expected, response.payloadBytes)
            }
        }
        val traversal = server.routeHttp("GET /kotlin/../styles.css HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray())
        assertEquals(404, traversal.status)
    }

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
            assertTrue("/patch-camera.css" in html)
            assertTrue("id=\"cancelRun\"" in html)

            val graal = GraalWire(JvmVitals(), null, null, scope)
                .route("GET", "/graal", "", null)
            assertEquals(200, graal?.status)
            val graalHtml = graal!!.payloadBytes.decodeToString()
            assertTrue("<title>Graal Console</title>" in graalHtml)
            assertTrue("id=\"map\"" in graalHtml, "/graal needs its own terrain canvas")
            assertTrue("BLACKBOARD" !in graalHtml, "/graal must not serve the blackboard harness")

            val server = borg.trikeshed.litebike.JvmKanbanServer()
            val harness = server.routeHttp("GET /harness HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray())
            assertEquals(html, harness.body, "/harness keeps the blackboard harness")
            for (path in listOf("/panels", "/panels.html")) {
                val panels = server.routeHttp("GET $path HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray())
                assertEquals(200, panels.status)
                assertTrue("<title>Construction Panels</title>" in panels.body)
                assertTrue("id=\"viewport\"" in panels.body && "id=\"world\"" in panels.body)
                assertTrue("./kotlin/spacegraph/spacegraph.js" in panels.body)
                assertTrue("localTreeshake" !in panels.body && "program:G" !in panels.body)
                assertTrue(panels.body != html, "$path must not serve the blackboard harness")
                assertTrue("/patch-camera.css" in panels.body)
            }
            assertEquals(200, server.routeHttp("GET /patch-camera.css HTTP/1.1\r\nHost: t\r\n\r\n".toByteArray()).status)
        } finally {
            scope.cancel()
        }
    }
}
