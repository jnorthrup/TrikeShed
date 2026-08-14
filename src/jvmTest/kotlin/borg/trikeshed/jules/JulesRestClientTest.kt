package borg.trikeshed.jules

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import keymux.KeyMux
import keymux.FixedKeySource

class JulesRestClientTest {
    private val testKeyMux = KeyMux { bind("*", FixedKeySource("test-key")) }

    @Test
    fun sessionsAndActivitiesUseTheConsolidatedRestShape() = withServer(
        responder = { exchange ->
            when (exchange.requestURI.path) {
                "/v1alpha/sessions" -> """{"sessions":[{"name":"sessions/s1","state":"IN_PROGRESS","title":"work"}]}"""
                "/v1alpha/sessions/s1/activities" -> """
                    {"activities":[
                      {"name":"sessions/s1/activities/a1","createTime":"2026-07-22T00:00:00Z","originator":"agent","agentMessaged":{"agentMessage":"Which codec?"}},
                      {"name":"sessions/s1/activities/a2","createTime":"2026-07-22T00:00:01Z","originator":"agent","artifacts":[{"changeSet":{"gitPatch":{"unidiffPatch":"diff --git a/A b/A\n+line\n"}}}]}
                    ]}
                """.trimIndent()
                else -> error("unexpected path ${exchange.requestURI}")
            }
        },
    ) { base, requests ->
        val client = JulesRestClient(testKeyMux, "$base/v1alpha")

        val sessions = client.listSessions()
        assertEquals(1, sessions.size)
        assertEquals("s1", sessions.single().id)
        assertEquals("IN_PROGRESS", sessions.single().state)

        val activities = client.activities("s1")
        assertEquals(listOf(0, 1), activities.map { it.seq })
        assertEquals("agentMessaged", activities[0].kind)
        assertEquals("Which codec?", activities[0].excerpt)
        assertEquals("artifacts", activities[1].kind)
        assertTrue(activities[1].patchBytes > 0)
        assertTrue(requests.all { it.apiKey == "test-key" })
    }

    @Test
    fun createAndAnswerReturnApiAssignedIds() = withServer(
        responder = { exchange ->
            when (exchange.requestURI.path) {
                "/v1alpha/sessions" -> """{"name":"sessions/new-session"}"""
                "/v1alpha/sessions/new-session:sendMessage" -> """{"name":"sessions/new-session/activities/answer-1"}"""
                else -> error("unexpected path ${exchange.requestURI}")
            }
        },
    ) { base, requests ->
        val client = JulesRestClient(testKeyMux, "$base/v1alpha")

        assertEquals("new-session", client.createSession("implement", "title"))
        assertEquals("answer-1", client.sendMessage("new-session", "Use Confix"))
        assertTrue(requests[0].body.contains("\"prompt\": \"implement\""))
        assertTrue(requests[1].body.contains("\"prompt\": \"Use Confix\""))
    }

    /**
     * lastPatch MUST read the session resource's outputs[*].changeSet.gitPatch.unidiffPatch,
     * not just the activity stream. The activity stream's `artifacts` is an
     * in-progress field that is often empty for sessions whose plan was
     * completed; the canonical landed patch lives on outputs. Without this
     * test, the flywheel drained zero patches for every COMPLETED session
     * because outputs was never consulted.
     */
    @Test
    fun lastPatchReadsSessionOutputsGitPatchUnidiff() = withServer(
        responder = { exchange ->
            when (exchange.requestURI.path) {
                "/v1alpha/sessions/s-outputs/activities" -> """{"activities":[]}"""
                "/v1alpha/sessions/s-outputs" -> """
                {
                  "name": "sessions/s-outputs",
                  "state": "COMPLETED",
                  "outputs": [
                    {
                      "changeSet": {
                        "source": "sources/github/jnorthrup/TrikeShed",
                        "gitPatch": {
                          "unidiffPatch": "diff --git a/A b/A\nnew file mode 100644\n--- /dev/null\n+++ b/A\n@@ -0,0 +1 @@\n+hi\n"
                        }
                      }
                    }
                  ]
                }
                """.trimIndent()
                else -> error("unexpected path " + exchange.requestURI.path)
            }
        },
    ) { base, requests ->
        val client = JulesRestClient(testKeyMux, "$base/v1alpha")
        val patch = client.lastPatch("s-outputs")
        assertTrue(patch != null, "lastPatch must return non-null when outputs has a patch; got null")
        assertTrue(patch!!.startsWith("diff --git a/A b/A"), "patch must be the unidiffPatch from outputs[0]; got: \${patch.take(80)}")
        // Both endpoints should have been hit: session resource first, then activities.
        val paths = requests.map { it.path }
        assertTrue("/v1alpha/sessions/s-outputs" in paths, "must read session resource (the fix); paths: \$paths")
    }

    private data class RecordedRequest(val path: String, val apiKey: String?, val body: String)

    private fun withServer(
        responder: (HttpExchange) -> String,
        block: suspend (String, List<RecordedRequest>) -> Unit,
    ) {
        val requests = mutableListOf<RecordedRequest>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            requests += RecordedRequest(
                path = exchange.requestURI.path,
                apiKey = exchange.requestHeaders.getFirst("x-goog-api-key"),
                body = body,
            )
            val payload = responder(exchange).encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            runBlocking { kotlinx.coroutines.withContext(borg.trikeshed.htx.openHtxElement()) { block("http://127.0.0.1:${server.address.port}", requests) } }
        } finally {
            server.stop(0)
        }
    }
}
