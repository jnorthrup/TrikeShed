package borg.trikeshed.couch.requestfactory

import borg.trikeshed.couch.transport.htx.HtxRequest
import borg.trikeshed.couch.transport.htx.HtxRequestFactoryBridge


data class RequestFactoryHtxExchange(
    val request: HtxRequest,
    val contentType: String,
    val body: String,
)

class RequestFactoryHtxClient {
    fun invoke(call: RequestFactoryCall): RequestFactoryHtxExchange {
        val body = RequestFactoryJsonCodec.callToJson(call)
        return RequestFactoryHtxExchange(
            request = HtxRequest(
                method = "POST",
                path = RequestFactoryTransportContract.PATH,
                accept = RequestFactoryTransportContract.CONTENT_TYPE,
            ),
            contentType = RequestFactoryTransportContract.CONTENT_TYPE,
            body = body,
        )
    }
}

class RequestFactoryHtxServer(
   val service: RequestFactoryTransportService,
   val bridge: HtxRequestFactoryBridge = HtxRequestFactoryBridge(),
) {
    fun handle(rawRequest: String): String {
        val plan = bridge.decode(rawRequest)
        require(plan.dispatchMode.isRequestFactory) { "Unsupported request path: ${plan.requestPath}" }
        require(plan.contentType == RequestFactoryTransportContract.CONTENT_TYPE) { "Unsupported content type: ${plan.contentType}" }
        val body = rawRequest.substringAfter("\r\n\r\n", "")
        var call = RequestFactoryJsonCodec.callFromJson(body)
        val ccekKey = plan.headers["x-ccek-key"]
        if (ccekKey != null) {
            call = call.copy(ccekKey = ccekKey)
        }
        val response = service.invoke(call)
        val responseBody = RequestFactoryJsonCodec.responseToJson(response)
        return buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${RequestFactoryTransportContract.CONTENT_TYPE}\r\n")
            append("Content-Length: ${responseBody.encodeToByteArray().size}\r\n\r\n")
            append(responseBody)
        }
    }
}
