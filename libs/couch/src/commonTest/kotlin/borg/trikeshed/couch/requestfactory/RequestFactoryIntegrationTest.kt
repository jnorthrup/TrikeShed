package borg.trikeshed.couch.requestfactory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

import borg.trikeshed.parse.confix.contextOf
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.path
import borg.trikeshed.parse.confix.Path
import borg.trikeshed.parse.confix.Combinators
import borg.trikeshed.parse.confix.asSeries

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel as KChannel

import borg.trikeshed.couch.userspace.nio.ReactorSupervisor
import borg.trikeshed.couch.htx.HtxBlock

/**
 * Integration tests for RequestFactory roundtrip and OpenAPI/Confix parsing.
 * - Verifies OpenAPI YAML is parseable with Confix and contains the CCEK header
 * - Verifies a RequestFactory client/server roundtrip carries X-CCEK-Key
 * - Verifies a ReactorSupervisor-hosted branch can run a RF server roundtrip
 */
class RequestFactoryIntegrationTest {

    @Test
    fun openApiYaml_parsesWithConfix() {
        val yaml = RequestFactoryOpenApiYamlCodec.toYaml()
        val ctx = contextOf(Syntax.YAML, yaml.asSeries())
        val node = Path.resolve(ctx, path("paths", RequestFactoryTransportContract.PATH, "post", "parameters", 0, "name"))
        assertNotNull(node, "OpenAPI parameters[0].name must be present")
        val name = Combinators.reify(node!!)
        assertEquals("X-CCEK-Key", name)
    }

    @Test
    fun clientServer_roundtrip_passesCcek() {
        val call = RequestFactoryCall(
            context = "test",
            method = "sayHello",
            arguments = listOf(TransportValue.StringValue("world")),
        )

        val client = RequestFactoryHtxClient()
        val exchange = client.invoke(call)
        val body = exchange.body

        val rawRequest = buildString {
            append("POST ${exchange.request.path} HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Content-Type: ${exchange.contentType}\r\n")
            append("X-CCEK-Key: test-key\r\n")
            append("Content-Length: ${body.toByteArray().size}\r\n\r\n")
            append(body)
        }

        val service = object : RequestFactoryTransportService {
            override fun invoke(call: RequestFactoryCall): RequestFactoryResponse {
                assertEquals("test-key", call.ccekKey)
                val arg0 = (call.arguments.first() as TransportValue.StringValue).value
                return RequestFactoryResponse(success = true, value = TransportValue.StringValue("hello $arg0"))
            }
        }

        val server = RequestFactoryHtxServer(service)
        val responseRaw = server.handle(rawRequest)
        val responseBody = responseRaw.substringAfter("\r\n\r\n")
        val resp = RequestFactoryJsonCodec.responseFromJson(responseBody)
        assertTrue(resp.success)
        val v = resp.value as? TransportValue.StringValue
        assertEquals("hello world", v?.value)
    }

    @Test
    fun reactorHostedServer_roundtrip() = runBlocking {
        val reactor = ReactorSupervisor("test-realm")
        reactor.open()
        reactor.activate()

        val chan = KChannel<HtxBlock>(capacity = 1)
        val done = CompletableDeferred<Boolean>()

        // Launch a branch that runs a RequestFactory server roundtrip inside the reactor context
        reactor.launchBranch("rf-branch", chan) {
            val clientCall = RequestFactoryCall(
                context = "ctx",
                method = "doIt",
                arguments = listOf(TransportValue.StringValue("arg")),
            )
            val exchange = RequestFactoryHtxClient().invoke(clientCall)
            val body = exchange.body
            val rawRequest = buildString {
                append("POST ${exchange.request.path} HTTP/1.1\r\n")
                append("Host: localhost\r\n")
                append("Content-Type: ${exchange.contentType}\r\n")
                append("X-CCEK-Key: reactor-key\r\n")
                append("Content-Length: ${body.toByteArray().size}\r\n\r\n")
                append(body)
            }

            val service = object : RequestFactoryTransportService {
                override fun invoke(call: RequestFactoryCall): RequestFactoryResponse {
                    // called inside the branch coroutine
                    assertEquals("reactor-key", call.ccekKey)
                    return RequestFactoryResponse(success = true, value = TransportValue.StringValue("ok"))
                }
            }

            val server = RequestFactoryHtxServer(service)
            val responseRaw = server.handle(rawRequest)
            val respBody = responseRaw.substringAfter("\r\n\r\n")
            val resp = RequestFactoryJsonCodec.responseFromJson(respBody)
            done.complete(resp.success)
        }

        val ok = done.await()
        assertTrue(ok)
        reactor.drain()
    }
}
