package borg.trikeshed.jules

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlywheelTendTest {
    @Test
    fun awaitingInquiryIsAnsweredOnceThroughTheProductionClient() = runBlocking {
        val requests = mutableListOf<Pair<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            requests += exchange.requestURI.path to body
            val payload = when (exchange.requestURI.path) {
                "/v1alpha/sessions/s1/activities" -> """
                    {"activities":[{"name":"sessions/s1/activities/a1","originator":"agent","agentMessaged":{"agentMessage":"Which codec should I use?"}}]}
                """.trimIndent()
                "/v1alpha/sessions/s1:sendMessage" -> """{"name":"sessions/s1/activities/reply-1"}"""
                else -> error("unexpected ${exchange.requestURI.path}")
            }.encodeToByteArray()
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            var prompt = ""
            val client = JulesRestClient("test", "http://127.0.0.1:${server.address.port}/v1alpha")
            val driver = FlywheelDriver(
                apiKey = "test",
                client = client,
                tendResponder = {
                    prompt = it
                    "Use borg.trikeshed.parse.json.JsonSupport. Continue TDD and run the focused test."
                },
            )

            val tended = driver.tendSessions(listOf(
                JulesRestClient.SessionInfo("s1", "AWAITING_USER_FEEDBACK", "Codec task", 0L)
            ))
            val repeated = driver.tendSessions(listOf(
                JulesRestClient.SessionInfo("s1", "AWAITING_USER_FEEDBACK", "Codec task", 0L)
            ))

            assertEquals(1, tended)
            assertEquals(0, repeated)
            assertTrue(prompt.contains("Which codec should I use?"))
            assertTrue(prompt.contains("Codec task"))
            assertTrue(requests.single { it.first.endsWith(":sendMessage") }.second.contains("JsonSupport"))
        } finally {
            server.stop(0)
        }
    }
}
