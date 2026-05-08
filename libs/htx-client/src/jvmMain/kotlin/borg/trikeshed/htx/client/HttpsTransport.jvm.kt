package borg.trikeshed.htx.client

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

actual fun createHttpsHandler(): HtxRequestHandler = { request ->
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()
    val builder = HttpRequest.newBuilder()
        .uri(URI.create(request.path))
        .timeout(Duration.ofSeconds(30))
    request.headers.forEach { (k, v) -> builder.header(k, v) }
    val bodyPublisher = if (request.body.isEmpty())
        HttpRequest.BodyPublishers.noBody()
    else
        HttpRequest.BodyPublishers.ofString(request.body)
    builder.method(request.method, bodyPublisher)
    val httpReq = builder.build()
    val resp = client.send(httpReq, HttpResponse.BodyHandlers.ofString())
    HtxClientMessage(status = resp.statusCode(), body = resp.body())
}
