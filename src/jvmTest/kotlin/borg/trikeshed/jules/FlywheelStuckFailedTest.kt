package borg.trikeshed.jules

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlywheelStuckFailedTest {
    @Test
    fun cycleActionsClassifiesInProgressWithoutInquiryAsStuckAndFailedAsFailed() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val payload = when (exchange.requestURI.path) {
                "/v1alpha/sessions" -> """
                    {"sessions":[
                      {"name":"sessions/1","state":"IN_PROGRESS","title":"Stuck job","sourceContext":{"source":"sources/github/jnorthrup/TrikeShed"}},
                      {"name":"sessions/2","state":"FAILED","title":"Dead job","sourceContext":{"source":"sources/github/jnorthrup/TrikeShed"}},
                      {"name":"sessions/3","state":"COMPLETED","title":"Done job","sourceContext":{"source":"sources/github/jnorthrup/TrikeShed"}}
                    ]}
                """.trimIndent()
                "/v1alpha/sessions/1/activities" -> """{"activities":[
                  {"name":"sessions/1/activities/a1","createTime":"2026-07-24T10:55:00Z","originator":"agent","progressUpdated":{"title":"thinking","description":"still thinking"}}
                ]}""".trimIndent()
                "/v1alpha/sessions/2/activities" -> """{"activities":[
                  {"name":"sessions/2/activities/a1","createTime":"2026-07-24T10:00:00Z","originator":"agent","agentMessaged":{"agentMessage":"Failed: bad import"}}
                ]}""".trimIndent()
                "/v1alpha/sessions/3/activities" -> """{"activities":[
                  {"name":"sessions/3/activities/a1","createTime":"2026-07-24T10:00:00Z","originator":"agent","artifacts":[{"changeSet":{"gitPatch":{"unidiffPatch":"diff --git a/A b/A\n+line\n"}}}]}
                ]}""".trimIndent()
                else -> error("unexpected ${exchange.requestURI.path}")
            }.encodeToByteArray()
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val client = JulesRestClient("test", "http://127.0.0.1:${server.address.port}/v1alpha")
            val driver = FlywheelDriver(apiKey = "test", client = client)
            val actions = driver.classifySessions(
                client.listSessions("sources/github/jnorthrup/TrikeShed"),
                nowMs = System.currentTimeMillis() + 60 * 60_000L,
                stuckThresholdMs = 60_000L,
            )

            val stuck = actions.single { it.sessionId == "1" }
            assertEquals(FlywheelDriver.SessionAction.STUCK, stuck.action)

            val failed = actions.single { it.sessionId == "2" }
            assertEquals(FlywheelDriver.SessionAction.FAILED, failed.action)

            val completed = actions.single { it.sessionId == "3" }
            assertEquals(FlywheelDriver.SessionAction.HARVEST, completed.action)

            assertTrue(actions.any { it.action == FlywheelDriver.SessionAction.STUCK })
            assertTrue(actions.any { it.action == FlywheelDriver.SessionAction.FAILED })
        } finally {
            server.stop(0)
        }
    }
}
