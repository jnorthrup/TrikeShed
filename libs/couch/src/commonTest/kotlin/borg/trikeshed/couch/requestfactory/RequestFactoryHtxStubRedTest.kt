package borg.trikeshed.couch.requestfactory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestFactoryHtxStubRedTest {
    @Test
    fun clientStubBuildsPostExchangeAgainstGwtRequestWithJsonBody() {
        val client = RequestFactoryHtxClient()
        val call = RequestFactoryCall(
            context = "EmployeeRequest",
            method = "findEmployee",
            arguments = listOf(TransportValue.IntegerValue(7)),
        )

        val exchange = client.invoke(call)

        assertEquals("POST", exchange.request.method)
        assertEquals(RequestFactoryTransportContract.PATH, exchange.request.path)
        assertEquals(RequestFactoryTransportContract.CONTENT_TYPE, exchange.contentType)
        assertEquals("application/json", exchange.request.accept)
        assertTrue(exchange.body.contains("\"findEmployee\""))
    }

    @Test
    fun serverStubDecodesRawGwtRequestDispatchesServiceAndReturnsJsonResponse() {
        val server = RequestFactoryHtxServer(
            object : RequestFactoryTransportService {
                override fun invoke(call: RequestFactoryCall): RequestFactoryResponse {
                    assertEquals("EmployeeRequest", call.context)
                    assertEquals("findEmployee", call.method)
                    assertEquals(listOf(TransportValue.IntegerValue(7)), call.arguments)
                    return RequestFactoryResponse(
                        success = true,
                        value = TransportValue.ObjectValue(
                            mapOf(
                                "id" to TransportValue.IntegerValue(7),
                                "displayName" to TransportValue.StringValue("Ada"),
                            ),
                        ),
                    )
                }
            },
        )

        val rawRequest =
            "POST /gwtRequest HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: 69\r\n\r\n" +
                RequestFactoryJsonCodec.callToJson(
                    RequestFactoryCall(
                        context = "EmployeeRequest",
                        method = "findEmployee",
                        arguments = listOf(TransportValue.IntegerValue(7)),
                    ),
                )

        val rawResponse = server.handle(rawRequest)

        assertTrue(rawResponse.startsWith("HTTP/1.1 200 OK"))
        assertTrue(rawResponse.contains("Content-Type: application/json"))
        assertTrue(rawResponse.contains("\"success\":true"))
        assertTrue(rawResponse.contains("\"displayName\":\"Ada\""))
    }
}
