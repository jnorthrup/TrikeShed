package borg.trikeshed.jules

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JulesRestClientTest {
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
        val client = JulesRestClient("test-key", "$base/v1alpha")

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
        val client = JulesRestClient("test-key", "$base/v1alpha")

        assertEquals("new-session", client.createSession("implement", "title"))
        assertEquals("answer-1", client.sendMessage("new-session", "Use Confix"))
        assertTrue(requests[0].body.contains("implement"))
        assertTrue(requests[1].body.contains("Use Confix"))
    }

    private data class RecordedRequest(val path: String, val apiKey: String?, val body: String)

    private fun withServer(
        responder: (HttpExchange) -> String,
        block: (String, List<RecordedRequest>) -> Unit,
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
            block("http://127.0.0.1:${server.address.port}", requests)
        } finally {
            server.stop(0)
        }
    }
}
