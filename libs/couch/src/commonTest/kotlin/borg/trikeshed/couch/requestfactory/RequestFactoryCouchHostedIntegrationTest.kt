package borg.trikeshed.couch.requestfactory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

import borg.trikeshed.couch.runtime.CouchRuntime
import borg.trikeshed.couch.transport.htx.HtxRequest



class RequestFactoryCouchHostedIntegrationTest {

    @Test
    fun reactorSelfHostCouch_roundtrip() = runTest {
        val runtime = CouchRuntime()

        val clientCall = RequestFactoryCall(
            context = "db",
            method = "view",
            arguments = listOf(TransportValue.StringValue("vw")),
        )

        val exchange = RequestFactoryHtxClient().invoke(clientCall)
        val body = exchange.body

        val rawRequest = buildString {
            append("POST ${exchange.request.path} HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Content-Type: ${exchange.contentType}\r\n")
            append("X-CCEK-Key: couch-key\r\n")
            append("Content-Length: ${body.encodeToByteArray().size}\r\n\r\n")
            append(body)
        }

        val service = object : RequestFactoryTransportService {
            override fun invoke(call: RequestFactoryCall): RequestFactoryResponse {
                // verify CCEK key was propagated
                assertEquals("couch-key", call.ccekKey)

                // Use the couch runtime transport to build a view fetch
                val exch = runtime.transport.view(
                    database = "acmevehicle",
                    path = "_design/example/_view/by_brand?key=%22vw%22",
                )
                val req: HtxRequest = exch.request
                assertEquals("GET", req.method)
                assertEquals("application/json", req.accept)

                return RequestFactoryResponse(success = true, value = TransportValue.StringValue("view-result"))
            }
        }

        val server = RequestFactoryHtxServer(service)
        val responseRaw = server.handle(rawRequest)
        val responseBody = responseRaw.substringAfter("\r\n\r\n")
        val resp = RequestFactoryJsonCodec.responseFromJson(responseBody)
        assertTrue(resp.success)
        val v = resp.value as? TransportValue.StringValue
        assertEquals("view-result", v?.value)
    }
}
