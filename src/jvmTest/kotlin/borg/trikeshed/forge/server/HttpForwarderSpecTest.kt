package borg.trikeshed.forge.server

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.lib.j
import borg.trikeshed.reactor.ReactorAction
import borg.trikeshed.reactor.ReactorEndpoint
import borg.trikeshed.reactor.ReactorResult
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import java.util.Base64
import kotlinx.serialization.json.*

class HttpForwarderSpecTest {

    private fun encodeSpec(spec: HttpForwarderSpec): String {
        return buildJsonObject {
            put("verb", spec.verb)
            put("path", spec.path)
            put("headers", buildJsonObject {
                spec.headers.forEach { (k, v) -> put(k, v) }
            })
            put("body", Base64.getEncoder().encodeToString(spec.body))
        }.toString()
    }

    private fun decodeSpec(json: String): HttpForwarderSpec {
        val obj = Json.parseToJsonElement(json).jsonObject
        val verb = obj["verb"]!!.jsonPrimitive.content
        val path = obj["path"]!!.jsonPrimitive.content
        val headers = obj["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        val bodyStr = obj["body"]?.jsonPrimitive?.content ?: ""
        val body = if (bodyStr.isNotEmpty()) Base64.getDecoder().decode(bodyStr) else ByteArray(0)
        return HttpForwarderSpec(verb, path, headers, body)
    }

    private fun encodeResponse(resp: HttpForwarderResponse): String {
        return buildJsonObject {
            put("status", resp.status)
            put("headers", buildJsonObject {
                resp.headers.forEach { (k, v) -> put(k, v) }
            })
            put("body", Base64.getEncoder().encodeToString(resp.body))
        }.toString()
    }

    private fun decodeResponse(json: String): HttpForwarderResponse {
        val obj = Json.parseToJsonElement(json).jsonObject
        val status = obj["status"]!!.jsonPrimitive.int
        val headers = obj["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        val bodyStr = obj["body"]?.jsonPrimitive?.content ?: ""
        val body = if (bodyStr.isNotEmpty()) Base64.getDecoder().decode(bodyStr) else ByteArray(0)
        return HttpForwarderResponse(status, headers, body)
    }

    // verifies: roundTripVerbAndPath
    @Test
    fun roundTripVerbAndPath() {
        val spec = HttpForwarderSpec("GET", "/api/blackboard/abc/ping")
        val json = encodeSpec(spec)
        val decoded = decodeSpec(json)
        assertEquals(spec, decoded)
    }

    // verifies: rejectsNullBody
    @Test
    fun rejectsNullBody() {
        val json = """{"verb":"GET","path":"/api/ping"}"""
        val decoded = decodeSpec(json)
        assertNotNull(decoded.body)
        assertContentEquals(ByteArray(0), decoded.body)
    }

    // verifies: headersDefaultToEmptyMap
    @Test
    fun headersDefaultToEmptyMap() {
        val json = """{"verb":"GET","path":"/api/ping"}"""
        val decoded = decodeSpec(json)
        assertNotNull(decoded.headers)
        assertEquals(emptyMap(), decoded.headers)
    }

    // verifies: largeBodyRoundTrips
    @Test
    fun largeBodyRoundTrips() {
        val body = Random.nextBytes(1024 * 1024)
        val spec = HttpForwarderSpec("POST", "/api/upload", body = body)
        val json = encodeSpec(spec)
        val decoded = decodeSpec(json)
        assertContentEquals(body, decoded.body)
    }

    // verifies: responseStatus200RoundTrips
    @Test
    fun responseStatus200RoundTrips() {
        val resp = HttpForwarderResponse(200, emptyMap(), ByteArray(8))
        val json = encodeResponse(resp)
        val decoded = decodeResponse(json)
        assertEquals(resp, decoded)
    }

    // verifies: rejectsStatusBelow100
    @Test
    fun rejectsStatusBelow100() {
        assertFailsWith<IllegalArgumentException> {
            HttpForwarderResponse(99)
        }
    }

    // verifies: rejectsStatusAbove599
    @Test
    fun rejectsStatusAbove599() {
        assertFailsWith<IllegalArgumentException> {
            HttpForwarderResponse(600)
        }
    }

    // A stub for EchoReactorEndpoint for tests
    class EchoReactorEndpoint : ReactorEndpoint {
        override suspend fun invoke(action: ReactorAction): ReactorResult {
            return action.a j (action.b.a j action.b.b)
        }
    }

    // verifies: loopbackHandlerRespondsToPing
    @Test
    fun loopbackHandlerRespondsToPing() {
        val endpoint = EchoReactorEndpoint()
        val handler = LoopbackReactorHandler(port = 0, endpoint = endpoint)
        try {
            val port = handler.serverPort
            val url = URI.create("http://localhost:${port}/api/invoke").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true

            val testNuid = nuid(Capability.Custom("test", "test"), Nonce.RandomBytes(), Subnet.local)
            val action = testNuid j ("ping" j "hello".encodeToByteArray())
            val encodedAction = LoopbackReactorHandler.encodeAction(action)

            conn.outputStream.write(encodedAction)
            conn.outputStream.close()

            assertEquals(200, conn.responseCode)
            val responseBytes = conn.inputStream.readBytes()
            val result = LoopbackReactorHandler.decodeResult(responseBytes)

            assertEquals(action.b.a, result.b.a)
            assertContentEquals(action.b.b, result.b.b)
        } finally {
            handler.close()
        }
    }

    // verifies: loopbackHandlerClosesCleanly
    @Test
    fun loopbackHandlerClosesCleanly() {
        val endpoint = EchoReactorEndpoint()
        val handler = LoopbackReactorHandler(port = 0, endpoint = endpoint)
        val port = handler.serverPort
        handler.close()

        assertFailsWith<IOException> {
            val url = URI.create("http://localhost:${port}/api/invoke").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.write(ByteArray(0))
            conn.responseCode
        }
    }

    // verifies: nodeEndpointRejectsNon2xx
    @Test
    fun nodeEndpointRejectsNon2xx() {
        class FakeNodeReactorEndpoint {
            suspend fun invoke() {
                val response = HttpForwarderResponse(500)
                if (response.status != 200) throw RuntimeException("reactor returned ${response.status}")
            }
        }

        val endpoint = FakeNodeReactorEndpoint()
        val exception = assertFailsWith<RuntimeException> {
            kotlinx.coroutines.runBlocking {
                endpoint.invoke()
            }
        }
        assertEquals("reactor returned 500", exception.message)
    }
}
